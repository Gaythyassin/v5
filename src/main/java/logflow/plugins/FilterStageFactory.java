package logflow.plugins;

import java.util.List;
import logflow.Comparison;
import logflow.Filters;
import logflow.LogRecord;
import logflow.PredicateFilterStage;
import logflow.Stage;
import logflow.config.ParamSpec;
import logflow.config.ParamType;
import logflow.config.Params;
import logflow.config.StageFactory;

/** {@code - type: filter, params: { field: status, op: ">=", value: 400 }} */
public class FilterStageFactory implements StageFactory {

    @Override
    public String type() {
        return "filter";
    }

    @Override
    public String description() {
        return "keeps records for which 'field op value' holds (LogRecord -> LogRecord)";
    }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.required("field", ParamType.STRING, "record field").oneOf(LogRecord.FIELD_NAMES),
                ParamSpec.required("op", ParamType.STRING, "comparison operator").oneOf(Comparison.symbols()),
                ParamSpec.required("value", ParamType.SCALAR, "number for status/bytes, time for timestamp, text otherwise"));
    }

    @Override
    public Class<?> inputType() {
        return LogRecord.class;
    }

    @Override
    public Class<?> outputType() {
        return LogRecord.class;
    }

    @Override
    public Stage<?, ?> create(Params params) {
        String field = params.string("field");
        Comparison op = Comparison.bySymbol(params.string("op")).orElseThrow();
        Object value = params.value("value");
        String description = op.isTextual() ? field + " " + op + " " + value : field + op + value;
        return new PredicateFilterStage<>(Filters.compare(field, op, value), description);
    }
}
