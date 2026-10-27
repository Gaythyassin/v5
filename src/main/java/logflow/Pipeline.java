package logflow;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Kaynak → aşamalar → hedef, ve bunun yanında ikinci, bağımsız bir çıkış yolu: dead-letter kanalı.
 *
 * <pre>
 * Source ──► stage 1 ──► stage 2 ──► … ──► Sink                 (ana yol)
 *               │           │
 *               └─ StageException ─► ErrorPolicy ─► FailedRecord ─► dead-letter Sink   (hata yolu)
 * </pre>
 *
 * Bir aşama bir kaydı işleyemezse ({@link StageException}) karar aşamanın değil, o aşamaya
 * yapılandırmada bağlanmış {@link ErrorPolicy}'nindir. Devam edilemeyen her durum
 * ({@link PipelineException}) çalıştırmayı durdurur; bileşenler her durumda kapatılır.
 */
public class Pipeline {

    /** Adı ve hata politikasıyla birlikte bir aşama. */
    public record StageSlot(String name, Stage<?, ?> stage, ErrorPolicy policy) {
        public StageSlot {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(policy, "policy");
        }
    }

    /** v2–v4 davranışı: kurtarılabilir kayıt hataları atlanır ve sayılır. */
    public static final ErrorPolicy DEFAULT_POLICY = ErrorPolicy.SKIP;

    private final String name;
    private final Source<?> source;
    private final List<StageSlot> slots;
    private final Sink<?> sink;
    private final Sink<FailedRecord> deadLetter;

    // tek bir çalıştırmanın durumu
    private StageMetrics.Counter[] counters;
    private long recordsIn;
    private long recordsOut;
    private long deadLettered;
    private long sinkNanos;
    private Object currentRaw;
    private long currentNumber;

    /**
     * @param deadLetter dead-letter hedefi; hiçbir aşama {@code DEAD_LETTER}/{@code RETRY} kullanmıyorsa {@code null} olabilir
     * @throws IllegalArgumentException bir aşama dead-letter kanalı gerektirdiği hâlde kanal yoksa
     *                                  ya da iki aşama aynı adı taşıyorsa
     */
    public Pipeline(String name, Source<?> source, List<StageSlot> slots, Sink<?> sink, Sink<FailedRecord> deadLetter) {
        this.name = Objects.requireNonNull(name, "name");
        this.source = Objects.requireNonNull(source, "source");
        this.slots = List.copyOf(slots);
        this.sink = Objects.requireNonNull(sink, "sink");
        this.deadLetter = deadLetter;
        Set<String> names = new HashSet<>();
        for (StageSlot slot : this.slots) {
            if (!names.add(slot.name())) {
                throw new IllegalArgumentException("Duplicate stage name: " + slot.name());
            }
            if (slot.policy().needsDeadLetter() && deadLetter == null) {
                throw new IllegalArgumentException("Stage '" + slot.name() + "' uses " + slot.policy()
                        + " but the pipeline has no dead-letter sink");
            }
        }
    }

    public Pipeline(Source<?> source, List<Stage<?, ?>> stages, Sink<?> sink) {
        this("pipeline", source, defaultSlots(stages), sink, null);
    }

    public Pipeline(Source<?> source, Sink<?> sink) {
        this(source, List.of(), sink);
    }

    /** Aşamalara sınıf adlarından türetilen benzersiz adlar ve {@link #DEFAULT_POLICY} verir. */
    static List<StageSlot> defaultSlots(List<Stage<?, ?>> stages) {
        List<StageSlot> slots = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (Stage<?, ?> stage : stages) {
            String base = stage.getClass().getSimpleName().isEmpty() ? "stage" : stage.getClass().getSimpleName();
            String n = base;
            for (int i = 2; !used.add(n); i++) {
                n = base + "#" + i;
            }
            slots.add(new StageSlot(n, stage, DEFAULT_POLICY));
        }
        return slots;
    }

    public String name() {
        return name;
    }

    /** Aşamaların değiştirilemez görünümü (sıra = veri akış sırası). */
    public List<Stage<?, ?>> stages() {
        return slots.stream().<Stage<?, ?>>map(StageSlot::stage).toList();
    }

    public List<StageSlot> slots() {
        return slots;
    }

    public Source<?> source() {
        return source;
    }

    public Sink<?> sink() {
        return sink;
    }

    public Sink<FailedRecord> deadLetter() {
        return deadLetter;
    }

