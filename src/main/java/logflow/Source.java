package logflow;

public interface Source<O> {
    void produce(Emitter<O> out);
}
