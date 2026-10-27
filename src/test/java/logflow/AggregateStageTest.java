package logflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class AggregateStageTest {

    private static LogRecord record(String ip, int status, long bytes) {
        return ParserStage.parse(ip + " - - [29/Sep/2026:10:00:00 +0300] \"GET / HTTP/1.1\" " + status + " " + bytes);
    }

    @Test
    void emitsNothingUntilFinishThenGroupsRankedBySum() throws Exception {
        AggregateStage stage = new AggregateStage("clientIp", "bytes", 0);
        CollectingEmitter<ProjectedRecord> out = new CollectingEmitter<>();
        stage.open();
        stage.process(record("10.0.0.1", 200, 100), out);
        stage.process(record("10.0.0.2", 200, 500), out);
        stage.process(record("10.0.0.1", 404, 50), out);
        assertTrue(out.items().isEmpty());

        stage.finish(out);

        assertEquals(List.of("clientIp=10.0.0.2 requests=1 bytes=500", "clientIp=10.0.0.1 requests=2 bytes=150"),
                out.items().stream().map(Object::toString).toList());
    }

    @Test
    void withoutSumRanksByCountAndTopLimits() throws Exception {
        AggregateStage stage = new AggregateStage("status", null, 1);
        CollectingEmitter<ProjectedRecord> out = new CollectingEmitter<>();
        stage.process(record("a", 200, 1), out);
        stage.process(record("b", 404, 1), out);
        stage.process(record("c", 404, 1), out);

        stage.finish(out);

        assertEquals(List.of("status=404 requests=2"), out.items().stream().map(Object::toString).toList());
    }

    @Test
    void finishOutputFlowsThroughLaterStagesInAPipeline() {
        List<Object> sink = new java.util.ArrayList<>();
        Source<LogRecord> source = out -> {
            out.emit(record("10.0.0.1", 200, 10));
            out.emit(record("10.0.0.2", 200, 30));
        };
        PipelineBuilder.from(source)
                .via(new AggregateStage("clientIp", "bytes", 0))
                .via(new PredicateFilterStage<ProjectedRecord>(p -> Long.parseLong(p.get("bytes")) > 20))
                .to(sink::add)
                .run();
        assertEquals(List.of("clientIp=10.0.0.2 requests=1 bytes=30"), sink.stream().map(Object::toString).toList());
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AggregateStage("ip", null, 0));
        assertThrows(IllegalArgumentException.class, () -> new AggregateStage("clientIp", "path", 0));
        assertThrows(IllegalArgumentException.class, () -> new AggregateStage("clientIp", null, -1));
    }
}
