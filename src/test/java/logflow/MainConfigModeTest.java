package logflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proje kökündeki gerçek yapılandırmalarla uçtan uca: aynı program, üç yapılandırma, üç farklı çıktı.
 * Maven testleri proje kökünde çalıştırır; göreli yollar (configs/, data/, out/) oradan çözülür.
 */
class MainConfigModeTest {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        return Main.runConfigMode(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String errText() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void errorReportConfigProducesTheErrorReport() throws Exception {
        assertEquals(Main.OK, run("--config", "configs/error-report.yaml"), errText());

        List<String> lines = Files.readAllLines(Path.of("out/errors.txt"));
        assertEquals(80, lines.size());
        assertEquals("timestamp=2026-09-29T10:01:18+03:00 clientIp=192.168.0.7 status=400 path=/cart", lines.get(0));
        assertTrue(lines.stream().allMatch(l -> l.matches(".* status=[45]\\d\\d .*")));
        assertTrue(errText().contains("Run summary: pipeline 'error-report' COMPLETED"), errText());
    }

    @Test
    void sameProgramThreeConfigsThreeDifferentOutputs() throws Exception {
        assertEquals(Main.OK, run("--config", "configs/error-report.yaml"), errText());
        assertEquals(Main.OK, run("--config", "configs/api-traffic.yaml"), errText());
        assertEquals(Main.OK, run("--config", "configs/bandwidth-by-client.yaml"), errText());

        List<String> errors = Files.readAllLines(Path.of("out/errors.txt"));
        List<String> api = Files.readAllLines(Path.of("out/api-traffic.csv"));
        List<String> bandwidth = Files.readAllLines(Path.of("out/bandwidth-by-client.csv"));

        assertEquals("timestamp,method,path,status,bytes", api.get(0));
        assertTrue(api.stream().skip(1).allMatch(l -> l.split(",")[2].startsWith("/api")));
        assertEquals("clientIp,requests,bytes", bandwidth.get(0));
        assertEquals(11, bandwidth.size()); // başlık + top 10
        assertNotEquals(errors.get(0), api.get(1));
        assertNotEquals(api.get(1), bandwidth.get(1));
    }

    @Test
    void invalidConfigExitsWithCode2AndExplainsWhy() {
        assertEquals(Main.CONFIG_ERROR, run("--config", "configs/invalid/unknown-stage.yaml"));
        assertTrue(errText().startsWith("Configuration error: Invalid pipeline configuration"), errText());
        assertTrue(errText().contains("Did you mean 'filter'?"));
    }

    @Test
    void everyShippedInvalidConfigIsRejected() throws Exception {
        try (var files = Files.list(Path.of("configs/invalid"))) {
            for (Path f : files.toList()) {
                assertEquals(Main.CONFIG_ERROR, run("--config", f.toString()), f.toString());
            }
        }
    }

    @Test
    void badArgumentsAndListStages() {
        assertEquals(Main.CONFIG_ERROR, run("--config"));
        assertEquals(Main.OK, run("--list-stages"));
        String listing = out.toString(StandardCharsets.UTF_8);
        assertTrue(listing.contains("filter - keeps records"), listing);
        assertTrue(listing.contains("op (string, required) one of [==, !=, >, >=, <, <=, startsWith, endsWith, contains]"), listing);
    }
}
