# Architecture

## Increment 1

The program is split into components instead of putting file I/O and printing logic inside `Main`.

```text
+---------------------------+       +----------------------------+
| FileLineSource            | ----> | Pipeline -> ConsoleSink    |
| implements Source<String> |       | pushes each emitted String|
+---------------------------+       +----------------------------+
```

Data flow:

```text
data/access-small.log
        |
        v
FileLineSource
        |
        | emits one String per line
        v
Pipeline
        |
        v
ConsoleSink
        |
        v
Console
```

## Responsibilities

### FileLineSource

Owns file-reading logic. It reads the input file and emits one `String` for every line.

### Pipeline

Owns component connection and record flow. It can hold an ordered list of `Stage<?, ?>` objects.
For Increment 1 that list may be empty, so the source output reaches the sink directly.

### ConsoleSink

Owns output behavior and prints each consumed item.

### Main

`Main` contains no business processing. It only:

1. reads the command-line argument,
2. assembles the components,
3. calls `pipeline.run()`.

## Increment 2

A real stage was added in the middle of the existing pipeline, and the data model changed
from `String` to `LogRecord`.

```text
FileLineSource --String--> ParserStage --LogRecord--> ConsoleSink
```

### Separation of concerns

- `FileLineSource`: knows files, knows nothing about log formats.
- `ParserStage`: knows the CLF format, knows nothing about files or printing.
- `ConsoleSink`: knows how to display a `LogRecord`, knows nothing about parsing.
- `Pipeline`: unchanged. It already accepted a list of stages.

### LogRecord and `attributes`

`LogRecord` is an immutable record. `attributes` is an immutable `Map<String,String>` that
is the extension point: later stages add data the parser does not know about by creating a
copy with `withAttribute(...)`, so the record type itself never has to change.

### Testability

`Stage.process` receives an `Emitter`, so a test can pass a `CollectingEmitter` instead of
a real downstream stage. No file or console is involved.

## Increment 3

Focus: modularity, composability, one responsibility per stage.

```text
FileLineSource --String--> ParserStage --LogRecord--> PredicateFilterStage* --LogRecord-->
    [ProjectionStage --ProjectedRecord-->] FileSink | ConsoleSink
```

`*` zero or more filters, in the order given on the command line.

### New stages

| Component | Type | Responsibility |
|---|---|---|
| `PredicateFilterStage<T>` | `Stage<T,T>` | Forwards a record unchanged only if a `Predicate<T>` holds. Counts evaluated / passed. |
| `Filters` | factory | Ready-made `Predicate<LogRecord>`s: `minStatus(400)`, `pathPrefix("/api")`, `between(from, to)` (half-open, offset-aware). |
| `ProjectionStage` | `Stage<LogRecord,ProjectedRecord>` | Reduces a record to a chosen, ordered subset of fields. Unknown / duplicate fields are rejected when the stage is built. |
| `FileSink<T>` | `Sink<T>` | Writes one line per record. Opens the writer in `open()`, flushes and closes it in `close()`. |

**Filtering vs. transforming.** A filter that drops records and a projection that rewrites
them are the same architectural element: both implement `Stage<I,O>` and emit zero or more
outputs per input. The pipeline does not know or care which is which; this is why they can be
reordered freely (as long as the types line up).

**Data minimisation.** `ProjectionStage` is the privacy tool of the pipeline. Fields that are
not selected (e.g. `clientIp`, `userAgent`, `referer`, `user`) no longer exist after the stage:
`ProjectedRecord.get("clientIp")` throws. Projecting as early as the downstream needs allow
keeps personal data out of output files and shrinks them.

### PipelineBuilder

```java
Pipeline p = PipelineBuilder.from(new FileLineSource(in))
        .via(new ParserStage())
        .via(new PredicateFilterStage<>(r -> r.status() >= 400))
        .via(new ProjectionStage("timestamp", "clientIp", "status", "path"))
        .to(new FileSink<>(out));
```

- `PipelineBuilder<T>` carries the current output type `T`; `via(Stage<? super T, R>)` returns
  a `PipelineBuilder<R>`. Putting a `LogRecord` filter **before** the parser is therefore a
  *compile-time* error, not a `ClassCastException` at run time.
- The builder is immutable. A shared prefix can be branched into several pipelines.
- It still produces the same `Pipeline` as before, so in Week 4 the builder calls can be
  driven by a configuration file instead of `Main`.

### Lifecycle hooks are no longer decoration

`Pipeline.run()` now:

