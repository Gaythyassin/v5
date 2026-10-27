package logflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Hata politikalarının işlem hattındaki davranışı; aşamalar politikadan habersizdir. */
class ErrorPolicyTest {

    /** "bad" içeren kaydı reddeden, diğerlerini büyük harfe çeviren aşama. */
    static final class Upper implements Stage<String, String> {
        @Override
        public void process(String input, Emitter<String> out) throws StageException {
            if (input.contains("bad")) {
                throw new StageException("contains 'bad': " + input);
            }
            out.emit(input.toUpperCase());
        }
    }

    /** İlk {@code failures} denemede başarısız olan, sonra başaran aşama (geçici G/Ç hatası benzeri). */
    static final class Flaky implements Stage<String, String> {
        int failures;
        int calls;

        Flaky(int failures) {
            this.failures = failures;
        }

        @Override
        public void process(String input, Emitter<String> out) throws StageException {
            calls++;
            if (failures-- > 0) {
                throw new StageException("temporarily unavailable");
            }
            out.emit(input);
        }
    }

    private static Source<String> source(String... items) {
        return out -> List.of(items).forEach(out::emit);
    }

    @Test
    void parsesPolicySyntax() {
        assertEquals(ErrorPolicy.FAIL_FAST, ErrorPolicy.parse("FAIL_FAST"));
        assertEquals(ErrorPolicy.SKIP, ErrorPolicy.parse(" SKIP "));
        assertEquals(ErrorPolicy.DEAD_LETTER, ErrorPolicy.parse("DEAD_LETTER"));
        assertEquals(ErrorPolicy.retry(3), ErrorPolicy.parse("RETRY(3)"));
        assertEquals("RETRY(3)", ErrorPolicy.retry(3).toString());
        assertThrows(IllegalArgumentException.class, () -> ErrorPolicy.parse("RETRY(0)"));
        assertThrows(IllegalArgumentException.class, () -> ErrorPolicy.parse("RETRY(11)"));
        assertThrows(IllegalArgumentException.class, () -> ErrorPolicy.parse("RETRY"));
        assertThrows(IllegalArgumentException.class, () -> ErrorPolicy.parse("skip"));
    }

    @Test
    void skipDropsAndCountsTheRecord() {
        List<String> sink = new ArrayList<>();
        RunSummary s = PipelineBuilder.from(source("a", "bad", "c")).via(new Upper(), ErrorPolicy.SKIP).to(sink::add).run();

        assertEquals(List.of("A", "C"), sink);
        assertEquals(1, s.stages().get(0).skipped());
        assertEquals(1, s.stages().get(0).failed());
        assertTrue(s.balanced());
    }

    @Test
    void failFastStopsTheRunWithAPipelineExceptionAndAPartialSummary() {
        List<String> sink = new ArrayList<>();
        Pipeline p = PipelineBuilder.from(source("a", "bad", "c")).via(new Upper(), ErrorPolicy.FAIL_FAST).to(sink::add);

        PipelineException e = assertThrows(PipelineException.class, p::run);

        assertEquals(List.of("A"), sink, "nothing after the failing record is processed");
        assertTrue(e.getMessage().contains("rejected record #2 and its policy is FAIL_FAST: contains 'bad'"), e.getMessage());
        assertTrue(e.getCause() instanceof StageException);
        assertEquals(RunSummary.Status.FAILED, e.summary().status());
        assertEquals(2, e.summary().recordsIn());
    }

    @Test
    void deadLetterRoutesTheFailedRecordWithContextToTheSecondSink() {
        List<String> sink = new ArrayList<>();
        List<FailedRecord> dead = new ArrayList<>();
        RunSummary s = PipelineBuilder.from(source("a", "bad", "c"))
                .via(new Upper(), ErrorPolicy.DEAD_LETTER)
                .deadLetter(dead::add)
                .to(sink::add)
                .run();

        assertEquals(List.of("A", "C"), sink);
        assertEquals(1, dead.size());
        FailedRecord f = dead.get(0);
        assertEquals("bad", f.rawInput());
        assertEquals("Upper", f.stageName());
        assertEquals("contains 'bad': bad", f.reason());
        assertEquals(2, f.recordNumber());
        assertEquals(1, s.deadLettered());
        assertTrue(s.balanced());
    }

    /** rawInput, kaydı reddeden aşamanın girdisi değil, işlem hattına giren özgün kayıttır. */
    @Test
    void deadLetterKeepsTheOriginalRawInputEvenForALaterStage() {
        List<FailedRecord> dead = new ArrayList<>();
        PipelineBuilder.from(source("x-bad"))
                .via((String in, Emitter<String> out) -> out.emit(in.substring(2)))
                .via(new Upper(), ErrorPolicy.DEAD_LETTER)
                .deadLetter(dead::add)
                .to(item -> { })
                .run();

        assertEquals("x-bad", dead.get(0).rawInput());
        assertEquals("contains 'bad': bad", dead.get(0).reason());
    }

