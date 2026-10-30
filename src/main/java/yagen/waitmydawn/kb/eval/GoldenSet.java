package yagen.waitmydawn.kb.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 黄金评测集模型与加载器（schema v2）。
 *
 * <p>向后兼容 v1：`turns` 里的字符串元素直接视为一道基础题（无标准答案与断言）。
 * v2 的 turn 为对象，可携带 type/category/expected/judgeRubric/assertions/stabilityRuns。
 *
 * <p>断言字段说明见 {@link Assertion} 与 {@link RuleJudge}。
 */
public final class GoldenSet {

    /** 单条可自动判定的断言。value/expected/min/max 按 kind 取用。 */
    public record Assertion(String kind, List<String> values, String value, Boolean expected,
                            Integer min, Integer max, double weight, boolean required) {}

    /**
     * 一轮提问。
     *
     * @param referenceAnswer 参考答案（人工核对用；裁判优先参考它。可为空=仅有要点）
     * @param stabilityRuns   >1 时评测器会额外重复跑（稳定性和 pass@N）；0=用 CLI --repeat
     */
    public record Turn(String id, String question, String referenceAnswer, List<String> referenceSources,
                       String type, String category, int stabilityRuns, List<String> answerPoints,
                       List<String> judgeRubric, List<Assertion> assertions) {
        public int effectiveRepeat(int defaultRepeat) {
            return stabilityRuns > 0 ? stabilityRuns : Math.max(1, defaultRepeat);
        }
    }

    public record Conversation(String id, String name, String modId, List<Turn> turns) {}

    public record Spec(String version, String name, List<Conversation> conversations) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GoldenSet() {}

    public static Spec load(Path file) throws IOException {
        JsonNode root = MAPPER.readTree(file.toFile());
        String version = root.path("version").asText("1");
        String name = root.path("name").asText(file.getFileName().toString());

        List<Conversation> conversations = new ArrayList<>();
        int ci = 0;
        for (JsonNode conv : root.path("conversations")) {
            ci++;
            String convId = conv.path("id").asText("D" + ci);
            String convName = conv.path("name").asText("Dialogue " + ci);
            String modId = conv.hasNonNull("modId") ? conv.get("modId").asText() : null;

            List<Turn> turns = new ArrayList<>();
            int ti = 0;
            for (JsonNode turn : conv.path("turns")) {
                ti++;
                turns.add(parseTurn(turn, convId, ti));
            }
            conversations.add(new Conversation(convId, convName, modId, turns));
        }
        return new Spec(version, name, conversations);
    }

    private static Turn parseTurn(JsonNode turn, String convId, int idx) {
        String fallbackId = convId + "-T" + String.format("%02d", idx);
        if (turn.isTextual()) {
            // v1 兼容：纯字符串题，无标准答案、无断言、只跑一次
            return new Turn(fallbackId, turn.asText(), "", List.of(), "basic", "general", 0,
                    List.of(), List.of(), List.of());
        }
        String id = turn.path("id").asText(fallbackId);
        String question = turn.path("question").asText("");
        String referenceAnswer = turn.path("referenceAnswer").asText("");
        List<String> referenceSources = readStringList(turn.path("referenceSources"));
        String type = turn.path("type").asText("basic");
        String category = turn.path("category").asText("general");
        // 0 = 未指定，使用 CLI --repeat 的全局默认
        int stability = turn.path("stabilityRuns").asInt(0);
        List<String> answerPoints = readStringList(turn.path("expected").path("answerPoints"));
        List<String> rubric = readStringList(turn.path("judgeRubric"));

        List<Assertion> assertions = new ArrayList<>();
        for (JsonNode a : turn.path("assertions")) {
            assertions.add(new Assertion(
                    a.path("kind").asText(""),
                    readStringList(a.path("values")),
                    a.hasNonNull("value") ? a.get("value").asText() : null,
                    a.hasNonNull("expected") ? a.get("expected").asBoolean() : null,
                    a.hasNonNull("min") ? a.get("min").asInt() : null,
                    a.hasNonNull("max") ? a.get("max").asInt() : null,
                    a.path("weight").asDouble(1.0),
                    a.path("required").asBoolean(false)));
        }
        // 未显式写断言但有标准要点时，自动生成一个「必须覆盖全部要点」的断言
        if (assertions.isEmpty() && !answerPoints.isEmpty()) {
            assertions.add(new Assertion("answer_contains_all", answerPoints, null, null,
                    null, null, 1.0, true));
        }
        return MultimodalAcceptance.apply(new Turn(id, question, referenceAnswer, referenceSources, type, category, stability,
                answerPoints, rubric, assertions));
    }

    private static List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual() && !item.asText().isBlank()) out.add(item.asText());
            else if (item.isNumber()) out.add(item.asText());
        }
        return out;
    }
}
