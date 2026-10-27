package logflow;

import java.time.Instant;
import java.util.Objects;

/**
 * Dead-letter kanalında taşınan başarısız kayıt: ham girdi ve tanılama bağlamı.
 *
 * @param rawInput     işlem hattına giren özgün kayıt (ör. log satırı), değiştirilmeden
 * @param stageName    kaydı reddeden aşamanın adı
 * @param exception    nedeni ({@link StageException#getMessage()} insan için açıklamadır)
 * @param timestamp    hatanın oluştuğu an
 * @param recordNumber kaynaktaki sıra numarası (1'den başlar; dosyada satır numarası),
 *                     kaynaktan bağımsız bir hata için (ör. {@code finish}) 0
 */
public record FailedRecord(String rawInput, String stageName, StageException exception, Instant timestamp, long recordNumber) {

    public FailedRecord {
        Objects.requireNonNull(rawInput, "rawInput");
        Objects.requireNonNull(stageName, "stageName");
        Objects.requireNonNull(exception, "exception");
        Objects.requireNonNull(timestamp, "timestamp");
    }

    /** Kısa neden metni. */
    public String reason() {
        return exception.getMessage();
    }

    @Override
    public String toString() {
        return "#" + recordNumber + " stage=" + stageName + " error=\"" + reason() + "\" raw=\"" + rawInput + "\"";
    }
}
