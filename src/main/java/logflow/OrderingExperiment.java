package logflow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Bileşilebilirlik deneyi (Artım 3, görev 4). Aynı veri, aynı aşamalardan farklı sıralarla
 * geçirilir; çıktıların aynı, maliyetin farklı olduğu ölçülür.
 *
 * <pre>
 * java -cp out logflow.OrderingExperiment data/access-small.log [targetLines] [runs]
 * </pre>
 *
 * Varyantlar:
 * <ul>
 *   <li>A: parse → status≥400 → path^/api → project</li>
 *   <li>B: parse → path^/api → status≥400 → project  (daha seçici filtre önce)</li>
 *   <li>C: parse → project → status≥400 → path^/api  (filtre projeksiyondan sonra)</li>
 * </ul>
 * Ayrıştırmadan önce filtrelemek mümkün değildir: {@link PipelineBuilder} bunu derleme
 * zamanında reddeder, çünkü filtreler {@code LogRecord} bekler, kaynak ise {@code String} üretir.
 */
public final class OrderingExperiment {
    static final String[] FIELDS = {"timestamp", "clientIp", "status", "path"};

    /** Çıktının sırası duyarlı özeti: aynı sayı + aynı karma = aynı çıktı. */
    static final class DigestSink implements Sink<Object> {
        long count;
        long hash = 1;

        @Override
        public void consume(Object item) {
            count++;
            hash = 31 * hash + item.toString().hashCode();
        }
    }

    record Result(String name, long count, long hash, long evaluations, double medianMs) {
    }

    private OrderingExperiment() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: OrderingExperiment <log-file> [targetLines=1000000] [runs=7]");
            System.exit(1);
        }
        List<String> base = Files.readAllLines(Path.of(args[0]));
        int target = args.length > 1 ? Integer.parseInt(args[1]) : 1_000_000;
        int runs = args.length > 2 ? Integer.parseInt(args[2]) : 7;
        List<String> lines = new ArrayList<>(target);
        while (lines.size() < target) {
            lines.addAll(base.subList(0, Math.min(base.size(), target - lines.size())));
        }
        List<LogRecord> parsed = new ArrayList<>(lines.size());
        lines.forEach(l -> {
            LogRecord r = ParserStage.parse(l);
            if (r != null) parsed.add(r);
        });

        System.out.printf(Locale.ROOT, "Input: %,d lines (%s repeated), %d timed runs after %d warm-ups%n%n",
                lines.size(), args[0], runs, 3);

        System.out.println("End-to-end (String source -> parse -> ...):");
        Source<String> lineSource = out -> lines.forEach(out::emit);
        printTable(Arrays.asList(
                measure("A status->path->project", runs, () -> variantA(PipelineBuilder.from(lineSource).via(new ParserStage()))),
                measure("B path->status->project", runs, () -> variantB(PipelineBuilder.from(lineSource).via(new ParserStage()))),
                measure("C project->status->path", runs, () -> variantC(PipelineBuilder.from(lineSource).via(new ParserStage())))));

        System.out.println();
        System.out.println("Filters + projection only (pre-parsed LogRecord source, parse cost removed):");
        Source<LogRecord> recordSource = out -> parsed.forEach(out::emit);
        printTable(Arrays.asList(
                measure("A status->path->project", runs, () -> variantA(PipelineBuilder.from(recordSource))),
                measure("B path->status->project", runs, () -> variantB(PipelineBuilder.from(recordSource))),
                measure("C project->status->path", runs, () -> variantC(PipelineBuilder.from(recordSource)))));
    }

    /** Bir varyant kurulumu: sink'e bağlanmaya hazır builder + filtre aşamaları. */
    record Built(PipelineBuilder<ProjectedRecord> builder, List<PredicateFilterStage<?>> filters) {
    }

    static Built variantA(PipelineBuilder<LogRecord> parsed) {
        var status = new PredicateFilterStage<LogRecord>(Filters.minStatus(400), "status>=400");
        var path = new PredicateFilterStage<LogRecord>(Filters.pathPrefix("/api"), "path^/api");
        return new Built(parsed.via(status).via(path).via(new ProjectionStage(FIELDS)), List.of(status, path));
    }

    static Built variantB(PipelineBuilder<LogRecord> parsed) {
        var path = new PredicateFilterStage<LogRecord>(Filters.pathPrefix("/api"), "path^/api");
        var status = new PredicateFilterStage<LogRecord>(Filters.minStatus(400), "status>=400");
        return new Built(parsed.via(path).via(status).via(new ProjectionStage(FIELDS)), List.of(path, status));
    }

    /** Projeksiyondan sonra filtreleme: koşullar artık metin alanlar üzerinde çalışır. */
    static Built variantC(PipelineBuilder<LogRecord> parsed) {
        var status = new PredicateFilterStage<ProjectedRecord>(
                p -> Integer.parseInt(p.get("status")) >= 400, "status>=400 (projected)");
        var path = new PredicateFilterStage<ProjectedRecord>(
                p -> p.get("path").startsWith("/api"), "path^/api (projected)");
        return new Built(parsed.via(new ProjectionStage(FIELDS)).via(status).via(path), List.of(status, path));
    }

    private static Result measure(String name, int runs, java.util.function.Supplier<Built> factory) {
        Function<Built, DigestSink> once = built -> {
            DigestSink sink = new DigestSink();
            built.builder().to(sink).run();
            return sink;
        };
        for (int i = 0; i < 3; i++) {
            once.apply(factory.get()); // JIT ısınması
        }
        double[] ms = new double[runs];
        DigestSink last = null;
        long evaluations = 0;
        for (int i = 0; i < runs; i++) {
            Built built = factory.get();
            long t0 = System.nanoTime();
            last = once.apply(built);
            ms[i] = (System.nanoTime() - t0) / 1e6;
            evaluations = built.filters().stream().mapToLong(PredicateFilterStage::evaluatedCount).sum();
        }
        Arrays.sort(ms);
        return new Result(name, last.count, last.hash, evaluations, ms[runs / 2]);
    }

    private static void printTable(List<Result> results) {
        System.out.printf(Locale.ROOT, "  %-26s %10s %22s %16s %12s%n",
                "variant", "output", "output hash", "predicate evals", "median ms");
        for (Result r : results) {
            System.out.printf(Locale.ROOT, "  %-26s %,10d %22d %,16d %12.1f%n",
                    r.name(), r.count(), r.hash(), r.evaluations(), r.medianMs());
        }
        boolean same = results.stream().allMatch(r -> r.count() == results.get(0).count()
                && r.hash() == results.get(0).hash());
        System.out.println("  identical output: " + same);
    }
}
