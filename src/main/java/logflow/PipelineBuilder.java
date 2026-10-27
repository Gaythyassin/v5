package logflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Akıcı, tip güvenli pipeline birleştirici.
 *
 * <pre>{@code
 * Pipeline p = PipelineBuilder.from(new FileLineSource(in))
 *         .via(new ParserStage(), ErrorPolicy.DEAD_LETTER)
 *         .via(new PredicateFilterStage<>(r -> r.status() >= 400))
 *         .via(new ProjectionStage("timestamp", "clientIp", "status", "path"))
 *         .deadLetter(new FileSink<>(dlq))
 *         .to(new FileSink<>(out));
 * }</pre>
 *
 * <p>{@code T}, şu ana kadarki zincirin çıktı tipidir. Bir aşama ancak girdisi {@code T}'yi
 * kabul ediyorsa eklenebilir; örneğin bir {@code LogRecord} filtresini ayrıştırıcıdan önce
 * koymak <b>derleme hatasıdır</b>. Her çağrı yeni bir builder döndürür (değişmez), böylece
 * ortak bir önek güvenle dallandırılabilir.
 */
public final class PipelineBuilder<T> {
    private final String name;
    private final Source<?> source;
    private final List<Stage<?, ?>> stages;
    private final List<ErrorPolicy> policies;
    private final Sink<FailedRecord> deadLetter;

    private PipelineBuilder(String name, Source<?> source, List<Stage<?, ?>> stages, List<ErrorPolicy> policies,
                            Sink<FailedRecord> deadLetter) {
        this.name = name;
        this.source = source;
        this.stages = List.copyOf(stages);
        this.policies = List.copyOf(policies);
        this.deadLetter = deadLetter;
    }

    public static <T> PipelineBuilder<T> from(Source<T> source) {
        return new PipelineBuilder<>("pipeline", Objects.requireNonNull(source, "source"), List.of(), List.of(), null);
    }

    /** Aşamayı varsayılan hata politikasıyla ({@link Pipeline#DEFAULT_POLICY}) ekler. */
    public <R> PipelineBuilder<R> via(Stage<? super T, R> stage) {
        return via(stage, Pipeline.DEFAULT_POLICY);
    }

    public <R> PipelineBuilder<R> via(Stage<? super T, R> stage, ErrorPolicy policy) {
        List<Stage<?, ?>> nextStages = new ArrayList<>(stages);
        nextStages.add(Objects.requireNonNull(stage, "stage"));
        List<ErrorPolicy> nextPolicies = new ArrayList<>(policies);
        nextPolicies.add(Objects.requireNonNull(policy, "policy"));
        return new PipelineBuilder<>(name, source, nextStages, nextPolicies, deadLetter);
    }

    /** Başarısız kayıtların gideceği ikinci, bağımsız hedef. */
    public PipelineBuilder<T> deadLetter(Sink<FailedRecord> sink) {
        return new PipelineBuilder<>(name, source, stages, policies, Objects.requireNonNull(sink, "sink"));
    }

    public PipelineBuilder<T> named(String pipelineName) {
        return new PipelineBuilder<>(Objects.requireNonNull(pipelineName, "name"), source, stages, policies, deadLetter);
    }

    public Pipeline to(Sink<? super T> sink) {
        List<Pipeline.StageSlot> defaults = Pipeline.defaultSlots(stages);
        List<Pipeline.StageSlot> slots = new ArrayList<>();
        for (int i = 0; i < defaults.size(); i++) {
            slots.add(new Pipeline.StageSlot(defaults.get(i).name(), stages.get(i), policies.get(i)));
        }
        return new Pipeline(name, source, slots, Objects.requireNonNull(sink, "sink"), deadLetter);
    }
}
