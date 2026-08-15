package yagen.waitmydawn.kb.dto;

import java.time.Instant;
import java.util.*;

/**
 * RAG 系统可观测性指标 — 每个 QA 请求的完整性能/质量/成本追踪。
 *
 * 指标维度:
 *   性能: TTFT, 各阶段耗时, 总耗时
 *   质量: 命中率, 精度, 数据层级, 实体解析成功率
 *   成本: LLM 调用次数, Token 消耗, DB 查询次数
 *   追踪: 每阶段详细记录
 */
public class QaMetrics {

    // ===== 性能指标 (ms) =====
    public long classifyTimeMs;
    public long entityResolveTimeMs;
    public long urlMatchTimeMs;           // UrlAgent 耗时
    public long incrementalFetchTimeMs;
    public long vectorSearchTimeMs;
    public long recipeSearchTimeMs;
    public long rerankTimeMs;
    public long answerGenTimeMs;
    public long totalTimeMs;

    /** Time To First Token — 从用户提问到第一个 Agent 返回实质性内容的时间 */
    public long ttftMs;

    /** 各 Agent 的 LLM 调用延迟 (ms) */
    public long classifyLlmMs;
    public long entityLlmMs;
    public long urlLlmMs;
    public long answerLlmMs;
    public long rerankLlmMs;
    public long fallbackLlmMs;
    public long recipeLlmMs;

    // ===== 质量指标 =====
    public int entityCount;                // 解析出的实体数
    public int subPageUrlCount;            // 匹配到的子网页 URL 数
    public int vectorResultCount;          // 向量搜索结果数
    public int vectorRelevantCount;        // 相关向量结果数 (score > 0.2)
    public double vectorMaxScore;          // 最高向量相似度分数
    public double vectorAvgScore;          // 平均向量相似度分数
    public int recipeResultCount;          // 配方结果数
    public int incrementalChunksAcquired;  // 增量获取的文本块数
    public boolean fallbackUsed;           // 是否使用了 LLM fallback
    public String classifyCategory;        // 分类结果
    public float classifyConfidence;       // 分类置信度
    public String dataTier;                // 数据层级: A/B/C/D
    public int answerLength;               // 回答字符数
    public boolean entityResolved;         // 是否成功解析出实体
    public boolean retrievalHit;           // 是否检索到相关数据 (>0 配方 或 最高向量分>0.2)
    public int contextChunksUsed;          // 实际用于生成回答的向量块数
    public double contextUtilization;      // 上下文利用率 (使用的块/检索到的块)

    // ===== 成本指标 =====
    public int llmCallCount;               // LLM API 调用次数
    public int tokenEstimateInput;         // 估算输入 token
    public int tokenEstimateOutput;        // 估算输出 token
    public int tokenTotalCount;            // 输入+输出 token 合计（有真实用量时优先）
    public int dbQueryCount;               // 数据库查询次数

