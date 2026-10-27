package logflow;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * Her kaydı çıktı dosyasına bir satır olarak yazar. Yazıcı {@link #open()}'da açılır,
 * {@link #close()}'da kapatılır; kapatılmazsa arabellekteki satırlar diske hiç ulaşmaz.
 * G/Ç hataları (dizin yok, disk dolu) {@link PipelineException}'dır: çıktı yazılamıyorsa devam edilemez.
 */
public class FileSink<T> implements Sink<T>, Reportable {
    private final Path path;
    private final Function<? super T, String> formatter;
    private BufferedWriter writer;
    private long written;

    public FileSink(Path path) { this(path, String::valueOf); }

    public FileSink(Path path, Function<? super T, String> formatter) {
        this.path = path;
        this.formatter = formatter;
    }

    @Override
    public void open() {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
            written = 0;
        } catch (IOException e) {
            throw new PipelineException("Could not open output file " + path + ": " + e, e);
        }
    }

    @Override
    public void consume(T item) {
        if (writer == null) throw new IllegalStateException("FileSink not open: " + path);
        try {
            writer.write(formatter.apply(item) + "\n");
            written++;
        } catch (IOException e) {
            throw new PipelineException("Could not write output file " + path + " (disk full?): " + e, e);
        }
    }

    @Override
    public void close() {
        try (BufferedWriter w = writer) { // try-with-resources: flush + close, null güvenli
            writer = null;
        } catch (IOException e) {
            throw new PipelineException("Could not close output file " + path + ": " + e, e);
        }
    }

    public long writtenCount() { return written; }

    public Path path() { return path; }

    @Override
    public String report() { return "sink       written: " + written + " -> " + path; }
}