    @Test
    void retrySucceedsAfterTransientFailures() {
        Flaky flaky = new Flaky(2);
        List<String> sink = new ArrayList<>();
        List<FailedRecord> dead = new ArrayList<>();
        RunSummary s = PipelineBuilder.from(source("a")).via(flaky, ErrorPolicy.retry(3)).deadLetter(dead::add).to(sink::add).run();

        assertEquals(List.of("a"), sink);
        assertTrue(dead.isEmpty());
        assertEquals(3, flaky.calls);
        assertEquals(2, s.stages().get(0).retries());
        assertEquals(0, s.stages().get(0).failed());
    }

    @Test
    void retryGivesUpAfterNAttemptsAndDeadLetters() {
        Flaky flaky = new Flaky(100);
        List<FailedRecord> dead = new ArrayList<>();
        RunSummary s = PipelineBuilder.from(source("a")).via(flaky, ErrorPolicy.retry(2)).deadLetter(dead::add).to(x -> { }).run();

        assertEquals(3, flaky.calls, "1 attempt + 2 retries");
        assertEquals(1, dead.size());
        assertEquals(2, s.stages().get(0).retries());
        assertEquals(1, s.deadLettered());
    }

    @Test
    void anUnexpectedRuntimeExceptionInAStageIsARecordFailureToo() {
        List<FailedRecord> dead = new ArrayList<>();
        RunSummary s = PipelineBuilder.from(source("1", "x", "3"))
                .via((String in, Emitter<Integer> out) -> out.emit(Integer.parseInt(in)), ErrorPolicy.DEAD_LETTER)
                .deadLetter(dead::add)
                .to(x -> { })
                .run();

        assertEquals(1, dead.size());
        assertTrue(dead.get(0).reason().startsWith("NumberFormatException"), dead.get(0).reason());
        assertEquals(RunSummary.Status.COMPLETED, s.status());
    }

    @Test
    void aFailingSinkStopsThePipeline() {
        Pipeline p = PipelineBuilder.from(source("a", "b")).via(new Upper(), ErrorPolicy.SKIP).to(item -> {
            throw new IllegalStateException("disk full");
        });

        PipelineException e = assertThrows(PipelineException.class, p::run);
        assertTrue(e.getMessage().contains("Sink failed on record #1: disk full"), e.getMessage());
        assertEquals(0, e.summary().stages().get(0).skipped(), "a sink failure is not the stage's fault");
    }

    @Test
    void aFailingDeadLetterSinkStopsThePipeline() {
        Pipeline p = PipelineBuilder.from(source("bad")).via(new Upper(), ErrorPolicy.DEAD_LETTER)
                .deadLetter(f -> {
                    throw new IllegalStateException("dlq down");
                }).to(x -> { });

        PipelineException e = assertThrows(PipelineException.class, p::run);
        assertTrue(e.getMessage().startsWith("dead-letter sink failed"), e.getMessage());
    }

    @Test
    void deadLetterPolicyWithoutADeadLetterSinkIsRejectedAtAssembly() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PipelineBuilder.from(source("a")).via(new Upper(), ErrorPolicy.DEAD_LETTER).to(x -> { }));
        assertTrue(e.getMessage().contains("has no dead-letter sink"));
    }

    @Test
    void summaryCountsDroppedRecordsAndBalances() {
        RunSummary s = PipelineBuilder.from(source("a", "b", "bad"))
                .via(new PredicateFilterStage<String>(x -> !x.equals("b")))
                .via(new Upper(), ErrorPolicy.SKIP)
                .to(x -> { })
                .run();

        assertEquals(3, s.recordsIn());
        assertEquals(1, s.recordsOut());
        assertEquals(1, s.dropped());
        assertEquals(1, s.skipped());
        assertTrue(s.balanceApplies());
        assertTrue(s.balanced());
        assertTrue(s.format().contains("balance: in 3 = sink 1 + dropped 1 + skipped 1 + dead letter 0  OK"), s.format());
        assertNull(s.failure());
        assertTrue(s.toJson().contains("\"balanced\":true"));
    }

    @Test
    void balanceIsNotApplicableWhenAStageMergesRecords() {
        Source<LogRecord> records = out -> {
            out.emit(ParserStage.parse("1.1.1.1 - - [30/Sep/2026:10:00:00 +0300] \"GET / HTTP/1.1\" 200 1"));
            out.emit(ParserStage.parse("1.1.1.1 - - [30/Sep/2026:10:00:01 +0300] \"GET / HTTP/1.1\" 200 1"));
        };
        RunSummary s = PipelineBuilder.from(records).via(new AggregateStage("clientIp", "bytes", 0)).to(x -> { }).run();

        assertFalse(s.balanceApplies());
        assertTrue(s.format().contains("balance: n/a"));
        assertTrue(s.toJson().contains("\"balanced\":null"));
    }
}
