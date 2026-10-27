package logflow;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import logflow.config.ComponentFactory;
import logflow.config.ConfigException;
import logflow.config.LoadedPipeline;
import logflow.config.ParamSpec;
import logflow.config.PipelineLoader;
import logflow.config.Plugins;
import logflow.config.Registry;

public class Main {
    static final String CONFIG_USAGE = String.join(System.lineSeparator(),
            "Usage: logflow --config <pipeline.yaml>   run a pipeline described in a config file",
            "       logflow --list-stages              list sources, stages and sinks with their parameters",
            "       logflow <log-file> [options]       v3 command-line mode (see below)",
            "");

    /** Çıkış kodları: 0 başarı, 1 çalıştırma durdu ({@link PipelineException}), 2 yapılandırma/kullanım hatası. */
    static final int OK = 0;
    static final int RUNTIME_ERROR = 1;
    static final int CONFIG_ERROR = 2;

    public static void main(String[] args) {
        List<String> argList = List.of(args);
        if (argList.contains("--config") || argList.contains("--list-stages")) {
            int code = runConfigMode(args, System.out, System.err);
            if (code != OK) {
                System.exit(code);
            }
            return;
        }
        runCliMode(args);
    }

    /**
     * {@code --config FILE}: pipeline tamamen yapılandırmadan kurulur. Önce yükleme ve doğrulama
     * (veri okunmaz), sonra çalıştırma; iki hata türü ayrı bildirilir ve ayrı çıkış kodu alır.
     */
    static int runConfigMode(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 1 && args[0].equals("--list-stages")) {
            listComponents(Plugins.discover(), out);
            return OK;
        }
        if (args.length != 2 || !args[0].equals("--config")) {
            err.println("Error: expected exactly '--config <file>' or '--list-stages'");
            err.print(CONFIG_USAGE);
            return CONFIG_ERROR;
        }

        LoadedPipeline loaded;
        try {
            loaded = new PipelineLoader().load(Path.of(args[1]));
        } catch (ConfigException e) {
            err.println("Configuration error: " + e.getMessage());
            return CONFIG_ERROR;
        }

        RunSummary summary;
        int code;
        try {
            summary = loaded.run();
            code = OK;
        } catch (PipelineException e) {
            err.println("Runtime error in pipeline '" + loaded.name() + "': " + e.getMessage());
            summary = e.summary();
            code = RUNTIME_ERROR;
        }
        if (summary != null) {
            err.println(summary.format());
            writeSummary(loaded.summaryFile(), summary, err);
        }
        return code;
    }

    /** Özeti JSON olarak yazar; yazılamazsa uyarır ama çalıştırmanın sonucunu değiştirmez. */
    private static void writeSummary(Path file, RunSummary summary, PrintStream err) {
        if (file == null) {
            return;
        }
        try {
            if (file.toAbsolutePath().getParent() != null) {
                Files.createDirectories(file.toAbsolutePath().getParent());
            }
            Files.writeString(file, summary.toJson() + System.lineSeparator());
        } catch (IOException e) {
            err.println("Warning: could not write run summary to " + file + ": " + e);
        }
    }

    static void listComponents(Plugins plugins, PrintStream out) {
        List<Registry<? extends ComponentFactory>> registries = List.of(plugins.sources(), plugins.stages(), plugins.sinks());
        for (Registry<? extends ComponentFactory> registry : registries) {
            out.println(registry.kind() + " types:");
            for (ComponentFactory f : registry.all()) {
                out.println("  " + f.type() + " - " + f.description());
                for (ParamSpec p : f.params()) {
                    out.println("      " + p.summary());
                }
            }
        }
    }

    /** v3 komut satırı modu, değişmeden. */
    static void runCliMode(String[] args) {
        // 1) Read command-line arguments.
        if (args.length == 0 || List.of(args).contains("--help") || List.of(args).contains("-h")) {
            System.err.print(CONFIG_USAGE);
            System.err.println(CliOptions.USAGE);
            System.exit(args.length == 0 ? 1 : 0);
        }
        CliOptions options;
        ProjectionStage projection;
        try {
            options = CliOptions.parse(args);
            projection = options.fields().isEmpty() ? null : new ProjectionStage(options.fields());
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println(CliOptions.USAGE);
            System.exit(2);
            return;
        }

        // 2) Assemble: Source -> Parser -> filters (in CLI order) -> [Projection] -> Sink.
        ParserStage parser = new ParserStage();
        PipelineBuilder<LogRecord> records = PipelineBuilder.from(new FileLineSource(options.input()))
                .via(parser);
        List<PredicateFilterStage<LogRecord>> filters = new ArrayList<>();
        for (CliOptions.NamedFilter f : options.filters()) {
            PredicateFilterStage<LogRecord> stage = new PredicateFilterStage<>(f.predicate(), f.description());
            filters.add(stage);
            records = records.via(stage);
        }

        FileSink<?> fileSink = null;
        Pipeline pipeline;
        if (projection == null) {
            if (options.output() == null) {
                pipeline = records.to(new ConsoleSink());
            } else {
                FileSink<LogRecord> sink = new FileSink<>(options.output(), ConsoleSink::format);
                fileSink = sink;
                pipeline = records.to(sink);
            }
        } else {
            PipelineBuilder<ProjectedRecord> projected = records.via(projection);
            if (options.output() == null) {
                pipeline = projected.to(r -> System.out.println(r));
            } else {
                FileSink<ProjectedRecord> sink = new FileSink<>(options.output());
                fileSink = sink;
                pipeline = projected.to(sink);
            }
        }

        // 3) Run it.
        pipeline.run();

        // 4) Report totals.
        System.out.printf("Parsed: %d, malformed (skipped): %d%n",
                parser.parsedCount(), parser.malformedCount());
        for (PredicateFilterStage<LogRecord> f : filters) {
            System.out.printf("Filter %-40s in: %d, passed: %d%n",
                    f.description(), f.evaluatedCount(), f.passedCount());
        }
        if (fileSink != null) {
            System.out.printf("Written: %d -> %s%n", fileSink.writtenCount(), fileSink.path());
        }
    }
}
