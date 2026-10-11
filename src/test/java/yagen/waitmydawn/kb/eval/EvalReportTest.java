package yagen.waitmydawn.kb.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalReportTest {
    @Test void humanReviewKeepsFullAnswersAndPendingDecisions() {
        var row=record("PARTIAL", "basic", "entity", "alexsmobs",true,"partial",false);
        row.put("answer","完整回答尾部".repeat(300)); row.put("retrievalEvidence","真实正文证据"); row.put("repeat",2);
        String report=EvalReport.humanReview(List.of(row,record("PASS","basic","entity","minecraft",true,"pass",false)));
        assertTrue(report.contains("PARTIAL / r2")); assertTrue(report.contains("真实正文证据"));
        assertTrue(report.contains("完整回答尾部".repeat(300))); assertTrue(report.contains("人工判定：待填写"));
        assertTrue(!report.contains("## PASS"));
    }

    @TempDir
    Path tempDir;

    @Test
    void summarizesPassRateGroupsAndJudgeStats() {
        List<Map<String, Object>> records = List.of(
                record("T1", "basic", "recipe", "minecraft", true, "pass", false),
                record("T2", "adversarial", "refusal", "unknown", false, "pass", false),
                record("T3", "multi_step", "recipe", "create", true, "fail", true));

        GoldenSet.Spec spec = new GoldenSet.Spec("2", "unit", List.of());
        EvalRunner.Config cfg = new EvalRunner.Config(tempDir, tempDir.resolve("golden-set.json"),
                Set.of(), "unit", 1, true);
        Map<String, Object> summary = EvalReport.summarize("run-test", spec, records, cfg, tempDir);

        assertEquals(3, summary.get("totalTurns"));
        assertEquals(2, summary.get("passedTurns"));
        assertEquals(0.6667, (double) summary.get("passRate"), 0.001);
        // T2 是唯一规则判分失败的题（T3 规则通过但裁判 fail）
        assertEquals(List.of("T2"), summary.get("failureTurnIds"));
        // T2：规则不过但裁判 pass；T3：规则通过但裁判 fail —— 两者都算不一致
        assertEquals(List.of("T2", "T3"), summary.get("disagreementTurnIds"));

        Map<String, Object> byType = mapOf(summary.get("byType"));
        assertEquals(1, mapOf(byType.get("basic")).get("passed"));
        assertEquals(1, mapOf(byType.get("adversarial")).get("total"));

        Map<String, Object> judge = mapOf(summary.get("judge"));
        assertEquals(3, judge.get("scored"));
        assertEquals(2, judge.get("passCount"));
        assertEquals(2, judge.get("disagreementCount"));
        assertEquals(0.3333, (double) judge.get("hallucinationRate"), 0.001);

        Map<String, Object> byStage = mapOf(summary.get("byStage"));
        assertEquals(1500L, mapOf(byStage.get("total")).get("avgMs"));
        assertEquals(500L, mapOf(byStage.get("answer")).get("avgMs"));
        assertEquals(0.3333, (double) mapOf(byStage.get("answer")).get("share"), 0.001);

        String md = EvalReport.markdown(summary, records);
        assertTrue(md.contains("评测报告 run-test"));
        assertTrue(md.contains("T2"));

        String bad = EvalReport.badCases(records);
        assertTrue(bad.contains("T2"));

        String dis = EvalReport.disagreements(records);
        assertTrue(dis.contains("T2"));
    }

    @Test
    void stabilityAggregatesRepeats() {
        List<Map<String, Object>> records = List.of(
                record("S1", "adversarial", "refusal", "unknown", true, "pass", false),
                record("S1", "adversarial", "refusal", "unknown", true, "pass", false),
                record("S1", "adversarial", "refusal", "unknown", false, "fail", false));

        GoldenSet.Spec spec = new GoldenSet.Spec("2", "unit", List.of());
        EvalRunner.Config cfg = new EvalRunner.Config(tempDir, tempDir.resolve("g.json"),
                Set.of(), "unit", 3, false);
        Map<String, Object> summary = EvalReport.summarize("run-s", spec, records, cfg, tempDir);

        Map<String, Object> stability = mapOf(summary.get("stability"));
        Map<String, Object> s1 = mapOf(stability.get("S1"));
        assertEquals(3, s1.get("runs"));
        assertEquals(2, s1.get("passes"));
        assertEquals(false, s1.get("passAtN"));
        assertEquals(0.0, (double) summary.get("stabilityPassAtN"), 0.001);
    }

    private static Map<String, Object> record(String turnId, String type, String category,
                                              String modId, boolean passed, String judgeVerdict,
                                              boolean hallucination) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("turnId", turnId);
        r.put("question", "Q-" + turnId);
        r.put("answer", "A-" + turnId);
        r.put("type", type);
        r.put("category", category);
        r.put("modId", modId);
        r.put("dialogueId", "D1");
        r.put("dialogueName", "conv");
        r.put("elapsedMs", 1000L);

        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("passed", passed);
        rule.put("score", passed ? 1.0 : 0.4);
        rule.put("outcomes", List.of());
        r.put("rule", rule);

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("tokenIn", 100);
        metrics.put("tokenOut", 50);
        metrics.put("answerLength", 200);
        metrics.put("retrievalHit", true);
        metrics.put("dataTier", "TIER B");
        metrics.put("vectorMaxScore", 0.8);
        metrics.put("recipeResultCount", 2);
        metrics.put("classifyTimeMs", 100L);
        metrics.put("entityResolveTimeMs", 200L);
        metrics.put("urlMatchTimeMs", 300L);
        metrics.put("vectorSearchTimeMs", 1L);
        metrics.put("recipeSearchTimeMs", 40L);
        metrics.put("rerankTimeMs", 0L);
        metrics.put("answerGenTimeMs", 500L);
        metrics.put("ttftMs", 300L);
        metrics.put("totalTimeMs", 1500L);
        r.put("metrics", metrics);

        Map<String, Object> judge = new LinkedHashMap<>();
        judge.put("verdict", judgeVerdict);
        judge.put("faithfulness", 4);
        judge.put("completeness", 4);
        judge.put("correctness", 4);
        judge.put("hallucination", hallucination ? 1 : 0);
        judge.put("reasons", "r");
        r.put("judge", judge);
        return r;
    }

    private static Map<String, Object> mapOf(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return Map.of();
    }
}
