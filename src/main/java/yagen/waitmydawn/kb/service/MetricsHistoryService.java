package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yagen.waitmydawn.kb.dto.QaMetrics;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 指标历史持久化服务。
 * 每个 QA 请求的指标以 JSONL 格式（每行一个 JSON 对象，不换行）
 * 保存到 {dataDir}/metrics/ 目录。
 */
public class MetricsHistoryService {

    private static final Logger log = LoggerFactory.getLogger(MetricsHistoryService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final Path metricsDir;

    public MetricsHistoryService(Path dataDir) {
        this.metricsDir = dataDir.resolve("metrics");
        try { Files.createDirectories(metricsDir); } catch (Exception ignored) {}
    }

    /**
     * Read all JSON objects from a file, supporting both:
     *  - Single-line JSONL (one JSON per line) — current format
     *  - Multi-line pretty-printed JSON — legacy format from INDENT_OUTPUT
     *
     * Detection: if the first non-blank line is exactly "{", it's multi-line.
     * Otherwise it's JSONL (each non-blank line is a complete JSON object).
     */
    private List<Map<String, Object>> readJsonObjects(File file) throws IOException {
        List<String> allLines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) allLines.add(line);
        }

        // Find the first non-blank line to detect format
        String firstJson = null;
        for (String line : allLines) {
            String t = line.trim();
            if (!t.isEmpty()) { firstJson = t; break; }
        }

        if (firstJson == null) return List.of();

        // JSONL: first non-blank line is a complete JSON object
        if (!"{".equals(firstJson)) {
            return parseJsonl(allLines);
        }

