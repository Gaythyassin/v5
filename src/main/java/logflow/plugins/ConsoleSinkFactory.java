package logflow.plugins;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import logflow.OutputFormat;
import logflow.Sink;
import logflow.config.ParamSpec;
import logflow.config.ParamType;
import logflow.config.Params;
import logflow.config.SinkFactory;

/** {@code sink: { type: console }} — kayıtları standart çıktıya yazar. */
public class ConsoleSinkFactory implements SinkFactory {

    @Override
    public String type() {
        return "console";
    }

    @Override
    public String description() {
        return "prints one line per record to standard output";
    }

    @Override
    public List<ParamSpec> params() {
        return List.of(ParamSpec.optional("format", ParamType.STRING, "text", "line format").oneOf(FileSinkFactory.FORMATS));
    }

    @Override
    public Class<?> inputType() {
        return Object.class;
    }

    @Override
    public Sink<?> create(Params params) {
        Function<Object, String> formatter = OutputFormat.valueOf(params.string("format").toUpperCase(Locale.ROOT)).formatter();
        return item -> System.out.println(formatter.apply(item));
    }
}
