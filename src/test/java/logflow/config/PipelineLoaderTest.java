package logflow.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import logflow.AggregateStage;
import logflow.ParserStage;
import logflow.PredicateFilterStage;
import logflow.ProjectionStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Görev 6: yükleyici testleri. Her hata durumunda hiçbir veri işlenmediği de doğrulanır. */
class PipelineLoaderTest {

    @TempDir
    Path dir;

    private final PipelineLoader loader = new PipelineLoader();

    static String fixture() throws URISyntaxException {
        return Path.of(PipelineLoaderTest.class.getResource("/fixture.log").toURI()).toString().replace('\\', '/');
    }

    /** Geçerli bir yapılandırma: kaynak = fixture, hedef = geçici dizinde bir dosya, aşamalar verilen metin. */
    private String config(String stagesYaml) throws URISyntaxException {
        return "pipeline:\n"
                + "  name: test\n"
                + "  source: { type: file, params: { path: \"" + fixture() + "\" } }\n"
                + "  stages:\n" + stagesYaml
                + "  sink: { type: file, params: { path: \"" + out().toString().replace('\\', '/') + "\" } }\n";
    }

    private Path out() {
        return dir.resolve("out/result.txt");
    }

    private ConfigException rejected(String yaml) {
        ConfigException e = assertThrows(ConfigException.class, () -> loader.load(yaml, "test.yaml"));
        assertFalse(Files.exists(out()), "fail fast: no output may be written for an invalid config");
        return e;
    }

    // ---- geçerli yapılandırma ----

    @Test
    void validConfigBuildsTheDeclaredPipelineAndRunsIt() throws Exception {
        LoadedPipeline loaded = loader.load(config("""
                    - type: parse
                      params: { format: clf }
                    - type: filter
                      params: { field: status, op: ">=", value: 400 }
                    - type: project
                      params: { fields: [timestamp, clientIp, status, path] }
                """), "test.yaml");

        assertEquals("test", loaded.name());
        var stages = loaded.pipeline().stages();
        assertEquals(3, stages.size());
        assertTrue(stages.get(0) instanceof ParserStage);
        assertTrue(stages.get(1) instanceof PredicateFilterStage);
        assertTrue(stages.get(2) instanceof ProjectionStage);
        assertFalse(Files.exists(out()), "loading alone must not touch the output");

        loaded.run();

        assertEquals(List.of(
                "timestamp=2026-09-29T10:00:20+03:00 clientIp=10.0.0.3 status=500 path=/api/orders",
                "timestamp=2026-09-29T10:00:30+03:00 clientIp=10.0.0.4 status=401 path=/login",
                "timestamp=2026-09-29T10:00:40+03:00 clientIp=10.0.0.5 status=404 path=/api/items/7",
                "timestamp=2026-09-29T10:01:00+03:00 clientIp=10.0.0.7 status=400 path=/api/cart",
                "timestamp=2026-09-29T10:01:10+03:00 clientIp=10.0.0.8 status=503 path=/apiary"),
                Files.readAllLines(out()));
        assertEquals(List.of(
                "parse      parsed: 8, malformed: 2",
                "filter     status>=400                    in: 8, passed: 5",
                "sink       written: 5 -> " + out().toString().replace('\\', '/')), loaded.report());
    }

    @Test
    void optionalParametersUseTheirDefaults() throws Exception {
        LoadedPipeline loaded = loader.load(config("""
                    - type: parse
                    - type: aggregate
                      params: { groupBy: method }
                """), "test.yaml");
        assertTrue(loaded.pipeline().stages().get(1) instanceof AggregateStage);

        loaded.run();

        // top varsayılanı 0 = bütün gruplar; sum yok -> istek sayısına, eşitlikte ada göre sıralı
        assertEquals(List.of("method=GET requests=5", "method=DELETE requests=1", "method=POST requests=1",
                "method=PUT requests=1"), Files.readAllLines(out()));
    }

    // ---- bilinmeyen aşama türü ----

