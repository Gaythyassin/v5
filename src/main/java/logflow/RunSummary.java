package logflow;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Bir çalıştırmanın özeti: kaç kayıt girdi, kaçı ana hedefe, kaçı dead-letter kanalına gitti,
 * her aşamada ne oldu ve ne kadar sürdü. Her çalıştırmanın sonunda yazdırılır; {@link #toJson()}
 * aynı veriyi makinece okunur biçimde verir (sonraki haftalardaki ölçümlerin temeli).
 *
 * @param status  {@code COMPLETED} ya da {@code FAILED} (bir {@link PipelineException} ile durdu)
 * @param failure durma nedeni; tamamlandıysa {@code null}
 */
public record RunSummary(String pipeline, Status status, String failure, Instant startedAt, Duration elapsed,
                         long recordsIn, long recordsOut, long deadLettered, long sinkNanos, List<StageMetrics> stages) {

    public enum Status { COMPLETED, FAILED }

    public RunSummary {
        stages = List.copyOf(stages);
    }

    public long dropped() {
        return stages.stream().mapToLong(StageMetrics::dropped).sum();
    }

    public long skipped() {
        return stages.stream().mapToLong(StageMetrics::skipped).sum();
    }

    public long failed() {
        return stages.stream().mapToLong(StageMetrics::failed).sum();
    }

    /** Uçtan uca denkleştirme yalnızca bütün aşamalar bire-bir ise anlamlıdır (toplama yapan aşama yoksa). */
    public boolean balanceApplies() {
        return stages.stream().allMatch(StageMetrics::oneToOne);
    }

    /** {@code giren = ana hedef + elenen + atlanan + dead-letter}: her kayıt tam olarak bir yerde. */
    public boolean balanced() {
        return recordsIn == recordsOut + dropped() + skipped() + deadLettered;
    }

    public String format() {
        StringBuilder sb = new StringBuilder();
        String nl = System.lineSeparator();
        sb.append(String.format(Locale.ROOT, "Run summary: pipeline '%s' %s in %.3f s%n", pipeline, status, elapsed.toNanos() / 1e9));
        if (failure != null) {
            sb.append("  stopped by: ").append(failure).append(nl);
        }
        sb.append(String.format(Locale.ROOT, "  records in: %d   to sink: %d   to dead letter: %d%n", recordsIn, recordsOut, deadLettered));
        sb.append(String.format(Locale.ROOT, "  %-14s %-12s %7s %7s %8s %7s %8s %12s %8s %9s%n",
                "stage", "policy", "in", "out", "dropped", "failed", "skipped", "dead-letter", "retries", "time ms"));
        for (StageMetrics s : stages) {
            sb.append(String.format(Locale.ROOT, "  %-14s %-12s %7d %7d %8d %7d %8d %12d %8d %9.1f%n",
                    s.name(), s.policy(), s.in(), s.out(), s.dropped(), s.failed(), s.skipped(), s.deadLettered(),
                    s.retries(), s.selfNanos() / 1e6));
        }
        if (!balanceApplies()) {
            sb.append("  balance: n/a (a stage merges records, e.g. aggregate)");
        } else {
            sb.append(String.format(Locale.ROOT, "  balance: in %d = sink %d + dropped %d + skipped %d + dead letter %d  %s",
                    recordsIn, recordsOut, dropped(), skipped(), deadLettered, balanced() ? "OK" : "MISMATCH"));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return format();
    }

    public String toJson() {
        String stagesJson = stages.stream().map(s -> String.format(Locale.ROOT,
                "{\"name\":%s,\"policy\":%s,\"in\":%d,\"out\":%d,\"dropped\":%d,\"failed\":%d,\"skipped\":%d,"
                        + "\"deadLettered\":%d,\"retries\":%d,\"selfMillis\":%.3f,\"oneToOne\":%b}",
                Json.quote(s.name()), Json.quote(s.policy()), s.in(), s.out(), s.dropped(), s.failed(), s.skipped(),
                s.deadLettered(), s.retries(), s.selfNanos() / 1e6, s.oneToOne())).collect(Collectors.joining(","));
        return String.format(Locale.ROOT,
                "{\"pipeline\":%s,\"status\":\"%s\",\"failure\":%s,\"startedAt\":\"%s\",\"elapsedMillis\":%.3f,"
                        + "\"recordsIn\":%d,\"recordsOut\":%d,\"deadLettered\":%d,\"dropped\":%d,\"skipped\":%d,"
                        + "\"sinkMillis\":%.3f,\"balanced\":%s,\"stages\":[%s]}",
                Json.quote(pipeline), status, failure == null ? "null" : Json.quote(failure), startedAt, elapsed.toNanos() / 1e6,
                recordsIn, recordsOut, deadLettered, dropped(), skipped(), sinkNanos / 1e6,
                balanceApplies() ? Boolean.toString(balanced()) : "null", stagesJson);
    }
}
