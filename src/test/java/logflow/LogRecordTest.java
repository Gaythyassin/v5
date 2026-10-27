package logflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LogRecordTest {

    static LogRecord sample(int status, String path) {
        return new LogRecord("10.0.0.1", "", OffsetDateTime.parse("2026-09-29T10:00:00+03:00"),
                "GET", path, "HTTP/1.1", status, 123, "", "", null);
    }

    @Test
    void withAttributeReturnsCopyAndLeavesOriginalUntouched() {
        LogRecord original = sample(200, "/");
        LogRecord copy = original.withAttribute("geo", "TR");

        assertTrue(original.attributes().isEmpty());
        assertEquals("TR", copy.attributes().get("geo"));
        assertEquals("TR", copy.field("geo"));
    }

    @Test
    void attributesAreDefensivelyCopied() {
        Map<String, String> map = new HashMap<>();
        map.put("k", "v");
        LogRecord r = new LogRecord("ip", null, OffsetDateTime.now(), "GET", "/", null, 200, 0, null, null, map);
        map.put("k", "changed");

        assertEquals("v", r.attributes().get("k"));
        assertThrows(UnsupportedOperationException.class, () -> r.attributes().put("x", "y"));
    }

    @Test
    void fieldReturnsTextValuesByName() {
        LogRecord r = sample(404, "/api/items");

        assertEquals("404", r.field("status"));
        assertEquals("/api/items", r.field("path"));
        assertEquals("2026-09-29T10:00:00+03:00", r.field("timestamp")); // saniye her zaman var
        assertThrows(IllegalArgumentException.class, () -> r.field("nope"));
    }
}