    @Test
    void unknownStageTypeIsRejectedWithSuggestionAndKnownTypes() throws Exception {
        ConfigException e = rejected(config("""
                    - type: parse
                    - type: filtr
                      params: { field: status, op: ">=", value: 400 }
                """));

        assertEquals(List.of("pipeline.stages[1].type: unknown stage type 'filtr'. Did you mean 'filter'? "
                + "Known stage types: [aggregate, filter, parse, project, validate]"), e.problems());
        assertTrue(e.getMessage().startsWith("Invalid pipeline configuration 'test.yaml' (1 problem); nothing was processed:"));
    }

    @Test
    void unknownSourceAndSinkTypesAreRejected() {
        ConfigException e = rejected("""
                pipeline:
                  name: x
                  source: { type: kafka }
                  stages: [ { type: parse } ]
                  sink: { type: s3 }
                """);

        assertEquals(List.of(
                "pipeline.source.type: unknown source type 'kafka'. Known source types: [file]",
                "pipeline.sink.type: unknown sink type 's3'. Known sink types: [console, file]"), e.problems());
    }

    // ---- eksik parametre ----

    @Test
    void missingRequiredParameterIsRejected() throws Exception {
        ConfigException e = rejected(config("""
                    - type: parse
                    - type: filter
                      params: { field: status, op: ">=" }
                """));

        assertEquals(1, e.problems().size());
        assertTrue(e.problems().get(0).startsWith(
                "pipeline.stages[1] (filter): missing required parameter 'value' (string or number)"), e.getMessage());
    }

    @Test
    void stageWithoutParamsBlockReportsEveryMissingParameter() throws Exception {
        ConfigException e = rejected(config("""
                    - type: parse
                    - type: filter
                """));

        assertEquals(3, e.problems().size(), e.getMessage());
        assertTrue(e.problems().get(0).contains("missing required parameter 'field'"));
        assertTrue(e.problems().get(1).contains("missing required parameter 'op'"));
        assertTrue(e.problems().get(2).contains("missing required parameter 'value'"));
    }

    @Test
    void missingSourceFileIsAConfigurationError() {
        ConfigException e = rejected("""
                pipeline:
                  name: x
                  source: { type: file, params: { path: does/not/exist.log } }
                  stages: [ { type: parse } ]
                  sink: { type: console }
                """);
        assertTrue(e.problems().get(0).startsWith("pipeline.source (file): input file not found: does/not/exist.log".replace('/', java.io.File.separatorChar)), e.getMessage());
    }

    // ---- yanlış türde parametre ----

    @Test
    void quotedNumberWhereIntegerIsExpectedIsRejected() throws Exception {
        ConfigException e = rejected(config("""
                    - type: parse
                    - type: aggregate
                      params: { groupBy: clientIp, top: "10" }
                """));

        assertEquals(List.of("pipeline.stages[1] (aggregate): parameter 'top' must be an integer, "
                + "got string \"10\" (remove the quotes: 10)"), e.problems());
    }

    @Test
    void stringWhereListIsExpectedIsRejected() throws Exception {
        ConfigException e = rejected(config("""
                    - type: parse
                    - type: project
                      params: { fields: clientIp }
                """));

        assertEquals(List.of("pipeline.stages[1] (project): parameter 'fields' must be a list of strings, "
                + "got string \"clientIp\" (write a list: [clientIp])"), e.problems());
    }

    @Test
    void valueThatDoesNotMatchTheFieldTypeIsRejected() throws Exception {
        ConfigException e = rejected(config("""
                    - type: parse
                    - type: filter
                      params: { field: status, op: ">=", value: "400" }
                """));

        assertEquals(List.of("pipeline.stages[1] (filter): field 'status' is numeric, so 'value' must be a number, "
                + "got \"400\" (remove the quotes)"), e.problems());
    }

    @Test
    void valueOutsideTheAllowedSetIsRejected() throws Exception {
        ConfigException e = rejected(config("""
                    - type: parse
                    - type: filter
                      params: { field: stauts, op: "=>", value: 400 }
                """));

        assertEquals(2, e.problems().size(), e.getMessage());
        assertTrue(e.problems().get(0).contains("parameter 'field' must be one of") && e.problems().get(0).endsWith("Did you mean 'status'?"));
        assertTrue(e.problems().get(1).contains("parameter 'op' must be one of"), e.problems().get(1));
    }

