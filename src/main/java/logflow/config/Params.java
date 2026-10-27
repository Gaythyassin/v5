package logflow.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Doğrulanmış parametreler. {@link #validate} başarılı olduysa her erişim, ilan edilen türü
 * garanti eder; fabrikalar dönüştürme ya da {@code null} denetimi yapmak zorunda kalmaz.
 */
public final class Params {
    private final String location;
    private final Map<String, Object> values;

    private Params(String location, Map<String, Object> values) {
        this.location = location;
        this.values = values;
    }

    /**
     * Ham parametreleri şemaya göre denetler: bilinmeyen ad, eksik zorunlu parametre, yanlış tür,
     * izin verilmeyen değer. Sorunları {@code problems} listesine ekler.
     *
     * @param location hata mesajlarında konum, ör. {@code pipeline.stages[1] (filter)}
     * @return geçerliyse parametreler, değilse {@code null}
     */
    public static Params validate(String location, Map<String, Object> raw, List<ParamSpec> specs, List<String> problems) {
        int before = problems.size();
        Map<String, Object> values = new LinkedHashMap<>();
        List<String> names = specs.stream().map(ParamSpec::name).toList();
        for (String key : raw.keySet()) {
            if (!names.contains(key)) {
                problems.add(location + ": unknown parameter '" + key + "'." + Suggestions.didYouMean(key, names)
                        + (names.isEmpty() ? " This component takes no parameters." : " Known parameters: " + names));
            }
        }
        for (ParamSpec spec : specs) {
            Object value = raw.get(spec.name());
            if (value == null) {
                if (spec.required()) {
                    problems.add(location + ": missing required parameter '" + spec.name() + "' (" + spec.type().label() + ")"
                            + (spec.description().isEmpty() ? "" : " - " + spec.description()));
                } else if (spec.defaultValue() != null) {
                    values.put(spec.name(), spec.defaultValue());
                }
                continue;
            }
            if (!spec.type().accepts(value)) {
                problems.add(location + ": parameter '" + spec.name() + "' must be " + spec.type().withArticle()
                        + ", got " + ParamType.describe(value) + hint(spec.type(), value));
                continue;
            }
            if (!spec.allowed().isEmpty() && !spec.allowed().contains(value)) {
                problems.add(location + ": parameter '" + spec.name() + "' must be one of " + spec.allowed()
                        + ", got " + ParamType.describe(value) + "." + Suggestions.didYouMean(value.toString(), spec.allowed()));
                continue;
            }
            values.put(spec.name(), value instanceof List<?> list ? List.copyOf(list) : value);
        }
        return problems.size() == before ? new Params(location, values) : null;
    }

    private static String hint(ParamType expected, Object value) {
        if (expected == ParamType.INTEGER && value instanceof String s && s.strip().matches("[-+]?\\d+")) {
            return " (remove the quotes: " + s.strip() + ")";
        }
        if (expected == ParamType.STRING_LIST && value instanceof String s) {
            return " (write a list: [" + s + "])";
        }
        if (expected == ParamType.STRING && value instanceof Number n) {
            return " (quote it: \"" + n + "\")";
        }
        return "";
    }

    public String location() {
        return location;
    }

    public boolean has(String name) {
        return values.containsKey(name);
    }

    public String string(String name) {
        return (String) values.get(name);
    }

    public int integer(String name) {
        Object v = values.get(name);
        long l = ((Number) v).longValue();
        if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("parameter '" + name + "' is out of range: " + l);
        }
        return (int) l;
    }

    public boolean bool(String name) {
        return (Boolean) values.get(name);
    }

    /** Ham değer (ör. {@link ParamType#SCALAR} için metin ya da sayı). */
    public Object value(String name) {
        return values.get(name);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> map(String name) {
        return (Map<String, Object>) values.get(name);
    }

    @SuppressWarnings("unchecked")
    public List<String> stringList(String name) {
        return new ArrayList<>((List<String>) values.get(name));
    }
}
