# LogFlow — Increment 5 (v5)

Error handling, validation and a dead-letter channel, on top of v4's configuration-driven
assembly and stage registry.

## What is new in v5

- A record a stage cannot process no longer disappears silently. The stage throws a
  `StageException` with a reason, and the stage's **error policy** from the config decides
  what happens: `FAIL_FAST`, `SKIP`, `DEAD_LETTER` or `RETRY(n)`.
- Failed records leave through a **dead-letter channel**, a second, independent sink.
  Each one is a `FailedRecord` carrying the raw input line, the stage name, the reason,
  the time and the line number.
- `validate` checks declarative rules: required fields, numeric ranges, timestamp window,
  IP format and allowed methods.
- Every run ends with a **run summary** (`RunSummary`) whose counts must add up.
- A chaos test runs a deliberately corrupted file (`data/access-dirty.log`). The run
  completes, valid records reach the main output, every bad record is in the dead-letter
  file, and `in = sink + dropped + skipped + dead letter`.

The error model, the policies, the dead-letter diagram and the table mapping each error
type in the dirty file to the policy that handles it are in **ARCHITECTURE.md → Increment 5**.

```bash
./logflow --config configs/chaos.yaml
```

```text
Run summary: pipeline 'chaos' COMPLETED in 0.102 s
  records in: 28   to sink: 10   to dead letter: 18
  stage          policy            in     out  dropped  failed  skipped  dead-letter  retries   time ms
  parse          DEAD_LETTER       28      17        0      11        0           11        0      32.3
  validate       DEAD_LETTER       17      10        0       7        0            7        0      14.7
  project        FAIL_FAST         10      10        0       0        0            0        0       1.2
  balance: in 28 = sink 10 + dropped 0 + skipped 0 + dead letter 18  OK
```

```text
$ head -3 out/dead-letter.jsonl
{"timestamp":"2026-10-27T10:59:56.331Z","recordNumber":"2","stage":"parse","error":"not a Common Log Format line","rawInput":"10.2.0.2 - - [30/Sep/2026:10:01:00 +0300"}
{"timestamp":"2026-10-27T10:59:56.333Z","recordNumber":"4","stage":"parse","error":"empty line","rawInput":""}
{"timestamp":"2026-10-27T10:59:56.333Z","recordNumber":"5","stage":"parse","error":"line is not valid UTF-8 (undecodable bytes at column 53)","rawInput":"10.2.0.20 - - [30/Sep/2026:10:10:00 +0300] \"GET /caf\ufffd\ufffd HTTP/1.1\" 200 10"}
```

The JSON summary of the same run is written to `out/chaos-summary.json`
(`pipeline.summary` in the config).

### Error policies in the configuration

```yaml
pipeline:
  name: chaos
  onError: FAIL_FAST              # default for stages without their own policy (built-in default: SKIP)
  summary: out/chaos-summary.json # optional: run summary as JSON
  source: { type: file, params: { path: data/access-dirty.log } }
  stages:
    - type: parse
      onError: DEAD_LETTER
      params: { maxLineLength: 8192 }
    - type: validate
      onError: DEAD_LETTER
      params:
        required: [clientIp, method, path, protocol]
        ranges: { status: [100, 599], bytes: [0, 104857600] }
        timestamp: { notBefore: "2000-01-01T00:00:00Z", notAfter: "2030-12-31T23:59:59Z" }
        ip: any
        methods: [GET, POST, PUT, DELETE, HEAD, PATCH, OPTIONS]
    - type: project
      params: { fields: [timestamp, clientIp, method, path, status, bytes] }
  sink: { type: file, params: { path: out/chaos-clean.txt } }
  deadLetter: { type: file, params: { path: out/dead-letter.jsonl, format: json } }
```

Exit codes: **0** the run completed (even if records went to the dead letter or were
skipped), **1** the run was stopped by a `PipelineException` (FAIL_FAST, unreadable input,
unwritable output); the partial summary is still printed, **2** configuration error, nothing
was processed.

```text
$ ./logflow --config configs/invalid/bad-policy.yaml
Configuration error: Invalid pipeline configuration 'configs/invalid/bad-policy.yaml' (2 problems); nothing was processed:
  - pipeline.stages[0].onError: unknown error policy 'DEAD-LETTER'; use one of [FAIL_FAST, SKIP, DEAD_LETTER, RETRY(n)]. Did you mean 'DEAD_LETTER'?
  - pipeline.stages[1] (validate) uses DEAD_LETTER but the pipeline has no 'deadLetter' sink; add e.g. 'deadLetter: { type: file, params: { path: out/dead-letter.jsonl, format: json } }'
```

## Goal (v4)

