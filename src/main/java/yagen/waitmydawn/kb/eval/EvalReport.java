package yagen.waitmydawn.kb.eval;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测汇总与报告渲染：summary.json 数据结构、report.md / bad-cases.md / disagreements.md。
 * 纯计算 + 字符串渲染，便于单测覆盖。
 */
public final class EvalReport {

    private EvalReport() {}

    // ==================== summarize ====================

    public static Map<String, Object> summarize(String runId, GoldenSet.Spec spec,
                                                List<Map<String, Object>> records,
                                                EvalRunner.Config cfg, Path runDir) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("runId", runId);
        s.put("goldenSet", cfg.goldenSetFile().toString());
        s.put("goldenSetVersion", spec.version());
        s.put("tag", cfg.tag());
        s.put("judgeEnabled", cfg.judgeEnabled());
        s.put("dialogueFilter", cfg.dialogueIndexes());
        s.put("defaultRepeat", cfg.defaultRepeat());
        s.put("runDir", runDir.toString());
        s.put("finishedAt", LocalDateTime.now().toString());

        int total = records.size();
        int passed = (int) records.stream().filter(EvalReport::rulePassed).count();
        double passRate = total > 0 ? (double) passed / total : 0;
        double avgRuleScore = records.stream().mapToDouble(EvalReport::ruleScore).average().orElse(0);
        s.put("totalTurns", total);
        s.put("passedTurns", passed);
        s.put("passRate", round(passRate, 4));
        s.put("avgRuleScore", round(avgRuleScore, 4));
        var generation=new LinkedHashMap<String,Integer>();
        records.forEach(r -> generation.merge(r.get("answerGeneration")==null?"NOT_RECORDED":r.get("answerGeneration").toString(),1,Integer::sum));
        s.put("answerGeneration",generation);
        var media=new LinkedHashMap<String,Integer>();
        media.put("observedTurns",(int)records.stream().filter(r -> mapOf(r.get("observation")).get("media") instanceof Map).count());
        for(String key:List.of("candidates","displayed","valid","validIllustrations","unavailable","citedSources","invalidReferences"))
            media.put(key,records.stream().map(r -> mapOf(mapOf(r.get("observation")).get("media"))).mapToInt(m -> intOf(m.get(key))).sum());
        s.put("media",media);

        s.put("byType", groupBy(records, "type"));
        s.put("byCategory", groupBy(records, "category"));
        s.put("byMod", groupBy(records, "modId"));
        s.put("byDialogue", byDialogue(records));

        Map<String, Map<String, Object>> stability = stability(records);
        s.put("stability", stability);
        s.put("stabilityPassAtN", passAtN(stability));