    @Test
    void unknownParameterIsRejected() throws Exception {
        ConfigException e = rejected(config("""
                    - type: parse
                    - type: project
                      params: { field: [clientIp] }
                """));

        assertEquals(2, e.problems().size(), e.getMessage());
        assertEquals("pipeline.stages[1] (project): unknown parameter 'field'. Did you mean 'fields'? Known parameters: [fields]",
                e.problems().get(0));
    }

    // ---- boş pipeline ----

    @Test
    void emptyStageListIsRejected() throws Exception {
        ConfigException e = rejected(config("    []\n").replace("stages:\n    []", "stages: []"));

        assertEquals(List.of("pipeline.stages: the pipeline is empty; add at least one stage, e.g. '- type: parse'"), e.problems());
    }

    @Test
    void emptyFileAndEmptyPipelineSectionAreRejected() {
        assertEquals(List.of("the configuration is empty; expected a top-level 'pipeline:' section"),
                rejected("# nothing here\n").problems());
        assertEquals(List.of("pipeline: the pipeline is empty; it needs 'name', 'source', 'stages' and 'sink'"),
                rejected("pipeline:\n").problems());
    }

    @Test
    void missingSectionsAreAllReported() {
        ConfigException e = rejected("pipeline:\n  name: x\n");

        assertEquals(List.of(
                "pipeline.source: missing; expected '{ type: ..., params: { ... } }'",
                "pipeline.sink: missing; expected '{ type: ..., params: { ... } }'",
                "pipeline.stages: missing; the pipeline needs at least one stage, e.g. '- type: parse'"), e.problems());
    }

    // ---- diğer yapılandırma zamanı hataları ----

    @Test
    void stageOrderIsCheckedBeforeAnyDataIsRead() throws Exception {
        ConfigException e = rejected(config("""
                    - type: filter
                      params: { field: status, op: ">=", value: 400 }
                """));

        assertEquals(List.of("pipeline.stages[0] (filter): expects LogRecord records, but the source 'file' produces String. "
                + "Add a 'parse' stage before it."), e.problems());
    }

    @Test
    void unknownKeysInTheStructureAreRejected() {
        ConfigException e = rejected("""
                pipeline:
                  name: x
                  sorce: { type: file }
                  source: { type: file, params: { path: x } }
                  stages: [ { type: parse, param: {} } ]
                  sink: { type: console }
                """);

        assertTrue(e.problems().get(0).startsWith("pipeline: unknown key 'sorce'. Did you mean 'source'?"), e.getMessage());
        assertTrue(e.problems().get(1).startsWith("pipeline.stages[0]: unknown key 'param'. Did you mean 'params'?"), e.getMessage());
    }

    @Test
    void allProblemsAreReportedTogether() throws Exception {
        ConfigException e = rejected(config("""
                    - type: parse
                    - type: filtr
                    - type: aggregate
                      params: { groupBy: clientIp, top: -1 }
                    - type: project
                      params: { fields: [ip] }
                """));

        assertEquals(4, e.problems().size(), e.getMessage());
        assertTrue(e.getMessage().contains("(4 problems)"));
        assertTrue(e.problems().get(0).contains("unknown stage type 'filtr'"));
        assertTrue(e.problems().get(1).contains("'top' must be 0 (all) or positive, got -1"));
        // aggregate ProjectedRecord üretir, project LogRecord bekler: sıra hatası da aynı raporda
        assertTrue(e.problems().get(2).contains("expects LogRecord records, but pipeline.stages[2] (aggregate) produces ProjectedRecord"));
        assertTrue(e.problems().get(3).contains("Unknown field: ip"));
    }

    @Test
    void missingConfigFileIsReported() {
        ConfigException e = assertThrows(ConfigException.class, () -> loader.load(dir.resolve("none.yaml")));
        assertTrue(e.problems().get(0).startsWith("config file not found: "));
    }

