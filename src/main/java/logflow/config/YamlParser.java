package logflow.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Bağımlılıksız, küçük bir YAML alt kümesi okuyucusu. Proje yalnızca {@code javac} ile
 * derlenebildiği için harici bir kütüphane (SnakeYAML) kullanılmadı.
 *
 * <p>Desteklenenler:
 * <ul>
 *   <li>girintili eşlemeler ({@code key: value}) ve diziler ({@code - item}, {@code - key: value});</li>
 *   <li>tek satırlık akış biçimleri: {@code { a: 1, b: [x, y] }} ve {@code [a, b]};</li>
 *   <li>tek/çift tırnaklı metinler, tamsayılar, ondalıklı sayılar, {@code true/false}, {@code null/~};</li>
 *   <li>{@code #} yorumları ve belge başındaki {@code ---}.</li>
 * </ul>
 * Desteklenmeyenler (çapa/takma ad, çok satırlı metin {@code | >}, çok satırlı akış biçimi)
 * açık bir sözdizimi hatası verir.
 *
 * <p>Sonuç: {@code Map<String,Object>} (sıralı), {@code List<Object>}, {@code String},
 * {@code Integer}/{@code Long}, {@code Double}, {@code Boolean} ya da {@code null}.
 */
public final class YamlParser {
    private static final Pattern INT = Pattern.compile("[-+]?\\d+");
    private static final Pattern DECIMAL = Pattern.compile("[-+]?(\\d+\\.\\d*|\\.\\d+)([eE][-+]?\\d+)?");

    private record Line(int number, int indent, String text) {
    }

    private final String source;
    private final List<Line> lines;
    private int pos;

    private YamlParser(String source, List<Line> lines) {
        this.source = source;
        this.lines = lines;
    }

    /**
     * @param text   YAML metni
     * @param source hata mesajlarında gösterilecek ad (ör. dosya yolu)
     * @return kök değer; belge boşsa {@code null}
     * @throws ConfigException sözdizimi hatasında (satır numarasıyla)
     */
    public static Object parse(String text, String source) {
        YamlParser parser = new YamlParser(source, tokenize(text, source));
        if (parser.lines.isEmpty()) {
            return null;
        }
        Object root = parser.parseNode(parser.lines.get(0).indent());
        if (parser.pos < parser.lines.size()) {
            throw parser.error(parser.lines.get(parser.pos), "unexpected content (check the indentation)");
        }
        return root;
    }

    // ---- satırlara bölme ----

    private static List<Line> tokenize(String text, String source) {
        List<Line> result = new ArrayList<>();
        String[] raw = text.split("\\r?\\n", -1);
        for (int i = 0; i < raw.length; i++) {
            String line = stripComment(raw[i]).stripTrailing();
            int indent = 0;
            while (indent < line.length() && (line.charAt(indent) == ' ' || line.charAt(indent) == '\t')) {
                if (line.charAt(indent) == '\t') {
                    throw new ConfigException(source, "line " + (i + 1) + ": tab character in indentation; YAML allows spaces only");
                }
                indent++;
            }
            String content = line.substring(indent);
            if (content.isEmpty() || (result.isEmpty() && content.equals("---"))) {
                continue;
            }
            result.add(new Line(i + 1, indent, content));
        }
        return result;
    }

    /** {@code #} bir yorum başlatır, ancak satır başında ya da boşluktan sonra ve tırnak dışındaysa. */
    private static String stripComment(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == '\\' && quote == '"') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    // ---- blok yapısı ----

    private Object parseNode(int indent) {
        Line first = lines.get(pos);
        if (isSequenceItem(first.text())) {
            return parseSequence(indent);
        }
        if (keyColon(first.text()) >= 0) {
            return parseMapping(indent);
        }
        pos++;
        return parseInline(first, first.text());
    }

    private List<Object> parseSequence(int indent) {
        List<Object> list = new ArrayList<>();
        while (pos < lines.size()) {
            Line line = lines.get(pos);
            if (line.indent() < indent || (line.indent() == indent && !isSequenceItem(line.text()))) {
                break;
            }
            if (line.indent() > indent) {
                throw error(line, "unexpected indentation");
            }
            String rest = line.text().substring(1);
            String value = rest.strip();
            if (value.isEmpty()) {
                pos++;
                boolean nested = pos < lines.size() && lines.get(pos).indent() > indent;
                list.add(nested ? parseNode(lines.get(pos).indent()) : null);
            } else {
                // "- key: value" -> öğe, '-' işaretinden sonraki sütunda başlayan bir bloktur
                int childIndent = indent + 1 + (rest.length() - rest.stripLeading().length());
                lines.set(pos, new Line(line.number(), childIndent, value));
                list.add(parseNode(childIndent));
            }
        }
        return list;
    }

    private Map<String, Object> parseMapping(int indent) {
        Map<String, Object> map = new LinkedHashMap<>();
        while (pos < lines.size()) {
            Line line = lines.get(pos);
            if (line.indent() < indent) {
                break;
            }
            if (line.indent() > indent) {
                throw error(line, "unexpected indentation");
            }
            if (isSequenceItem(line.text())) {
                break;
            }
            int colon = keyColon(line.text());
            if (colon < 0) {
                throw error(line, "expected 'key: value' but found '" + line.text() + "'");
            }
            String key = unquoteKey(line, line.text().substring(0, colon).strip());
            if (map.containsKey(key)) {
                throw error(line, "duplicate key '" + key + "'");
            }
            String valueText = line.text().substring(colon + 1).strip();
            pos++;
            Object value;
            if (!valueText.isEmpty()) {
                value = parseInline(line, valueText);
            } else if (pos < lines.size() && lines.get(pos).indent() > indent) {
                value = parseNode(lines.get(pos).indent());
            } else if (pos < lines.size() && lines.get(pos).indent() == indent && isSequenceItem(lines.get(pos).text())) {
                value = parseSequence(indent); // "key:\n- a" (dizi, anahtarla aynı girintide)
            } else {
                value = null;
            }
            map.put(key, value);
        }
        return map;
    }

    private static boolean isSequenceItem(String text) {
        return text.equals("-") || text.startsWith("- ");
    }

    /** Tırnak ve köşeli/süslü parantez dışındaki, ardından boşluk ya da satır sonu gelen ilk ':' konumu. */
    private static int keyColon(String text) {
        if (text.startsWith("{") || text.startsWith("[")) {
            return -1;
        }
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == ':' && (i + 1 == text.length() || text.charAt(i + 1) == ' ')) {
                return i;
            }
        }
        return -1;
    }

    private String unquoteKey(Line line, String key) {
        if (key.length() >= 2 && (key.charAt(0) == '"' || key.charAt(0) == '\'') && key.endsWith(key.substring(0, 1))) {
            return key.substring(1, key.length() - 1);
        }
        if (key.isEmpty()) {
            throw error(line, "empty key");
        }
        return key;
    }

    // ---- tek satırlık değerler (akış biçimi) ----

    private Object parseInline(Line line, String text) {
        if (text.startsWith("&") || text.startsWith("*") || text.startsWith("|") || text.startsWith(">") && !text.startsWith(">=")) {
            throw error(line, "unsupported YAML feature in '" + text + "' (anchors, aliases and block scalars are not supported; quote the value)");
        }
        Flow flow = new Flow(line, text);
        Object value = flow.value(false);
        flow.skipSpaces();
        if (!flow.atEnd()) {
            throw error(line, "unexpected '" + text.substring(flow.i) + "' after value");
        }
        return value;
    }

    private final class Flow {
        private final Line line;
        private final String s;
        private int i;

        Flow(Line line, String s) {
            this.line = line;
            this.s = s;
        }

        boolean atEnd() {
            return i >= s.length();
        }

        void skipSpaces() {
            while (i < s.length() && s.charAt(i) == ' ') {
                i++;
            }
        }

        Object value(boolean inFlow) {
            skipSpaces();
            if (atEnd()) {
                return null;
            }
            char c = s.charAt(i);
            if (c == '{') {
                return map();
            }
            if (c == '[') {
                return list();
            }
            if (c == '"' || c == '\'') {
                return quoted();
            }
            return scalar(plain(inFlow, false));
        }

        private Map<String, Object> map() {
            Map<String, Object> map = new LinkedHashMap<>();
            i++; // '{'
            skipSpaces();
            if (consume('}')) {
                return map;
            }
            while (true) {
                skipSpaces();
                String key = !atEnd() && (s.charAt(i) == '"' || s.charAt(i) == '\'') ? quoted() : plain(true, true);
                if (key.isEmpty()) {
                    throw error(line, "empty key in '" + s + "'");
                }
                skipSpaces();
                if (!consume(':')) {
                    throw error(line, "expected ':' after key '" + key + "' in '" + s + "'");
                }
                if (map.containsKey(key)) {
                    throw error(line, "duplicate key '" + key + "'");
                }
                map.put(key, value(true));
                skipSpaces();
                if (consume('}')) {
                    return map;
                }
                if (!consume(',')) {
                    throw error(line, "expected ',' or '}' in '" + s + "' (multi-line { } is not supported)");
                }
            }
        }

        private List<Object> list() {
            List<Object> list = new ArrayList<>();
            i++; // '['
            skipSpaces();
            if (consume(']')) {
                return list;
            }
            while (true) {
                list.add(value(true));
                skipSpaces();
                if (consume(']')) {
                    return list;
                }
                if (!consume(',')) {
                    throw error(line, "expected ',' or ']' in '" + s + "' (multi-line [ ] is not supported)");
                }
            }
        }

        private String quoted() {
            char q = s.charAt(i++);
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == q) {
                    if (q == '\'' && i < s.length() && s.charAt(i) == '\'') { // '' -> '
                        sb.append('\'');
                        i++;
                        continue;
                    }
                    return sb.toString();
                }
                if (c == '\\' && q == '"' && i < s.length()) {
                    char e = s.charAt(i++);
                    sb.append(switch (e) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        default -> e;
                    });
                } else {
                    sb.append(c);
                }
            }
            throw error(line, "unterminated " + (q == '"' ? "double" : "single") + "-quoted string");
        }

        private String plain(boolean inFlow, boolean key) {
            int start = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (inFlow && (c == ',' || c == ']' || c == '}')) {
                    break;
                }
                if (key && c == ':') {
                    break;
                }
                i++;
            }
            return s.substring(start, i).strip();
        }

        private boolean consume(char c) {
            if (i < s.length() && s.charAt(i) == c) {
                i++;
                return true;
            }
            return false;
        }
    }

    /** Tırnaksız bir değerin türünü çözer. */
    static Object scalar(String text) {
        switch (text) {
            case "", "~", "null", "Null", "NULL":
                return null;
            case "true", "True", "TRUE":
                return Boolean.TRUE;
            case "false", "False", "FALSE":
                return Boolean.FALSE;
            default:
                break;
        }
        if (INT.matcher(text).matches()) {
            try {
                long v = Long.parseLong(text.startsWith("+") ? text.substring(1) : text);
                return v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE ? (Object) (int) v : (Object) v;
            } catch (NumberFormatException tooBig) {
                return text;
            }
        }
        if (DECIMAL.matcher(text).matches()) {
            return Double.parseDouble(text);
        }
        return text;
    }

    private ConfigException error(Line line, String message) {
        return new ConfigException(source, "line " + line.number() + ": " + message);
    }
}
