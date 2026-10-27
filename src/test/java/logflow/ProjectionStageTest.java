package logflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProjectionStageTest {

    private static ProjectedRecord project(ProjectionStage stage, LogRecord r) {
        CollectingEmitter<ProjectedRecord> out = new CollectingEmitter<>();
        stage.process(r, out);
        assertEquals(1, out.items().size()); // projeksiyon 1 girdi -> 1 çıktı
        return out.items().get(0);
    }

    @Test
    void keepsOnlySelectedFieldsInSelectedOrder() {
        ProjectedRecord p = project(new ProjectionStage("status", "clientIp", "path"),
                LogRecordTest.sample(404, "/cart"));

        assertEquals(List.of("status", "clientIp", "path"), List.copyOf(p.fields().keySet()));
        assertEquals("404", p.get("status"));
        assertEquals("status=404 clientIp=10.0.0.1 path=/cart", p.toString());
    }

    @Test
    void droppedFieldsAreNoLongerAccessible() {
        ProjectedRecord p = project(new ProjectionStage("timestamp", "status"),
                LogRecordTest.sample(200, "/secret"));

        // Veri minimizasyonu: atılan alan aşağı akışta yoktur.
        assertThrows(IllegalArgumentException.class, () -> p.get("path"));
        assertThrows(IllegalArgumentException.class, () -> p.get("clientIp"));
    }

    @Test
    void quotesValuesWithSpacesOrEmpty() {
        LogRecord r = new LogRecord("1.2.3.4", "", OffsetDateTime.parse("2026-09-29T10:00:00+03:00"),
                "GET", "/", "HTTP/1.1", 200, 0, "", "Mozilla/5.0 (X11) \"x\"", null);

        ProjectedRecord p = project(new ProjectionStage("userAgent", "referer"), r);

        assertEquals("userAgent=\"Mozilla/5.0 (X11) \\\"x\\\"\" referer=\"\"", p.toString());
    }

    @Test
    void rejectsUnknownEmptyOrDuplicateFieldsAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new ProjectionStage("status", "nope"));
        assertThrows(IllegalArgumentException.class, () -> new ProjectionStage(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ProjectionStage("status", "status"));
    }

    @Test
    void projectedRecordIsImmutable() {
        ProjectedRecord p = new ProjectedRecord(Map.of("a", "1"));
        assertThrows(UnsupportedOperationException.class, () -> p.fields().put("b", "2"));
    }
}
