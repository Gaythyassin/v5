package logflow.config;

import java.util.List;
import logflow.PipelineException;

/**
 * Yapılandırma zamanı hatası: dosya geçersiz, hiçbir veri işlenmedi. Bir {@link PipelineException}
 * türüdür: işlem hattı hiç başlayamaz. Yükleyici bulduğu
 * <b>bütün</b> sorunları toplar, böylece kullanıcı hepsini tek seferde düzeltebilir.
 *
 * <p>Çalışma zamanı veri hatalarından (bozuk log satırı, okunamayan dosya) ayrıdır; onlar
 * işlem sırasında ortaya çıkar ve bu sınıfla bildirilmez.
 */
public class ConfigException extends PipelineException {
    private static final long serialVersionUID = 1L;

    private final String source;
    private final transient List<String> problems;

    public ConfigException(String source, List<String> problems) {
        super(format(source, problems));
        this.source = source;
        this.problems = List.copyOf(problems);
    }

    public ConfigException(String source, String problem) {
        this(source, List.of(problem));
    }

    /** Yapılandırma dosyasının adı (mesajlarda gösterilir). */
    public String source() {
        return source;
    }

    /** Her biri {@code konum: açıklama} biçiminde sorunlar. */
    public List<String> problems() {
        return problems;
    }

    private static String format(String source, List<String> problems) {
        StringBuilder sb = new StringBuilder("Invalid pipeline configuration '").append(source).append("' (")
                .append(problems.size()).append(problems.size() == 1 ? " problem" : " problems")
                .append("); nothing was processed:");
        for (String p : problems) {
            sb.append(System.lineSeparator()).append("  - ").append(p);
        }
        return sb.toString();
    }
}
