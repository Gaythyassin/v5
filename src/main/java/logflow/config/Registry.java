package logflow.config;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;

/**
 * Tür adı → fabrika tablosu. Kayıt veri odaklıdır: {@code switch} yoktur, fabrikalar
 * {@link ServiceLoader} ile keşfedilir ya da {@link #register} ile eklenir.
 */
public class Registry<F extends ComponentFactory> {
    private final String kind;
    private final Map<String, F> byType = new TreeMap<>();

    /** @param kind mesajlarda kullanılan bileşen türü: {@code "stage"}, {@code "source"}, {@code "sink"} */
    public Registry(String kind) {
        this.kind = kind;
    }

    /** Sınıf yolundaki {@code META-INF/services/<service>} dosyalarında ilan edilen tüm fabrikalar. */
    public static <F extends ComponentFactory> Registry<F> discover(String kind, Class<F> service) {
        return new Registry<F>(kind).registerAll(ServiceLoader.load(service));
    }

    protected Registry<F> registerAll(Iterable<? extends F> factories) {
        factories.forEach(this::register);
        return this;
    }

    /** @throws IllegalStateException aynı ad iki kez kaydedilirse (iki eklenti çakışıyorsa) */
    public Registry<F> register(F factory) {
        F previous = byType.putIfAbsent(factory.type(), factory);
        if (previous != null && previous != factory) {
            throw new IllegalStateException("Duplicate " + kind + " type '" + factory.type() + "': "
                    + previous.getClass().getName() + " and " + factory.getClass().getName());
        }
        return this;
    }

    public Optional<F> find(String type) {
        return Optional.ofNullable(byType.get(type));
    }

    public Set<String> types() {
        return byType.keySet();
    }

    public Collection<F> all() {
        return List.copyOf(byType.values());
    }

    public String kind() {
        return kind;
    }

    /** {@code unknown stage type 'filtr'. Did you mean 'filter'? Known stage types: [...]} */
    public String unknownTypeMessage(String type) {
        return "unknown " + kind + " type '" + type + "'." + Suggestions.didYouMean(type, types())
                + " Known " + kind + " types: " + types();
    }
}
