package logflow.plugins;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import logflow.LogRecord;
import logflow.Stage;
import logflow.ValidationRule;
import logflow.ValidationStage;
import logflow.config.ParamSpec;
import logflow.config.ParamType;
import logflow.config.Params;
import logflow.config.StageFactory;

/**
 * {@code - type: validate} — bildirimsel kurallar:
 *
 * <pre>
 * params:
 *   required: [clientIp, method, path]
 *   ranges:   { status: [100, 599], bytes: [0, 1073741824] }
 *   timestamp: { notBefore: "2000-01-01T00:00:00Z", notAfter: "2030-12-31T23:59:59Z" }
 *   ip: ipv4                # ya da any (IPv4 veya IPv6)
 *   methods: [GET, POST, PUT, DELETE, HEAD]
 * </pre>
 */
public class ValidateStageFactory implements StageFactory {

    @Override
    public String type() {
        return "validate";
    }

    @Override
    public String description() {
        return "rejects records that break declarative rules; the stage's onError policy decides where they go (LogRecord -> LogRecord)";
    }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.optional("required", ParamType.STRING_LIST, null, "fields that must not be empty"),
                ParamSpec.optional("ranges", ParamType.MAP, null, "numeric field -> [min, max], e.g. { status: [100, 599] }"),
                ParamSpec.optional("timestamp", ParamType.MAP, null, "{ notBefore: TIME, notAfter: TIME }"),
                ParamSpec.optional("ip", ParamType.STRING, null, "clientIp format").oneOf(List.of("ipv4", "any")),
                ParamSpec.optional("methods", ParamType.STRING_LIST, null, "allowed HTTP methods"));
    }

    @Override
    public Class<?> inputType() {
        return LogRecord.class;
    }

    @Override
    public Class<?> outputType() {
        return LogRecord.class;
    }

    @Override
    public Stage<?, ?> create(Params params) {
        List<ValidationRule> rules = new ArrayList<>();
        if (params.has("required")) {
            params.stringList("required").forEach(f -> rules.add(ValidationRule.required(f)));
        }
        if (params.has("ranges")) {
            for (Map.Entry<String, Object> e : params.map("ranges").entrySet()) {
                if (!(e.getValue() instanceof List<?> bounds) || bounds.size() != 2
                        || !(bounds.get(0) instanceof Integer || bounds.get(0) instanceof Long)
                        || !(bounds.get(1) instanceof Integer || bounds.get(1) instanceof Long)) {
                    throw new IllegalArgumentException("ranges." + e.getKey() + " must be [min, max] with two integers, got " + e.getValue());
                }
                rules.add(ValidationRule.range(e.getKey(), ((Number) bounds.get(0)).longValue(), ((Number) bounds.get(1)).longValue()));
            }
        }
        if (params.has("timestamp")) {
            Map<String, Object> t = params.map("timestamp");
            for (String key : t.keySet()) {
                if (!key.equals("notBefore") && !key.equals("notAfter")) {
                    throw new IllegalArgumentException("timestamp." + key + " is not a known rule; use notBefore and/or notAfter");
                }
            }
            if (t.isEmpty()) {
                throw new IllegalArgumentException("timestamp needs notBefore and/or notAfter");
            }
            rules.add(ValidationRule.timestampBetween(time(t, "notBefore"), time(t, "notAfter")));
        }
        if (params.has("ip")) {
            rules.add(ValidationRule.ipAddress(params.string("ip").equals("any")));
        }
        if (params.has("methods")) {
            rules.add(ValidationRule.methodIn(params.stringList("methods")));
        }
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("no rules given; set at least one of required, ranges, timestamp, ip, methods");
        }
        return new ValidationStage(rules);
    }

    private static OffsetDateTime time(Map<String, Object> t, String key) {
        Object v = t.get(key);
        if (v == null) {
            return null;
        }
        if (!(v instanceof String s)) {
            throw new IllegalArgumentException("timestamp." + key + " must be a time string like \"2026-01-01T00:00:00Z\", got " + v);
        }
        try {
            return OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("timestamp." + key + " is not an ISO-8601 time with offset: \"" + s + "\"");
        }
    }
}
