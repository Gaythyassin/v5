package logflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ConsoleSinkTest {

    @Test
    void printsOneReadableLinePerRecord() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ConsoleSink sink = new ConsoleSink(new PrintStream(buffer, true, StandardCharsets.UTF_8));

        sink.consume(LogRecordTest.sample(404, "/cart"));

        assertEquals("[2026-09-29T10:00+03:00] 10.0.0.1 GET /cart -> 404 (123 B)" + System.lineSeparator(),
                buffer.toString(StandardCharsets.UTF_8));
    }
}
