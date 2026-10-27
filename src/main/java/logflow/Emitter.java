package logflow;

@FunctionalInterface
public interface Emitter<T> {
    void emit(T item);
}
