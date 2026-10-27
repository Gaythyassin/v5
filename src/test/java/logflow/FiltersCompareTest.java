package logflow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class FiltersCompareTest {
    private static final LogRecord R = ParserStage.parse(
            "10.0.0.5 - alice [29/Sep/2026:10:00:40 +0300] \"DELETE /api/items/7 HTTP/1.1\" 404 0");

    private static boolean holds(String field, String op, Object value) {
        Predicate<LogRecord> p = Filters.compare(field, Comparison.bySymbol(op).orElseThrow(), value);
        return p.test(R);
    }

    @Test
    void numericFieldsCompareAsNumbers() {
        assertTrue(holds("status", ">=", 400));
        assertTrue(holds("status", "==", 404));
        assertFalse(holds("status", "<", 400));
        assertTrue(holds("bytes", "<=", 0));
    }

    @Test
    void textFieldsSupportEqualityAndTextOperators() {
        assertTrue(holds("path", "startsWith", "/api"));
        assertTrue(holds("path", "endsWith", "/7"));
        assertTrue(holds("method", "==", "DELETE"));
        assertTrue(holds("user", "!=", "bob"));
        assertTrue(holds("status", "startsWith", "4"));
    }

    @Test
    void timestampComparesAsAnInstantAcrossTimeZones() {
        assertTrue(holds("timestamp", ">=", "2026-09-29T07:00:40Z"));
        assertFalse(holds("timestamp", ">", "2026-09-29T07:00:40Z"));
        assertTrue(holds("timestamp", "<", "29/Sep/2026:10:01:00 +0300"));
    }

    @Test
    void mismatchedValueTypesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> holds("status", ">=", "abc"));
        assertThrows(IllegalArgumentException.class, () -> holds("path", ">", "/a"));
        assertThrows(IllegalArgumentException.class, () -> holds("path", "==", 3));
        assertThrows(IllegalArgumentException.class, () -> holds("path", "startsWith", 3));
        assertThrows(IllegalArgumentException.class, () -> holds("timestamp", ">", "yesterday"));
        assertThrows(IllegalArgumentException.class, () -> holds("nope", "==", "x"));
    }
}