        List<Long> durations = new ArrayList<>();
        List<Long> evalDurations = new ArrayList<>();
        List<Long> renderDurations = new ArrayList<>();
        List<Long> judgeDurations = new ArrayList<>();
        List<Integer> tokIn = new ArrayList<>();
        List<Integer> tokOut = new ArrayList<>();
        List<Integer> ansLen = new ArrayList<>();
        int hits = 0;
        Map<String, Integer> tiers = new LinkedHashMap<>();
        double vecMaxSum = 0;
        double recipeSum = 0;
        for (Map<String, Object> r : records) {
            Map<String, Object> m = mapOf(r.get("metrics"));
            durations.add(longOf(r.get("elapsedMs")));
            evalDurations.add(longOf(r.get("evalTotalMs")));
            renderDurations.add(longOf(r.get("renderMs")));
            judgeDurations.add(longOf(r.get("judgeMs")));
            tokIn.add(intOf(m.get("tokenIn")));
            tokOut.add(intOf(m.get("tokenOut")));
            ansLen.add(intOf(m.get("answerLength")));
            if (boolOf(m.get("retrievalHit"))) hits++;
            String tier = stringOf(m.get("dataTier"));
            tiers.merge(tier == null ? "UNKNOWN" : tier, 1, Integer::sum);
            vecMaxSum += doubleOf(m.get("vectorMaxScore"));
            recipeSum += intOf(m.get("recipeResultCount"));
        }
        s.put("hitRate", total > 0 ? round((double) hits / total, 4) : 0);
        s.put("tierDistribution", tiers);
        s.put("avgVectorMaxScore", total > 0 ? round(vecMaxSum / total, 4) : 0);
        s.put("avgRecipeCount", total > 0 ? round(recipeSum / total, 2) : 0);
        s.put("avgTotalMs", total > 0 ? Math.round(durations.stream().mapToLong(Long::longValue).average().orElse(0)) : 0);
        s.put("p95TotalMs", percentile(durations, 0.95));
        s.put("avgEvalTotalMs", evalDurations.isEmpty() ? 0
                : Math.round(evalDurations.stream().mapToLong(Long::longValue).average().orElse(0)));
        s.put("p95EvalTotalMs", percentile(evalDurations, 0.95));
        s.put("avgRenderMs", renderDurations.isEmpty() ? 0
                : Math.round(renderDurations.stream().mapToLong(Long::longValue).average().orElse(0)));
        s.put("avgJudgeMs", judgeDurations.isEmpty() ? 0
                : Math.round(judgeDurations.stream().mapToLong(Long::longValue).average().orElse(0)));
        s.put("avgTokenIn", total > 0 ? Math.round(tokIn.stream().mapToInt(Integer::intValue).average().orElse(0)) : 0);
        s.put("avgTokenOut", total > 0 ? Math.round(tokOut.stream().mapToInt(Integer::intValue).average().orElse(0)) : 0);
        s.put("avgAnswerLength", total > 0 ? Math.round(ansLen.stream().mapToInt(Integer::intValue).average().orElse(0)) : 0);
        s.put("totalTokenIn", tokIn.stream().mapToInt(Integer::intValue).sum());
        s.put("totalTokenOut", tokOut.stream().mapToInt(Integer::intValue).sum());
        s.put("byStage", byStage(records));
        long observed=records.stream().filter(r->mapOf(r.get("metrics")).containsKey("httpRequestCount")).count();
        s.put("ioObservedTurns",observed);
        var io=new LinkedHashMap<String,Object>();
        for(String key:List.of("httpRequestCount","httpErrorCount","httpResponseBytes","memoryPageCacheHits","persistentPageCacheHits","documentEmbeddingCount","queryEmbeddingCount","reusedEmbeddingCount","embeddingTimeMs"))
            io.put(key,observed==0?null:records.stream().map(r->mapOf(r.get("metrics"))).mapToLong(m->longOf(m.get(key))).sum());
        s.put("retrievalIo",io);