    /**
     * Yaşam döngüsü: aşamaları, hedefi ve dead-letter hedefini açar, kaynağı akıtır, aşamaların
     * {@code finish} kancalarını sırayla çağırır, ardından <b>her durumda</b> hepsini kapatır.
     *
     * @return çalıştırma özeti
     * @throws PipelineException çalıştırma devam edemediyse; o ana kadarki özeti taşır
     */
    public RunSummary run() {
        Instant startedAt = Instant.now();
        long t0 = System.nanoTime();
        counters = new StageMetrics.Counter[slots.size()];
        for (int i = 0; i < slots.size(); i++) {
            counters[i] = new StageMetrics.Counter(slots.get(i).name(), slots.get(i).policy().toString());
        }
        recordsIn = 0;
        recordsOut = 0;
        deadLettered = 0;
        sinkNanos = 0;
        currentRaw = null;
        currentNumber = 0;

        List<Runnable> closers = new ArrayList<>();
        RuntimeException failure = null;
        try {
            for (StageSlot slot : slots) {
                slot.stage().open();
                closers.add(slot.stage()::close);
            }
            sink.open();
            closers.add(sink::close);
            if (deadLetter != null) {
                deadLetter.open();
                closers.add(deadLetter::close);
            }
            emitFromSource();
            finishStages();
        } catch (RuntimeException e) {
            failure = e;
        } finally {
            for (Runnable closer : closers) {
                try {
                    closer.run();
                } catch (RuntimeException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
        }

        PipelineException stop = failure == null ? null : asPipelineException(failure);
        RunSummary summary = new RunSummary(name, stop == null ? RunSummary.Status.COMPLETED : RunSummary.Status.FAILED,
                stop == null ? null : stop.getMessage(), startedAt, Duration.ofNanos(System.nanoTime() - t0),
                recordsIn, recordsOut, deadLettered, sinkNanos,
                Arrays.stream(counters).map(StageMetrics.Counter::snapshot).toList());
        if (stop != null) {
            throw stop.withSummary(summary);
        }
        return summary;
    }

    private static PipelineException asPipelineException(RuntimeException e) {
        if (e instanceof PipelineException pe) {
            return pe;
        }
        return new PipelineException("Pipeline stopped: " + e, e);
    }

    @SuppressWarnings("unchecked")
    private void emitFromSource() {
        Source<Object> typedSource = (Source<Object>) source;
        typedSource.produce(item -> {
            recordsIn++;
            currentNumber = recordsIn;
            currentRaw = item;
            pushToStage(0, item);
        });
    }

    /** Aşama i'nin bitişte yaydıkları yalnızca i+1..n aşamalarından geçer; sıra korunur. */
    @SuppressWarnings("unchecked")
    private void finishStages() {
        currentRaw = null;
        currentNumber = 0;
        for (int i = 0; i < slots.size(); i++) {
            int index = i;
            StageMetrics.Counter c = counters[i];
            Stage<Object, Object> stage = (Stage<Object, Object>) slots.get(i).stage();
            long[] downstream = {0};
            long[] emitted = {0};
            long start = System.nanoTime();
            try {
                stage.finish(output -> {
                    emitted[0]++;
                    c.out++;
                    long t = System.nanoTime();
                    pushToStage(index + 1, output);
                    downstream[0] += System.nanoTime() - t;
                });
            } catch (StageException e) {
                handleFailure(index, "(finish of " + c.name + ")", e);
            } catch (PipelineException e) {
                throw e;
            } catch (RuntimeException e) {
                handleFailure(index, "(finish of " + c.name + ")", unexpected(e));
            } finally {
                c.selfNanos += System.nanoTime() - start - downstream[0];
                if (emitted[0] > 0) {
                    c.oneToOne = false;
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void pushToStage(int index, Object item) {
        if (index >= slots.size()) {
            long t = System.nanoTime();
            try {
                ((Sink<Object>) sink).consume(item);
            } catch (PipelineException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new PipelineException("Sink failed on record #" + currentNumber + ": " + e.getMessage(), e);
            } finally {
                sinkNanos += System.nanoTime() - t;
            }
            recordsOut++;
            return;
        }

        StageSlot slot = slots.get(index);
        StageMetrics.Counter c = counters[index];
        Stage<Object, Object> stage = (Stage<Object, Object>) slot.stage();
        c.in++;

        long[] emitted = {0};
        long[] downstream = {0};
        Emitter<Object> next = output -> {
            emitted[0]++;
            c.out++;
            long t = System.nanoTime();
            try {
                pushToStage(index + 1, output);
            } finally {
                downstream[0] += System.nanoTime() - t;
            }
        };

        for (int attempt = 0; ; attempt++) {
            StageException error;
            long start = System.nanoTime();
            try {
                stage.process(item, next);
                error = null;
            } catch (StageException e) {
                error = e;
            } catch (PipelineException e) {
                throw e; // sonraki bir aşamadan ya da hedeften gelen, devam edilemeyen hata
            } catch (RuntimeException e) {
                error = unexpected(e);
            } finally {
                c.selfNanos += System.nanoTime() - start - downstream[0];
                downstream[0] = 0;
            }
            if (error == null) {
                if (emitted[0] == 0) {
                    c.dropped++;
                } else if (emitted[0] > 1) {
                    c.oneToOne = false;
                }
                return;
            }
            // Yalnızca hiçbir şey yaymamış bir deneme tekrarlanır; aksi hâlde çıktı çoğalırdı.
            if (slot.policy().kind() == ErrorPolicy.Kind.RETRY && attempt < slot.policy().retries() && emitted[0] == 0) {
                c.retries++;
                continue;
            }
            handleFailure(index, rawInput(item), error);
            return;
        }
    }

    /** Aşamanın beklenmeyen çalışma zamanı istisnası da yalnızca o kaydın hatasıdır. */
    private static StageException unexpected(RuntimeException e) {
        return new StageException(e.getClass().getSimpleName() + ": " + e.getMessage(), e);
    }

    private String rawInput(Object item) {
        return String.valueOf(currentRaw != null ? currentRaw : item);
    }

    private void handleFailure(int index, String raw, StageException error) {
        StageSlot slot = slots.get(index);
        StageMetrics.Counter c = counters[index];
        c.failed++;
        switch (slot.policy().kind()) {
            case FAIL_FAST -> throw new PipelineException("stage '" + slot.name() + "' rejected record #" + currentNumber
                    + " and its policy is FAIL_FAST: " + error.getMessage(), error);
            case SKIP -> c.skipped++;
            case DEAD_LETTER, RETRY -> {
                FailedRecord failed = new FailedRecord(raw, slot.name(), error, Instant.now(), currentNumber);
                try {
                    deadLetter.consume(failed);
                } catch (RuntimeException e) {
                    throw new PipelineException("dead-letter sink failed, cannot preserve " + failed + ": " + e.getMessage(), e);
                }
                c.deadLettered++;
                deadLettered++;
            }
        }
    }
}
