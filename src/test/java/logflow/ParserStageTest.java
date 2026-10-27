package logflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ParserStageTest {

    @Test
    void parsesCommonLogFormatLine() throws StageException {
        ParserStage stage = new ParserStage();
        CollectingEmitter<LogRecord> out = new CollectingEmitter<>();

        stage.process("192.168.0.7 - - [29/Sep/2026:10:01:18 +0300] \"PUT /cart HTTP/1.1\" 400 478", out);

        assertEquals(1, out.items().size());
        LogRecord r = out.items().get(0);
        assertEquals("192.168.0.7", r.clientIp());
        assertEquals(OffsetDateTime.of(2026, 9, 29, 10, 1, 18, 0, ZoneOffset.ofHours(3)), r.timestamp());
        assertEquals("PUT", r.method());
        assertEquals("/cart", r.path());
        assertEquals("HTTP/1.1", r.protocol());
        assertEquals(400, r.status());
        assertEquals(478, r.bytes());
        assertEquals("", r.userAgent());
        assertEquals(1, stage.parsedCount());
    }

    @Test
    void parsesCombinedFormatRefererAndUserAgent() throws StageException {
        ParserStage stage = new ParserStage();
        CollectingEmitter<LogRecord> out = new CollectingEmitter<>();

        stage.process("10.0.0.1 - bob [29/Sep/2026:10:00:00 +0300] \"GET /api/x HTTP/1.1\" 200 - "
                + "\"https://example.com/\" \"curl/8.0\"", out);

        LogRecord r = out.items().get(0);
        assertEquals("bob", r.user());
        assertEquals(0, r.bytes());
        assertEquals("https://example.com/", r.referer());
        assertEquals("curl/8.0", r.userAgent());
    }

    /** v5: bozuk satır sessizce atlanmaz; nedeniyle reddedilir, karar hata politikasınındır. */
    @Test
    void rejectsMalformedLinesWithAReasonAndCountsThem() {
        ParserStage stage = new ParserStage(100);
        CollectingEmitter<LogRecord> out = new CollectingEmitter<>();

        assertEquals("empty line", reason(stage, "", out));
        assertEquals("empty line", reason(stage, "   ", out));
        assertEquals("not a Common Log Format line", reason(stage, "this is not a log line", out));
        assertEquals("invalid timestamp '99/Foo/2026:10:00:00 +0300'",
                reason(stage, "1.1.1.1 - - [99/Foo/2026:10:00:00 +0300] \"GET / HTTP/1.1\" 200 1", out));
        assertEquals("invalid timestamp '31/Feb/2026:10:00:00 +0300'",
                reason(stage, "1.1.1.1 - - [31/Feb/2026:10:00:00 +0300] \"GET / HTTP/1.1\" 200 1", out));
        assertEquals("line too long: 101 characters (limit 100)", reason(stage, "x".repeat(101), out));
        assertEquals("line is not valid UTF-8 (undecodable bytes at column 3)", reason(stage, "ab\uFFFDcd", out));
        assertEquals("byte count out of range: 99999999999999999999",
                reason(stage, "1.1.1.1 - - [30/Sep/2026:10:00:00 +0300] \"GET / HTTP/1.1\" 200 99999999999999999999", out));
        assertTrue(reason(stage, "1.1.1.1 - - [30/Sep/2026:10:00:00 +0300] \"GET /abc", out).contains("truncated"));

        assertTrue(out.items().isEmpty());
        assertEquals(0, stage.parsedCount());
        assertEquals(9, stage.malformedCount());
    }

    /** Sözdizimi geçerli ama anlamsız değerler ayrıştırıcıdan geçer; onları ValidationStage reddeder. */
    @Test
    void negativeBytesAreSyntaxNotSemantics() throws StageException {
        CollectingEmitter<LogRecord> out = new CollectingEmitter<>();
        new ParserStage().process("1.1.1.1 - - [01/Jan/1970:00:00:00 +0000] \"GET / HTTP/1.1\" 200 -5", out);
        assertEquals(-5, out.items().get(0).bytes());
    }

    private static String reason(ParserStage stage, String line, CollectingEmitter<LogRecord> out) {
        return assertThrows(StageException.class, () -> stage.process(line, out)).getMessage();
    }
}
