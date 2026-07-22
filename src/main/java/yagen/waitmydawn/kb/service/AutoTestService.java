package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yagen.waitmydawn.kb.dto.QaMetrics;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * 自动化测试服务 — 按预设对话脚本执行 QA 测试，收集指标并生成报告。
 *
 * 测试问题文件格式 (JSON):
 * {
 *   "conversations": [
 *     {
 *       "name": "对话名称",
 *       "turns": ["问题1", "问题2", ...]
 *     }
 *   ]
 * }
 */
public class AutoTestService {

    private static final Logger log = LoggerFactory.getLogger(AutoTestService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final QaPipeline pipeline;

    public AutoTestService(QaPipeline pipeline) {
        this.pipeline = pipeline;
    }

    /** Run all conversations from a test file */
    public TestReport runFromFile(Path testFile) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> config = mapper.readValue(testFile.toFile(), Map.class);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conversations = (List<Map<String, Object>>) config.get("conversations");
        if (conversations == null || conversations.isEmpty()) {
            throw new IllegalArgumentException("Test file must contain a 'conversations' array");
        }

        TestReport report = new TestReport();
        report.testFile = testFile.getFileName().toString();
        report.startTime = Instant.now();

        for (Map<String, Object> conv : conversations) {
            String name = (String) conv.get("name");
            @SuppressWarnings("unchecked")
            List<String> turns = (List<String>) conv.get("turns");
            if (turns == null || turns.isEmpty()) continue;

            log.info("=== Conversation: {} ({} turns) ===", name, turns.size());
            System.out.println("\n══════════════════════════════════════════");
            System.out.println("  Conversation: " + name);
            System.out.println("══════════════════════════════════════════");

            pipeline.clearHistory();
            List<QaMetrics> convMetrics = new ArrayList<>();

            for (int i = 0; i < turns.size(); i++) {
                String question = turns.get(i);
                System.out.println("\n[Turn " + (i + 1) + "/" + turns.size() + "] Q: " + question);

                long start = System.currentTimeMillis();
                QaPipeline.QaResult result = pipeline.process(question);
                long elapsed = System.currentTimeMillis() - start;

                QaMetrics m = result.metrics;
                convMetrics.add(m);

                // Print compact result
                System.out.printf("  Tier: %s | Hit: %s | TTFT: %dms | Total: %dms | LLM: %d calls%n",
                        m.dataTier, m.retrievalHit, m.ttftMs, m.totalTimeMs, m.llmCallCount);
                System.out.printf("  Entities: %d | Recipes: %d | Vectors: %d (maxScore=%.3f)%n",
                        m.entityCount, m.recipeResultCount, m.vectorResultCount, m.vectorMaxScore);

                // Print first 3 lines of answer
                if (result.answer != null) {
                    String[] lines = result.answer.split("\n");
                    for (int j = 0; j < Math.min(3, lines.length); j++) {
                        String line = lines[j];
                        if (line.length() > 120) line = line.substring(0, 120) + "...";
                        System.out.println("  A: " + line);
                    }
                    if (lines.length > 3) System.out.println("     ... (" + lines.length + " lines total)");
                }

                // Brief pause between turns to avoid API rate limits
                if (i < turns.size() - 1) {
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                }
            }

            report.conversationResults.add(new ConversationResult(name, convMetrics));
        }

        report.endTime = Instant.now();
        report.computeSummary();

        return report;
    }

    /** Run a single question and return metrics */
    public QaMetrics runSingle(String question) {
        pipeline.clearHistory();
        long start = System.currentTimeMillis();
        QaPipeline.QaResult result = pipeline.process(question);
        return result.metrics;
    }

    // ==================== Report classes ====================

    public static class TestReport {
        public String testFile;
        public Instant startTime;
        public Instant endTime;
        public int totalTurns;
        public int totalHits;
        public int totalFallbacks;
        public double hitRate;
        public long avgTotalMs;
        public long avgTtftMs;
        public double avgVectorMaxScore;
        public long totalTokensIn;
        public long totalTokensOut;
        public Map<String, Integer> tierDistribution = new LinkedHashMap<>();
        public List<ConversationResult> conversationResults = new ArrayList<>();

