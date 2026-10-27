package logflow.config;

import java.util.List;
import java.util.Objects;

/**
 * Bir parametrenin bildirimsel tanımı. Fabrikalar parametrelerini bu şekilde ilan eder;
 * {@link Params#validate} hepsini aynı kurallarla denetler, böylece her aşama kendi
 * doğrulama kodunu yazmak zorunda kalmaz ve {@code --list-stages} çıktısı da buradan üretilir.
 *
 * @param allowed boş değilse izin verilen metin değerleri
 */
public record ParamSpec(String name, ParamType type, boolean required, Object defaultValue,
                        List<String> allowed, String description) {

    public ParamSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        allowed = List.copyOf(allowed);
        description = description == null ? "" : description;
        if (required && defaultValue != null) {
            throw new IllegalArgumentException("A required parameter has no default: " + name);
        }
    }

    public static ParamSpec required(String name, ParamType type, String description) {
        return new ParamSpec(name, type, true, null, List.of(), description);
    }

    public static ParamSpec optional(String name, ParamType type, Object defaultValue, String description) {
        return new ParamSpec(name, type, false, defaultValue, List.of(), description);
    }

    /** Aynı tanım, değer kümesi kısıtlamasıyla. */
    public ParamSpec oneOf(List<String> values) {
        return new ParamSpec(name, type, required, defaultValue, values, description);
    }

    /** {@code --list-stages} için tek satırlık özet. */
    public String summary() {
        StringBuilder sb = new StringBuilder(name).append(" (").append(type.label());
        sb.append(required ? ", required" : ", optional" + (defaultValue == null ? "" : ", default " + defaultValue));
        sb.append(")");
        if (!allowed.isEmpty()) {
            sb.append(" one of ").append(allowed);
        }
        if (!description.isEmpty()) {
            sb.append(": ").append(description);
        }
        return sb.toString();
    }
}
