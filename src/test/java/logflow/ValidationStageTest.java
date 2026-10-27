package logflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ValidationStageTest {

    private static LogRecord line(String ip, String date, String method, int status, long bytes) {
        return ParserStage.parse(ip + " - - [" + date + ":10:00:00 +0000] \"" + method + " /x HTTP/1.1\" " + status + " " + bytes);
    }

    private final ValidationStage stage = new ValidationStage(List.of(
            ValidationRule.required("path"),
            ValidationRule.range("status", 100, 599),
            ValidationRule.range("bytes", 0, 1_000_000),
            ValidationRule.timestampBetween(OffsetDateTime.parse("2000-01-01T00:00:00Z"), OffsetDateTime.parse("2030-12-31T23:59:59Z")),
            ValidationRule.ipAddress(true),
            ValidationRule.methodIn(List.of("GET", "POST"))));

    @Test
    void validRecordPassesUnchanged() throws StageException {
        CollectingEmitter<LogRecord> out = new CollectingEmitter<>();
        LogRecord r = line("10.0.0.1", "30/Sep/2026", "GET", 200, 10);
        stage.process(r, out);
        assertEquals(List.of(r), out.items());
    }

    @Test
    void everyViolationIsReportedTogether() {
        StageException e = assertThrows(StageException.class,
                () -> stage.process(line("999.1.1.1", "01/Jan/1970", "BREW", 999, -5), new CollectingEmitter<>()));
        assertEquals("status 999 is outside [100, 599]; bytes -5 is outside [0, 1000000]; "
                + "timestamp 1970-01-01T10:00:00Z is before 2000-01-01T00:00Z; "
                + "clientIp '999.1.1.1' is not a valid IPv4 or IPv6 address; method 'BREW' is not one of [GET, POST]", e.getMessage());
    }

    @Test
    void futureTimestampIsRejected() {
        StageException e = assertThrows(StageException.class,
                () -> stage.process(line("10.0.0.1", "01/Jan/2099", "GET", 200, 1), new CollectingEmitter<>()));
        assertEquals("timestamp 2099-01-01T10:00:00Z is after 2030-12-31T23:59:59Z", e.getMessage());
    }

    @Test
    void ipFormats() {
        assertTrue(IpAddresses.isIpv4("192.168.0.1"));
        assertTrue(IpAddresses.isIpv4("0.0.0.0"));
        assertFalse(IpAddresses.isIpv4("256.1.1.1"));
        assertFalse(IpAddresses.isIpv4("01.1.1.1"));
        assertFalse(IpAddresses.isIpv4("1.1.1"));
        assertTrue(IpAddresses.isIpv6("2001:db8::7"));
        assertTrue(IpAddresses.isIpv6("::1"));
        assertTrue(IpAddresses.isIpv6("::ffff:10.0.0.1"));
        assertTrue(IpAddresses.isIpv6("1:2:3:4:5:6:7:8"));
        assertFalse(IpAddresses.isIpv6("1:2:3:4:5:6:7"));
        assertFalse(IpAddresses.isIpv6("1::2::3"));
        assertFalse(IpAddresses.isIpv6("12345::1"));
        assertFalse(IpAddresses.isIpv6("localhost"));
    }

    @Test
    void invalidRuleDefinitionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ValidationRule.range("path", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> ValidationRule.range("status", 5, 1));
        assertThrows(IllegalArgumentException.class, () -> ValidationRule.required("nope"));
        assertThrows(IllegalArgumentException.class, () -> new ValidationStage(List.of()));
        assertThrows(IllegalArgumentException.class, () -> ValidationRule.timestampBetween(
                OffsetDateTime.parse("2030-01-01T00:00:00Z"), OffsetDateTime.parse("2000-01-01T00:00:00Z")));
    }
}
