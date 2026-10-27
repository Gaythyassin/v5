package logflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSinkTest {

    @TempDir
    Path dir;

    @Test
    void writesOneLinePerItemAndCreatesParentDirectories() throws IOException {
        Path file = dir.resolve("nested/out.txt");
        FileSink<String> sink = new FileSink<>(file);

        sink.open();
        sink.consume("first");
        sink.consume("second");
        sink.close();

        assertEquals(List.of("first", "second"), Files.readAllLines(file));
        assertEquals(2, sink.writtenCount());
    }

    @Test
    void usesFormatter() throws IOException {
        Path file = dir.resolve("out.txt");
        FileSink<Integer> sink = new FileSink<>(file, n -> "n=" + n);

        sink.open();
        sink.consume(7);
        sink.close();

        assertEquals(List.of("n=7"), Files.readAllLines(file));
    }

    /**
     * Bu haftanın dersi: close() çağrılmazsa çıktı arabellekte kalır ve dosya boştur.
     * Aynı sink kapatıldığında tüm satırlar diske ulaşır.
     */
    @Test
    void outputIsLostUntilCloseFlushesTheBuffer() throws IOException {
        Path file = dir.resolve("buffered.txt");
        FileSink<String> sink = new FileSink<>(file);

        sink.open();
        for (int i = 0; i < 10; i++) {
            sink.consume("line " + i);
        }
        assertEquals(0, Files.size(file), "before close() nothing has reached the disk");

        sink.close();
        assertEquals(10, Files.readAllLines(file).size());
    }

    @Test
    void consumeBeforeOpenOrAfterCloseFails() {
        FileSink<String> sink = new FileSink<>(dir.resolve("x.txt"));
        assertThrows(IllegalStateException.class, () -> sink.consume("too early"));

        sink.open();
        sink.close();
        sink.close(); // ikinci close zararsızdır
        assertThrows(IllegalStateException.class, () -> sink.consume("too late"));
    }

    @Test
    void openTruncatesAnExistingFile() throws IOException {
        Path file = dir.resolve("old.txt");
        Files.writeString(file, "stale\ncontent\n");
        FileSink<String> sink = new FileSink<>(file);

        sink.open();
        sink.consume("fresh");
        sink.close();

        assertEquals(List.of("fresh"), Files.readAllLines(file));
        assertTrue(Files.exists(file));
    }
}
