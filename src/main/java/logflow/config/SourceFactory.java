package logflow.config;

import logflow.Source;

/** {@code source} bölümünü bir {@link Source} nesnesine çevirir. */
public interface SourceFactory extends ComponentFactory {
    Class<?> outputType();

    /** @throws IllegalArgumentException parametreler geçersizse (ör. dosya yok) */
    Source<?> create(Params params);
}
