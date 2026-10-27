package logflow;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@link ProjectionStage} çıktısı: yalnızca seçilen alanları, seçim sırasıyla taşır.
 * Atılan alanlar artık yoktur; sonraki aşamalar onlara erişemez (veri minimizasyonu).
 */
public record ProjectedRecord(Map<String, String> fields) {

    public ProjectedRecord {
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    public String get(String name) {
        String value = fields.get(name);
        if (value == null) {
            throw new IllegalArgumentException("Field not projected: " + name
                    + " (available: " + fields.keySet() + ")");
        }
        return value;
    }

    /** {@code key=value} biçiminde tek satır; boşluk içeren veya boş değerler tırnaklanır. */
    @Override
    public String toString() {
        return fields.entrySet().stream()
                .map(e -> e.getKey() + "=" + quote(e.getValue()))
                .collect(Collectors.joining(" "));
    }

    private static String quote(String v) {
        boolean plain = !v.isEmpty() && v.chars().noneMatch(c -> Character.isWhitespace(c) || c == '"');
        return plain ? v : "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
