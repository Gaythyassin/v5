package logflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PipelineBuilderTest {

    /** Olayları sırasıyla kaydeden aşama/sink; yaşam döngüsü sırasını doğrulamak için. */
    static final class Recorder<T> implements Stage<T, T>, Sink<T> {
        final String name;
        final List<String> log;

        Recorder(String name, List<String> log) {
            this.name = name;
            this.log = log;
        }

        @Override public void process(T input, Emitter<T> out) { log.add(name + ":" + input); out.emit(input); }
        @Override public void consume(T item) { log.add(name + ":" + item); }
        @Override public void open() { log.add(name + ".open"); }
        @Override public void close() { log.add(name + ".close"); }
    }

    @Test
    void chainsStagesInOrderAndRunsLifecycleHooks() {
        List<String> log = new ArrayList<>();
        Source<String> source = out -> List.of("a", "b").forEach(out::emit);

        PipelineBuilder.from(source)
                .via(new Recorder<String>("s1", log))
                .via(new PredicateFilterStage<>(s -> !s.equals("b")))
                .to(new Recorder<String>("sink", log))
                .run();

        assertEquals(List.of("s1.open", "sink.open", "s1:a", "sink:a", "s1:b", "s1.close", "sink.close"), log);
    }

    @Test
    void closesEverythingEvenWhenTheSourceFails() {
        List<String> log = new ArrayList<>();
        Source<String> failing = out -> {
            out.emit("x");
            throw new IllegalStateException("boom");
        };
        Pipeline p = PipelineBuilder.from(failing)
                .via(new Recorder<String>("stage", log))
                .to(new Recorder<String>("sink", log));

        // v5: işlem hattını durduran her şey bir PipelineException'dır; özgün neden korunur.
        PipelineException e = assertThrows(PipelineException.class, p::run);

        assertEquals("boom", e.getCause().getMessage());
        assertEquals(RunSummary.Status.FAILED, e.summary().status());
        assertEquals(1, e.summary().recordsIn());
        assertEquals(List.of("stage.open", "sink.open", "stage:x", "sink:x", "stage.close", "sink.close"), log);
    }

    @Test
    void builderIsImmutableSoAPrefixCanBeBranched() {
        CollectingSink<Integer> all = new CollectingSink<>();
        CollectingSink<Integer> big = new CollectingSink<>();
        PipelineBuilder<Integer> prefix = PipelineBuilder.from(out -> List.of(1, 5, 10).forEach(out::emit));

        Pipeline p1 = prefix.to(all);
        Pipeline p2 = prefix.via(new PredicateFilterStage<Integer>(n -> n >= 5)).to(big);
        p1.run();
        p2.run();

        assertEquals(0, p1.stages().size());
        assertEquals(1, p2.stages().size());
        assertEquals(List.of(1, 5, 10), all.items);
        assertEquals(List.of(5, 10), big.items);
    }

    @Test
    void stagesChangeTheElementType() {
        CollectingSink<ProjectedRecord> sink = new CollectingSink<>();
        Source<String> lines = out -> out.emit(
                "1.1.1.1 - - [29/Sep/2026:10:00:00 +0300] \"GET /api/a HTTP/1.1\" 503 9");

        // String -> LogRecord -> LogRecord -> ProjectedRecord; her adım derleyici tarafından denetlenir.
        PipelineBuilder.from(lines)
                .via(new ParserStage())
                .via(new PredicateFilterStage<>(r -> r.status() >= 400))
                .via(new ProjectionStage("status", "path"))
                .to(sink)
                .run();

        assertEquals("status=503 path=/api/a", sink.items.get(0).toString());
    }

    static final class CollectingSink<T> implements Sink<T> {
        final List<T> items = new ArrayList<>();

        @Override
        public void consume(T item) {
            items.add(item);
        }
    }
}
