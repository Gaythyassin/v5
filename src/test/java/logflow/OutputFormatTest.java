package logflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class OutputFormatTest {

    @Test
    void csvWritesHeaderOnceAndQuotesWhenNeeded() {
        Function<Object, String> csv = OutputFormat.CSV.formatter();
        Map<String, String> first = new LinkedHashMap<>();
        first.put("path", "/a,b");
        first.put("agent", "say \"hi\"");

        assertEquals("path,agent\n\"/a,b\",\"say \"\"hi\"\"\"", csv.apply(new ProjectedRecord(first)));
        Map<String, String> second = new LinkedHashMap<>();
        second.put("path", "/c");
        second.put("agent", "x");
        assertEquals("/c,x", csv.apply(new ProjectedRecord(second)));
    }

    @Test
    void textUsesTheRecordsOwnFormat() {
        LogRecord r = ParserStage.parse("10.0.0.1 - - [29/Sep/2026:10:00:00 +0300] \"GET / HTTP/1.1\" 200 100");
        assertEquals(ConsoleSink.format(r), OutputFormat.TEXT.formatter().apply(r));
        assertEquals("a=1", OutputFormat.TEXT.formatter().apply(new ProjectedRecord(Map.of("a", "1"))));
    }
}
