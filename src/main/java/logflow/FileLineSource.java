package logflow;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Dosyayı UTF-8 olarak satır satır okur. Geçersiz bayt dizileri okumayı durdurmaz: yerlerine
 * U+FFFD konur ve satır yine yayılır, böylece o <b>tek satır</b> bir aşamanın hata politikasına
 * düşer. Dosya açılamaz ya da okunamazsa {@link PipelineException} (devam edilemez).
 */
public class FileLineSource implements Source<String> {
    private final Path path;

    public FileLineSource(Path path) {
        this.path = path;
    }

    @Override
    public void produce(Emitter<String> out) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(path), decoder))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.emit(line);
            }
        } catch (IOException e) {
            throw new PipelineException("Could not read input file " + path + ": " + e, e);
        }
    }

    public Path path() {
        return path;
    }
}
