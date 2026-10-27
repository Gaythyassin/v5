package logflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import logflow.config.LoadedPipeline;
import logflow.config.PipelineLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Görev 5, kaos testi: {@code data/access-dirty.log} (bkz. {@code tools/generate_access_dirty.py})
 * {@code configs/chaos.yaml} ile işlenir. Çalıştırma tamamlanmalı, geçerli kayıtlar doğru çıktıyı
 * üretmeli, her hatalı kayıt dead-letter hedefinde olmalı ve sayılar birbirini tutmalı.
 */
class DirtyDataChaosTest {

    @TempDir
    Path dir;

    private LoadedPipeline chaos(String input) throws Exception {
        String yaml = Files.readString(Path.of("configs/chaos.yaml"))
                .replace("data/access-dirty.log", input)
                .replace("out/chaos-clean.txt", dir.resolve("clean.txt").toString().replace('\\', '/'))
                .replace("out/dead-letter.jsonl", dir.resolve("dead.jsonl").toString().replace('\\', '/'))
                .replace("out/chaos-summary.json", dir.resolve("summary.json").toString().replace('\\', '/'));
        return new PipelineLoader().load(yaml, "chaos.yaml");
    }

    @Test
    void dirtyFileCompletesAndEveryRecordIsAccountedFor() throws Exception {
        RunSummary s = chaos("data/access-dirty.log").run();

        assertEquals(RunSummary.Status.COMPLETED, s.status());
        assertEquals(28, s.recordsIn(), "every line, including empty ones and an unterminated last line");
        assertEquals(10, s.recordsOut());
        assertEquals(18, s.deadLettered());
        assertTrue(s.balanced(), s.format());

        assertEquals(List.of(
                "timestamp=2026-09-30T10:00:00+03:00 clientIp=10.2.0.1 method=GET path=/ status=200 bytes=512",
                "timestamp=2026-09-30T10:01:00+03:00 clientIp=10.2.0.2 method=GET path=/api/items status=200 bytes=734",
                "timestamp=2026-09-30T10:02:00+03:00 clientIp=10.2.0.3 method=POST path=/api/orders status=201 bytes=128",
                "timestamp=2026-09-30T10:03:00+03:00 clientIp=10.2.0.4 method=GET path=/missing status=404 bytes=0",
                "timestamp=2026-09-30T10:04:00+03:00 clientIp=10.2.0.5 method=GET path=/static/app.js status=200 bytes=48213",
                "timestamp=2026-09-30T10:05:00+03:00 clientIp=2001:db8::7 method=GET path=/ipv6 status=200 bytes=300",
                "timestamp=2026-09-30T10:06:00+03:00 clientIp=10.2.0.6 method=DELETE path=/api/items/7 status=500 bytes=64",
                "timestamp=2026-09-30T10:07:00+03:00 clientIp=10.2.0.7 method=PUT path=/api/cart status=200 bytes=77",
                "timestamp=2026-09-30T10:08:00+03:00 clientIp=10.2.0.8 method=GET path=/login status=302 bytes=0",
                "timestamp=2026-09-30T10:09:00+03:00 clientIp=10.2.0.9 method=HEAD path=/ status=200 bytes=0"),
                Files.readAllLines(dir.resolve("clean.txt")));
    }