        void computeSummary() {
            List<QaMetrics> all = new ArrayList<>();
            for (var cr : conversationResults) {
                all.addAll(cr.metrics);
                totalTurns += cr.metrics.size();
            }

            totalHits = (int) all.stream().filter(m -> m.retrievalHit).count();
            totalFallbacks = (int) all.stream().filter(m -> m.fallbackUsed).count();
            hitRate = totalTurns > 0 ? (double) totalHits / totalTurns : 0;
            avgTotalMs = totalTurns > 0 ? (long) all.stream().mapToLong(m -> m.totalTimeMs).average().orElse(0) : 0;
            avgTtftMs = totalTurns > 0 ? (long) all.stream().mapToLong(m -> m.ttftMs).average().orElse(0) : 0;
            avgVectorMaxScore = totalTurns > 0 ? all.stream().mapToDouble(m -> m.vectorMaxScore).average().orElse(0) : 0;
            totalTokensIn = all.stream().mapToLong(m -> m.tokenEstimateInput).sum();
            totalTokensOut = all.stream().mapToLong(m -> m.tokenEstimateOutput).sum();

            tierDistribution = new LinkedHashMap<>();
            for (QaMetrics m : all) {
                String tier = m.dataTier != null ? m.dataTier : "TIER D";
                tierDistribution.merge(tier, 1, Integer::sum);
            }
        }

        public String toTextReport() {
            StringBuilder sb = new StringBuilder();
            sb.append("\n══════════════════════════════════════════════════\n");
            sb.append("           AUTO TEST REPORT\n");
            sb.append("══════════════════════════════════════════════════\n\n");
            sb.append(String.format("Test File:   %s\n", testFile));
            sb.append(String.format("Duration:    %d seconds\n",
                    (endTime.toEpochMilli() - startTime.toEpochMilli()) / 1000));
            sb.append(String.format("Total Turns: %d\n\n", totalTurns));

            sb.append("--- Overall Metrics ---\n");
            sb.append(String.format("  Hit Rate:        %.0f%% (%d/%d)\n", hitRate * 100, totalHits, totalTurns));
            sb.append(String.format("  Avg Total Time:  %d ms\n", avgTotalMs));
            sb.append(String.format("  Avg TTFT:        %d ms\n", avgTtftMs));
            sb.append(String.format("  Avg Vec MaxScore: %.3f\n", avgVectorMaxScore));
            sb.append(String.format("  Fallbacks:       %d\n", totalFallbacks));
            sb.append(String.format("  Tokens:          %d in / %d out\n", totalTokensIn, totalTokensOut));
            sb.append("  Tiers: ");
            tierDistribution.forEach((t, c) -> sb.append(t).append("=").append(c).append(" "));
            sb.append("\n\n");

            sb.append("--- Per-Conversation Breakdown ---\n");
            for (var cr : conversationResults) {
                List<QaMetrics> metrics = cr.metrics;
                long avgMs = metrics.isEmpty() ? 0 :
                        (long) metrics.stream().mapToLong(m -> m.totalTimeMs).average().orElse(0);
                int hits = (int) metrics.stream().filter(m -> m.retrievalHit).count();
                sb.append(String.format("\n  [%s] %d turns, %d hits, avg %dms\n", cr.name, metrics.size(), hits, avgMs));
                for (int i = 0; i < metrics.size(); i++) {
                    QaMetrics m = metrics.get(i);
                    sb.append(String.format("    Turn %d: tier=%s hit=%s ttft=%dms total=%dms vecMax=%.3f entities=%d recipes=%d\n",
                            i + 1, m.dataTier, m.retrievalHit, m.ttftMs, m.totalTimeMs,
                            m.vectorMaxScore, m.entityCount, m.recipeResultCount));
                }
            }

            return sb.toString();
        }

        /** Save report to a file */
        public void saveToFile(Path outputPath) throws IOException {
            Files.writeString(outputPath, toTextReport());
            log.info("Test report saved to {}", outputPath);
        }

        /** Save detailed CSV to a file */
        public void saveCsv(Path outputPath) throws IOException {
            try (BufferedWriter w = Files.newBufferedWriter(outputPath)) {
                w.write(QaMetrics.csvHeader());
                w.newLine();
                for (var cr : conversationResults) {
                    for (QaMetrics m : cr.metrics) {
                        w.write(m.toCsvRow());
                        w.newLine();
                    }
                }
            }
            log.info("Test CSV saved to {}", outputPath);
        }
    }

    public record ConversationResult(String name, List<QaMetrics> metrics) {}
}
