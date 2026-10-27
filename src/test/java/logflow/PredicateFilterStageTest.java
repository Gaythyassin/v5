package logflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class PredicateFilterStageTest {

    private static LogRecord at(String time, int status, String path) {
        return new LogRecord("10.0.0.1", "", OffsetDateTime.parse(time), "GET", path, "HTTP/1.1",
                status, 1, "", "", null);
    }

    private static <T> List<T> run(PredicateFilterStage<T> stage, List<T> inputs) {
        CollectingEmitter<T> out = new CollectingEmitter<>();
        inputs.forEach(i -> stage.process(i, out));
        return out.items();
    }

    @Test
    void passesOnlyMatchingRecordsUnchanged() {
        LogRecord ok = LogRecordTest.sample(200, "/");
        LogRecord err = LogRecordTest.sample(404, "/x");
        PredicateFilterStage<LogRecord> stage = new PredicateFilterStage<>(Filters.minStatus(400), "status>=400");

        List<LogRecord> out = run(stage, List.of(ok, err));

        assertEquals(1, out.size());
        assertSame(err, out.get(0)); // eleyen filtre kaydı dönüştürmez
        assertEquals(2, stage.evaluatedCount());
        assertEquals(1, stage.passedCount());
        assertEquals("Filter[status>=400]", stage.toString());
    }

    @Test
    void minStatusIsInclusive() {
        assertTrue(Filters.minStatus(400).test(LogRecordTest.sample(400, "/")));
        assertFalse(Filters.minStatus(400).test(LogRecordTest.sample(399, "/")));
    }

    @Test
    void pathPrefixMatchesStartOnly() {
        var stage = new PredicateFilterStage<>(Filters.pathPrefix("/api"));
        List<LogRecord> out = run(stage, List.of(
                LogRecordTest.sample(200, "/api/items"),
                LogRecordTest.sample(200, "/v1/api"),
                LogRecordTest.sample(200, "/")));

        assertEquals(List.of("/api/items"), out.stream().map(LogRecord::path).toList());
    }

    @Test
    void timeRangeIsHalfOpenAndOffsetAware() {
        var range = Filters.between(OffsetDateTime.parse("2026-09-29T10:00:00+03:00"),
                OffsetDateTime.parse("2026-09-29T10:01:00+03:00"));

        assertTrue(range.test(at("2026-09-29T10:00:00+03:00", 200, "/")));   // from dahil
        assertTrue(range.test(at("2026-09-29T07:00:30Z", 200, "/")));        // aynı an, UTC
        assertFalse(range.test(at("2026-09-29T10:01:00+03:00", 200, "/")));  // to hariç
        assertFalse(range.test(at("2026-09-29T09:59:59+03:00", 200, "/")));
    }

    @Test
    void timeRangeAllowsOpenEndsAndRejectsInvertedRange() {
        OffsetDateTime t = OffsetDateTime.parse("2026-09-29T10:00:00+03:00");
        assertTrue(Filters.between(null, null).test(at("2000-01-01T00:00:00Z", 200, "/")));
        assertTrue(Filters.between(t, null).test(at("2030-01-01T00:00:00Z", 200, "/")));
        assertFalse(Filters.between(null, t).test(at("2030-01-01T00:00:00Z", 200, "/")));
        assertThrows(IllegalArgumentException.class, () -> Filters.between(t, t));
    }

    @Test
    void isGenericAndWorksOnAnyType() {
        var stage = new PredicateFilterStage<String>(s -> s.length() > 2);
        assertEquals(List.of("abc"), run(stage, List.of("a", "abc")));
    }
}
