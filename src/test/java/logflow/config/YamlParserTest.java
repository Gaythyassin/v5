package logflow.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class YamlParserTest {

    private static Object parse(String yaml) {
        return YamlParser.parse(yaml, "test.yaml");
    }

    @Test
    void parsesTheExampleFromTheAssignment() {
        Object root = parse("""
                pipeline:
                  name: error-report

                  source:
                    type: file
                    params: { path: data/access.log }

                  stages:
                    - type: parse
                      params: { format: clf }

                    - type: filter
                      params: { field: status, op: ">=", value: 400 }

                    - type: project
                      params: { fields: [timestamp, clientIp, status, path] }

                  sink:
                    type: file
                    params: { path: out/errors.txt }
                """);

        assertEquals(Map.of("pipeline", Map.of(
                "name", "error-report",
                "source", Map.of("type", "file", "params", Map.of("path", "data/access.log")),
                "stages", List.of(
                        Map.of("type", "parse", "params", Map.of("format", "clf")),
                        Map.of("type", "filter", "params", Map.of("field", "status", "op", ">=", "value", 400)),
                        Map.of("type", "project", "params", Map.of("fields", List.of("timestamp", "clientIp", "status", "path")))),
                "sink", Map.of("type", "file", "params", Map.of("path", "out/errors.txt")))), root);
    }

    @Test
    void resolvesScalarTypes() {
        Object root = parse("""
                int: 400
                negative: -3
                decimal: 1.5
                yes: true
                nothing: ~
                quotedNumber: "400"
                single: 'it''s'
                escaped: "a \\"b\\""
                time: 2026-09-29T10:10:00+03:00
                path: /api # yorum
                hash: "#not a comment"
                """);

        Map<?, ?> m = (Map<?, ?>) root;
        assertEquals(400, m.get("int"));
        assertEquals(-3, m.get("negative"));
        assertEquals(1.5, m.get("decimal"));
        assertEquals(Boolean.TRUE, m.get("yes"));
        assertNull(m.get("nothing"));
        assertTrue(m.containsKey("nothing"));
        assertEquals("400", m.get("quotedNumber"));
        assertEquals("it's", m.get("single"));
        assertEquals("a \"b\"", m.get("escaped"));
        assertEquals("2026-09-29T10:10:00+03:00", m.get("time"));
        assertEquals("/api", m.get("path"));
        assertEquals("#not a comment", m.get("hash"));
    }

    @Test
    void parsesBlockListsAndNestedFlowValues() {
        Object root = parse("""
                fields:
                - a
                - b
                nested: { list: [1, [2, 3]], map: {}, empty: [] }
                """);

        assertEquals(Map.of("fields", List.of("a", "b"),
                "nested", Map.of("list", List.of(1, List.of(2, 3)), "map", Map.of(), "empty", List.of())), root);
    }

    @Test
    void emptyDocumentIsNull() {
        assertNull(parse("# only a comment\n\n---\n"));
    }

    @Test
    void tabIndentationIsRejectedWithLineNumber() {
        ConfigException e = assertThrows(ConfigException.class, () -> parse("pipeline:\n\tname: x\n"));
        assertTrue(e.problems().get(0).startsWith("line 2: tab character"), e.getMessage());
    }

    @Test
    void duplicateKeyIsRejected() {
        ConfigException e = assertThrows(ConfigException.class, () -> parse("a: 1\nb: 2\na: 3\n"));
        assertEquals(List.of("line 3: duplicate key 'a'"), e.problems());
    }

    @Test
    void unterminatedFlowAndQuoteAreRejected() {
        assertThrows(ConfigException.class, () -> parse("a: [1, 2\n"));
        assertThrows(ConfigException.class, () -> parse("a: { x: 1\n"));
        assertThrows(ConfigException.class, () -> parse("a: \"open\n"));
    }

    @Test
    void badIndentationIsRejected() {
        ConfigException e = assertThrows(ConfigException.class, () -> parse("a: 1\n  b: 2\n"));
        assertTrue(e.problems().get(0).startsWith("line 2:"), e.getMessage());
    }

    @Test
    void unsupportedFeaturesFailLoudly() {
        assertThrows(ConfigException.class, () -> parse("a: &anchor 1\n"));
        assertThrows(ConfigException.class, () -> parse("a: |\n  text\n"));
    }
}