        s.put("judge", judgeSummary(records));
        s.put("failureTurnIds", records.stream().filter(r -> !rulePassed(r))
                .map(r -> stringOf(r.get("turnId"))).toList());
        s.put("disagreementTurnIds", disagreementsOf(records).stream()
                .map(m -> stringOf(m.get("turnId"))).toList());
        return s;
    }

    private static Map<String, Object> groupBy(List<Map<String, Object>> records, String field) {
        Map<String, int[]> acc = new LinkedHashMap<>();
        for (Map<String, Object> r : records) {
            String key = stringOf(r.get(field));
            if (key == null) key = "unknown";
            int[] v = acc.computeIfAbsent(key, k -> new int[2]);
            v[0]++;
            if (rulePassed(r)) v[1]++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        acc.forEach((k, v) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("total", v[0]);
            m.put("passed", v[1]);
            m.put("passRate", v[0] > 0 ? round((double) v[1] / v[0], 4) : 0);
            out.put(k, m);
        });
        return out;
    }

    private static Map<String, Object> byDialogue(List<Map<String, Object>> records) {
        Map<String, int[]> acc = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (Map<String, Object> r : records) {
            String key = stringOf(r.get("dialogueId"));
            names.put(key, stringOf(r.get("dialogueName")));
            int[] v = acc.computeIfAbsent(key, k -> new int[2]);
            v[0]++;
            if (rulePassed(r)) v[1]++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        acc.forEach((k, v) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", names.get(k));
            m.put("total", v[0]);
            m.put("passed", v[1]);
            m.put("passRate", v[0] > 0 ? round((double) v[1] / v[0], 4) : 0);
            out.put(k, m);
        });
        return out;
    }

    private static Map<String, Map<String, Object>> stability(List<Map<String, Object>> records) {
        Map<String, List<Map<String, Object>>> byTurn = new LinkedHashMap<>();
        for (Map<String, Object> r : records) {
            byTurn.computeIfAbsent(stringOf(r.get("turnId")), k -> new ArrayList<>()).add(r);
        }
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        byTurn.forEach((turnId, list) -> {
            if (list.size() <= 1) return;
            int pass = (int) list.stream().filter(EvalReport::rulePassed).count();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("runs", list.size());
            m.put("passes", pass);
            m.put("passAtN", list.size() == pass);
            m.put("passRate", round((double) pass / list.size(), 4));
            out.put(turnId, m);
        });
        return out;
    }

    private static double passAtN(Map<String, Map<String, Object>> stability) {
        if (stability.isEmpty()) return 0;
        long all = stability.values().stream().filter(m -> boolOf(m.get("passAtN"))).count();
        return round((double) all / stability.size(), 4);
    }

    private static Map<String, Object> judgeSummary(List<Map<String, Object>> records) {
        List<Map<String, Object>> judges = new ArrayList<>();
        for (Map<String, Object> r : records) {
            Map<String, Object> j = mapOf(r.get("judge"));
            if (!j.isEmpty() && stringOf(j.get("verdict")) != null) judges.add(j);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scored", judges.size());
        var calls=records.stream().map(r -> mapOf(r.get("judge"))).filter(j -> !j.isEmpty()).toList();
        long observed=calls.stream().filter(j -> Boolean.TRUE.equals(j.get("usageReported"))).count();
        m.put("attempted",calls.size());m.put("usageObserved",observed);
        m.put("totalTokenIn",observed==0?null:calls.stream().filter(j -> Boolean.TRUE.equals(j.get("usageReported"))).mapToLong(j -> longOf(j.get("tokenIn"))).sum());
        m.put("totalTokenOut",observed==0?null:calls.stream().filter(j -> Boolean.TRUE.equals(j.get("usageReported"))).mapToLong(j -> longOf(j.get("tokenOut"))).sum());
        m.put("avgFaithfulness", avg(judges, "faithfulness"));
        m.put("avgCompleteness", avg(judges, "completeness"));
        m.put("avgCorrectness", avg(judges, "correctness"));
        long hallucinated = judges.stream().filter(j -> intOf(j.get("hallucination")) == 1).count();
        m.put("hallucinationCount", (int) hallucinated);
        m.put("hallucinationRate", judges.isEmpty() ? 0 : round((double) hallucinated / judges.size(), 4));
        long pass = judges.stream().filter(j -> "pass".equalsIgnoreCase(stringOf(j.get("verdict")))).count();
        m.put("passCount", (int) pass);
        m.put("passRate", judges.isEmpty() ? 0 : round((double) pass / judges.size(), 4));
        m.put("disagreementCount", disagreementsOf(records).size());
        return m;
    }

    /** 各阶段耗时（来自每题 QaMetrics）：avg / P95 / max / 占总时长比例。 */
    private static Map<String, Object> byStage(List<Map<String, Object>> records) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("classify", "classifyTimeMs");
        fields.put("entity", "entityResolveTimeMs");
        fields.put("url", "urlMatchTimeMs");
        fields.put("vector", "vectorSearchTimeMs");
        fields.put("recipe", "recipeSearchTimeMs");
        fields.put("rerank", "rerankTimeMs");
        fields.put("answer", "answerGenTimeMs");
        fields.put("ttft", "ttftMs");
        fields.put("total", "totalTimeMs");

        Map<String, Object> out = new LinkedHashMap<>();
        double totalAvg = stageValues(records, "totalTimeMs").stream()
                .mapToLong(Long::longValue).average().orElse(0);
        fields.forEach((stage, field) -> {
            List<Long> values = stageValues(records, field);
            double avg = values.stream().mapToLong(Long::longValue).average().orElse(0);
            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("avgMs", Math.round(avg));
            stat.put("p95Ms", percentile(values, 0.95));
            stat.put("maxMs", values.stream().mapToLong(Long::longValue).max().orElse(0));
            if (!"total".equals(stage)) {
                stat.put("share", totalAvg > 0 ? round(avg / totalAvg, 4) : 0);
            }
            out.put(stage, stat);
        });
        return out;
    }

    private static List<Long> stageValues(List<Map<String, Object>> records, String field) {
        List<Long> values = new ArrayList<>();
        for (Map<String, Object> r : records) {
            values.add(longOf(mapOf(r.get("metrics")).get(field)));
        }
        return values;
    }

    private static double avg(List<Map<String, Object>> list, String field) {
        List<Double> values = new ArrayList<>();
        for (Map<String, Object> m : list) {
            Object v = m.get(field);
            if (v instanceof Number n) values.add(n.doubleValue());
        }
        return values.isEmpty() ? 0 : round(values.stream().mapToDouble(Double::doubleValue).average().orElse(0), 2);
    }

    private static List<Map<String, Object>> disagreementsOf(List<Map<String, Object>> records) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : records) {
            Map<String, Object> j = mapOf(r.get("judge"));
            String verdict = stringOf(j.get("verdict"));
            if (verdict == null) continue;
            boolean rulePass = rulePassed(r);
            boolean judgePass = "pass".equalsIgnoreCase(verdict);
            if (rulePass != judgePass) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("turnId", stringOf(r.get("turnId")));
                m.put("question", stringOf(r.get("question")));
                m.put("rulePassed", rulePass);
                m.put("judgeVerdict", verdict);
                m.put("judgeReasons", stringOf(j.get("reasons")));
                out.add(m);
            }
        }
        return out;
    }

    // ==================== markdown ====================

    @SuppressWarnings("unchecked")
    public static String markdown(Map<String, Object> s, List<Map<String, Object>> records) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 评测报告 ").append(s.get("runId")).append("\n\n");
        sb.append("- 评测集: ").append(s.get("goldenSet")).append(" (v").append(s.get("goldenSetVersion")).append(")\n");
        sb.append("- tag: ").append(s.get("tag")).append("\n");
        sb.append("- LLM 裁判: ").append(boolOf(s.get("judgeEnabled")) ? "开启" : "关闭").append("\n");
        sb.append("- 回答生成路径: ").append(s.getOrDefault("answerGeneration","未记录")).append("（离线摘录不等同于真实模型回答）\n");
        sb.append("- 运行目录: ").append(s.get("runDir")).append("\n\n");

        sb.append("## 总览\n\n");
        sb.append("| 指标 | 值 |\n|---|---|\n");
        sb.append("| 总轮次 | ").append(s.get("totalTurns")).append(" |\n");
        sb.append("| 规则判分通过 | ").append(s.get("passedTurns")).append(" |\n");
        sb.append("| 通过率 | ").append(pct(s.get("passRate"))).append(" |\n");
        sb.append("| 平均规则得分 | ").append(s.get("avgRuleScore")).append(" |\n");
        sb.append("| 检索命中率(Hit) | ").append(pct(s.get("hitRate"))).append(" |\n");
        var media=mapOf(s.get("media"));
        if(!media.isEmpty()) {
            sb.append("| 图文统计覆盖轮次 | ").append(media.get("observedTurns")).append(" |\n");
            sb.append("| 候选图片 / 实际引用 / 有效图片 | ").append(media.get("candidates")).append(" / ").append(media.get("displayed")).append(" / ").append(media.get("valid")).append(" |\n");
            sb.append("| 有效资料插图 / 不可用图片 / 无效引用 | ").append(media.get("validIllustrations")).append(" / ").append(media.get("unavailable")).append(" / ").append(media.get("invalidReferences")).append(" |\n");
        }
        sb.append("| 平均耗时 | ").append(s.get("avgTotalMs")).append(" ms（P95 ").append(s.get("p95TotalMs")).append(" ms）|\n");
        sb.append("| 端到端耗时（含渲染+裁判） | ").append(s.get("avgEvalTotalMs")).append(" ms（P95 ")
                .append(s.get("p95EvalTotalMs")).append(" ms；渲染 ").append(s.get("avgRenderMs"))
                .append(" ms / 裁判 ").append(s.get("avgJudgeMs")).append(" ms）|\n");
        sb.append("| 平均 Token | in ").append(s.get("avgTokenIn")).append(" / out ").append(s.get("avgTokenOut")).append(" |\n");
        sb.append("| 平均答案长度 | ").append(s.get("avgAnswerLength")).append(" 字符 |\n");
        if (!((Map<String, Object>) s.get("stability")).isEmpty()) {
            sb.append("| 稳定性 pass@N | ").append(pct(s.get("stabilityPassAtN"))).append(" |\n");
        }
        sb.append("\nTier 分布: ").append(s.get("tierDistribution")).append("\n\n");
        sb.append("## 网页访问与向量推理\n\n计数覆盖轮次：").append(s.get("ioObservedTurns")).append("。旧版本未记录时为未知，不能解释成零请求。向量复用计数包括缓存载入与排序读取，可能多次读取同一块。\n\n");
        sb.append("| 实际计数 | 合计 |\n|---|---|\n");
        mapOf(s.get("retrievalIo")).forEach((key,value)->sb.append("| ").append(key).append(" | ").append(value==null?"未观测":value).append(" |\n"));
        sb.append('\n');

        Map<String, Object> byStage = (Map<String, Object>) s.get("byStage");
        if (byStage != null && !byStage.isEmpty()) {
            sb.append("## 各阶段耗时\n\n");
            sb.append("| 阶段 | 平均(ms) | P95(ms) | 最大(ms) | 占总时长 |\n|---|---|---|---|---|\n");
            byStage.forEach((stage, v) -> {
                Map<String, Object> m = mapOf(v);
                sb.append("| ").append(stage).append(" | ").append(m.get("avgMs")).append(" | ")
                        .append(m.get("p95Ms")).append(" | ").append(m.get("maxMs")).append(" | ");
                Object share = m.get("share");
                sb.append(share == null ? "-" : pct(share)).append(" |\n");
            });
            sb.append("\n");
        }

        Map<String, Object> judge = (Map<String, Object>) s.get("judge");
        sb.append("## LLM 裁判维度（复核）\n\n");
        sb.append("文本裁判未接收图片像素，不能代替图片语义核对。\n\n");
        sb.append("| 指标 | 值 |\n|---|---|\n");
        sb.append("| 已判分轮次 | ").append(judge.get("scored")).append(" |\n");
        sb.append("| 裁判通过率 | ").append(pct(judge.get("passRate"))).append(" |\n");
        sb.append("| 裁判用量观测 | ").append(judge.getOrDefault("usageObserved",0)).append(" / ").append(judge.getOrDefault("attempted",0)).append(" |\n");
        sb.append("| 裁判 Token in / out | ").append(judge.get("totalTokenIn")==null?"未观测":judge.get("totalTokenIn"))
                .append(" / ").append(judge.get("totalTokenOut")==null?"未观测":judge.get("totalTokenOut")).append(" |\n");
        sb.append("| 平均忠实度/完整性/正确性 | ").append(judge.get("avgFaithfulness")).append(" / ")
                .append(judge.get("avgCompleteness")).append(" / ").append(judge.get("avgCorrectness")).append(" |\n");
        sb.append("| 幻觉率 | ").append(pct(judge.get("hallucinationRate"))).append(" |\n");
        sb.append("| 与规则判分不一致 | ").append(judge.get("disagreementCount")).append(" |\n\n");

        sb.append("## 分类通过率\n\n");
        sb.append("### 按题型 type\n\n");
        sb.append(groupTable((Map<String, Object>) s.get("byType")));
        sb.append("\n### 按类别 category\n\n");
        sb.append(groupTable((Map<String, Object>) s.get("byCategory")));
        sb.append("\n### 按模组 modId\n\n");
        sb.append(groupTable((Map<String, Object>) s.get("byMod")));
        sb.append("\n### 按对话\n\n");
        sb.append(groupTable((Map<String, Object>) s.get("byDialogue")));

        Map<String, Object> stability = (Map<String, Object>) s.get("stability");
        if (!stability.isEmpty()) {
            sb.append("\n## 稳定性（重复运行）\n\n");
            sb.append("| Turn | Runs | Passes | pass@N |\n|---|---|---|---|\n");
            stability.forEach((turnId, v) -> {
                Map<String, Object> m = (Map<String, Object>) v;
                sb.append("| ").append(turnId).append(" | ").append(m.get("runs")).append(" | ")
                        .append(m.get("passes")).append(" | ").append(boolOf(m.get("passAtN")) ? "✅" : "❌").append(" |\n");
            });
        }

        sb.append("\n## 失败题（bad cases）\n\n");
        List<Map<String, Object>> failed = records.stream().filter(r -> !rulePassed(r)).toList();
        if (failed.isEmpty()) {
            sb.append("无\n");
        } else {
            for (Map<String, Object> r : failed) {
                sb.append("### ").append(r.get("turnId")).append(" — ").append(r.get("question")).append("\n\n");
                sb.append("- 规则得分: ").append(mapOf(r.get("rule")).get("score")).append("\n");
                Map<String, Object> j = mapOf(r.get("judge"));
                if (!j.isEmpty()) sb.append("- 裁判: ").append(j.get("verdict")).append("（").append(j.get("reasons")).append("）\n");
                sb.append("- 失败断言: ").append(failedOutcomes(r)).append("\n\n");
            }
        }
        return sb.toString();
    }

    public static String badCases(List<Map<String, Object>> records) {
        StringBuilder sb = new StringBuilder("# Bad Cases\n\n");
        List<Map<String, Object>> failed = records.stream().filter(r -> !rulePassed(r)).toList();
        if (failed.isEmpty()) return sb.append("无\n").toString();
        for (Map<String, Object> r : failed) {
            sb.append("## ").append(r.get("turnId")).append(" [").append(r.get("type")).append("/")
                    .append(r.get("category")).append("]\n\n");
            sb.append("Q: ").append(r.get("question")).append("\n\n");
            sb.append("规则: score=").append(mapOf(r.get("rule")).get("score"))
                    .append(" failed=").append(failedOutcomes(r)).append("\n\n");
            Map<String, Object> j = mapOf(r.get("judge"));
            sb.append("裁判: ").append(j.isEmpty() ? "(未启用)" : j.get("verdict") + " — " + j.get("reasons")).append("\n\n");
            sb.append("回答:\n\n```\n").append(truncate(stringOf(r.get("answer")), 1200)).append("\n```\n\n---\n\n");
        }
        return sb.toString();
    }

    public static String disagreements(List<Map<String, Object>> records) {
        StringBuilder sb = new StringBuilder("# 规则 / 裁判 不一致\n\n");
        List<Map<String, Object>> list = disagreementsOf(records);
        if (list.isEmpty()) return sb.append("无\n").toString();
        sb.append("| Turn | 问题 | 规则 | 裁判 | 裁判理由 |\n|---|---|---|---|---|\n");
        for (Map<String, Object> m : list) {
            sb.append("| ").append(m.get("turnId")).append(" | ").append(truncate(stringOf(m.get("question")), 40))
                    .append(" | ").append(boolOf(m.get("rulePassed")) ? "PASS" : "FAIL")
                    .append(" | ").append(m.get("judgeVerdict")).append(" | ")
                    .append(truncate(stringOf(m.get("judgeReasons")), 80)).append(" |\n");
        }
        return sb.toString();
    }

    /** 保留完整回答和实际证据；人工决定只追加，不覆写原始规则及裁判记录。 */
    public static String humanReview(List<Map<String,Object>> records) {
        var sb=new StringBuilder("# 待人工复核\n\n可逐项回复 `题号/rN：PASS、PARTIAL、FAIL 或 REFERENCE_ERROR；理由`。未回复的项目保持待判定，自动裁判不视为人工结论。参考答案及原始裁判 prompt 见本目录 golden-set.json 与 judge/。\n\n");
        int count=0;
        for(var r:records) {
            var judge=mapOf(r.get("judge")); String verdict=stringOf(judge.get("verdict"));
            if(rulePassed(r) && (judge.isEmpty() || "pass".equals(verdict))) continue;
            count++;
            sb.append("## ").append(r.get("turnId")).append(" / r").append(r.get("repeat")).append("\n\n")
              .append("问题：").append(r.get("question")).append("\n\n")
              .append("自动规则：").append(rulePassed(r)?"PASS":"FAIL").append("；失败断言：").append(failedOutcomes(r)).append("\n\n")
              .append("自动裁判：").append(verdict).append("；理由：").append(judge.get("reasons")).append("；错误：").append(judge.get("error")).append("\n\n")
              .append("### 完整回答\n\n").append(stringOf(r.get("answer"))).append("\n\n")
              .append("### 实际检索证据\n\n").append(stringOf(r.get("retrievalEvidence"))).append("\n\n")
              .append("人工判定：待填写\n\n理由／参考答案修订：待填写\n\n---\n\n");
        }
        if(count==0) sb.append("本次没有待复核项目。\n");
        return sb.toString();
    }

    private static String groupTable(Map<String, Object> groups) {
        if (groups == null || groups.isEmpty()) return "无\n";
        StringBuilder sb = new StringBuilder("| 分组 | 总数 | 通过 | 通过率 |\n|---|---|---|---|\n");
        groups.forEach((k, v) -> {
            Map<String, Object> m = mapOf(v);
            sb.append("| ").append(k).append(" | ").append(m.get("total")).append(" | ")
                    .append(m.get("passed")).append(" | ").append(pct(m.get("passRate"))).append(" |\n");
        });
        return sb.toString();
    }

    private static String failedOutcomes(Map<String, Object> record) {
        Object outcomes = mapOf(record.get("rule")).get("outcomes");
        if (!(outcomes instanceof List<?> list)) return "[]";
        List<String> failed = new ArrayList<>();
        for (Object o : list) {
            Map<String, Object> m = mapOf(o);
            if (!boolOf(m.get("passed"))) {
                failed.add(m.get("kind") + "(" + truncate(stringOf(m.get("detail")), 80) + ")");
            }
        }
        return failed.isEmpty() ? "[]" : failed.toString();
    }

    // ==================== helpers ====================

    private static boolean rulePassed(Map<String, Object> record) {
        return boolOf(mapOf(record.get("rule")).get("passed"));
    }

    private static double ruleScore(Map<String, Object> record) {
        return doubleOf(mapOf(record.get("rule")).get("score"));
    }

    private static long percentile(List<Long> values, double p) {
        if (values.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        idx = Math.max(0, Math.min(idx, sorted.size() - 1));
        return sorted.get(idx);
    }

    private static String pct(Object rate) {
        return String.format("%.1f%%", doubleOf(rate) * 100);
    }

    private static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static Map<String, Object> mapOf(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return Map.of();
    }

    private static String stringOf(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static boolean boolOf(Object v) {
        return v instanceof Boolean b && b;
    }

    private static int intOf(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static long longOf(Object v) {
        return v instanceof Number n ? n.longValue() : 0;
    }

    private static double doubleOf(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0;
    }
}