    // ===== 元数据 =====
    public String sessionId;               // 会话 ID
    public String questionHash;            // 问题哈希 (用于去重分析)
    public Instant timestamp;              // 请求时间戳
    public int conversationTurn;           // 当前会话中的第几轮

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
        tokenTotalCount += estimatedInputTokens + estimatedOutputTokens;
    }

    /** Compute TTFT — first agent response time (classify + entity resolve) */
    public void computeTtft() {
        this.ttftMs = classifyTimeMs + entityResolveTimeMs;
    }

    /** Compute vector relevance score summary and statistics */
    public void computeVectorRelevance(List<?> results, double threshold) {
        int rel = 0;
        double maxScore = 0;
        double sumScore = 0;
        int count = 0;
        for (Object r : results) {
            try {
                var f = r.getClass().getDeclaredField("score");
                f.setAccessible(true);
                double score = (double) f.get(r);
                if (score > maxScore) maxScore = score;
                if (score > threshold) rel++;
                sumScore += score;
                count++;
            } catch (Exception ignored) {}
        }
        this.vectorRelevantCount = rel;
        this.vectorMaxScore = maxScore;
        this.vectorAvgScore = count > 0 ? sumScore / count : 0;
    }

    /** Compute composite quality indicators */
    public void computeQualityIndicators() {
        this.entityResolved = entityCount > 0;
        this.retrievalHit = recipeResultCount > 0 || vectorMaxScore > 0.2;
        this.contextUtilization = vectorResultCount > 0
                ? (double) contextChunksUsed / vectorResultCount : 0;
    }

    /** Format as compact single-line log string */
    public String toLogString() {
        return String.format(
                "Metrics[total=%dms ttft=%dms | cls=%dms ent=%dms url=%dms inc=%dms vec=%dms rec=%dms rrk=%dms ans=%dms | "
                        + "tier=%s hit=%s ent=%d(%s) urls=%d vec=%d(rel=%d max=%.3f avg=%.3f) rec=%d incChk=%d | "
                        + "llm=%d tokIn=%d tokOut=%d fallback=%s cat=%s(%.2f) ansLen=%d ctxUse=%.0f%%]",
                totalTimeMs, ttftMs,
                classifyTimeMs, entityResolveTimeMs, urlMatchTimeMs, incrementalFetchTimeMs,
                vectorSearchTimeMs, recipeSearchTimeMs, rerankTimeMs, answerGenTimeMs,
                dataTier, retrievalHit, entityCount, entityResolved,
                subPageUrlCount, vectorResultCount, vectorRelevantCount, vectorMaxScore, vectorAvgScore,
                recipeResultCount, incrementalChunksAcquired,
                llmCallCount, tokenEstimateInput, tokenEstimateOutput,
                fallbackUsed, classifyCategory, classifyConfidence,
                answerLength, contextUtilization * 100);
    }

    /** Format as CSV header */
    public static String csvHeader() {
        return "timestamp,sessionId,turn,totalMs,ttftMs,classifyMs,entityMs,urlMs,incMs,vecMs,recipeMs,rerankMs,answerMs,"
                + "classifyLlmMs,entityLlmMs,urlLlmMs,answerLlmMs,rerankLlmMs,fallbackLlmMs,"
                + "dataTier,hit,entityCount,entityResolved,subPageUrls,vecCount,vecRel,vecMaxScore,vecAvgScore,"
                + "recipeCount,incChunks,contextUsed,contextUtil,"
                + "llmCalls,tokIn,tokOut,dbQueries,fallback,"
                + "category,confidence,answerLen,questionHash";
    }

    /** Format as CSV row */
    public String toCsvRow() {
        return String.format("%s,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%s,%s,%d,%s,%d,%d,%d,%.3f,%.3f,%d,%d,%d,%.3f,%d,%d,%d,%d,%s,%s,%.2f,%d,%s",
                timestamp, sessionId, conversationTurn,
                totalTimeMs, ttftMs, classifyTimeMs, entityResolveTimeMs, urlMatchTimeMs,
                incrementalFetchTimeMs, vectorSearchTimeMs, recipeSearchTimeMs, rerankTimeMs, answerGenTimeMs,
                classifyLlmMs, entityLlmMs, urlLlmMs, answerLlmMs, rerankLlmMs, fallbackLlmMs,
                dataTier, retrievalHit, entityCount, entityResolved,
                subPageUrlCount, vectorResultCount, vectorRelevantCount, vectorMaxScore, vectorAvgScore,
                recipeResultCount, incrementalChunksAcquired, contextChunksUsed, contextUtilization,
                llmCallCount, tokenEstimateInput, tokenEstimateOutput, dbQueryCount, fallbackUsed,
                classifyCategory, classifyConfidence, answerLength, questionHash);
    }

    /** Format as human-readable summary for UI */
    public String toUiSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("══════════════ QA Metrics Report ══════════════\n\n");

        // Performance
        sb.append("┌── Performance ──────────────────────────────────┐\n");
        sb.append(String.format("│ %-20s %8d ms\n", "TTFT (First Token):", ttftMs));
        sb.append(String.format("│ %-20s %8d ms\n", "Total Latency:", totalTimeMs));
        sb.append("├──────────────────────────────────────────────────┤\n");
        for (TraceStep step : trace) {
            String bar = "█".repeat(Math.min((int) (step.durationMs / 40), 25));
            sb.append(String.format("│ %-18s %6dms %s\n", step.stage + ":", step.durationMs, bar));
        }
        sb.append("└──────────────────────────────────────────────────┘\n\n");

        // LLM Latency breakdown
        sb.append("┌── LLM Call Latency ─────────────────────────────┐\n");
        sb.append(String.format("│ Classify:    %6d ms\n", classifyLlmMs));
        sb.append(String.format("│ Entity:      %6d ms\n", entityLlmMs));
        sb.append(String.format("│ UrlAgent:    %6d ms\n", urlLlmMs));
        sb.append(String.format("│ Rerank:      %6d ms\n", rerankLlmMs));
        sb.append(String.format("│ Answer:      %6d ms\n", answerLlmMs));
        if (fallbackUsed) sb.append(String.format("│ Fallback:    %6d ms\n", fallbackLlmMs));
        sb.append("└──────────────────────────────────────────────────┘\n\n");

        // Quality
        sb.append("┌── Quality ───────────────────────────────────────┐\n");
        sb.append(String.format("│ Data Tier:    %-5s  Hit: %s\n", dataTier, retrievalHit ? "YES" : "NO"));
        sb.append(String.format("│ Category:     %-15s  conf=%.2f\n", classifyCategory, classifyConfidence));
        sb.append(String.format("│ Entities:     %-5d  Resolved: %s\n", entityCount, entityResolved ? "YES" : "no"));
        sb.append(String.format("│ SubPage URLs: %-5d  Inc Chunks: %d\n", subPageUrlCount, incrementalChunksAcquired));
        sb.append(String.format("│ Recipes:      %-5d\n", recipeResultCount));
        sb.append("├──────────────────────────────────────────────────┤\n");
        sb.append(String.format("│ Vector Results:  %-4d  Relevant: %d\n", vectorResultCount, vectorRelevantCount));
        sb.append(String.format("│ Vector Scores:   max=%.3f  avg=%.3f\n", vectorMaxScore, vectorAvgScore));
        sb.append(String.format("│ Context Chunks:  %-4d used  Utilization: %.0f%%\n", contextChunksUsed, contextUtilization * 100));
        sb.append(String.format("│ Answer Length:   %-5d chars\n", answerLength));
        sb.append("└──────────────────────────────────────────────────┘\n\n");

        // Cost
        sb.append("┌── Cost ──────────────────────────────────────────┐\n");
        sb.append(String.format("│ LLM Calls:    %-4d   Tokens: ~%d in / ~%d out\n",
                llmCallCount, tokenEstimateInput, tokenEstimateOutput));
        sb.append(String.format("│ DB Queries:   %d\n", dbQueryCount));
        sb.append(String.format("│ Tokens/char:  %.2f (efficiency)\n",
                answerLength > 0 ? (double) (tokenEstimateInput + tokenEstimateOutput) / answerLength : 0));
        sb.append("└──────────────────────────────────────────────────┘\n\n");

        // Session Cumulative
        sb.append("┌── Session Cumulative ────────────────────────────┐\n");
        sb.append(String.format("│ Requests:     %-5d    Avg Latency: %dms\n",
                cumulative.requestCount,
                cumulative.requestCount > 0 ? cumulative.totalTimeMs / cumulative.requestCount : 0));
        sb.append(String.format("│ Hit Rate:     %.0f%%     (%d/%d)\n",
                cumulative.requestCount > 0 ? 100.0 * cumulative.hits / cumulative.requestCount : 0,
                cumulative.hits, cumulative.requestCount));
        sb.append(String.format("│ Tier A/B/C/D: %d/%d/%d/%d\n",
                cumulative.tierA, cumulative.tierB, cumulative.tierC, cumulative.tierD));
        sb.append(String.format("│ Tokens:       ~%d in / ~%d out\n",
                cumulative.totalTokensIn, cumulative.totalTokensOut));
        sb.append(String.format("│ Entities:     %d resolved  Recipes: %d found\n",
                cumulative.totalEntities, cumulative.totalRecipes));
        sb.append(String.format("│ Avg TTFT:     %dms\n",
                cumulative.requestCount > 0 ? cumulative.totalTtftMs / cumulative.requestCount : 0));
        sb.append("└──────────────────────────────────────────────────┘\n");

        return sb.toString();
    }

    /** Accumulate into session-level stats */
    public void accumulate() {
        cumulative.requestCount++;
        cumulative.totalTimeMs += totalTimeMs;
        cumulative.totalTtftMs += ttftMs;
        cumulative.totalTokensIn += tokenEstimateInput;
        cumulative.totalTokensOut += tokenEstimateOutput;
        cumulative.totalEntities += entityCount;
        cumulative.totalRecipes += recipeResultCount;
        cumulative.totalVectorHits += vectorRelevantCount;
        cumulative.totalLlmCalls += llmCallCount;
        if (retrievalHit) cumulative.hits++;
        if (fallbackUsed) cumulative.fallbacks++;
        switch (dataTier != null ? dataTier : "D") {
            case "TIER A" -> cumulative.tierA++;
            case "TIER B" -> cumulative.tierB++;
            case "TIER C" -> cumulative.tierC++;
            default -> cumulative.tierD++;
        }
        cumulative.totalVectorMaxScoreSum += vectorMaxScore;
    }

    // ===== Data records =====

    public record TraceStep(String stage, long durationMs, String detail) {}

    public static class CumulativeStats {
        public int requestCount;
        public int hits;
        public int fallbacks;
        public int tierA, tierB, tierC, tierD;
        public long totalTimeMs;
        public long totalTtftMs;
        public int totalTokensIn;
        public int totalTokensOut;
        public int totalEntities;
        public int totalRecipes;
        public int totalVectorHits;
        public int totalLlmCalls;
        public double totalVectorMaxScoreSum;

        public synchronized void reset() {
            requestCount = 0; hits = 0; fallbacks = 0;
            tierA = 0; tierB = 0; tierC = 0; tierD = 0;
            totalTimeMs = 0; totalTtftMs = 0; totalTokensIn = 0;
            totalTokensOut = 0; totalEntities = 0; totalRecipes = 0;
            totalVectorHits = 0; totalLlmCalls = 0;
            totalVectorMaxScoreSum = 0;
        }

        public double avgLatencyMs() { return requestCount > 0 ? (double) totalTimeMs / requestCount : 0; }
        public double avgTtftMs() { return requestCount > 0 ? (double) totalTtftMs / requestCount : 0; }
        public double hitRate() { return requestCount > 0 ? (double) hits / requestCount : 0; }
        public double avgVectorMaxScore() { return requestCount > 0 ? totalVectorMaxScoreSum / requestCount : 0; }
    }
}
