package logflow.plugins;

import java.util.List;
import logflow.LogRecord;
import logflow.ParserStage;
import logflow.Stage;
import logflow.config.ParamSpec;
import logflow.config.ParamType;
import logflow.config.Params;
import logflow.config.StageFactory;

/** {@code - type: parse} — satırı {@link LogRecord}'a çevirir; bozuk satırlar atlanır ve sayılır. */
public class ParseStageFactory implements StageFactory {

    @Override
    public String type() {
        return "parse";
    }

    @Override
    public String description() {
        return "parses Common/Combined Log Format lines; rejects malformed ones with a reason (String -> LogRecord)";
    }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.optional("format", ParamType.STRING, "clf",
                        "clf = Common Log Format, combined = CLF + referer and user agent").oneOf(List.of("clf", "combined")),
                ParamSpec.optional("maxLineLength", ParamType.INTEGER, ParserStage.DEFAULT_MAX_LINE_LENGTH,
                        "longer lines are rejected"));
    }

    @Override
    public Class<?> inputType() {
        return String.class;
    }

    @Override
    public Class<?> outputType() {
        return LogRecord.class;
    }

    @Override
    public Stage<?, ?> create(Params params) {
        // ParserStage iki biçimi de tanır: Combined alanları isteğe bağlıdır.
        return new ParserStage(params.integer("maxLineLength"));
    }
}