1. calls `open()` on every stage, then on the sink;
2. streams the source;
3. in a `finally` block calls `close()` on everything that was opened, collecting any
   secondary failure as a suppressed exception.

`FileSink` uses a `BufferedWriter` (8 KB buffer). If `close()` is never called, small outputs
never reach the disk at all: the file exists but is **empty**.
`FileSinkTest.outputIsLostUntilCloseFlushesTheBuffer` writes ten lines, asserts the file is
still 0 bytes, then calls `close()` and finds all ten. `PipelineBuilderTest` checks that
`close()` also runs when the source throws.

### Command line

`CliOptions` turns `--min-status`, `--path-prefix`, `--from`/`--to`, `--fields` and `--out` into
data; `Main` turns that data into stages with the builder. Filters keep the command-line
order, so the ordering experiment can also be repeated by hand:

```bash
./logflow.sh data/access-small.log --min-status 400 --path-prefix /api   # status first
./logflow.sh data/access-small.log --path-prefix /api --min-status 400   # path first
```

### Ordering experiment

`OrderingExperiment` repeats `data/access-small.log` to 1,000,000 lines, keeps them in memory
(so disk I/O does not hide the differences) and runs three orderings of the same stages.
Each variant has 3 warm-up runs; the table shows the median of 15 timed runs (JDK 21, 2 vCPU
Linux container). The experiment was repeated three times; the numbers below are the
third run, and the other runs agreed to within about 10%.

| Variant | Order | Output records | Output hash | Predicate evaluations | End-to-end (ms) | Filters + projection only (ms) |
|---|---|---:|---:|---:|---:|---:|
| A | parse → status≥400 → path^/api → project | 50,000 | 4636657668748302969 | 1,400,000 | 1,567 | 105 |
| B | parse → path^/api → status≥400 → project | 50,000 | 4636657668748302969 | 1,125,000 | 1,598 | 109 |
| C | parse → project → status≥400 → path^/api | 50,000 | 4636657668748302969 | 1,400,000 | 2,495 | 548 |

"End-to-end" starts from raw lines; "filters + projection only" starts from already-parsed
`LogRecord`s to remove the parsing cost. The order-sensitive output hash is identical in all
variants; `PipelineIntegrationTest.filterOrderDoesNotChangeTheResult` checks the same thing
on the fixture by comparing the two output files line by line.

**Same results.** Both filters are pure predicates on one record, so they commute:
`status≥400 ∧ path^/api` = `path^/api ∧ status≥400`. Order changes how much work is done,
never what comes out.

**Different performance, and why:**

1. **Parsing dominates.** About 93% of the end-to-end time is regex parsing, so on this
   data set the A/B difference disappears in the noise end-to-end.
2. **Selectivity is not the whole story (A vs. B).** In this data set 40% of records have
   status ≥ 400, but only 12.5% have a path starting with `/api`. Putting the more selective
   filter first (B) cuts predicate calls by 20% (1.125 M vs 1.4 M). B was still not faster: its
   first filter is a `String.startsWith` on every record, while A's first filter is a single
   `int` comparison. The cost of a filter is about (cost per call) × (records that reach
   it). The rule is to sort filters by `cost / (1 − pass rate)`, not by selectivity alone.
   With an expensive predicate (a regex, a lookup) the more selective filter first would win
   clearly.
3. **Filter before or after projection is a real trade-off (C).** Projecting first is ~5×
   slower in the filter section (~+60% end-to-end). Every one of the 1 M records is copied into
   a `ProjectedRecord`, not just the 50 k survivors, and the predicates must turn text
   back into numbers (`Integer.parseInt(p.get("status"))`). It also *couples* the stages: the
   projection must keep every field a later filter needs. Here `status` and `path` must stay,
   even if the output should not contain them. Projecting early is only worth it when the
   projection drops a lot of data that is expensive to carry (e.g. before a network hop or
   a sort), or when privacy requires that later stages never see the dropped fields.
4. **Filtering before parsing is not possible.** The filters need a `LogRecord`, but the
   source emits `String`. `PipelineBuilder` rejects that order at compile time. A raw-text
   pre-filter (e.g. `line.contains("/api")`) would be a *different* filter with different
   semantics (it would also match a referer containing `/api`), not the same stage moved
   earlier.

**Conclusion.** Composability gives correctness for free (any valid order gives the same
answer). Performance is a separate decision: filter early, put cheap and selective
predicates first, and project late unless size or privacy says otherwise.

