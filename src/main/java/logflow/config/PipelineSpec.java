package logflow.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Yapılandırma dosyasının yapısal okuması: hangi bileşenler, hangi sırayla, hangi parametrelerle.
 * Henüz hiçbir sınıf seçilmez; bu, mimari tanımı gerçekleştirimden ayıran katmandır.
 *
 * <pre>
 * pipeline:
 *   name: error-report
 *   source: { type: file, params: { path: data/access-small.log } }
 *   stages:
 *     - type: parse
 *     - type: filter
 *       params: { field: status, op: "&gt;=", value: 400 }
 *   sink: { type: file, params: { path: out/errors.txt } }
 * </pre>
 *
 * v5: {@code onError} (işlem hattı varsayılanı ve aşama başına), {@code deadLetter} (ikinci hedef)
 * ve {@code summary} (çalıştırma özetinin JSON dosyası).
 */
public record PipelineSpec(String name, String description, String onError, ComponentSpec source, List<ComponentSpec> stages,
                           ComponentSpec sink, ComponentSpec deadLetter, String summary) {

    static final List<String> ROOT_KEYS = List.of("pipeline");
    static final List<String> PIPELINE_KEYS =
            List.of("name", "description", "onError", "source", "stages", "sink", "deadLetter", "summary");
    static final List<String> COMPONENT_KEYS = List.of("type", "params");
    static final List<String> STAGE_KEYS = List.of("type", "name", "onError", "params");

    public PipelineSpec {
        stages = List.copyOf(stages);
    }

    /**
     * Ayrıştırılmış YAML'dan tanımı çıkarır; yapısal sorunları {@code problems} listesine ekler.
     *
     * @return yapı geçerliyse tanım, değilse {@code null}
     */
    static PipelineSpec from(Object root, List<String> problems) {
        if (root == null) {
            problems.add("the configuration is empty; expected a top-level 'pipeline:' section");
            return null;
        }
        if (!(root instanceof Map<?, ?> rootMap)) {
            problems.add("the top level must be a mapping with a 'pipeline:' section, got " + ParamType.describe(root));
            return null;
        }
        unknownKeys("top level", rootMap, ROOT_KEYS, problems);
        Object p = rootMap.get("pipeline");
        if (p == null) {
            if (rootMap.containsKey("pipeline")) {
                problems.add("pipeline: the pipeline is empty; it needs 'name', 'source', 'stages' and 'sink'");
            } else {
                problems.add("missing top-level 'pipeline:' section");
            }
            return null;
        }
        if (!(p instanceof Map<?, ?> pipeline)) {
            problems.add("pipeline: must be a mapping, got " + ParamType.describe(p));
            return null;
        }
        int before = problems.size();
        unknownKeys("pipeline", pipeline, PIPELINE_KEYS, problems);

        String name = string(pipeline, "name", "pipeline.name", true, problems);
        String description = string(pipeline, "description", "pipeline.description", false, problems);
        String onError = string(pipeline, "onError", "pipeline.onError", false, problems);
        String summary = string(pipeline, "summary", "pipeline.summary", false, problems);
        ComponentSpec source = component(pipeline.get("source"), "pipeline.source", COMPONENT_KEYS, problems);
        ComponentSpec sink = component(pipeline.get("sink"), "pipeline.sink", COMPONENT_KEYS, problems);
        ComponentSpec deadLetter = pipeline.get("deadLetter") == null && !pipeline.containsKey("deadLetter")
                ? null : component(pipeline.get("deadLetter"), "pipeline.deadLetter", COMPONENT_KEYS, problems);

        List<ComponentSpec> stages = new ArrayList<>();
        Object s = pipeline.get("stages");
        if (s == null) {
            problems.add("pipeline.stages: missing; the pipeline needs at least one stage, e.g. '- type: parse'");
        } else if (!(s instanceof List<?> list)) {
            problems.add("pipeline.stages: must be a list ('- type: ...' items), got " + ParamType.describe(s));
        } else if (list.isEmpty()) {
            problems.add("pipeline.stages: the pipeline is empty; add at least one stage, e.g. '- type: parse'");
        } else {
            for (int i = 0; i < list.size(); i++) {
                stages.add(component(list.get(i), "pipeline.stages[" + i + "]", STAGE_KEYS, problems));
            }
        }
        return problems.size() == before
                ? new PipelineSpec(name, description, onError, source, stages, sink, deadLetter, summary) : null;
    }

    private static ComponentSpec component(Object value, String location, List<String> keys, List<String> problems) {
        if (value == null) {
            problems.add(location + ": missing; expected '{ type: ..., params: { ... } }'");
            return null;
        }
        if (!(value instanceof Map<?, ?> map)) {
            problems.add(location + ": must be a mapping with 'type' and optional 'params', got " + ParamType.describe(value));
            return null;
        }
        boolean stage = keys == STAGE_KEYS;
        if (!stage && map.containsKey("onError")) {
            problems.add(location + ": 'onError' is only allowed on stages; a failing source or sink cannot be skipped,"
                    + " it stops the pipeline (PipelineException)");
        }
        unknownKeys(location, map, keys, problems, "onError"); // kaynak/hedefte onError yukarıda açıklandı
        String type = string(map, "type", location + ".type", true, problems);
        String name = stage ? string(map, "name", location + ".name", false, problems) : null;
        String onError = stage ? string(map, "onError", location + ".onError", false, problems) : null;
        Object params = map.get("params");
        if (params != null && !(params instanceof Map<?, ?>)) {
            problems.add(location + ".params: must be a mapping like '{ name: value }', got " + ParamType.describe(params));
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) params;
        return type == null ? null : new ComponentSpec(location, type, typed, name, onError);
    }

    private static String string(Map<?, ?> map, String key, String location, boolean required, List<String> problems) {
        Object v = map.get(key);
        if (v == null) {
            if (required) {
                problems.add(location + ": missing (string)");
            }
            return null;
        }
        if (!(v instanceof String s) || s.isBlank()) {
            problems.add(location + ": must be a non-empty string, got " + ParamType.describe(v));
            return null;
        }
        return s;
    }

    private static void unknownKeys(String location, Map<?, ?> map, List<String> known, List<String> problems,
                                    String... alreadyReported) {
        for (Object k : map.keySet()) {
            if (!known.contains(String.valueOf(k)) && !List.of(alreadyReported).contains(String.valueOf(k))) {
                problems.add(location + ": unknown key '" + k + "'." + Suggestions.didYouMean(String.valueOf(k), known)
                        + " Allowed keys: " + known);
            }
        }
    }
}
