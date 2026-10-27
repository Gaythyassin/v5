package logflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class CliOptionsTest {

    private static List<String> descriptions(CliOptions o) {
        return o.filters().stream().map(CliOptions.NamedFilter::description).toList();
    }

    @Test
    void parsesTheExampleFromTheAssignment() {
        CliOptions o = CliOptions.parse("data/in.log",
                "--min-status", "400", "--path-prefix", "/api", "--fields", "timestamp,clientIp,status");

        assertEquals(Path.of("data/in.log"), o.input());
        assertEquals(List.of("status>=400", "path^/api"), descriptions(o));
        assertEquals(List.of("timestamp", "clientIp", "status"), o.fields());
        assertNull(o.output());
    }

    @Test
    void filtersKeepCommandLineOrder() {
        CliOptions o = CliOptions.parse("--path-prefix", "/api", "in.log", "--min-status", "500", "--out", "r.txt");

        assertEquals(List.of("path^/api", "status>=500"), descriptions(o));
        assertEquals(Path.of("r.txt"), o.output());
    }

    @Test
    void noOptionsMeansNoFiltersAndNoProjection() {
        CliOptions o = CliOptions.parse("in.log");
        assertTrue(o.filters().isEmpty());
        assertTrue(o.fields().isEmpty());
    }

    @Test
    void fromAndToBecomeOneTimeFilterInPlaceOfTheFirst() {
        CliOptions o = CliOptions.parse("in.log", "--from", "29/Sep/2026:10:00:00 +0300",
                "--min-status", "400", "--to", "2026-09-29T10:01:00+03:00");

        assertEquals(2, o.filters().size());
        assertTrue(o.filters().get(0).description().startsWith("time in [2026-09-29T10:00+03:00"));
        var time = o.filters().get(0).predicate();
        assertTrue(time.test(rec("2026-09-29T10:00:30+03:00")));
        assertFalse(time.test(rec("2026-09-29T10:01:00+03:00")));
    }

    @Test
    void reportsHelpfulErrors() {
        assertThrows(IllegalArgumentException.class, () -> CliOptions.parse());
        assertThrows(IllegalArgumentException.class, () -> CliOptions.parse("in.log", "--min-status"));
        assertThrows(IllegalArgumentException.class, () -> CliOptions.parse("in.log", "--min-status", "abc"));
        assertThrows(IllegalArgumentException.class, () -> CliOptions.parse("in.log", "--bogus"));
        assertThrows(IllegalArgumentException.class, () -> CliOptions.parse("a.log", "b.log"));
        assertThrows(IllegalArgumentException.class, () -> CliOptions.parse("in.log", "--from", "yesterday"));
        assertThrows(IllegalArgumentException.class, () -> CliOptions.parse("in.log", "--fields", " , "));
        assertThrows(IllegalArgumentException.class, () -> CliOptions.parse("in.log",
                "--from", "2026-09-29T11:00:00+03:00", "--to", "2026-09-29T10:00:00+03:00"));
    }

    private static LogRecord rec(String time) {
        return new LogRecord("ip", "", OffsetDateTime.parse(time), "GET", "/", "HTTP/1.1", 200, 0, "", "", null);
    }
}
