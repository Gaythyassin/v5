package logflow;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * {@link ValidationStage}'in tek bir kuralı. Kural ihlal edilmişse insan için açıklamayı döndürür.
 * Kurallar veridir: yapılandırmadan üretilir, aşama hangi kuralların olduğunu bilmez.
 */
public interface ValidationRule {

    /** @return ihlal varsa açıklama, yoksa boş */
    Optional<String> check(LogRecord r);

    /** Alan boş olmamalı ({@code "-"} ayrıştırıcıda boş metne çevrilir). */
    static ValidationRule required(String field) {
        requireKnown(field);
        return r -> r.field(field).isEmpty() ? Optional.of("required field '" + field + "' is empty") : Optional.empty();
    }

    /** Sayısal alan {@code [min, max]} aralığında olmalı. */
    static ValidationRule range(String field, long min, long max) {
        if (!LogRecord.NUMERIC_FIELDS.contains(field)) {
            throw new IllegalArgumentException("range rules need a numeric field " + LogRecord.NUMERIC_FIELDS + ", got '" + field + "'");
        }
        if (min > max) {
            throw new IllegalArgumentException("range for '" + field + "' has min " + min + " > max " + max);
        }
        return r -> {
            long v = r.number(field);
            return v < min || v > max ? Optional.of(field + " " + v + " is outside [" + min + ", " + max + "]") : Optional.empty();
        };
    }

    /** Zaman damgası {@code [notBefore, notAfter]} aralığında olmalı; uçlardan biri {@code null} olabilir. */
    static ValidationRule timestampBetween(OffsetDateTime notBefore, OffsetDateTime notAfter) {
        if (notBefore != null && notAfter != null && notBefore.isAfter(notAfter)) {
            throw new IllegalArgumentException("timestamp.notBefore " + notBefore + " is after notAfter " + notAfter);
        }
        Instant from = notBefore == null ? null : notBefore.toInstant();
        Instant to = notAfter == null ? null : notAfter.toInstant();
        return r -> {
            Instant t = r.timestamp().toInstant();
            if (from != null && t.isBefore(from)) {
                return Optional.of("timestamp " + LogRecord.TIMESTAMP_FORMAT.format(r.timestamp()) + " is before " + notBefore);
            }
            if (to != null && t.isAfter(to)) {
                return Optional.of("timestamp " + LogRecord.TIMESTAMP_FORMAT.format(r.timestamp()) + " is after " + notAfter);
            }
            return Optional.empty();
        };
    }

    /** {@code clientIp} geçerli bir IPv4 (ya da {@code allowIpv6} ise IPv6) adresi olmalı. DNS sorgusu yapılmaz. */
    static ValidationRule ipAddress(boolean allowIpv6) {
        return r -> {
            String ip = r.clientIp();
            boolean ok = IpAddresses.isIpv4(ip) || (allowIpv6 && IpAddresses.isIpv6(ip));
            return ok ? Optional.empty()
                    : Optional.of("clientIp '" + ip + "' is not a valid " + (allowIpv6 ? "IPv4 or IPv6" : "IPv4") + " address");
        };
    }

    /** {@code method} izin verilen listede olmalı. */
    static ValidationRule methodIn(List<String> methods) {
        List<String> allowed = List.copyOf(methods);
        return r -> allowed.contains(r.method()) ? Optional.empty()
                : Optional.of("method '" + r.method() + "' is not one of " + allowed);
    }

    private static void requireKnown(String field) {
        if (!LogRecord.FIELD_NAMES.contains(field)) {
            throw new IllegalArgumentException("unknown field '" + field + "' (known: " + LogRecord.FIELD_NAMES + ")");
        }
    }
}
