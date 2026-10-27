package logflow;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bir aşama bir kaydı işleyemediğinde ({@link StageException}) ne olacağı. Aşama başına,
 * yapılandırmada seçilir; aşamanın kodu bundan habersizdir. Hata yönlendirmesi böylece bir
 * {@code try/catch} ayrıntısı değil, işlem hattının topolojisinin parçası olur.
 *
 * <ul>
 *   <li>{@code FAIL_FAST}: çalıştırmayı durdur ({@link PipelineException}).</li>
 *   <li>{@code SKIP}: kaydı at, say; özet raporda görünür.</li>
 *   <li>{@code DEAD_LETTER}: kaydı tanılama bağlamıyla ({@link FailedRecord}) dead-letter kanalına gönder.</li>
 *   <li>{@code RETRY(n)}: n kez daha dene; hâlâ başarısızsa dead-letter kanalına gönder.
 *       Geçici G/Ç hataları içindir; aynı girdiyle hep aynı sonucu veren aşamalarda işe yaramaz.</li>
 * </ul>
 */
public record ErrorPolicy(Kind kind, int retries) {

    public enum Kind { FAIL_FAST, SKIP, DEAD_LETTER, RETRY }

    public static final ErrorPolicy FAIL_FAST = new ErrorPolicy(Kind.FAIL_FAST, 0);
    public static final ErrorPolicy SKIP = new ErrorPolicy(Kind.SKIP, 0);
    public static final ErrorPolicy DEAD_LETTER = new ErrorPolicy(Kind.DEAD_LETTER, 0);
    public static final int MAX_RETRIES = 10;

    /** Yapılandırmada kullanılabilecek yazımlar. */
    public static final List<String> SYNTAX = List.of("FAIL_FAST", "SKIP", "DEAD_LETTER", "RETRY(n)");

    private static final Pattern RETRY = Pattern.compile("RETRY\\((\\d+)\\)");

    public ErrorPolicy {
        if (kind == Kind.RETRY ? retries < 1 || retries > MAX_RETRIES : retries != 0) {
            throw new IllegalArgumentException("RETRY(n) needs 1 <= n <= " + MAX_RETRIES + ", got " + retries);
        }
    }

    public static ErrorPolicy retry(int n) {
        return new ErrorPolicy(Kind.RETRY, n);
    }

    /**
     * @param text {@code FAIL_FAST}, {@code SKIP}, {@code DEAD_LETTER} ya da {@code RETRY(3)}
     * @throws IllegalArgumentException tanınmayan yazımda (mesaj kullanıcıya gösterilir)
     */
    public static ErrorPolicy parse(String text) {
        String t = text.strip();
        switch (t) {
            case "FAIL_FAST":
                return FAIL_FAST;
            case "SKIP":
                return SKIP;
            case "DEAD_LETTER":
                return DEAD_LETTER;
            default:
                break;
        }
        Matcher m = RETRY.matcher(t);
        if (m.matches()) {
            int n;
            try {
                n = Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                n = Integer.MAX_VALUE;
            }
            return retry(n);
        }
        throw new IllegalArgumentException("unknown error policy '" + text + "'; use one of " + SYNTAX);
    }

    /** Başarısız kayıtlar sonunda dead-letter kanalına mı gider? (O zaman kanal tanımlı olmalıdır.) */
    public boolean needsDeadLetter() {
        return kind == Kind.DEAD_LETTER || kind == Kind.RETRY;
    }

    @Override
    public String toString() {
        return kind == Kind.RETRY ? "RETRY(" + retries + ")" : kind.name();
    }
}
