package logflow;

/**
 * İşlem hattı devam edemez: yapılandırma hatası, okunamayan girdi, yazılamayan çıktı (disk dolu),
 * çalışmayan dead-letter hedefi ya da {@code FAIL_FAST} politikasıyla durdurulan bir kayıt.
 *
 * <p>Kayıt düzeyindeki {@link StageException}'ın tersine kurtarılamaz: hiçbir hata politikası
 * onu yakalamaz, çalıştırma durur ve bütün bileşenler kapatılır. Çalıştırma başladıysa, o ana
 * kadarki {@link RunSummary} de taşınır.
 */
public class PipelineException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private transient RunSummary summary;

    public PipelineException(String message) {
        super(message);
    }

    public PipelineException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Durma anına kadarki özet; çalıştırma hiç başlamadıysa (ör. yapılandırma hatası) {@code null}. */
    public RunSummary summary() {
        return summary;
    }

    PipelineException withSummary(RunSummary summary) {
        if (this.summary == null) {
            this.summary = summary;
        }
        return this;
    }
}
