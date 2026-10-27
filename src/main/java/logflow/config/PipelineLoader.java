package logflow.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import logflow.ErrorPolicy;
import logflow.FailedRecord;
import logflow.Pipeline;
import logflow.Sink;
import logflow.Source;
import logflow.Stage;

/**
 * Yapılandırma dosyasını okur, her bileşenin gerçekleştirimini kayıtlardan bulur,
 * parametreleri doğrular, aşama türlerinin birbirine uyduğunu denetler ve bir {@link Pipeline} kurar.
 *
 * <p><b>Hızlı ve yüksek sesle başarısız ol:</b> bütün denetimler tek bir veri satırı okunmadan
 * yapılır. Bir ya da daha fazla sorun varsa, hepsini listeleyen bir {@link ConfigException}
 * fırlatılır; hiçbir dosya açılmaz, hiçbir çıktı yazılmaz.
 */
public final class PipelineLoader {
    private final Plugins plugins;

    public PipelineLoader(Plugins plugins) {
        this.plugins = plugins;
    }

    /** Sınıf yolundaki bütün bileşenlerle. */
    public PipelineLoader() {
        this(Plugins.discover());
    }

    public LoadedPipeline load(Path file) {
        String text;
        try {
            text = Files.readString(file);
        } catch (NoSuchFileException e) {
            throw new ConfigException(file.toString(), "config file not found: " + file.toAbsolutePath());
        } catch (IOException e) {
            throw new ConfigException(file.toString(), "cannot read config file: " + e.getMessage());
        }
        return load(text, file.toString());
    }

    /** @param sourceName mesajlarda gösterilecek ad */
    public LoadedPipeline load(String yaml, String sourceName) {
        List<String> problems = new ArrayList<>();
        PipelineSpec spec = PipelineSpec.from(YamlParser.parse(yaml, sourceName), problems);
        if (spec == null) {
            throw new ConfigException(sourceName, problems);
        }
        return build(spec, sourceName);
    }

    /** Tanımı ({@link PipelineSpec}) gerçekleştirime çevirir. */
    public LoadedPipeline build(PipelineSpec spec, String sourceName) {
        List<String> problems = new ArrayList<>();

        ErrorPolicy defaultPolicy = Pipeline.DEFAULT_POLICY;
        if (spec.onError() != null) {
            defaultPolicy = policy(spec.onError(), "pipeline.onError", problems);
        }

        SourceFactory sourceFactory = resolve(plugins.sources(), spec.source(), problems);
        Source<?> source = create(sourceFactory, spec.source(), sourceFactory == null ? null : sourceFactory::create, problems);
        Class<?> current = sourceFactory == null ? null : sourceFactory.outputType();
        String producer = "the source '" + spec.source().type() + "'";

        List<Pipeline.StageSlot> slots = new ArrayList<>();
        Set<String> names = new HashSet<>();
        List<String> needDeadLetter = new ArrayList<>();
        for (ComponentSpec stageSpec : spec.stages()) {
            ErrorPolicy policy = stageSpec.onError() == null ? defaultPolicy
                    : policy(stageSpec.onError(), stageSpec.location() + ".onError", problems);
            String name = stageName(stageSpec, names, problems);
            if (policy != null && policy.needsDeadLetter()) {
                needDeadLetter.add(stageSpec.label() + " uses " + policy);
            }
            StageFactory factory = resolve(plugins.stages(), stageSpec, problems);
            if (factory == null) {
                current = null; // sonraki aşamalar için tür bilinmiyor; sahte hata üretme
                continue;
            }
            checkFlow(stageSpec, factory.inputType(), current, producer, problems);
            Stage<?, ?> stage = create(factory, stageSpec, factory::create, problems);
            if (stage != null && policy != null) {
                slots.add(new Pipeline.StageSlot(name, stage, policy));
            }
            current = factory.outputType();
            producer = stageSpec.label();
        }

        SinkFactory sinkFactory = resolve(plugins.sinks(), spec.sink(), problems);
        Sink<?> sink = null;
        if (sinkFactory != null) {
            checkFlow(spec.sink(), sinkFactory.inputType(), current, producer, problems);
            sink = create(sinkFactory, spec.sink(), sinkFactory::create, problems);
        }

        Sink<FailedRecord> deadLetter = null;
        if (spec.deadLetter() == null) {
            for (String user : needDeadLetter) {
                problems.add(user + " but the pipeline has no 'deadLetter' sink; add e.g. "
                        + "'deadLetter: { type: file, params: { path: out/dead-letter.jsonl, format: json } }'");
            }
        } else {
            SinkFactory dlFactory = resolve(plugins.sinks(), spec.deadLetter(), problems);
            if (dlFactory != null) {
                checkFlow(spec.deadLetter(), dlFactory.inputType(), FailedRecord.class, "the error path", problems);
                @SuppressWarnings("unchecked")
                Sink<FailedRecord> created = (Sink<FailedRecord>) create(dlFactory, spec.deadLetter(), dlFactory::create, problems);
                deadLetter = created;
            }
        }

        if (!problems.isEmpty()) {
            throw new ConfigException(sourceName, problems);
        }
        Pipeline pipeline = new Pipeline(spec.name(), source, slots, sink, deadLetter);
        return new LoadedPipeline(spec.name(), sourceName, pipeline, spec.summary() == null ? null : Path.of(spec.summary()));
    }