## Increment 4 — configuration-driven assembly and stage registry

Focus: extensibility. The *architecture description* of a pipeline (which components, in
which order, with which parameters) now lives in a declarative YAML file; the *architecture
implementation* (Java classes) is found through a registry. A new pipeline needs a new
config file, not a recompile.

```text
configs/x.yaml
     |  YamlParser            text -> Map / List / scalars (syntax errors with line numbers)
     v
PipelineSpec / ComponentSpec  structure only: name, source, stages[], sink (no classes yet)
     |  PipelineLoader
     |    - Registry lookup     type name -> factory   (unknown type -> error + suggestion)
     |    - Params.validate     declared ParamSpecs    (missing / unknown / wrong type / not allowed)
     |    - type-flow check     outputType -> inputType (e.g. filter before parse)
     |    - factory.create      semantic checks        (e.g. "value must be a number for status")
     v
LoadedPipeline (Pipeline)     nothing has been read or written yet
     |  run()
     v
Source -> Stage* -> Sink      same runtime as v3
```

### Configuration schema

```yaml
pipeline:                     # required, the only top-level key
  name: <string>              # required
  description: <string>       # optional
  source:                     # required
    type: <source type>       # required, see "Registered components"
    params: { ... }           # optional, depends on the type
  stages:                     # required, a non-empty list, executed top to bottom
    - type: <stage type>
      params: { ... }
  sink:                       # required
    type: <sink type>
    params: { ... }
```

Rules:

- Unknown keys are errors at every level (`sorce:` → *Did you mean 'source'?*).
- `params` must be a mapping; parameter names and types are declared by each factory.
- Relative paths (`data/...`, `out/...`) are resolved against the working directory.
- The record type must line up from the source through every stage to the sink.

Supported YAML subset (`YamlParser`, no external library so that plain `javac` still builds
the project): block mappings and lists, one-line flow `{ }` and `[ ]`, single/double quoted
strings, integers, decimals, `true/false`, `null/~`, `#` comments. Anchors, aliases, block
scalars (`|`, `>`) and multi-line flow collections are rejected with a clear error.

### Registered components

`./logflow --list-stages` prints this table from the registry itself.

| Kind | Type | In → Out | Parameters |
|---|---|---|---|
| source | `file` | – → `String` | `path` (string, required) |
| stage | `parse` | `String` → `LogRecord` | `format` (string, `clf`\|`combined`, default `clf`) |
| stage | `filter` | `LogRecord` → `LogRecord` | `field` (one of the record fields), `op` (`== != > >= < <= startsWith endsWith contains`), `value` (string or number), all required |
| stage | `project` | `LogRecord` → `ProjectedRecord` | `fields` (list of strings, required) |
| stage | `aggregate` | `LogRecord` → `ProjectedRecord` | `groupBy` (field, required), `sum` (`status`\|`bytes`, optional), `top` (integer, default 0 = all) |
| sink | `file` | any → file | `path` (string, required), `format` (`text`\|`csv`, default `text`) |
| sink | `console` | any → stdout | `format` (`text`\|`csv`, default `text`) |

`filter` value rules: `status`/`bytes` need a number; `timestamp` needs an ISO-8601 or CLF
time (compared as an instant); other fields are text and allow `==`, `!=`, `startsWith`,
`endsWith`, `contains`.

### Registry: data-driven, no `switch`

```java
public interface StageFactory extends ComponentFactory {
    String type();                 // "filter"
    List<ParamSpec> params();      // declared, validated generically
    Class<?> inputType();          // LogRecord
    Class<?> outputType();         // LogRecord
    Stage<?, ?> create(Params p);  // p is already type-checked
}
```

- `Registry<F>` is a `TreeMap<String, F>` filled by `java.util.ServiceLoader` from
  `src/main/resources/META-INF/services/logflow.config.{Source,Stage,Sink}Factory`.
  `StageRegistry` adds `converters(from, to)`, used for the "add a 'parse' stage" hint.
- Two factories with the same name fail at start-up (`Duplicate stage type 'filter'`).
- **Adding a stage**: write a class implementing `StageFactory`, add its name to
  `META-INF/services/logflow.config.StageFactory` (or put a jar with its own services file on
  the class path). `PipelineLoader`, `Main` and the existing stages do not change.
  `StageRegistryTest.aNewStageIsUsableWithoutChangingTheLoader` does exactly this.
- Parameter schemas are data (`ParamSpec`), so validation, error messages and the
  `--list-stages` documentation all come from one place.

