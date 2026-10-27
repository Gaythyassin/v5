package logflow;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import logflow.config.PipelineLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProvidedFixtureTest {
    @TempDir Path dir;

    private RunSummary run(String input) throws Exception {
        String yaml = Files.readString(Path.of("configs/fixture-chaos.yaml"))
            .replace("data/fixture/access-dirty.log", input)
            .replace("out/fixture-clean.txt", dir.resolve("clean.txt").toString().replace('\\', '/'))
            .replace("out/fixture-dead-letter.jsonl", dir.resolve("dead.jsonl").toString().replace('\\', '/'))
            .replace("out/fixture-summary.json", dir.resolve("summary.json").toString().replace('\\', '/'));
        return new PipelineLoader().load(yaml, "fixture-chaos.yaml").run();
    }

    @Test void providedDirtyFilePreservesGoodRecordsAndReportsAllFailures() throws Exception {
        RunSummary result = run("data/fixture/access-dirty.log");
        assertEquals(RunSummary.Status.COMPLETED, result.status());
        assertEquals(11, result.recordsIn());
        assertEquals(3, result.recordsOut());
        assertEquals(8, result.deadLettered());
        assertTrue(result.balanced());
        List<String> clean = Files.readAllLines(dir.resolve("clean.txt"));
        assertEquals(3, clean.size());
        assertTrue(clean.get(0).contains("path=/index.html status=200 bytes=1234"));
        assertTrue(clean.get(1).contains("path=/api/login status=401 bytes=287"));
        assertTrue(clean.get(2).contains("path=/images/logo.png status=304 bytes=0"));
        List<String> errors = Files.readAllLines(dir.resolve("dead.jsonl"));
        assertEquals(8, errors.size());
        assertTrue(errors.stream().anyMatch(s -> s.contains("bytes -45")));
        assertTrue(errors.stream().anyMatch(s -> s.contains("1970-01-01")));
        assertTrue(errors.stream().anyMatch(s -> s.contains("2099-12-31")));
        assertTrue(errors.stream().anyMatch(s -> s.contains("not valid UTF-8")));
        assertTrue(errors.stream().anyMatch(s -> s.contains("line too long: 102400")));
    }

    @Test void providedEmptyFileProducesNoRecords() throws Exception {
        RunSummary result = run("data/fixture/access-empty.log");
        assertEquals(RunSummary.Status.COMPLETED, result.status());
        assertEquals(0, result.recordsIn());
        assertEquals(0, result.recordsOut());
        assertEquals(0, result.deadLettered());
        assertTrue(result.balanced());
    }
}
