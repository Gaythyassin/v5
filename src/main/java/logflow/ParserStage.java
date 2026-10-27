package logflow;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Common Log Format (isteğe bağlı Combined alanlarıyla) satırını {@link LogRecord}'a çevirir.
 *
 * <p>v5: bozuk bir satır artık sessizce atlanmaz; nedeniyle birlikte {@link StageException}
 * fırlatılır ve ne olacağına aşamanın hata politikası karar verir. Bu aşama yalnızca
 * <b>sözdizimini</b> denetler; değerlerin anlamlı olup olmadığı (negatif bayt, 1970 tarihi,
 * geçersiz IP) {@link ValidationStage}'in işidir.
 */
public class ParserStage implements Stage<String, LogRecord>, Reportable {
    private static final Pattern CLF = Pattern.compile(
            "^(\\S+) \\S+ (\\S+) \\[([^\\]]+)\\] \"(\\S+) (\\S+)(?: (\\S+))?\" (\\d{3}) (-?\\d+|-)"
                    + "(?: \"([^\"]*)\" \"([^\"]*)\")?\\s*$");
    /** Katı çözümleme: {@code 31/Feb} reddedilir ({@code SMART} onu sessizce 28 Şubat yapardı). */
    static final DateTimeFormatter CLF_TIME =
            DateTimeFormatter.ofPattern("dd/MMM/uuuu:HH:mm:ss Z", Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT);

    /** Bu uzunluktan uzun satırlar reddedilir (bozuk dosya, sonsuz satır). */
    public static final int DEFAULT_MAX_LINE_LENGTH = 8192;

    /** Kod çözücünün geçersiz UTF-8 baytları yerine koyduğu karakter. */
    static final char REPLACEMENT = '�';

    private final int maxLineLength;
    private long parsed;
    private long malformed;

    public ParserStage() {
        this(DEFAULT_MAX_LINE_LENGTH);
    }

    public ParserStage(int maxLineLength) {
        if (maxLineLength < 1) {
            throw new IllegalArgumentException("maxLineLength must be positive, got " + maxLineLength);
        }
        this.maxLineLength = maxLineLength;
    }

    @Override
    public void process(String line, Emitter<LogRecord> out) throws StageException {
        LogRecord record;
        try {
            record = parseStrict(line, maxLineLength);
        } catch (StageException e) {
            malformed++;
            throw e;
        }
        parsed++;
        out.emit(record);
    }

    /** Satırı ayrıştırır; biçim geçersizse {@code null} döner (nedeni gerekmeyen çağıranlar için). */
    static LogRecord parse(String line) {
        try {
            return parseStrict(line, Integer.MAX_VALUE);
        } catch (StageException e) {
            return null;
        }
    }

    /** @throws StageException satır ayrıştırılamıyorsa; mesaj nedeni söyler */
    static LogRecord parseStrict(String line, int maxLineLength) throws StageException {
        if (line == null || line.isBlank()) {
            throw new StageException("empty line");
        }
        if (line.length() > maxLineLength) {
            throw new StageException("line too long: " + line.length() + " characters (limit " + maxLineLength + ")");
        }
        if (line.indexOf(REPLACEMENT) >= 0) {
            throw new StageException("line is not valid UTF-8 (undecodable bytes at column " + (line.indexOf(REPLACEMENT) + 1) + ")");
        }
        Matcher m = CLF.matcher(line);
        if (!m.matches()) {
            throw new StageException("not a Common Log Format line" + (line.length() < 40 || !line.contains("\"") ? "" : " (truncated?)"));
        }
        OffsetDateTime ts;
        try {
            ts = OffsetDateTime.parse(m.group(3), CLF_TIME);
        } catch (DateTimeParseException e) {
            throw new StageException("invalid timestamp '" + m.group(3) + "'", e);
        }
        long bytes;
        try {
            bytes = "-".equals(m.group(8)) ? 0 : Long.parseLong(m.group(8));
        } catch (NumberFormatException e) {
            throw new StageException("byte count out of range: " + m.group(8), e);
        }
        String user = "-".equals(m.group(2)) ? "" : m.group(2);
        String referer = m.group(9) == null || "-".equals(m.group(9)) ? "" : m.group(9);
        return new LogRecord(m.group(1), user, ts, m.group(4), m.group(5), m.group(6),
                Integer.parseInt(m.group(7)), bytes, referer, m.group(10), null);
    }

    public long parsedCount() {
        return parsed;
    }

    public long malformedCount() {
        return malformed;
    }

    public int maxLineLength() {
        return maxLineLength;
    }

    @Override
    public String report() {
        return "parse      parsed: " + parsed + ", malformed: " + malformed;
    }
}