### Configuration-time vs. run-time errors

| | Configuration time | Run time |
|---|---|---|
| When | `PipelineLoader.load`, before `run()` | during `Pipeline.run()` |
| Examples | unknown type, missing / unknown / wrongly typed parameter, empty pipeline, wrong stage order, input file missing, YAML syntax | malformed log line, I/O failure while reading or writing |
| Data touched | none: no input read, no output file created | partial |
| Policy | **fail fast, fail loudly**: collect *every* problem, print them all with their location (`pipeline.stages[1] (filter)`), exit code 2 | malformed lines are skipped and counted (v2); hard failures exit with code 1. Detailed handling is the Week 5 topic. |

Collecting all problems instead of stopping at the first one means a user fixes a broken
config in one round trip. Type-flow errors are suppressed after an unknown stage type so
that one typo does not produce a cascade of follow-up errors.

### Stage lifecycle extended: `finish`

`aggregate` cannot emit anything until it has seen every record. `Stage` gained
`default void finish(Emitter<O> out)`, called by `Pipeline.run()` after the source is
exhausted, stage by stage in order, before `close()`. Whatever stage *i* emits in `finish`
flows through stages *i+1..n* and into the sink, so an aggregate can be followed by more
stages. Existing stages inherit the empty default and are unchanged.

### Three configurations, one executable

| Config | Pipeline | Output |
|---|---|---|
| `configs/error-report.yaml` | parse → filter `status >= 400` → project 4 fields | `out/errors.txt` (80 lines, `key=value`) |
| `configs/api-traffic.yaml` | parse → filter `path startsWith /api` → project 5 fields (no client IP) | `out/api-traffic.csv` (25 rows) |
| `configs/bandwidth-by-client.yaml` | parse → filter `status < 400` → aggregate by `clientIp`, sum `bytes`, top 10 | `out/bandwidth-by-client.csv` (10 rows) |

The bandwidth report reads `data/access-clients.log` (300 synthetic lines, 12 clients),
because in `data/access-small.log` every client IP occurs exactly once and grouping would
be trivial.

### v3 command line

`logflow <log-file> --min-status ...` still works unchanged; `Main` chooses the config mode
only when `--config` or `--list-stages` is given.

## Increment 5 — error handling, validation and the dead-letter channel

Focus: error handling as an architectural concern. "Silently skip malformed lines" is
replaced by a designed strategy: every stage has an error **policy** chosen in the
configuration, failed records leave the main path through a second, independent output
(the **dead-letter channel**), and every run ends with a **summary** whose numbers add up.

### Error model: recoverable vs. fatal

| | `StageException` | `PipelineException` |
|---|---|---|
| Meaning | one stage could not process **one record** | the **pipeline cannot continue** |
| Kind | checked exception, thrown by `Stage.process` / `finish` | unchecked; `ConfigException extends PipelineException` |
| Examples | malformed / truncated / empty line, invalid UTF-8, line too long, invalid date, validation rule broken (negative bytes, 1970/2099, bad IP, status 999, unknown method) | invalid configuration, input file missing or unreadable, output file cannot be opened or written (disk full), dead-letter sink fails, a record rejected by a `FAIL_FAST` stage |
| Who decides what happens | the stage's `ErrorPolicy` (configuration), **not** the stage | nobody: the run stops, everything that was opened is closed, the partial `RunSummary` is attached |
| Exit code | 0 (the run completes) | 1 at run time, 2 for configuration errors |

A stage only *reports* that a record is bad (`throw new StageException(reason)`). It never
decides whether that means "stop", "skip" or "keep for later". An unexpected
`RuntimeException` thrown by a stage (a bug such as `NumberFormatException`) is also treated as
a failure of that one record and goes through the same policy; only a `PipelineException`
escapes. Source and sink failures are always fatal: a source that cannot be read or a sink
that cannot be written cannot be "skipped" (config: `onError` is rejected on `source`/`sink`).

### Policies

```yaml
pipeline:
  onError: FAIL_FAST          # default for stages without their own (built-in default: SKIP)
  stages:
    - type: parse
      onError: DEAD_LETTER    # per stage
```

| Policy | Effect on the failed record | Run |
|---|---|---|
| `FAIL_FAST` | none: `PipelineException("stage 'x' rejected record #n and its policy is FAIL_FAST: …")` | stops; components are closed, already written output is flushed |
| `SKIP` | dropped, counted as `failed` + `skipped` in the summary | continues |
| `DEAD_LETTER` | wrapped in a `FailedRecord` and sent to the dead-letter sink | continues |
| `RETRY(n)` | `process` is called again up to *n* times (1 ≤ n ≤ 10); still failing → dead letter | continues |

