package logflow.plugins;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import logflow.FileLineSource;
import logflow.Source;
import logflow.config.ParamSpec;
import logflow.config.ParamType;
import logflow.config.Params;
import logflow.config.SourceFactory;

/** {@code source: { type: file, params: { path: ... } }} — dosyayı satır satır okur. */
public class FileSourceFactory implements SourceFactory {

    @Override
    public String type() {
        return "file";
    }

    @Override
    public String description() {
        return "reads a text file line by line (emits String)";
    }

    @Override
    public List<ParamSpec> params() {
        return List.of(ParamSpec.required("path", ParamType.STRING, "input file, relative to the working directory"));
    }

    @Override
    public Class<?> outputType() {
        return String.class;
    }

    @Override
    public Source<?> create(Params params) {
        Path path = Path.of(params.string("path"));
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("input file not found: " + path
                    + " (relative paths are resolved against " + Path.of("").toAbsolutePath() + ")");
        }
        return new FileLineSource(path);
    }
}
