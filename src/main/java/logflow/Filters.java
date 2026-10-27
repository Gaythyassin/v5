package logflow;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.function.Predicate;

/** {@link PredicateFilterStage} için hazır {@code Predicate<LogRecord>} koşulları. */
public final class Filters {
    private Filters() {
    }

    /** {@code status >= min}, ör. {@code minStatus(400)} hataları seçer. */
    public static Predicate<LogRecord> minStatus(int min) {
        return r -> r.status() >= min;
    }

    /** {@code path.startsWith(prefix)}, ör. {@code pathPrefix("/api")}. */
    public static Predicate<LogRecord> pathPrefix(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        return r -> r.path().startsWith(prefix);
    }

    /**
     * Zaman damgası {@code [from, to)} aralığında mı? Uçlardan biri {@code null} ise o taraf
     * sınırsızdır. Karşılaştırma ani zamana göredir; farklı saat dilimleri doğru işlenir.
     */
    public static Predicate<LogRecord> between(OffsetDateTime from, OffsetDateTime to) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new IllegalArgumentException("'from' must be before 'to': " + from + " / " + to);
        }
        return r -> (from == null || !r.timestamp().isBefore(from))
                && (to == null || r.timestamp().isBefore(to));
    }

    /**
     * Genel alan koşulu: {@code field op value}. Kurallar alanın türüne göre değişir:
     * <ul>
     *   <li>sayısal alanlar ({@code status, bytes}): {@code value} bir sayı olmalı;</li>
     *   <li>{@code timestamp}: {@code value} ISO-8601 veya CLF zamanı olmalı;</li>
     *   <li>diğer (metin) alanlar: yalnızca {@code == != startsWith endsWith contains}.</li>
     * </ul>
     * {@code startsWith/endsWith/contains} her alanın metin biçimiyle çalışır.
     *
     * @throws IllegalArgumentException alan, işleç ve değer birbirine uymuyorsa (mesaj kullanıcıya gösterilir)
     */
    public static Predicate<LogRecord> compare(String field, Comparison op, Object value) {
        Objects.requireNonNull(op, "op");
        if (!LogRecord.FIELD_NAMES.contains(field)) {
            throw new IllegalArgumentException("unknown field '" + field + "' (known: " + LogRecord.FIELD_NAMES + ")");
        }
        if (op.isTextual()) {
            if (!(value instanceof String text)) {
                throw new IllegalArgumentException("operator '" + op + "' compares text, so 'value' must be a string, got " + show(value));
            }
            return r -> op.testText(r.field(field), text);
        }
        if (LogRecord.NUMERIC_FIELDS.contains(field)) {
            if (!(value instanceof Number number)) {
                throw new IllegalArgumentException("field '" + field + "' is numeric, so 'value' must be a number, got " + show(value)
                        + (value instanceof String s && s.matches("-?\\d+") ? " (remove the quotes)" : ""));
            }
            long expected = number.longValue();
            return r -> op.test(Long.compare(r.number(field), expected));
        }
        if (field.equals("timestamp")) {
            if (!(value instanceof String text)) {
                throw new IllegalArgumentException("field 'timestamp' needs a time string like \"2026-09-29T10:10:00+03:00\", got " + show(value));
            }
            Instant expected = CliOptions.parseTime("value", text).toInstant();
            return r -> op.test(r.timestamp().toInstant().compareTo(expected));
        }
        if (op.isOrdering()) {
            throw new IllegalArgumentException("field '" + field + "' is text; use one of ==, !=, startsWith, endsWith, contains (not '" + op + "')");
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("field '" + field + "' is text, so 'value' must be a string, got " + show(value));
        }
        return r -> op.testText(r.field(field), text);
    }

    private static String show(Object value) {
        return value instanceof String s ? "\"" + s + "\"" : String.valueOf(value);
    }
}