    @Test
    void yamlSyntaxErrorsCarryTheLineNumber() {
        ConfigException e = rejected("pipeline:\n  name: x\n  stages: [ { type: parse }\n");
        assertTrue(e.problems().get(0).startsWith("line 3: expected ',' or ']'"), e.getMessage());
    }

    // ---- v5: hata politikaları ve dead-letter ----

    @Test
    void policiesAndDeadLetterAreWiredFromTheConfig() throws Exception {
        LoadedPipeline loaded = loader.load(config("""
                    - type: parse
                      onError: DEAD_LETTER
                    - type: filter
                      name: errors-only
                      params: { field: status, op: ">=", value: 400 }
                    - type: filter
                      onError: RETRY(2)
                      params: { field: path, op: startsWith, value: / }
                """).replace("  sink:", "  onError: SKIP\n  deadLetter: { type: console, params: { format: json } }\n  sink:"), "test.yaml");

        var slots = loaded.pipeline().slots();
        assertEquals(List.of("parse", "errors-only", "filter"), slots.stream().map(logflow.Pipeline.StageSlot::name).toList());
        assertEquals(List.of("DEAD_LETTER", "SKIP", "RETRY(2)"), slots.stream().map(sl -> sl.policy().toString()).toList());
        assertTrue(loaded.pipeline().deadLetter() != null);
    }

    @Test
    void deadLetterPolicyWithoutDeadLetterSinkIsAConfigurationError() throws Exception {
        ConfigException e = rejected(config("""
                    - type: parse
                      onError: DEAD_LETTER
                """));

        assertEquals(1, e.problems().size(), e.getMessage());
        assertTrue(e.problems().get(0).startsWith("pipeline.stages[0] (parse) uses DEAD_LETTER but the pipeline has no 'deadLetter' sink"),
                e.getMessage());
    }

    @Test
    void unknownPolicyIsRejectedWithSuggestion() throws Exception {
        ConfigException e = rejected(config("""
                    - type: parse
                      onError: dead-letter
                    - type: project
                      onError: RETRY(99)
                      params: { fields: [path] }
                """));

        assertEquals(2, e.problems().size(), e.getMessage());
        assertTrue(e.problems().get(0).startsWith("pipeline.stages[0].onError: unknown error policy 'dead-letter'"), e.getMessage());
        assertTrue(e.problems().get(0).endsWith("Did you mean 'DEAD_LETTER'?"), e.getMessage());
        assertTrue(e.problems().get(1).contains("RETRY(n) needs 1 <= n <= 10, got 99"), e.getMessage());
    }

    @Test
    void onErrorOnSourceOrSinkIsRejected() {
        ConfigException e = rejected("""
                pipeline:
                  name: x
                  source: { type: file, onError: SKIP, params: { path: x } }
                  stages: [ { type: parse } ]
                  sink: { type: console }
                """);
        assertTrue(e.problems().get(0).startsWith("pipeline.source: 'onError' is only allowed on stages"), e.getMessage());
    }

    @Test
    void duplicateExplicitStageNamesAreRejected() throws Exception {
        ConfigException e = rejected(config("""
                    - type: parse
                      name: step
                    - type: project
                      name: step
                      params: { fields: [path] }
                """));
        assertEquals(List.of("pipeline.stages[1].name: duplicate stage name 'step'"), e.problems());
    }

    @Test
    void invalidValidationRulesAreConfigurationErrors() throws Exception {
        ConfigException e = rejected(config("""
                    - type: parse
                    - type: validate
                      params:
                        ranges: { status: [599, 100], path: [0, 1] }
                        timestamp: { notBefore: yesterday }
                    - type: validate
                """));

        assertEquals(2, e.problems().size(), e.getMessage());
        assertTrue(e.problems().get(0).startsWith("pipeline.stages[1] (validate): range for 'status' has min 599 > max 100"), e.getMessage());
        assertTrue(e.problems().get(1).startsWith("pipeline.stages[2] (validate): no rules given"), e.getMessage());
    }
}
