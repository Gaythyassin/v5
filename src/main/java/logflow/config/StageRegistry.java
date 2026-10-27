package logflow.config;

import java.util.List;
import java.util.ServiceLoader;

/**
 * Aşama kayıt sistemi: aşama türü adlarını {@link StageFactory} uygulamalarına eşler.
 * Yerleşik aşamalar {@code META-INF/services/logflow.config.StageFactory} dosyasında ilan edilir.
 */
public final class StageRegistry extends Registry<StageFactory> {

    public StageRegistry() {
        super("stage");
    }

    /** Sınıf yolundaki bütün aşamalar ({@link ServiceLoader}). */
    public static StageRegistry discover() {
        StageRegistry registry = new StageRegistry();
        registry.registerAll(ServiceLoader.load(StageFactory.class));
        return registry;
    }

    @Override
    public StageRegistry register(StageFactory factory) {
        super.register(factory);
        return this;
    }

    /** {@code from} türünü {@code to} türüne çeviren aşamalar (hata mesajındaki öneriler için). */
    public List<String> converters(Class<?> from, Class<?> to) {
        return all().stream()
                .filter(f -> f.inputType().isAssignableFrom(from) && to.isAssignableFrom(f.outputType()))
                .map(StageFactory::type)
                .toList();
    }
}