A pipeline is no longer assembled in Java code. A YAML file describes it, and the
**same, unmodified executable** runs any pipeline described this way:

```bash
./logflow --config configs/error-report.yaml
```

Stage type names (`parse`, `filter`, `project`, `aggregate`, ...) are mapped to their
implementations through a `ServiceLoader`-based registry. Every configuration error is
detected **before any data is read** and reported clearly (fail fast, fail loudly).

## Project structure

```text
logflow-project/
├── configs/
│   ├── chaos.yaml                 v5: dirty file, parse/validate -> dead letter
│   ├── error-report.yaml          error report (status >= 400)
│   ├── api-traffic.yaml           API traffic extract (CSV)
│   ├── bandwidth-by-client.yaml   bandwidth per client (aggregate, CSV)
│   └── invalid/                   deliberately broken configs (see "Error messages")
├── data/
│   ├── access-small.log           200 lines, every client IP once
│   ├── access-clients.log         300 synthetic lines, 12 recurring clients
│   ├── access-dirty.log           v5: 28 deliberately corrupted / valid lines (chaos test)
│   └── access-empty.log           v5: empty file
├── tools/generate_access_dirty.py v5: regenerates the two files above
├── src/main/java/logflow/
│   ├── PipelineException.java     v5: the run cannot continue (carries the partial summary)
│   ├── StageException.java        v5: one record failed, recoverable
│   ├── ErrorPolicy.java           v5: FAIL_FAST | SKIP | DEAD_LETTER | RETRY(n)
│   ├── FailedRecord.java          v5: what travels on the dead-letter channel
│   ├── ValidationStage.java / ValidationRule.java / IpAddresses.java   v5
│   ├── RunSummary.java / StageMetrics.java                             v5
│   ├── Stage.java                 (v4: finish(Emitter) hook)
│   ├── Pipeline.java              (v5: per-stage policy, dead-letter channel, metrics)
│   ├── AggregateStage.java        (new: group, count, sum, top N)
│   ├── Comparison.java            (new: == != > >= < <= startsWith endsWith contains)
│   ├── Filters.java               (v4: compare(field, op, value))
│   ├── OutputFormat.java          (new: text | csv)
│   ├── Reportable.java            (new: one-line run summary per component)
│   ├── Main.java                  (v4: --config, --list-stages; v3 options still work)
│   └── ... v1–v3 classes
├── src/main/java/logflow/config/  (new)
│   ├── YamlParser.java            dependency-free YAML subset reader
│   ├── PipelineSpec.java          config structure (description, no classes)
│   ├── ComponentSpec.java
│   ├── PipelineLoader.java        spec -> validated Pipeline
│   ├── LoadedPipeline.java
│   ├── ComponentFactory.java      type(), description(), params()
│   ├── SourceFactory.java / StageFactory.java / SinkFactory.java
│   ├── Registry.java              type name -> factory (ServiceLoader)
│   ├── StageRegistry.java
│   ├── Plugins.java               source + stage + sink registries
│   ├── ParamSpec.java / ParamType.java / Params.java   declarative parameter validation
│   ├── ConfigException.java       all configuration problems at once
│   └── Suggestions.java           "Did you mean ...?"
├── src/main/java/logflow/plugins/ (new: built-in factories)
│   ├── FileSourceFactory.java
│   ├── ParseStageFactory.java  FilterStageFactory.java  ProjectStageFactory.java  AggregateStageFactory.java
│   ├── ValidateStageFactory.java  (v5)
│   └── FileSinkFactory.java    ConsoleSinkFactory.java
├── src/main/resources/META-INF/services/
│   ├── logflow.config.SourceFactory
│   ├── logflow.config.StageFactory
│   └── logflow.config.SinkFactory
├── src/test/java/logflow/config/  PipelineLoaderTest, StageRegistryTest, YamlParserTest (new)
├── src/test/java/logflow/         DirtyDataChaosTest, ErrorPolicyTest, ValidationStageTest (v5),
│                                  AggregateStageTest, FiltersCompareTest, OutputFormatTest,
│                                  MainConfigModeTest + v2/v3 tests
├── logflow / logflow.sh / logflow.bat
├── pom.xml                        (JUnit 5 + JaCoCo, runnable jar)
├── ARCHITECTURE.md                (Increment 5: error model, policies, dead letter, error table)
└── CHANGELOG.md
```

## Build and run

Requires JDK 17+. No external runtime dependencies.

macOS / Linux (the `logflow` script compiles into `build/classes` and runs):

```bash
./logflow --config configs/error-report.yaml
./logflow --config configs/api-traffic.yaml
./logflow --config configs/bandwidth-by-client.yaml
./logflow --list-stages              # every registered source, stage and sink with its parameters
```

