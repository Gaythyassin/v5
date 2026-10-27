package logflow.plugins;

import java.util.List;
import logflow.LogRecord;
import logflow.ProjectedRecord;
import logflow.ProjectionStage;
import logflow.Stage;
import logflow.config.ParamSpec;
import logflow.config.ParamType;
import logflow.config.Params;
import logflow.config.StageFactory;

/** {@code - type: project, params: { fields: [timestamp, clientIp] }} */
public class ProjectStageFactory implements StageFactory {

    @Override
    public String type() {
        return "project";
    }

    @Override
    public String description() {
        return "keeps only the listed fields, in that order (LogRecord -> ProjectedRecord)";
    }

    @Override
    public List<ParamSpec> params() {
        return List.of(ParamSpec.required("fields", ParamType.STRING_LIST, "fields to keep, from " + LogRecord.FIELD_NAMES));
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
        return new ProjectionStage(params.stringList("fields"));
    }
}
