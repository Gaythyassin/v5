package logflow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kayıtları bir alana göre gruplar; her grup için istek sayısını ve (isteğe bağlı) sayısal bir
 * alanın toplamını tutar. Akış sırasında hiçbir şey yaymaz; sonuçları {@link #finish} sırasında,
 * en büyükten küçüğe sıralı olarak yayar.
 *
 * <p>Örnek: {@code groupBy=clientIp, sum=bytes} → {@code clientIp=10.0.0.1 requests=12 bytes=48213}
 */
public class AggregateStage implements Stage<LogRecord, ProjectedRecord>, Reportable {
    private final String groupBy;
    private final String sum;
    private final int top;
    private final Map<String, long[]> groups = new HashMap<>(); // [istek sayısı, toplam]
    private long seen;
    private long emitted;

    /**
     * @param groupBy gruplama alanı ({@link LogRecord#FIELD_NAMES})
     * @param sum     toplanacak sayısal alan ya da {@code null}
     * @param top     yalnızca ilk N grubu yay; 0 = hepsi
     */
    public AggregateStage(String groupBy, String sum, int top) {
        if (!LogRecord.FIELD_NAMES.contains(groupBy)) {
            throw new IllegalArgumentException("unknown field '" + groupBy + "' (known: " + LogRecord.FIELD_NAMES + ")");
        }
        if (sum != null && !LogRecord.NUMERIC_FIELDS.contains(sum)) {
            throw new IllegalArgumentException("can only sum a numeric field " + LogRecord.NUMERIC_FIELDS + ", got '" + sum + "'");
        }
        if (top < 0) {
            throw new IllegalArgumentException("'top' must be 0 (all) or positive, got " + top);
        }
        this.groupBy = groupBy;
        this.sum = sum;
        this.top = top;
    }

    @Override
    public void open() {
        groups.clear();
        seen = 0;
        emitted = 0;
    }

    @Override
    public void process(LogRecord input, Emitter<ProjectedRecord> out) {
        seen++;
        long[] g = groups.computeIfAbsent(input.field(groupBy), k -> new long[2]);
        g[0]++;
        if (sum != null) {
            g[1] += input.number(sum);
        }
    }

    @Override
    public void finish(Emitter<ProjectedRecord> out) {
        int rankBy = sum == null ? 0 : 1;
        List<Map.Entry<String, long[]>> sorted = new ArrayList<>(groups.entrySet());
        sorted.sort(Comparator.<Map.Entry<String, long[]>>comparingLong(e -> -e.getValue()[rankBy])
                .thenComparing(Map.Entry::getKey));
        int limit = top == 0 ? sorted.size() : Math.min(top, sorted.size());
        for (Map.Entry<String, long[]> e : sorted.subList(0, limit)) {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put(groupBy, e.getKey());
            fields.put("requests", Long.toString(e.getValue()[0]));
            if (sum != null) {
                fields.put(sum, Long.toString(e.getValue()[1]));
            }
            emitted++;
            out.emit(new ProjectedRecord(fields));
        }
    }

    @Override
    public String report() {
        return String.format("aggregate  by %-27s in: %d, groups: %d, emitted: %d", groupBy, seen, groups.size(), emitted);
    }
}
