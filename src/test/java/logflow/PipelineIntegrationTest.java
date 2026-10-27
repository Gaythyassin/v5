package logflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Gerçek dosyadan okuyup gerçek dosyaya yazan, uçtan uca 3 aşamalı pipeline testi. */
class PipelineIntegrationTest {

    @TempDir
    Path dir;

    static Path fixture() throws URISyntaxException {
        return Path.of(PipelineIntegrationTest.class.getResource("/fixture.log").toURI());
    }

    @Test
    void parseFilterProjectWritesExpectedFile() throws Exception {
        Path out = dir.resolve("errors.txt");
        ParserStage parser = new ParserStage();

        Pipeline p = PipelineBuilder.from(new FileLineSource(fixture()))
                .via(parser)
                .via(new PredicateFilterStage<>(r -> r.status() >= 400))
                .via(new ProjectionStage("timestamp", "clientIp", "status", "path"))
                .to(new FileSink<>(out));
        p.run();

        assertEquals(List.of(
                "timestamp=2026-09-29T10:00:20+03:00 clientIp=10.0.0.3 status=500 path=/api/orders",
                "timestamp=2026-09-29T10:00:30+03:00 clientIp=10.0.0.4 status=401 path=/login",
                "timestamp=2026-09-29T10:00:40+03:00 clientIp=10.0.0.5 status=404 path=/api/items/7",
                "timestamp=2026-09-29T10:01:00+03:00 clientIp=10.0.0.7 status=400 path=/api/cart",
                "timestamp=2026-09-29T10:01:10+03:00 clientIp=10.0.0.8 status=503 path=/apiary"),
                Files.readAllLines(out));
        assertEquals(8, parser.parsedCount());
        assertEquals(2, parser.malformedCount());
    }

    /** Görev 4: iki filtre her iki sırada aynı çıktıyı üretir; yalnızca iş miktarı değişir. */
    @Test
    void filterOrderDoesNotChangeTheResult() throws Exception {
        Path ab = dir.resolve("status-then-path.txt");
        Path ba = dir.resolve("path-then-status.txt");
        var status1 = new PredicateFilterStage<LogRecord>(Filters.minStatus(400));
        var path1 = new PredicateFilterStage<LogRecord>(Filters.pathPrefix("/api/"));
        var path2 = new PredicateFilterStage<LogRecord>(Filters.pathPrefix("/api/"));
        var status2 = new PredicateFilterStage<LogRecord>(Filters.minStatus(400));

        PipelineBuilder.from(new FileLineSource(fixture())).via(new ParserStage())
                .via(status1).via(path1).via(new ProjectionStage("clientIp", "status", "path"))
                .to(new FileSink<>(ab)).run();
        PipelineBuilder.from(new FileLineSource(fixture())).via(new ParserStage())
                .via(path2).via(status2).via(new ProjectionStage("clientIp", "status", "path"))
                .to(new FileSink<>(ba)).run();

        List<String> expected = List.of(
                "clientIp=10.0.0.3 status=500 path=/api/orders",
                "clientIp=10.0.0.5 status=404 path=/api/items/7",
                "clientIp=10.0.0.7 status=400 path=/api/cart");
        assertEquals(expected, Files.readAllLines(ab));
        assertEquals(expected, Files.readAllLines(ba));
        // Aynı sonuç, farklı iş: status önce -> 8 + 5 = 13 değerlendirme; path önce -> 8 + 4 = 12.
        assertEquals(13, status1.evaluatedCount() + path1.evaluatedCount());
        assertEquals(12, path2.evaluatedCount() + status2.evaluatedCount());
    }

    @Test
    void mainWritesProjectedOutputFile() throws IOException, URISyntaxException {
        Path out = dir.resolve("cli.txt");

        Main.main(new String[] {fixture().toString(),
                "--min-status", "400", "--path-prefix", "/api/", "--fields", "clientIp,status", "--out", out.toString()});

        assertEquals(List.of("clientIp=10.0.0.3 status=500", "clientIp=10.0.0.5 status=404",
                "clientIp=10.0.0.7 status=400"), Files.readAllLines(out));
    }
}