    private static ErrorPolicy policy(String text, String location, List<String> problems) {
        try {
            return ErrorPolicy.parse(text);
        } catch (IllegalArgumentException e) {
            String hint = Suggestions.didYouMean(text.toUpperCase(Locale.ROOT).replace('-', '_'),
                    List.of("FAIL_FAST", "SKIP", "DEAD_LETTER"));
            problems.add(location + ": " + e.getMessage() + "." + hint);
            return null;
        }
    }

    /** Görünen ad: {@code name} ya da tür adı; tür tekrarlanırsa {@code filter#2}. Açık adlar benzersiz olmalı. */
    private static String stageName(ComponentSpec spec, Set<String> used, List<String> problems) {
        if (spec.name() != null) {
            if (!used.add(spec.name())) {
                problems.add(spec.location() + ".name: duplicate stage name '" + spec.name() + "'");
            }
            return spec.name();
        }
        String n = spec.type();
        for (int i = 2; !used.add(n); i++) {
            n = spec.type() + "#" + i;
        }
        return n;
    }

    private <F extends ComponentFactory> F resolve(Registry<F> registry, ComponentSpec spec, List<String> problems) {
        F factory = registry.find(spec.type()).orElse(null);
        if (factory == null) {
            problems.add(spec.location() + ".type: " + registry.unknownTypeMessage(spec.type()));
        }
        return factory;
    }

    /** Parametreleri doğrular, geçerliyse bileşeni kurar; fabrikanın anlamsal itirazları da sorun olarak toplanır. */
    private <T> T create(ComponentFactory factory, ComponentSpec spec, Function<Params, T> constructor, List<String> problems) {
        if (factory == null) {
            return null;
        }
        Params params = Params.validate(spec.label(), spec.params(), factory.params(), problems);
        if (params == null) {
            return null;
        }
        try {
            return constructor.apply(params);
        } catch (IllegalArgumentException e) {
            problems.add(spec.label() + ": " + e.getMessage());
            return null;
        }
    }

    private void checkFlow(ComponentSpec spec, Class<?> expected, Class<?> actual, String producer, List<String> problems) {
        if (actual == null || expected.isAssignableFrom(actual)) {
            return;
        }
        List<String> converters = plugins.stages().converters(actual, expected);
        problems.add(spec.label() + ": expects " + expected.getSimpleName() + " records, but " + producer
                + " produces " + actual.getSimpleName()
                + (converters.isEmpty() ? "." : ". Add a " + String.join(" or ", converters.stream().map(c -> "'" + c + "'").toList())
                        + " stage before it."));
    }
}
