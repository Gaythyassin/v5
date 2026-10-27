package logflow.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import logflow.Pipeline;
import logflow.Reportable;
import logflow.RunSummary;

/**
 * Yapılandırmadan kurulmuş, çalışmaya hazır bir pipeline.
 *
 * @param summaryFile çalıştırma özetinin JSON olarak yazılacağı dosya ya da {@code null}
 */
public record LoadedPipeline(String name, String source, Pipeline pipeline, Path summaryFile) {

    public LoadedPipeline(String name, String source, Pipeline pipeline) {
        this(name, source, pipeline, null);
    }

    /** @throws logflow.PipelineException çalıştırma devam edemezse (o ana kadarki özetle) */
    public RunSummary run() {
        return pipeline.run();
    }

    /** Çalıştıktan sonra, özet verebilen her bileşenden bir satır (akış sırasıyla). */
    public List<String> report() {
        List<Object> components = new ArrayList<>();
        components.add(pipeline.source());
        components.addAll(pipeline.stages());
        components.add(pipeline.sink());
        components.add(pipeline.deadLetter());
        return components.stream().filter(c -> c instanceof Reportable).map(c -> ((Reportable) c).report()).toList();
    }
}
