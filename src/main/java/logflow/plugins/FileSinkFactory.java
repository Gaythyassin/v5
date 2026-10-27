package logflow.plugins;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import logflow.FileSink;
import logflow.OutputFormat;
import logflow.Sink;
import logflow.config.ParamSpec;
import logflow.config.ParamType;
import logflow.config.Params;
import logflow.config.SinkFactory;

/** {@code sink: { type: file, params: { path: out/x.txt, format: text } }} */
public class FileSinkFactory implements SinkFactory {
    static final List<String> FORMATS = List.of("text", "csv", "json");

    @Override
    public String type() {
        return "file";
    }

    @Override
    public String description() {
        return "writes one line per record to a file (created with its parent directories)";
    }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.required("path", ParamType.STRING, "output file"),
                ParamSpec.optional("format", ParamType.STRING, "text", "line format").oneOf(FORMATS));
    }

    @Override
    public Class<?> inputType() {
        return Object.class;
    }

    @Override
    public Sink<?> create(Params params) {
        OutputFormat format = OutputFormat.valueOf(params.string("format").toUpperCase(Locale.ROOT));
        return new FileSink<Object>(Path.of(params.string("path")), format.formatter());
    }
}