Windows:

```text
logflow.bat --config configs\error-report.yaml
```

With Maven:

```bash
mvn package
java -jar target/logflow-5.0.0.jar --config configs/error-report.yaml
```

Relative paths in a config are resolved against the directory the command is run from,
so run the commands above from the project root.

### Same executable, three configs, three outputs

```text
$ ./logflow --config configs/error-report.yaml
Run summary: pipeline 'error-report' COMPLETED in 0.107 s
  records in: 200   to sink: 80   to dead letter: 0
  stage          policy            in     out  dropped  failed  skipped  dead-letter  retries   time ms
  parse          SKIP             200     200        0       0        0            0        0      58.2
  filter         SKIP             200      80      120       0        0            0        0       0.5
  project        SKIP              80      80        0       0        0            0        0       6.6
  balance: in 200 = sink 80 + dropped 120 + skipped 0 + dead letter 0  OK

$ head -2 out/errors.txt
timestamp=2026-09-29T10:01:18+03:00 clientIp=192.168.0.7 status=400 path=/cart
timestamp=2026-09-29T10:01:31+03:00 clientIp=192.168.0.8 status=401 path=/checkout

$ ./logflow --config configs/api-traffic.yaml
Run summary: pipeline 'api-traffic' COMPLETED in 0.117 s
  records in: 200   to sink: 25   to dead letter: 0
  ...

$ head -3 out/api-traffic.csv
timestamp,method,path,status,bytes
2026-09-29T10:00:52+03:00,GET,/api/items,204,404
2026-09-29T10:02:36+03:00,GET,/api/items,200,700

$ ./logflow --config configs/bandwidth-by-client.yaml
Run summary: pipeline 'bandwidth-by-client' COMPLETED in 0.091 s
  records in: 300   to sink: 10   to dead letter: 0
  stage          policy            in     out  dropped  failed  skipped  dead-letter  retries   time ms
  parse          SKIP             300     300        0       0        0            0        0      61.4
  filter         SKIP             300     256       44       0        0            0        0       0.9
  aggregate      SKIP             256      10      256       0        0            0        0       7.9
  balance: n/a (a stage merges records, e.g. aggregate)

$ head -4 out/bandwidth-by-client.csv
clientIp,requests,bytes
10.1.0.1,71,1997893
10.1.0.3,42,1201792
10.1.0.2,43,1072821
```

The run summary goes to standard error, so a `console` sink's output on standard output
can be piped. The v4 configs set no `onError`, so their stages use the built-in default `SKIP`
(failed records are dropped but counted in the summary, never silently).

## Configuration format

```yaml
pipeline:
  name: error-report

  source:
    type: file
    params: { path: data/access-small.log }

  stages:
    - type: parse
      params: { format: clf }

    - type: filter
      params: { field: status, op: ">=", value: 400 }

    - type: project
      params: { fields: [timestamp, clientIp, status, path] }

  sink:
    type: file
    params: { path: out/errors.txt }
```

The full schema, every registered component and its parameters, and the supported YAML
subset are documented in **ARCHITECTURE.md → Increment 4**.

## Error messages

Configuration errors are found before any input is read or any output file is created.
All problems are reported together, each with its location, and the program exits with
code **2**. (Runtime failures exit with code 1.) The files below are in `configs/invalid/`.

**Unknown stage type**

```text
$ ./logflow --config configs/invalid/unknown-stage.yaml
Configuration error: Invalid pipeline configuration 'configs/invalid/unknown-stage.yaml' (1 problem); nothing was processed:
  - pipeline.stages[1].type: unknown stage type 'filtr'. Did you mean 'filter'? Known stage types: [aggregate, filter, parse, project]
```

**Missing parameter**

```text
$ ./logflow --config configs/invalid/missing-param.yaml
Configuration error: Invalid pipeline configuration 'configs/invalid/missing-param.yaml' (2 problems); nothing was processed:
  - pipeline.stages[1] (filter): missing required parameter 'value' (string or number) - number for status/bytes, time for timestamp, text otherwise
  - pipeline.stages[2] (project): missing required parameter 'fields' (list of strings) - fields to keep, from [clientIp, user, timestamp, method, path, protocol, status, bytes, referer, userAgent]
```

**Parameter of the wrong type**

```text
$ ./logflow --config configs/invalid/wrong-type.yaml
Configuration error: Invalid pipeline configuration 'configs/invalid/wrong-type.yaml' (3 problems); nothing was processed:
  - pipeline.stages[1] (filter): field 'status' is numeric, so 'value' must be a number, got "abc"
  - pipeline.stages[2] (project): parameter 'fields' must be a list of strings, got string "clientIp" (write a list: [clientIp])
  - pipeline.sink (file): parameter 'path' must be a string, got integer 42 (quote it: "42")
```