`RETRY` only repeats an attempt that has emitted nothing yet, so a retry can never
duplicate output. It is meant for the I/O stages of Week 6 (transient failures); retrying a
deterministic stage such as `parse` only wastes time. `DEAD_LETTER` and `RETRY` without a
`deadLetter` sink are configuration errors.

**Routing is topological, not a `try/catch`.** Where a failed record goes is part of the
pipeline's shape (which edge it leaves by), declared next to the stage in the configuration
and visible in the diagram below. The `try/catch` lives once, in `Pipeline`, and only
dispatches to the declared edge.

### Dead-letter channel

```text
                         main path
 Source ──► parse ──► validate ──► project ──────────────────────────► Sink (out/chaos-clean.txt)
              │           │
              │ StageException (policy DEAD_LETTER / RETRY exhausted)
              ▼           ▼
           FailedRecord(rawInput, stageName, exception, timestamp, recordNumber)
              │
              └──────────────────────────────────────────────────────► dead-letter Sink (out/dead-letter.jsonl)
                         error path (second, independent output)
```

- `FailedRecord` carries the **original raw input** (the line as it entered the pipeline, not
  the parsed object a later stage saw), the stage name, the `StageException` (reason), the
  time and the record number (line number for files). Raw input is kept whole (also the
  100 KB line), so dead letters can be inspected and replayed.
- The dead-letter sink is any registered sink: `deadLetter: { type: file, params: { path: …,
  format: json } }` writes JSON Lines. It has its own open/close lifecycle.
- If the dead-letter sink itself fails, failed records could be lost silently, so that is a
  `PipelineException`.

### ValidationStage: declarative rules

`parse` checks **syntax** (is this a CLF line?). `validate` checks **meaning** (is the value
plausible?). The rules are data, built from the configuration (`ValidationRule`):

```yaml
- type: validate
  onError: DEAD_LETTER
  params:
    required: [clientIp, method, path, protocol]                 # mandatory fields
    ranges: { status: [100, 599], bytes: [0, 104857600] }         # numeric ranges
    timestamp: { notBefore: "2000-01-01T00:00:00Z", notAfter: "2030-12-31T23:59:59Z" }
    ip: any                                                       # ipv4 | any (IPv4 or IPv6), no DNS lookup
    methods: [GET, POST, PUT, DELETE, HEAD, PATCH, OPTIONS]
```

All violations of one record are reported together (`status 999 is outside [100, 599];
clientIp '999.1.1.1' is not a valid …`). Invalid rule definitions (`min > max`, a range on a
text field, no rules at all) are configuration errors.

### RunSummary

`Pipeline.run()` returns a `RunSummary`; `Main` prints it after every run, also after a
failed one (from `PipelineException.summary()`), and `pipeline.summary: <file>` writes it as JSON.

```text
Run summary: pipeline 'chaos' COMPLETED in 0.102 s
  records in: 28   to sink: 10   to dead letter: 18
  stage          policy            in     out  dropped  failed  skipped  dead-letter  retries   time ms
  parse          DEAD_LETTER       28      17        0      11        0           11        0      32.3
  validate       DEAD_LETTER       17      10        0       7        0            7        0      14.7
  project        FAIL_FAST         10      10        0       0        0            0        0       1.2
  balance: in 28 = sink 10 + dropped 0 + skipped 0 + dead letter 18  OK
```

Design for the Week 8–9 measurements:

- Counters are collected by `Pipeline` around every stage call (`StageMetrics`), not by
  the stages, so every stage, including future plugins, is measured the same way.
- `time ms` is the stage's **own** time: time spent downstream (the push model calls the next
  stage from inside `emit`) is subtracted. Sink time is kept separately.
- `in`, `out`, `dropped` (processed without error, no output, e.g. filtered), `failed`,
  `skipped`, `dead-letter`, `retries`, status, start time and wall-clock time are immutable
  records (`RunSummary`, `StageMetrics`) with `toJson()`, ready to be collected over many runs.
- **Balance**: `in = sink + dropped + skipped + dead letter` holds whenever every stage is
  one-to-one. A stage that emits from `finish` or emits several outputs per input (e.g.
  `aggregate`, which shows its absorbed inputs as `dropped`) makes the check *not
  applicable*; the summary says so instead of printing a wrong equation.

