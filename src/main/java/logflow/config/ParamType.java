package logflow.config;

import java.util.List;
import java.util.Map;

/** Bir aşama parametresinin beklenen türü. Doğrulama yükleme sırasında, veri işlenmeden yapılır. */
public enum ParamType {
    STRING("string"),
    INTEGER("integer"),
    BOOLEAN("boolean"),
    /** Metin ya da sayı; anlamı aşamaya göre değişir (ör. {@code filter.value}). */
    SCALAR("string or number"),
    STRING_LIST("list of strings"),
    /** İç içe yapı ({@code { a: 1, b: [x] }}); içeriğini fabrika denetler. */
    MAP("mapping");

    private final String label;

    ParamType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** {@code "an integer"}, {@code "a list of strings"} */
    public String withArticle() {
        return ("aeiou".indexOf(label.charAt(0)) >= 0 ? "an " : "a ") + label;
    }

    public boolean accepts(Object value) {
        return switch (this) {
            case STRING -> value instanceof String;
            case INTEGER -> value instanceof Integer || value instanceof Long;
            case BOOLEAN -> value instanceof Boolean;
            case SCALAR -> value instanceof String || value instanceof Number;
            case STRING_LIST -> value instanceof List<?> list && list.stream().allMatch(e -> e instanceof String);
            case MAP -> value instanceof Map<?, ?>;
        };
    }

    /** Hata mesajları için: {@code string "abc"}, {@code integer 400}, {@code list [a, 1]} ... */
    public static String describe(Object value) {
        if (value == null) {
            return "nothing (null)";
        }
        if (value instanceof String s) {
            return "string \"" + s + "\"";
        }
        if (value instanceof Integer || value instanceof Long) {
            return "integer " + value;
        }
        if (value instanceof Number) {
            return "number " + value;
        }
        if (value instanceof Boolean) {
            return "boolean " + value;
        }
        if (value instanceof List<?> list) {
            return "list " + list;
        }
        if (value instanceof Map<?, ?> map) {
            return "mapping " + map;
        }
        return value.getClass().getSimpleName() + " " + value;
    }
}
