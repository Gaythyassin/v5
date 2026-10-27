package logflow.plugins;

import java.util.List;
import logflow.AggregateStage;
import logflow.LogRecord;
import logflow.ProjectedRecord;
import logflow.Stage;
import logflow.config.ParamSpec;
import logflow.config.ParamType;
import logflow.config.Params;
import logflow.config.StageFactory;

/** {@code - type: aggregate, params: { groupBy: clientIp, sum: bytes, top: 10 }} */
public class AggregateStageFactory implements StageFactory {

    @Override
    public String type() {
        return "aggregate";
    }

    @Override
    public String description() {
        return "groups records, counts requests and sums a numeric field (LogRecord -> ProjectedRecord)";
    }

    @Override
    public List<ParamSpec> params() {
        return List.of(
                ParamSpec.required("groupBy", ParamType.STRING, "field to group by").oneOf(LogRecord.FIELD_NAMES),
                ParamSpec.optional("sum", ParamType.STRING, null, "numeric field to total; groups are ranked by it")
                        .oneOf(LogRecord.NUMERIC_FIELDS),
                ParamSpec.optional("top", ParamType.INTEGER, 0, "emit only the first N groups; 0 = all"));
    }

    @Override
    public Class<?> inputType() {
        return LogRecord.class;
    }

    @Override
    public Class<?> outputType() {
        return ProjectedRecord.class;
    }

    @Override
    public Stage<?, ?> create(Params params) {
        return new AggregateStage(params.string("groupBy"), params.string("sum"), params.integer("top"));
    }
}
