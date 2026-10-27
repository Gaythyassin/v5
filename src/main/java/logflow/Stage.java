package logflow;

public interface Stage<I, O> {
    void process(I input, Emitter<O> out) throws StageException;

    /** Yaşam döngüsü kancası: ilk kayıttan önce bir kez çağrılır. */
    default void open() {
    }

    /**
     * Kaynak tükendikten sonra, {@link #close()}'dan önce bir kez çağrılır. Kayıtları biriktiren
     * aşamalar (ör. {@link AggregateStage}) sonuçlarını burada yayar; varsayılan: hiçbir şey.
     */
    default void finish(Emitter<O> out) throws StageException {
    }

    /** Yaşam döngüsü kancası: son kayıttan sonra, hata olsa bile, bir kez çağrılır. */
    default void close() {
    }
}