**Empty pipeline**

```text
$ ./logflow --config configs/invalid/empty-pipeline.yaml
Configuration error: Invalid pipeline configuration 'configs/invalid/empty-pipeline.yaml' (1 problem); nothing was processed:
  - pipeline.stages: the pipeline is empty; add at least one stage, e.g. '- type: parse'
```

**Stages in an impossible order** (types are checked at load time)

```text
$ ./logflow --config configs/invalid/wrong-order.yaml
Configuration error: Invalid pipeline configuration 'configs/invalid/wrong-order.yaml' (2 problems); nothing was processed:
  - pipeline.stages[0] (filter): expects LogRecord records, but the source 'file' produces String. Add a 'parse' stage before it.
  - pipeline.stages[1] (parse): expects String records, but pipeline.stages[0] (filter) produces LogRecord.
```

Other examples produced by the loader:

```text
  - pipeline.stages[0]: unknown key 'param'. Did you mean 'params'? Allowed keys: [type, params]
  - pipeline.stages[1] (project): unknown parameter 'field'. Did you mean 'fields'? Known parameters: [fields]
  - pipeline.stages[1] (aggregate): parameter 'top' must be an integer, got string "10" (remove the quotes: 10)
  - pipeline.stages[1] (filter): parameter 'field' must be one of [clientIp, user, ...], got string "stauts". Did you mean 'status'?
  - pipeline.source (file): input file not found: data/acess.log (relative paths are resolved against /home/me/logflow)
  - line 3: expected ',' or ']' in '[ { type: parse }' (multi-line [ ] is not supported)
  - config file not found: /home/me/logflow/nope.yaml
```

## Adding a new stage

1. Write a class implementing `logflow.config.StageFactory` (type name, parameters,
   input/output types, `create`).
2. Add its fully qualified name to
   `src/main/resources/META-INF/services/logflow.config.StageFactory`.
3. Use `- type: <name>` in a config.

No change to `PipelineLoader`, `Main` or any other stage.

## Tests and coverage

```bash
mvn test
```

114 tests. Line coverage (JaCoCo, `target/site/jacoco/index.html`): **85%**.

v5 tests:

- `DirtyDataChaosTest`: the dirty file completes, the exact 10 valid output lines, each of the
  18 bad lines lands in the dead letter at the expected stage with the expected reason, the
  100 KB line is kept whole, the empty file gives an all-zero balanced summary, and the
  same file under `FAIL_FAST` stops at line 2.
- `ErrorPolicyTest`: SKIP, FAIL_FAST (partial summary), DEAD_LETTER (original raw input
  kept), RETRY succeeding after transient failures and giving up after *n*, unexpected
  runtime exceptions, failing sink / dead-letter sink, balance check.
- `ValidationStageTest`: every rule, all violations reported together, IPv4/IPv6 formats.
- `PipelineLoaderTest` (v5 part): policies and names wired from the config, `DEAD_LETTER`
  without a dead-letter sink, unknown policy with a suggestion, `onError` on a sink,
  invalid validation rules.

Loader tests (`PipelineLoaderTest`) cover the cases from the assignment and more:

| Case | Test |
|---|---|
| valid configuration | `validConfigBuildsTheDeclaredPipelineAndRunsIt`, `optionalParametersUseTheirDefaults` |
| unknown stage type | `unknownStageTypeIsRejectedWithSuggestionAndKnownTypes`, `unknownSourceAndSinkTypesAreRejected` |
| missing parameter | `missingRequiredParameterIsRejected`, `stageWithoutParamsBlockReportsEveryMissingParameter`, `missingSourceFileIsAConfigurationError` |
| wrong parameter type | `quotedNumberWhereIntegerIsExpectedIsRejected`, `stringWhereListIsExpectedIsRejected`, `valueThatDoesNotMatchTheFieldTypeIsRejected`, `valueOutsideTheAllowedSetIsRejected` |
| empty pipeline | `emptyStageListIsRejected`, `emptyFileAndEmptyPipelineSectionAreRejected`, `missingSectionsAreAllReported` |
| other | stage order, unknown keys, all problems at once, missing config file, YAML syntax errors |

Every rejection test also asserts that **no output file was created**.
`MainConfigModeTest` runs the three shipped configs through the same `Main` and checks
the three different outputs; `StageRegistryTest` registers a new stage without touching
the loader.

## v3 command-line mode

The v3 options still work when `--config` is not given:

```text
logflow <log-file> [--min-status N] [--path-prefix P] [--from TIME] [--to TIME] [--fields a,b,c] [--out FILE]
```

See the v3 tag for details.
