package logflow;

public interface Sink<I> {
    void consume(I item);

    /** Yaşam döngüsü kancası: ilk kayıttan önce bir kez çağrılır (ör. dosyayı aç). */
    default void open() {
    }

    /**
     * Yaşam döngüsü kancası: son kayıttan sonra, hata olsa bile, bir kez çağrılır.
     * Arabellekli bir yazıcıyı kapatmayan sink, arabellekteki çıktıyı sessizce kaybeder.
     */
    default void close() {
    }
}