### Chaos test: every error type and the policy that handles it

`data/access-dirty.log` (28 lines, generated by `tools/generate_access_dirty.py`) and
`data/access-empty.log` (0 bytes), run with `configs/chaos.yaml`
(`parse` and `validate` → `DEAD_LETTER`, everything else `FAIL_FAST`).
`DirtyDataChaosTest` asserts every row of this table.

| Error type in the dirty file | Line(s) | Detected by | Reason in the dead letter | Policy → destination |
|---|---|---|---|---|
| truncated line | 2, 14 | `parse` | `not a Common Log Format line` (`(truncated?)`) | `DEAD_LETTER` → dead-letter file |
| truncated last line without newline | 28 | `parse` | `not a Common Log Format line` | `DEAD_LETTER` |
| empty / whitespace-only line | 4, 13 | `parse` | `empty line` | `DEAD_LETTER` |
| non-UTF-8 bytes | 5, 23 | source decodes to U+FFFD (does not stop), `parse` rejects | `line is not valid UTF-8 (undecodable bytes at column n)` | `DEAD_LETTER` |
| 100 KB single line | 11 | `parse` (`maxLineLength: 8192`) | `line too long: 102466 characters (limit 8192)` | `DEAD_LETTER` (raw line kept whole) |
| binary garbage / not a log line | 19 | `parse` | `not a Common Log Format line` | `DEAD_LETTER` |
| impossible date (31 Feb) | 20 | `parse` (strict date resolution) | `invalid timestamp '31/Feb/2026:…'` | `DEAD_LETTER` |
| byte count overflows `long` | 21 | `parse` | `byte count out of range: 99999999999999999999` | `DEAD_LETTER` |
| negative byte count | 7, 26 | `validate` (`ranges.bytes`) | `bytes -512 is outside [0, 104857600]` | `DEAD_LETTER` |
| timestamp in 1970 | 8 | `validate` (`timestamp.notBefore`) | `timestamp 1970-01-01T10:12:00+03:00 is before 2000-01-01T00:00Z` | `DEAD_LETTER` |
| timestamp in 2099 | 10 | `validate` (`timestamp.notAfter`) | `timestamp 2099-01-01T10:13:00+03:00 is after 2030-12-31T23:59:59Z` | `DEAD_LETTER` |
| invalid IP address | 16 | `validate` (`ip`) | `clientIp '999.10.10.1' is not a valid IPv4 or IPv6 address` | `DEAD_LETTER` |
| status out of range | 17 | `validate` (`ranges.status`) | `status 999 is outside [100, 599]` | `DEAD_LETTER` |
| unknown HTTP method | 24 | `validate` (`methods`) | `method 'BREW' is not one of […]` | `DEAD_LETTER` |
| empty file (`access-empty.log`) | – | nothing to detect | – | run completes, all counts 0, balance OK |
| unreadable / missing input file | – | source | `PipelineException` | run stops (exit 1; missing file is already caught at load time, exit 2) |
| output not writable / disk full | – | sink | `PipelineException` | run stops, partial summary printed |

Result: 28 lines in, 10 valid records written (IPv4 and IPv6 clients), 18 records in the
dead-letter file, `28 = 10 + 0 + 0 + 18`. With `parse` switched to `FAIL_FAST`
the same file stops at line 2 with exit code 1; line 1 is already flushed to the output.

Two bugs that this test found and that are fixed in v5:

1. `31/Feb/2026` was silently accepted as **28 Feb**: `DateTimeFormatter` resolves dates
   leniently by default (`SMART`). The CLF formatter now uses `ResolverStyle.STRICT`.
2. A single invalid UTF-8 byte made `Files.newBufferedReader` throw and **abort the whole
   run**. The source now decodes with replacement characters so the error stays a
   one-record problem.

### Eklenen kullanıcı kaos veri seti

`data/fixture/access-dirty.log` ve `data/fixture/access-empty.log` kullanıcının gönderdiği dosyalardır; önceki kaos veri seti korunmuştur. `configs/fixture-chaos.yaml` ile çalıştırılır. İlk açıklama satırı geçerli log olmadığı için parse / DEAD_LETTER ile işlenir. Sonuç 11 girdi = 3 geçerli + 8 dead-letter. Kesik satırlar, geçersiz UTF-8 ve 100 KiB satır parse / DEAD_LETTER; negatif bayt ve 1970/2099 tarihleri validate / DEAD_LETTER olarak yönlendirilir. Boş dosya sıfır kayıtla tamamlanır.
