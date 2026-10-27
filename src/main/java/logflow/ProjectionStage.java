package logflow;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Her kaydı seçilen alanların alt kümesine indirger (çıktı boyutu ve gizlilik). */
public class ProjectionStage implements Stage<LogRecord, ProjectedRecord> {
    private final List<String> fields;

    public ProjectionStage(String... fields) {
        this(List.of(fields));
    }

    public ProjectionStage(List<String> fields) {
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("At least one field must be selected");
        }
        for (String f : fields) {
            if (!LogRecord.FIELD_NAMES.contains(f)) {
                throw new IllegalArgumentException("Unknown field: " + f + " (known: " + LogRecord.FIELD_NAMES + ")");
            }
        }
        if (new HashSet<>(fields).size() != fields.size()) {
            throw new IllegalArgumentException("Duplicate field in " + fields);
        }
        this.fields = List.copyOf(fields);
    }

    @Override
    public void process(LogRecord input, Emitter<ProjectedRecord> out) {
        Map<String, String> selected = new LinkedHashMap<>();
        for (String f : fields) {
            selected.put(f, input.field(f));
        }
        out.emit(new ProjectedRecord(selected));
    }

    public List<String> fields() { return fields; }
}
