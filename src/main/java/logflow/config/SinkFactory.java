package logflow.config;

import logflow.Sink;

/** {@code sink} bölümünü bir {@link Sink} nesnesine çevirir. */
public interface SinkFactory extends ComponentFactory {
    Class<?> inputType();

    /** @throws IllegalArgumentException parametreler geçersizse */
    Sink<?> create(Params params);
}
