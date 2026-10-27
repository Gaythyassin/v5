package logflow;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pipeline boyunca taşınan değişmez (immutable) alan modeli.
 *
 * <p>{@code attributes} genişleme noktasıdır: sonraki aşamalar ayrıştırıcının bilmediği
 * verileri {@link #withAttribute(String, String)} ile kopya oluşturarak ekler.
 */
public record LogRecord(
        String clientIp,
        String user,
        OffsetDateTime timestamp,
        String method,
        String path,
        String protocol,
        int status,
        long bytes,
        String referer,
        String userAgent,
        Map<String, String> attributes) {

    /** Projeksiyonda seçilebilecek alan adları (tanım sırasıyla). */
    public static final List<String> FIELD_NAMES = List.of(
            "clientIp", "user", "timestamp", "method", "path",
            "protocol", "status", "bytes", "referer", "userAgent");

    /** Sayısal alanlar: karşılaştırma ve toplama bunlar üzerinde sayı olarak yapılır. */
    public static final List<String> NUMERIC_FIELDS = List.of("status", "bytes");

    /** Saniyeleri her zaman içeren ISO-8601 biçimi (toString() ":00" saniyeyi düşürür). */
    public static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    public LogRecord {
        Objects.requireNonNull(clientIp, "clientIp");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        user = user == null ? "" : user;
        protocol = protocol == null ? "" : protocol;
        referer = referer == null ? "" : referer;
        userAgent = userAgent == null ? "" : userAgent;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /** Ek alan içeren yeni bir kopya döndürür; bu nesne değişmez. */
    public LogRecord withAttribute(String key, String value) {
        Map<String, String> copy = new HashMap<>(attributes);
        copy.put(key, value);
        return new LogRecord(clientIp, user, timestamp, method, path, protocol,
                status, bytes, referer, userAgent, copy);
    }

    /** Sayısal bir alanın değeri ({@link #NUMERIC_FIELDS}). */
    public long number(String name) {
        return switch (name) {
            case "status" -> status;
            case "bytes" -> bytes;
            default -> throw new IllegalArgumentException("Not a numeric field: " + name + " (numeric: " + NUMERIC_FIELDS + ")");
        };
    }

    /** Bir alanın değerini adıyla, metin olarak döndürür (projeksiyon için). */
    public String field(String name) {
        return switch (name) {
            case "clientIp" -> clientIp;
            case "user" -> user;
            case "timestamp" -> TIMESTAMP_FORMAT.format(timestamp);
            case "method" -> method;
            case "path" -> path;
            case "protocol" -> protocol;
            case "status" -> Integer.toString(status);
            case "bytes" -> Long.toString(bytes);
            case "referer" -> referer;
            case "userAgent" -> userAgent;
            default -> {
                String v = attributes.get(name);
                if (v == null) {
                    throw new IllegalArgumentException("Unknown field: " + name);
                }
                yield v;
            }
        };
    }
}
