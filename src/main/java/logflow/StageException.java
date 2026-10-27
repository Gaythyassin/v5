package logflow;

/**
 * Bir aşama <b>tek bir kaydı</b> işleyemedi; işlem hattı devam edebilir. Ne olacağına aşama
 * yakalamaz: aşamanın {@link ErrorPolicy}'si karar verir (durdur, atla, dead-letter, yeniden dene).
 * Mesaj, kaydın neden reddedildiğini insan için açıklamalıdır.
 *
 * @see PipelineException devam edilemeyen hatalar
 */
public class StageException extends Exception {
    private static final long serialVersionUID = 1L;

    public StageException(String message) {
        super(message);
    }

    public StageException(String message, Throwable cause) {
        super(message, cause);
    }

    public StageException(Throwable cause) {
        super(cause);
    }
}
