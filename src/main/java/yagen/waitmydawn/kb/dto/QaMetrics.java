package yagen.waitmydawn.kb.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 系统可观测性指标 — 每个 QA 请求的完整性能/质量/成本追踪。
 */
public class QaMetrics {

    // ===== 性能指标 (ms) =====
    public long classifyTimeMs;
    public long entityResolveTimeMs;
    public long incrementalFetchTimeMs;
    public long vectorSearchTimeMs;
    public long recipeSearchTimeMs;
    public long answerGenTimeMs;
    public long totalTimeMs;

    // ===== 质量指标 =====
    public int entityCount;           // 解析出的实体数
    public int subPageUrlCount;       // 匹配到的子网页 URL 数
    public int vectorResultCount;     // 向量搜索结果数
    public int vectorRelevantCount;   // 相关向量结果数 (score > 0.2)
    public int recipeResultCount;     // 配方结果数
    public boolean fallbackUsed;      // 是否使用了 LLM fallback
    public String classifyCategory;   // 分类结果
    public float classifyConfidence;  // 分类置信度

    // ===== 成本指标 =====
    public int llmCallCount;          // LLM API 调用次数
    public int tokenEstimateInput;    // 估算输入 token
    public int tokenEstimateOutput;   // 估算输出 token
    public int dbQueryCount;          // 数据库查询次数

    // ===== 追踪 =====
    public final List<TraceStep> trace = new ArrayList<>();

    // ===== 累计统计 (跨请求) =====
    public static final CumulativeStats cumulative = new CumulativeStats();

    /** Record a pipeline step */
    public void addTrace(String stage, long durationMs, String detail) {
        trace.add(new TraceStep(stage, durationMs, detail));
    }

    /** Mark LLM API call with estimated tokens */
    public void recordLlmCall(int estimatedInputTokens, int estimatedOutputTokens) {
        llmCallCount++;
        tokenEstimateInput += estimatedInputTokens;
        tokenEstimateOutput += estimatedOutputTokens;
    }

    /** Compute vector relevance score summary */
    public void computeVectorRelevance(List<?> results, double threshold) {
        int rel = 0;
        for (Object r : results) {
            try {
                var f = r.getClass().getDeclaredField("score");
                f.setAccessible(true);
                double score = (double) f.get(r);
                if (score > threshold) rel++;
            } catch (Exception ignored) {}
        }
        this.vectorRelevantCount = rel;
    }

    /** Format as compact metrics string */
    public String toLogString() {
        return String.format(
                "Metrics[total=%dms | classify=%dms entity=%dms inc=%dms vec=%dms recipe=%dms answer=%dms | "
                        + "entities=%d subPages=%d vecRes=%d(vecRel=%d) recipes=%d | "
                        + "llmCalls=%d tokIn=%d tokOut=%d fallback=%s cat=%s(%.2f)]",
                totalTimeMs, classifyTimeMs, entityResolveTimeMs, incrementalFetchTimeMs,
                vectorSearchTimeMs, recipeSearchTimeMs, answerGenTimeMs,
                entityCount, subPageUrlCount, vectorResultCount, vectorRelevantCount, recipeResultCount,
                llmCallCount, tokenEstimateInput, tokenEstimateOutput,
                fallbackUsed, classifyCategory, classifyConfidence);
    }

    /** Format as human-readable summary for UI */
    public String toUiSummary() {
        StringBuilder sb = new StringBuilder();

        // Pipeline trace
        sb.append("┌── Pipeline Trace ──────────────────────────────┐\n");
        for (TraceStep step : trace) {
            String bar = "█".repeat(Math.min((int) (step.durationMs / 50), 30));
            sb.append(String.format("│ %-16s %6dms %s\n", step.stage + ":", step.durationMs, bar));
        }
        sb.append(String.format("│ %-16s %6dms\n", "TOTAL:", totalTimeMs));
        sb.append("└────────────────────────────────────────────────┘\n\n");

        // Results
        sb.append("┌── Results ─────────────────────────────────────┐\n");
        sb.append(String.format("│ Category:  %-15s  confidence: %.2f\n", classifyCategory, classifyConfidence));
        sb.append(String.format("│ Entities:  %-5d  Sub-page URLs: %d\n", entityCount, subPageUrlCount));
        sb.append(String.format("│ Recipes:   %-5d  Vector chunks:  %d (relevant: %d)\n",
                recipeResultCount, vectorResultCount, vectorRelevantCount));
        sb.append(String.format("│ Fallback:  %-5s\n", fallbackUsed ? "YES" : "no"));
        sb.append("└────────────────────────────────────────────────┘\n\n");

        // Cost
        sb.append("┌── Cost ────────────────────────────────────────┐\n");
        sb.append(String.format("│ LLM calls: %-4d  Tokens: ~%d in + ~%d out\n",
                llmCallCount, tokenEstimateInput, tokenEstimateOutput));
        sb.append(String.format("│ DB queries: %d\n", dbQueryCount));
        sb.append("└────────────────────────────────────────────────┘\n");

        // Cumulative
        sb.append("\n┌── Session Cumulative ──────────────────────────┐\n");
        sb.append(String.format("│ Requests: %-5d  Avg latency: %dms\n",
                cumulative.requestCount,
                cumulative.requestCount > 0 ? cumulative.totalTimeMs / cumulative.requestCount : 0));
        sb.append(String.format("│ Tokens:    ~%d in / ~%d out\n",
                cumulative.totalTokensIn, cumulative.totalTokensOut));
        sb.append(String.format("│ Entities resolved: %d  Recipes found: %d\n",
                cumulative.totalEntities, cumulative.totalRecipes));
        sb.append("└────────────────────────────────────────────────┘\n");

        return sb.toString();
    }

    /** Accumulate into session-level stats */
    public void accumulate() {
        cumulative.requestCount++;
        cumulative.totalTimeMs += totalTimeMs;
        cumulative.totalTokensIn += tokenEstimateInput;
        cumulative.totalTokensOut += tokenEstimateOutput;
        cumulative.totalEntities += entityCount;
        cumulative.totalRecipes += recipeResultCount;
        cumulative.totalVectorHits += vectorRelevantCount;
        cumulative.totalLlmCalls += llmCallCount;
    }

    // ===== Data records =====

    public record TraceStep(String stage, long durationMs, String detail) {}

    public static class CumulativeStats {
        public int requestCount;
        public long totalTimeMs;
        public int totalTokensIn;
        public int totalTokensOut;
        public int totalEntities;
        public int totalRecipes;
        public int totalVectorHits;
        public int totalLlmCalls;

        public synchronized void reset() {
            requestCount = 0; totalTimeMs = 0; totalTokensIn = 0;
            totalTokensOut = 0; totalEntities = 0; totalRecipes = 0;
            totalVectorHits = 0; totalLlmCalls = 0;
        }
    }
}