        // Legacy multi-line: brace-balanced blocks
        return parseMultiLineJson(allLines);
    }

    private List<Map<String, Object>> parseJsonl(List<String> allLines) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String line : allLines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = mapper.readValue(trimmed, Map.class);
                result.add(map);
            } catch (Exception ignored) {}
        }
        return result;
    }

    private List<Map<String, Object>> parseMultiLineJson(List<String> allLines) {
        List<Map<String, Object>> result = new ArrayList<>();
        int braceDepth = 0;
        StringBuilder buf = new StringBuilder();
        for (String line : allLines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() && braceDepth == 0) continue;

            for (char c : trimmed.toCharArray()) {
                if (c == '{') braceDepth++;
                else if (c == '}') braceDepth--;
            }

            if (braceDepth > 0 || trimmed.startsWith("{") || buf.length() > 0) {
                if (buf.length() > 0) buf.append(' ');
                buf.append(trimmed);
            }

            if (braceDepth == 0 && buf.length() > 0) {
                String json = buf.toString().trim();
                if (json.startsWith("{") && json.endsWith("}")) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = mapper.readValue(json, Map.class);
                        result.add(map);
                    } catch (Exception ignored) {}
                }
                buf.setLength(0);
            }
        }
        return result;
    }

    /** Save a single QA metrics entry to today's JSONL file */
    public void save(QaMetrics m) {
        try {
            String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            Path file = metricsDir.resolve("qa_metrics_" + dateStr + ".jsonl");
            String json = mapper.writeValueAsString(toMap(m));
            try (BufferedWriter w = new BufferedWriter(new FileWriter(file.toFile(), true))) {
                w.write(json);
                w.newLine();
            }
        } catch (Exception e) {
            log.warn("Failed to save metrics: {}", e.getMessage());
        }
    }

    /** Load all metrics from a specific date */
    public List<QaMetrics> loadDate(LocalDate date) {
        String dateStr = date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        Path file = metricsDir.resolve("qa_metrics_" + dateStr + ".jsonl");
        return loadFile(file);
    }

    /** Load all metrics from all dates */
    public List<QaMetrics> loadAll() {
        List<QaMetrics> all = new ArrayList<>();
        File[] files = metricsDir.toFile().listFiles(
                f -> f.getName().startsWith("qa_metrics_") && f.getName().endsWith(".jsonl"));
        if (files != null) {
            for (File f : files) {
                all.addAll(loadFile(f.toPath()));
            }
        }
        all.sort(Comparator.comparing(m -> m.timestamp != null ? m.timestamp : Instant.EPOCH));
        return all;
    }

    /** Load metrics from the last N days (inclusive of today) */
    public List<QaMetrics> loadRecentDays(int days) {
        List<QaMetrics> all = new ArrayList<>();
        LocalDate cutoff = LocalDate.now().minusDays(days - 1);
        File[] files = metricsDir.toFile().listFiles(
                f -> f.getName().startsWith("qa_metrics_") && f.getName().endsWith(".jsonl"));
        if (files != null) {
            for (File f : files) {
                String name = f.getName();
                String dateStr = name.substring("qa_metrics_".length(), name.length() - ".jsonl".length());
                LocalDate fileDate;
                try { fileDate = LocalDate.parse(dateStr); } catch (Exception e) { continue; }
                if (fileDate.isBefore(cutoff)) continue;

                List<QaMetrics> fileMetrics = loadFile(f.toPath());
                all.addAll(fileMetrics);
                log.debug("Loaded {} entries from {} (date: {})", fileMetrics.size(), name, dateStr);
            }
        }
        all.sort(Comparator.comparing(m -> m.timestamp != null ? m.timestamp : Instant.EPOCH));
        log.info("loadRecentDays({}) → {} entries (cutoff: {})", days, all.size(), cutoff);
        return all;
    }

    /** Load the most recent N metric entries across all dates */
    public List<QaMetrics> loadRecent(int count) {
        List<QaMetrics> all = new ArrayList<>();
        File[] files = metricsDir.toFile().listFiles(
                f -> f.getName().startsWith("qa_metrics_") && f.getName().endsWith(".jsonl"));
        if (files != null) {
            // Process files in reverse chronological order (newest first)
            Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
            for (File f : files) {
                List<QaMetrics> fileMetrics = loadFile(f.toPath());
                // Entries are in chronological order within a file, take from end
                for (int i = fileMetrics.size() - 1; i >= 0 && all.size() < count; i--) {
                    all.add(fileMetrics.get(i));
                }
                if (all.size() >= count) break;
            }
        }
        all.sort(Comparator.comparing(m -> m.timestamp != null ? m.timestamp : Instant.EPOCH));
        return all;
    }

    /** Load all QaMetrics from a single JSONL file (handles both single-line and multi-line formats) */
    private List<QaMetrics> loadFile(Path filePath) {
        List<QaMetrics> list = new ArrayList<>();
        if (!Files.exists(filePath)) return list;

        try {
            List<Map<String, Object>> objects = readJsonObjects(filePath.toFile());
            for (Map<String, Object> map : objects) {
                try {
                    list.add(fromMap(map));
                } catch (Exception ignored) {
                    // Skip individual malformed entries
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load metrics from {}: {}", filePath.getFileName(), e.getMessage());
        }
        return list;
    }

    /** Get aggregate summary across a list of metrics */
    public static MetricsSummary summarize(List<QaMetrics> metrics) {
        MetricsSummary s = new MetricsSummary();
        if (metrics.isEmpty()) return s;

        s.totalRequests = metrics.size();
        s.hits = (int) metrics.stream().filter(m -> m.retrievalHit).count();
        s.fallbacks = (int) metrics.stream().filter(m -> m.fallbackUsed).count();
        s.hitRate = (double) s.hits / s.totalRequests;

        // Latency stats
        LongSummaryStatistics totalLat = metrics.stream()
                .mapToLong(m -> m.totalTimeMs).summaryStatistics();
        s.avgTotalMs = (long) totalLat.getAverage();
        s.minTotalMs = totalLat.getMin();
        s.maxTotalMs = totalLat.getMax();

        LongSummaryStatistics ttftLat = metrics.stream()
                .mapToLong(m -> m.ttftMs).summaryStatistics();
        s.avgTtftMs = (long) ttftLat.getAverage();

        // Vector quality
        s.avgVectorMaxScore = metrics.stream()
                .mapToDouble(m -> m.vectorMaxScore).average().orElse(0);
        s.avgVectorRelevant = metrics.stream()
                .mapToInt(m -> m.vectorRelevantCount).average().orElse(0);

        // Tokens
        s.totalTokensIn = metrics.stream().mapToLong(m -> m.tokenEstimateInput).sum();
        s.totalTokensOut = metrics.stream().mapToLong(m -> m.tokenEstimateOutput).sum();
        s.avgLlmCalls = metrics.stream().mapToInt(m -> m.llmCallCount).average().orElse(0);

        // Tiers
        for (QaMetrics m : metrics) {
            String tier = m.dataTier;
            if ("TIER A".equals(tier)) s.tierA++;
            else if ("TIER B".equals(tier)) s.tierB++;
            else if ("TIER C".equals(tier)) s.tierC++;
            else s.tierD++;
        }

        // Category distribution
        Map<String, Long> catCounts = new LinkedHashMap<>();
        for (QaMetrics m : metrics) {
            String cat = m.classifyCategory != null ? m.classifyCategory : "unknown";
            catCounts.merge(cat, 1L, Long::sum);
        }
        s.categoryDistribution = catCounts;

        return s;
    }

    /** Get list of available metric dates */
    public List<String> listDates() {
        List<String> dates = new ArrayList<>();
        File[] files = metricsDir.toFile().listFiles(f -> f.getName().startsWith("qa_metrics_") && f.getName().endsWith(".jsonl"));
        if (files != null) {
            for (File f : files) {
                String name = f.getName();
                String date = name.substring("qa_metrics_".length(), name.length() - ".jsonl".length());
                dates.add(date);
            }
        }
        Collections.sort(dates);
        return dates;
    }

    // --- Serialization helpers ---

    private Map<String, Object> toMap(QaMetrics m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("timestamp", m.timestamp != null ? m.timestamp.toString() : Instant.now().toString());
        map.put("sessionId", m.sessionId);
        map.put("conversationTurn", m.conversationTurn);
        map.put("totalTimeMs", m.totalTimeMs);
        map.put("ttftMs", m.ttftMs);
        map.put("classifyTimeMs", m.classifyTimeMs);
        map.put("entityResolveTimeMs", m.entityResolveTimeMs);
        map.put("urlMatchTimeMs", m.urlMatchTimeMs);
        map.put("incrementalFetchTimeMs", m.incrementalFetchTimeMs);
        map.put("vectorSearchTimeMs", m.vectorSearchTimeMs);
        map.put("recipeSearchTimeMs", m.recipeSearchTimeMs);
        map.put("rerankTimeMs", m.rerankTimeMs);
        map.put("answerGenTimeMs", m.answerGenTimeMs);
        map.put("classifyLlmMs", m.classifyLlmMs);
        map.put("entityLlmMs", m.entityLlmMs);
        map.put("urlLlmMs", m.urlLlmMs);
        map.put("answerLlmMs", m.answerLlmMs);
        map.put("rerankLlmMs", m.rerankLlmMs);
        map.put("fallbackLlmMs", m.fallbackLlmMs);
        map.put("dataTier", m.dataTier);
        map.put("retrievalHit", m.retrievalHit);
        map.put("entityResolved", m.entityResolved);
        map.put("entityCount", m.entityCount);
        map.put("subPageUrlCount", m.subPageUrlCount);
        map.put("vectorResultCount", m.vectorResultCount);
        map.put("vectorRelevantCount", m.vectorRelevantCount);
        map.put("vectorMaxScore", m.vectorMaxScore);
        map.put("vectorAvgScore", m.vectorAvgScore);
        map.put("recipeResultCount", m.recipeResultCount);
        map.put("incrementalChunksAcquired", m.incrementalChunksAcquired);
        map.put("contextChunksUsed", m.contextChunksUsed);
        map.put("contextUtilization", m.contextUtilization);
        map.put("llmCallCount", m.llmCallCount);
        map.put("tokenEstimateInput", m.tokenEstimateInput);
        map.put("tokenEstimateOutput", m.tokenEstimateOutput);
        map.put("dbQueryCount", m.dbQueryCount);
        map.put("fallbackUsed", m.fallbackUsed);
        map.put("classifyCategory", m.classifyCategory);
        map.put("classifyConfidence", m.classifyConfidence);
        map.put("answerLength", m.answerLength);
        map.put("questionHash", m.questionHash);
        return map;
    }

    private QaMetrics fromMap(Map<String, Object> map) {
        QaMetrics m = new QaMetrics();
        try { m.timestamp = Instant.parse(str(map, "timestamp")); } catch (Exception e) { m.timestamp = Instant.EPOCH; }
        m.sessionId = str(map, "sessionId");
        m.conversationTurn = i(map, "conversationTurn");
        m.totalTimeMs = l(map, "totalTimeMs");
        m.ttftMs = l(map, "ttftMs");
        m.classifyTimeMs = l(map, "classifyTimeMs");
        m.entityResolveTimeMs = l(map, "entityResolveTimeMs");
        m.urlMatchTimeMs = l(map, "urlMatchTimeMs");
        m.incrementalFetchTimeMs = l(map, "incrementalFetchTimeMs");
        m.vectorSearchTimeMs = l(map, "vectorSearchTimeMs");
        m.recipeSearchTimeMs = l(map, "recipeSearchTimeMs");
        m.rerankTimeMs = l(map, "rerankTimeMs");
        m.answerGenTimeMs = l(map, "answerGenTimeMs");
        m.classifyLlmMs = l(map, "classifyLlmMs");
        m.entityLlmMs = l(map, "entityLlmMs");
        m.urlLlmMs = l(map, "urlLlmMs");
        m.answerLlmMs = l(map, "answerLlmMs");
        m.rerankLlmMs = l(map, "rerankLlmMs");
        m.fallbackLlmMs = l(map, "fallbackLlmMs");
        m.dataTier = str(map, "dataTier");
        m.retrievalHit = b(map, "retrievalHit");
        m.entityResolved = b(map, "entityResolved");
        m.entityCount = i(map, "entityCount");
        m.subPageUrlCount = i(map, "subPageUrlCount");
        m.vectorResultCount = i(map, "vectorResultCount");
        m.vectorRelevantCount = i(map, "vectorRelevantCount");
        m.vectorMaxScore = d(map, "vectorMaxScore");
        m.vectorAvgScore = d(map, "vectorAvgScore");
        m.recipeResultCount = i(map, "recipeResultCount");
        m.incrementalChunksAcquired = i(map, "incrementalChunksAcquired");
        m.contextChunksUsed = i(map, "contextChunksUsed");
        m.contextUtilization = d(map, "contextUtilization");
        m.llmCallCount = i(map, "llmCallCount");
        m.tokenEstimateInput = i(map, "tokenEstimateInput");
        m.tokenEstimateOutput = i(map, "tokenEstimateOutput");
        m.dbQueryCount = i(map, "dbQueryCount");
        m.fallbackUsed = b(map, "fallbackUsed");
        m.classifyCategory = str(map, "classifyCategory");
        m.classifyConfidence = (float) d(map, "classifyConfidence");
        m.answerLength = i(map, "answerLength");
        m.questionHash = str(map, "questionHash");
        return m;
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v != null ? v.toString() : null;
    }
    private static long l(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Number n ? n.longValue() : 0;
    }
    private static int i(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Number n ? n.intValue() : 0;
    }
    private static double d(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Number n ? n.doubleValue() : 0;
    }
    private static boolean b(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Boolean b ? b : false;
    }

    /** Aggregated metrics summary */
    public static class MetricsSummary {
        public int totalRequests;
        public int hits;
        public int fallbacks;
        public double hitRate;
        public long avgTotalMs;
        public long minTotalMs;
        public long maxTotalMs;
        public long avgTtftMs;
        public double avgVectorMaxScore;
        public double avgVectorRelevant;
        public long totalTokensIn;
        public long totalTokensOut;
        public double avgLlmCalls;
        public int tierA, tierB, tierC, tierD;
        public Map<String, Long> categoryDistribution = new LinkedHashMap<>();
        public Map<String, MetricsSummary> perDay = new LinkedHashMap<>();

        public String toTextReport() {
            StringBuilder sb = new StringBuilder();
            sb.append("══════════ Metrics Summary ═══════════\n\n");
            sb.append(String.format("Total Requests: %d  Hits: %d  Hit Rate: %.0f%%  Fallbacks: %d\n",
                    totalRequests, hits, hitRate * 100, fallbacks));
            sb.append(String.format("Latency — Avg: %dms  Min: %dms  Max: %dms  Avg TTFT: %dms\n",
                    avgTotalMs, minTotalMs, maxTotalMs, avgTtftMs));
            sb.append(String.format("Tiers — A: %d  B: %d  C: %d  D: %d\n",
                    tierA, tierB, tierC, tierD));
            sb.append(String.format("Vector — Avg Max Score: %.3f  Avg Relevant: %.1f\n",
                    avgVectorMaxScore, avgVectorRelevant));
            sb.append(String.format("Tokens — In: %d  Out: %d  Avg LLM Calls: %.1f\n",
                    totalTokensIn, totalTokensOut, avgLlmCalls));
            if (!categoryDistribution.isEmpty()) {
                sb.append("Categories: ");
                categoryDistribution.forEach((cat, cnt) -> sb.append(cat).append("=").append(cnt).append(" "));
                sb.append("\n");
            }
            if (!perDay.isEmpty()) {
                sb.append("\n--- Per-Day Breakdown ---\n");
                perDay.forEach((day, sum) ->
                        sb.append(String.format("  %s: %d reqs, %.0f%% hit, %dms avg\n",
                                day, sum.totalRequests, sum.hitRate * 100, sum.avgTotalMs)));
            }
            return sb.toString();
        }
    }
}