    /** Bozuk dosyadaki her hata türü, beklenen aşamada ve beklenen nedenle dead-letter'a düşer. */
    @Test
    void eachKindOfCorruptionIsCaughtByTheRightStage() throws Exception {
        chaos("data/access-dirty.log").run();
        List<String> dead = Files.readAllLines(dir.resolve("dead.jsonl"));
        assertEquals(18, dead.size());

        Map<Integer, String> byLine = new TreeMap<>();
        for (String json : dead) {
            int n = Integer.parseInt(json.replaceAll(".*\"recordNumber\":\"(\\d+)\".*", "$1"));
            String stage = json.replaceAll(".*\"stage\":\"([^\"]+)\".*", "$1");
            String error = json.replaceAll(".*\"error\":\"((?:[^\"\\\\]|\\\\.)*)\".*", "$1");
            byLine.put(n, stage + ": " + error);
        }

        Map<Integer, String> expected = new TreeMap<>(Map.ofEntries(
                Map.entry(2, "parse: not a Common Log Format line"),                       // kesik satır
                Map.entry(4, "parse: empty line"),                                         // boş satır
                Map.entry(5, "parse: line is not valid UTF-8 (undecodable bytes at column 53)"),
                Map.entry(7, "validate: bytes -512 is outside [0, 104857600]"),            // negatif bayt
                Map.entry(8, "validate: timestamp 1970-01-01T10:12:00+03:00 is before 2000-01-01T00:00Z"),
                Map.entry(10, "validate: timestamp 2099-01-01T10:13:00+03:00 is after 2030-12-31T23:59:59Z"),
                Map.entry(11, "parse: line too long: 102466 characters (limit 8192)"),     // 100 KB'lık satır
                Map.entry(13, "parse: empty line"),                                        // yalnızca boşluk
                Map.entry(14, "parse: not a Common Log Format line (truncated?)"),
                Map.entry(16, "validate: clientIp '999.10.10.1' is not a valid IPv4 or IPv6 address"),
                Map.entry(17, "validate: status 999 is outside [100, 599]"),
                Map.entry(19, "parse: not a Common Log Format line"),                      // anlamsız metin
                Map.entry(20, "parse: invalid timestamp '31/Feb/2026:10:17:00 +0300'"),
                Map.entry(21, "parse: byte count out of range: 99999999999999999999"),
                Map.entry(23, "parse: line is not valid UTF-8 (undecodable bytes at column 82)"),
                Map.entry(24, "validate: method 'BREW' is not one of [GET, POST, PUT, DELETE, HEAD, PATCH, OPTIONS]"),
                Map.entry(26, "validate: bytes -1 is outside [0, 104857600]"),
                Map.entry(28, "parse: not a Common Log Format line")));                    // sonu kesik son satır
        assertEquals(expected, byLine);
    }

    @Test
    void deadLetterKeepsTheFullRawLineForReplay() throws Exception {
        chaos("data/access-dirty.log").run();
        String huge = Files.readAllLines(dir.resolve("dead.jsonl")).stream()
                .filter(l -> l.contains("\"recordNumber\":\"11\"")).findFirst().orElseThrow();
        assertTrue(huge.length() > 100 * 1024, "the 100 KB line is preserved, not truncated");
    }

    @Test
    void emptyFileCompletesWithAnAllZeroSummary() throws Exception {
        RunSummary s = chaos("data/access-empty.log").run();

        assertEquals(RunSummary.Status.COMPLETED, s.status());
        assertEquals(0, s.recordsIn());
        assertEquals(0, s.recordsOut());
        assertEquals(0, s.deadLettered());
        assertTrue(s.balanced());
        assertEquals(List.of(), Files.readAllLines(dir.resolve("clean.txt")));
        assertEquals(List.of(), Files.readAllLines(dir.resolve("dead.jsonl")));
    }

    @Test
    void theSameDirtyFileStopsAFailFastPipeline() throws Exception {
        String yaml = Files.readString(Path.of("configs/chaos.yaml"))
                .replace("onError: DEAD_LETTER      # sözdizimi", "onError: FAIL_FAST      # sözdizimi")
                .replace("out/chaos-clean.txt", dir.resolve("clean.txt").toString().replace('\\', '/'))
                .replace("out/dead-letter.jsonl", dir.resolve("dead.jsonl").toString().replace('\\', '/'));
        LoadedPipeline strict = new PipelineLoader().load(yaml, "strict.yaml");

        PipelineException e = org.junit.jupiter.api.Assertions.assertThrows(PipelineException.class, strict::run);

        assertTrue(e.getMessage().startsWith("stage 'parse' rejected record #2 and its policy is FAIL_FAST"), e.getMessage());
        assertEquals(1, e.summary().recordsOut(), "only line 1 got through before the stop");
        assertEquals(List.of("timestamp=2026-09-30T10:00:00+03:00 clientIp=10.2.0.1 method=GET path=/ status=200 bytes=512"),
                Files.readAllLines(dir.resolve("clean.txt")), "the sink was still flushed and closed");
    }
}
