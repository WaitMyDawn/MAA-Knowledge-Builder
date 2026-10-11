package yagen.waitmydawn.kb.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 两个评测 run 的版本对比：主指标 diff + 每题回归/进步清单。
 * 用于「优化前 vs 优化后」的消融对比表（面试/CI 门禁证据）。
 */
public final class RunComparator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RunComparator() {}

    public static String compare(Path runDirA, Path runDirB) throws IOException {
        Map<String, Object> a = readJson(runDirA.resolve("summary.json"));
        Map<String, Object> b = readJson(runDirB.resolve("summary.json"));
        Map<String, TurnStat> turnsA = loadTurns(runDirA.resolve("turns.jsonl"));
        Map<String, TurnStat> turnsB = loadTurns(runDirB.resolve("turns.jsonl"));

        StringBuilder sb = new StringBuilder();
        sb.append("# 评测对比\n\n");
        sb.append("- A: `").append(a.get("runId")).append("` (").append(a.get("tag")).append(")\n");
        sb.append("- B: `").append(b.get("runId")).append("` (").append(b.get("tag")).append(")\n\n");

        if (Files.exists(runDirA.resolve("config.json")) && Files.exists(runDirB.resolve("config.json"))) {
            Map<String, Object> configA = readJson(runDirA.resolve("config.json"));
            Map<String, Object> configB = readJson(runDirB.resolve("config.json"));
            List<String> differences = new ArrayList<>();
            for (String field : List.of("embeddingProfile", "baseContentSha256", "goldenSetSha256", "cachePolicy")) {
                if (!java.util.Objects.equals(configA.get(field), configB.get(field))) differences.add(field);
            }
            if (!differences.isEmpty()) sb.append("> 比较条件不同：").append(String.join(", ", differences))
                    .append("。指标差异不能直接归因于代码变更。\n\n");
        } else {
            sb.append("> 缺少配置指纹，无法确认两个运行的输入条件一致。\n\n");
        }

        sb.append("## 主指标\n\n");
        sb.append("| 指标 | A | B | Δ (B-A) |\n|---|---|---|---|\n");
        rowRate(sb, "通过率", a.get("passRate"), b.get("passRate"));
        rowNum(sb, "平均规则得分", a.get("avgRuleScore"), b.get("avgRuleScore"));
        rowRate(sb, "检索命中率", a.get("hitRate"), b.get("hitRate"));
        rowNum(sb, "平均耗时(ms)", a.get("avgTotalMs"), b.get("avgTotalMs"));
        rowNum(sb, "P95 耗时(ms)", a.get("p95TotalMs"), b.get("p95TotalMs"));
        rowNum(sb, "端到端耗时(ms)", a.get("avgEvalTotalMs"), b.get("avgEvalTotalMs"));
        rowNum(sb, "平均 Token in", a.get("avgTokenIn"), b.get("avgTokenIn"));
        rowNum(sb, "平均 Token out", a.get("avgTokenOut"), b.get("avgTokenOut"));
        Map<String, Object> ja = mapOf(a.get("judge"));
        Map<String, Object> jb = mapOf(b.get("judge"));
        // 裁判未启用的 run（scored=0）显示 "-"，避免把"未测"误读成 0%/退化
        if (intOf(ja.get("scored")) > 0 || intOf(jb.get("scored")) > 0) {
            judgeRow(sb, "裁判通过率", ja, jb, "passRate");
            judgeRow(sb, "裁判幻觉率", ja, jb, "hallucinationRate");
        }
        Object sA = a.get("stabilityPassAtN");
        Object sB = b.get("stabilityPassAtN");
        if (sA != null && sB != null && (doubleOf(sA) > 0 || doubleOf(sB) > 0)) {
            rowRate(sb, "稳定性 pass@N", sA, sB);
        }

        sb.append("\n## 按题型\n\n");
        sb.append("| type | A 通过率 | B 通过率 | Δ |\n|---|---|---|---|\n");
        Map<String, Object> ta = mapOf(a.get("byType"));
        Map<String, Object> tb = mapOf(b.get("byType"));
        for (String key : union(ta.keySet(), tb.keySet())) {
            double pa = mapOf(ta.get(key)).isEmpty() ? -1 : doubleOf(mapOf(ta.get(key)).get("passRate"));
            double pb = mapOf(tb.get(key)).isEmpty() ? -1 : doubleOf(mapOf(tb.get(key)).get("passRate"));
            if (pa < 0 || pb < 0) {
                sb.append("| ").append(key).append(" | ").append(fmtRate(pa)).append(" | ")
                        .append(fmtRate(pb)).append(" | - |\n");
                continue;
            }
            sb.append("| ").append(key).append(" | ").append(fmtRate(pa)).append(" | ")
                    .append(fmtRate(pb)).append(" | ").append(fmtPp(pb - pa)).append(" |\n");
        }

        sb.append("\n## 各阶段耗时（平均 ms）\n\n");
        Map<String, Object> sa = mapOf(a.get("byStage"));
        Map<String, Object> sb2 = mapOf(b.get("byStage"));
        if (!sa.isEmpty() || !sb2.isEmpty()) {
            sb.append("| 阶段 | A | B | Δ (B-A) |\n|---|---|---|---|\n");
            for (String key : union(sa.keySet(), sb2.keySet())) {
                double va = doubleOf(mapOf(sa.get(key)).get("avgMs"));
                double vb = doubleOf(mapOf(sb2.get(key)).get("avgMs"));
                sb.append("| ").append(key).append(" | ").append(fmtNum(va)).append(" | ")
                        .append(fmtNum(vb)).append(" | ").append(fmtNumDelta(vb - va)).append(" |\n");
            }
        }

        List<String> regressed = new ArrayList<>();
        List<String> fixed = new ArrayList<>();
        for (String turnId : union(turnsA.keySet(), turnsB.keySet())) {
            TurnStat x = turnsA.get(turnId);
            TurnStat y = turnsB.get(turnId);
            if (x == null || y == null) continue;
            if (x.passedAll() && !y.passedAll()) regressed.add(turnId);
            if (!x.passedAll() && y.passedAll()) fixed.add(turnId);
        }
        sb.append("\n## 逐题变化\n\n");
        sb.append("- 回归（A 过 B 不过）: ").append(regressed.isEmpty() ? "无" : regressed).append("\n");
        sb.append("- 修复（A 不过 B 过）: ").append(fixed.isEmpty() ? "无" : fixed).append("\n");
        return sb.toString();
    }

    private static void rowRate(StringBuilder sb, String name, Object a, Object b) {
        double da = doubleOf(a);
        double db = doubleOf(b);
        sb.append("| ").append(name).append(" | ").append(fmtRate(da))
                .append(" | ").append(fmtRate(db))
                .append(" | ").append(fmtPp(db - da)).append(" |\n");
    }

    private static void rowNum(StringBuilder sb, String name, Object a, Object b) {
        double da = doubleOf(a);
        double db = doubleOf(b);
        sb.append("| ").append(name).append(" | ").append(fmtNum(da))
                .append(" | ").append(fmtNum(db))
                .append(" | ").append(fmtNumDelta(db - da)).append(" |\n");
    }

    /** 裁判类指标：未启用裁判的 run 显示 "-"，两者都启用时才给差值。 */
    private static void judgeRow(StringBuilder sb, String name, Map<String, Object> ja,
                                 Map<String, Object> jb, String field) {
        boolean aOk = intOf(ja.get("scored")) > 0;
        boolean bOk = intOf(jb.get("scored")) > 0;
        String av = aOk ? fmtRate(doubleOf(ja.get(field))) : "-";
        String bv = bOk ? fmtRate(doubleOf(jb.get(field))) : "-";
        String dv = (aOk && bOk)
                ? fmtPp(doubleOf(jb.get(field)) - doubleOf(ja.get(field))) : "n/a";
        sb.append("| ").append(name).append(" | ").append(av)
                .append(" | ").append(bv)
                .append(" | ").append(dv).append(" |\n");
    }

    private static Map<String, TurnStat> loadTurns(Path turnsFile) throws IOException {
        Map<String, TurnStat> out = new LinkedHashMap<>();
        if (!Files.exists(turnsFile)) return out;
        for (String line : Files.readAllLines(turnsFile)) {
            if (line.isBlank()) continue;
            JsonNode node = MAPPER.readTree(line);
            String turnId = node.path("turnId").asText("");
            boolean passed = node.path("rule").path("passed").asBoolean(false);
            TurnStat stat = out.computeIfAbsent(turnId, k -> new TurnStat());
            stat.runs++;
            if (passed) stat.passes++;
        }
        return out;
    }

    private static Map<String, Object> readJson(Path file) throws IOException {
        return MAPPER.readValue(file.toFile(), Map.class);
    }

    private static List<String> union(Iterable<String> a, Iterable<String> b) {
        Map<String, Boolean> set = new LinkedHashMap<>();
        a.forEach(k -> set.put(k, true));
        b.forEach(k -> set.put(k, true));
        return new ArrayList<>(set.keySet());
    }

    private static String fmtRate(double v) {
        return v < 0 ? "-" : String.format("%.1f%%", v * 100);
    }

    private static String fmtNum(double v) {
        return String.format("%.0f", v);
    }

    /** 比率类指标的差值：百分点（pp）。 */
    private static String fmtPp(double delta) {
        String sign = delta > 0 ? "+" : "";
        return sign + String.format("%.1f", delta * 100) + "pp";
    }

    /** 数值类指标的差值：原单位。 */
    private static String fmtNumDelta(double delta) {
        String sign = delta > 0 ? "+" : "";
        return sign + String.format("%.0f", delta);
    }

    private static int intOf(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static Map<String, Object> mapOf(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return Map.of();
    }

    private static double doubleOf(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0;
    }

    private static final class TurnStat {
        int runs;
        int passes;

        boolean passedAll() {
            return runs > 0 && runs == passes;
        }
    }
}
