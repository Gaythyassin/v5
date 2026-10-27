package logflow.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import logflow.LogRecord;
import logflow.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StageRegistryTest {

    @TempDir
    Path dir;

    /** Yükleyiciyi değiştirmeden eklenen yeni bir aşama: yolu büyük harfe çevirir. */
    static class UppercasePathFactory implements StageFactory {
        @Override
        public String type() {
            return "uppercase-path";
        }

        @Override
        public String description() {
            return "test stage";
        }

        @Override
        public List<ParamSpec> params() {
            return List.of(ParamSpec.optional("suffix", ParamType.STRING, "", "appended to the path"));
        }

        @Override
        public Class<?> inputType() {
            return LogRecord.class;
        }

        @Override
        public Class<?> outputType() {
            return String.class;
        }

        @Override
        public Stage<?, ?> create(Params params) {
            String suffix = params.string("suffix");
            return (Stage<LogRecord, String>) (r, out) -> out.emit(r.path().toUpperCase(Locale.ROOT) + suffix);
        }
    }

    @Test
    void builtInStagesAreDiscoveredThroughServiceLoader() {
        StageRegistry registry = StageRegistry.discover();
        assertEquals(List.of("aggregate", "filter", "parse", "project", "validate"), List.copyOf(registry.types()));

        Plugins plugins = Plugins.discover();
        assertEquals(List.of("file"), List.copyOf(plugins.sources().types()));
        assertEquals(List.of("console", "file"), List.copyOf(plugins.sinks().types()));
    }

    @Test
    void aNewStageIsUsableWithoutChangingTheLoader() throws Exception {
        Plugins builtIn = Plugins.discover();
        StageRegistry stages = StageRegistry.discover().register(new UppercasePathFactory());
        PipelineLoader loader = new PipelineLoader(new Plugins(builtIn.sources(), stages, builtIn.sinks()));
        Path out = dir.resolve("paths.txt");

        loader.load("""
                pipeline:
                  name: custom
                  source: { type: file, params: { path: "%s" } }
                  stages:
                    - type: parse
                    - type: uppercase-path
                      params: { suffix: "!" }
                  sink: { type: file, params: { path: "%s" } }
                """.formatted(PipelineLoaderTest.fixture(), out.toString().replace('\\', '/')), "custom.yaml").run();

        assertEquals("/!", Files.readAllLines(out).get(0));
        assertEquals("/API/ITEMS!", Files.readAllLines(out).get(1));
    }

    @Test
    void duplicateTypeNamesAreRejected() {
        StageRegistry registry = StageRegistry.discover();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> registry.register(new UppercasePathFactory() {
            @Override
            public String type() {
                return "filter";
            }
        }));
        assertTrue(e.getMessage().startsWith("Duplicate stage type 'filter'"), e.getMessage());
    }

    @Test
    void convertersAreFoundByType() {
        assertEquals(List.of("parse"), StageRegistry.discover().converters(String.class, LogRecord.class));
    }

    @Test
    void suggestionsOnlyForCloseNames() {
        assertEquals("filter", Suggestions.closest("filtr", List.of("parse", "filter")).orElseThrow());
        assertTrue(Suggestions.closest("kafka", List.of("file")).isEmpty());
    }
}
