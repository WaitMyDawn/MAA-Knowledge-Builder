package yagen.waitmydawn.kb.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yagen.waitmydawn.kb.agent.AnswerAgent;
import yagen.waitmydawn.kb.agent.AnswerAgent.AnswerContext;
import yagen.waitmydawn.kb.agent.AnswerAgent.RecipeMatch;
import yagen.waitmydawn.kb.agent.ClassifyAgent;
import yagen.waitmydawn.kb.agent.EntityAgent;
import yagen.waitmydawn.kb.agent.EntityAgent.ResolvedEntity;
import yagen.waitmydawn.kb.agent.UrlAgent;
import yagen.waitmydawn.kb.dto.ClassificationResult;
import yagen.waitmydawn.kb.dto.QaMetrics;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import yagen.waitmydawn.kb.service.WikiScraperService.McmodCategory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.*;

/**
 * Agentic RAG QA Pipeline with comprehensive metrics collection.
 *
 *   Agent 1 — ClassifyAgent: LLM classifies question -> McmodCategory
 *   Agent 2 — EntityAgent:   LLM resolves entity registry names
 *   Agent 2.5 — UrlAgent:    LLM matches entities to sub-page URLs
 *   Incremental fetch:       Acquires MC百科 sub-pages into session DB
 *   Dual-DB vector search:   Base + incremental cosine similarity
 *   LLM Rerank (optional):   LLM filters relevant vector results
 *   Recipe search:           SQL LIKE on rag_recipe
 *   Agent 3 — AnswerAgent:   LLM composes Markdown answer (with fallback)
 */
public class QaPipeline {

    private static final Logger log = LoggerFactory.getLogger(QaPipeline.class);

    private final ClassifyAgent classifyAgent;
    private final EntityAgent entityAgent;
    private final UrlAgent urlAgent;
    private final AnswerAgent answerAgent;
    private final RagAgentService llm;
    private final VectorStore baseVectorStore;
    private final EmbeddingService embedder;
    private final DatabaseBuilder baseDb;
    private final MultiDBManager dbManager;
    private final IncrementalKnowledgeService incremental;
    private final MetricsHistoryService metricsHistory;

    // Conversation history: last N Q&A pairs for context
    private static final int MAX_CONTEXT_PAIRS = 5;
    private final List<QAPair> conversationHistory = new ArrayList<>();
    private int conversationTurn = 0;

    public QaPipeline(ClassifyAgent classifyAgent, EntityAgent entityAgent,
                      UrlAgent urlAgent, AnswerAgent answerAgent,
                      RagAgentService llm, VectorStore baseVectorStore,
                      EmbeddingService embedder, DatabaseBuilder baseDb,
                      MultiDBManager dbManager) {
        this.classifyAgent = classifyAgent;
        this.entityAgent = entityAgent;
        this.urlAgent = urlAgent;
        this.answerAgent = answerAgent;
        this.llm = llm;
        this.baseVectorStore = baseVectorStore;
        this.embedder = embedder;
        this.baseDb = baseDb;
        this.dbManager = dbManager;
        this.incremental = new IncrementalKnowledgeService(baseDb);
        this.metricsHistory = new MetricsHistoryService(
                baseDb.getJdbcUrl().contains("file:")
                    ? java.nio.file.Path.of(baseDb.getJdbcUrl()
                        .replaceAll(".*file:", "")
                        .replaceAll(";.*", ""))
                        .getParent()
                    : java.nio.file.Path.of("."));
    }

    // ==================== Main entry point ====================

    public QaResult process(String question) {
        long t0 = System.nanoTime();
        conversationTurn++;
        QaMetrics m = new QaMetrics();
        m.timestamp = Instant.now();
        m.conversationTurn = conversationTurn;
        m.questionHash = sha256hex(question);
        m.sessionId = dbManager.getCurrentSessionId();

        incremental.resetCache();
        QaResult result = new QaResult();

        // Build conversation context string
        String convContext = buildConversationContext();

        // === Agent 1: Classify (with conversation context) ===
        long t1 = System.nanoTime();
        ClassifyAgent.Classification cls = classifyAgent.classify(question, convContext);
        m.classifyTimeMs = (System.nanoTime() - t1) / 1_000_000;
        m.classifyLlmMs = m.classifyTimeMs; // ClassifyAgent = pure LLM call
        m.recordLlmCall(estimatePromptTokens(question) + 200, 20);

        McmodCategory category = cls.category();
        result.questionType = ClassificationResult.toQuestionType(category);
        result.mcmodCategory = category;
        m.classifyCategory = category != null ? category.getName() : "GENERAL";
        m.classifyConfidence = cls.confidence();
        m.addTrace("Classify", m.classifyTimeMs, m.classifyCategory);

        log.info("QA[Classify] '{}' -> {} (conf={}) [{}ms]",
                question, category != null ? category.name() : "GENERAL", cls.confidence(), m.classifyTimeMs);

        // === Agent 2: Resolve entities with entity registry hints ===
        long t2 = System.nanoTime();
        List<String> entityHints = List.of();
        if (category == McmodCategory.ENTITY) {
            entityHints = gatherEntityHints(result);
        }

        List<ResolvedEntity> resolvedEntities = entityAgent.resolve(question, convContext, entityHints);
        m.entityResolveTimeMs = (System.nanoTime() - t2) / 1_000_000;
        m.entityLlmMs = m.entityResolveTimeMs; // EntityAgent = pure LLM call
        m.entityCount = resolvedEntities.size();
        m.recordLlmCall(estimatePromptTokens(question) + estimateModListTokens() + entityHints.size() * 10, 30);

        // Agent 2.5: UrlAgent — match entity descriptions to subWebPage cn(en)->URL keys
        long t25 = System.nanoTime();
        Map<String, String> subPageUrls = matchSubPagesByEntity(resolvedEntities);
        m.urlMatchTimeMs = (System.nanoTime() - t25) / 1_000_000;
        m.urlLlmMs = m.urlMatchTimeMs;
        m.subPageUrlCount = subPageUrls.size();
        // UrlAgent may call LLM — track it
        if (m.urlMatchTimeMs > 50) {
            m.recordLlmCall(resolvedEntities.size() * 20, 15);
        }

        m.addTrace("EntityResolve", m.entityResolveTimeMs + m.urlMatchTimeMs,
                String.format("%d entities, %d URLs", m.entityCount, m.subPageUrlCount));

        log.info("QA[Entity] {} entities ({} hints), {} sub-page URLs [{}ms]",
                resolvedEntities.size(), entityHints.size(), subPageUrls.size(), m.entityResolveTimeMs);

        result.resolvedEntities = resolvedEntities;
        result.subPageUrls = subPageUrls;

        // === Incremental fetch ===
        long t3 = System.nanoTime();
        String incInfo = null;
        if (!subPageUrls.isEmpty()) {
            IncrementalDB incDb = dbManager.getCurrentIncDB();
            if (incDb != null) {
                incremental.setCurrentDB(incDb);
                incInfo = incremental.acquireFromSubPages(subPageUrls, embedder);
                if (incInfo != null) {
                    log.info("QA[Incremental] {}", incInfo);
                    // Count acquired chunks from the info string
                    m.incrementalChunksAcquired = countChunksFromInfo(incInfo);
                }
            }
        }
        m.incrementalFetchTimeMs = (System.nanoTime() - t3) / 1_000_000;
        m.addTrace("IncrementalFetch", m.incrementalFetchTimeMs,
                String.format("%d URLs, %d chunks", subPageUrls.size(), m.incrementalChunksAcquired));

        // === Vector semantic search ===
        long t4 = System.nanoTime();
        List<VectorStore.SearchResult> vectorResults = List.of();
        List<DatabaseBuilder> activeDbs = dbManager.getActiveDatabases();
        if (!activeDbs.isEmpty()) {
            float[] qVec = embedder.embed(question);
            vectorResults = VectorStore.searchAcross(activeDbs, qVec, 20);
        }
        m.vectorSearchTimeMs = (System.nanoTime() - t4) / 1_000_000;
        m.vectorResultCount = vectorResults.size();
        m.computeVectorRelevance(vectorResults, 0.2);
        m.dbQueryCount += activeDbs.size();
        m.addTrace("VectorSearch", m.vectorSearchTimeMs,
                String.format("%d results (%d DBs), maxScore=%.3f", m.vectorResultCount, activeDbs.size(), m.vectorMaxScore));

        // === Recipe search ===
        long t5 = System.nanoTime();
        List<RecipeMatch> recipeResults = findRecipes(resolvedEntities);
        if (recipeResults.isEmpty() && resolvedEntities.isEmpty()) {
            log.info("QA[Fallback] EntityAgent returned 0 entities, searching recipes by text");
            recipeResults = findRecipesByText(question);
            if (recipeResults.isEmpty() && llm != null) {
                long tLlm = System.nanoTime();
                recipeResults = findRecipesByLlmExtraction(question);
                m.recordLlmCall(50, 20);
                m.recipeLlmMs = (System.nanoTime() - tLlm) / 1_000_000;
            }
        }
        m.recipeSearchTimeMs = (System.nanoTime() - t5) / 1_000_000;
        m.recipeResultCount = recipeResults.size();
        m.dbQueryCount += Math.max(1, resolvedEntities.size() * 2);
        m.addTrace("RecipeSearch", m.recipeSearchTimeMs,
                String.format("%d recipes found", m.recipeResultCount));

        // === LLM Rerank ===
        m.rerankTimeMs = 0;
        if (vectorResults.size() > 10 && llm != null) {
            long tr = System.nanoTime();
            List<VectorStore.SearchResult> reranked = rerankWithLLM(question, vectorResults);
            m.rerankLlmMs = (System.nanoTime() - tr) / 1_000_000;
            m.rerankTimeMs = m.rerankLlmMs;
            m.recordLlmCall(vectorResults.size() * 100, 10);
            if (reranked != null && reranked.size() < vectorResults.size()) {
                vectorResults = reranked;
            }
            m.addTrace("Rerank", m.rerankTimeMs, String.format("%d -> %d", m.vectorResultCount, vectorResults.size()));
        }

        result.recipeResults = recipeResults;
        result.vectorResults = vectorResults;
        result.incrementalInfo = incInfo;
        result.recipesToRender = recipeResults;

        // === Detect data tier (matching AnswerAgent's logic) ===
        boolean hasRecipes = !recipeResults.isEmpty();
        boolean hasRelevantVectors = vectorResults.stream().anyMatch(v -> v.score() > 0.3);
        boolean hasEntities = !resolvedEntities.isEmpty();
        boolean hasAnyVectors = !vectorResults.isEmpty();

        if (hasRecipes && hasRelevantVectors) m.dataTier = "TIER A";
        else if (hasEntities && (hasAnyVectors || hasRecipes)) m.dataTier = "TIER B";
        else if (hasEntities || hasAnyVectors) m.dataTier = "TIER C";
        else m.dataTier = "TIER D";

        // Count context chunks that will be used (AnswerAgent uses top 8 above 0.1)
        m.contextChunksUsed = (int) vectorResults.stream()
                .filter(v -> v.score() > 0.1).limit(8).count();

        // === Agent 3: Compose answer (with LLM fallback) ===
        long t6 = System.nanoTime();
        List<AnswerAgent.RecipeMatch> answerRecipeMatches = recipeResults.stream()
                .map(rm -> new AnswerAgent.RecipeMatch(rm.recipeJson(), rm.outputItem(), rm.sourceMod()))
                .toList();

        boolean hasRelevantData = !recipeResults.isEmpty()
                || vectorResults.stream().anyMatch(v -> v.score() > 0.2);

        AnswerContext ctx = new AnswerContext(
                question, category, resolvedEntities, answerRecipeMatches,
                vectorResults, incInfo);

        long tAnsLlm = System.nanoTime();
        result.answer = answerAgent.compose(ctx);
        m.answerLlmMs = (System.nanoTime() - tAnsLlm) / 1_000_000;

        // If data is sparse (Tier C/D), supplement with LLM fallback
        m.fallbackUsed = false;
        if (!hasRelevantData && llm != null) {
            long tFb = System.nanoTime();
            String fallback = answerWithFallback(question, category, resolvedEntities,
                    vectorResults, recipeResults, convContext);
            m.fallbackLlmMs = (System.nanoTime() - tFb) / 1_000_000;
            if (fallback != null) {
                result.answer = fallback;
                m.fallbackUsed = true;
            }
        }
        m.answerGenTimeMs = (System.nanoTime() - t6) / 1_000_000;
        m.recordLlmCall(estimateContextTokens(ctx), estimateAnswerTokens(result.answer));
        m.answerLength = result.answer != null ? result.answer.length() : 0;

        m.addTrace("AnswerGen", m.answerGenTimeMs, m.fallbackUsed ? "LLM fallback" : "RAG compose");

        // === Compute composite indicators ===
        m.computeTtft();
        m.computeQualityIndicators();

        // === Totals ===
        m.totalTimeMs = (System.nanoTime() - t0) / 1_000_000;

        // Accumulate session stats
        m.accumulate();
        result.metrics = m;

        log.info("QA {}", m.toLogString());

        // Persist metrics
        try { metricsHistory.save(m); } catch (Exception e) {
            log.debug("Metrics save failed: {}", e.getMessage());
        }

        // Save to conversation history
        conversationHistory.add(new QAPair(question, result.answer));
        while (conversationHistory.size() > MAX_CONTEXT_PAIRS) {
            conversationHistory.remove(0);
        }

        return result;
    }

    // ==================== Conversation context ====================

    private String buildConversationContext() {
        if (conversationHistory.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("[Previous Conversation]\n");
        for (int i = 0; i < conversationHistory.size(); i++) {
            QAPair pair = conversationHistory.get(i);
            String shortQ = pair.question.length() > 80 ? pair.question.substring(0, 80) + "..." : pair.question;
            String shortA = extractKeyInfo(pair.answer);
            sb.append(String.format("Q%d: %s\nA%d: %s\n", i + 1, shortQ, i + 1, shortA));
        }
        return sb.toString();
    }

    /** Extract key info from answer for context (entity names, mod info) */
    private String extractKeyInfo(String answer) {
        if (answer == null || answer.isBlank()) return "(no answer)";
        StringBuilder key = new StringBuilder();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("`([a-z_]+:[a-z_]+)`").matcher(answer);
        while (m.find()) key.append(m.group(1)).append(" ");
        if (key.isEmpty()) {
            String firstLine = answer.split("\n")[0];
            if (firstLine.length() > 120) firstLine = firstLine.substring(0, 120) + "...";
            key.append(firstLine);
        }
        return key.toString().trim();
    }

    // ==================== Entity registry hints ====================

    private List<String> gatherEntityHints(QaResult result) {
        Set<String> modIds = new LinkedHashSet<>();
        modIds.add("minecraft");

        for (ResolvedEntity e : result.resolvedEntities) {
            if (!"unknown".equals(e.modId())) modIds.add(e.modId());
        }
        if (modIds.size() <= 1) {
            try {
                List<yagen.waitmydawn.kb.model.ModEntry> mods = baseDb.findAllModEntries();
                for (var m : mods) modIds.add(m.getModId());
            } catch (Exception ignored) {}
        }

        List<String> allHints = new ArrayList<>();
        for (String modId : modIds) {
            allHints.addAll(entityAgent.getEntityRegistries(modId));
        }
        return allHints;
    }

    // ==================== Sub-page URL matching (UrlAgent) ====================

    private Map<String, String> matchSubPagesByEntity(List<ResolvedEntity> entities) {
        Map<String, String> urls = new LinkedHashMap<>();
        if (entities.isEmpty()) return urls;

        Map<String, List<String>> byModId = new LinkedHashMap<>();
        for (ResolvedEntity e : entities) {
            if ("minecraft".equals(e.modId()) || "unknown".equals(e.modId())) continue;
            byModId.computeIfAbsent(e.modId(), k -> new ArrayList<>())
                    .add(e.reason().isEmpty() ? e.registry() : e.reason());
        }

        for (var entry : byModId.entrySet()) {
            String modId = entry.getKey();
            List<String> descriptions = entry.getValue();
            Map<String, String> matched = urlAgent.matchUrls(descriptions, modId);
            if (matched != null) urls.putAll(matched);
        }

        Map<String, String> exactUrls = entityAgent.lookupSubPages(entities);
        for (var e : exactUrls.entrySet()) {
            urls.putIfAbsent(e.getKey(), e.getValue());
        }

        return urls;
    }

    // ==================== LLM fallback ====================

    private String answerWithFallback(String question, McmodCategory category,
                                      List<ResolvedEntity> entities,
                                      List<VectorStore.SearchResult> vectorResults,
                                      List<RecipeMatch> recipeResults,
                                      String convContext) {
        try {
            StringBuilder ctx = new StringBuilder();
            if (convContext != null && !convContext.isBlank()) {
                ctx.append("[Conversation]\n").append(convContext).append("\n\n");
            }

            String catStr = category != null ? category.getName() : "通用";
            ctx.append("Category: ").append(catStr).append("\n");

            if (!entities.isEmpty()) {
                ctx.append("Resolved entities:\n");
                for (ResolvedEntity e : entities) {
                    ctx.append("  - `").append(e.registry()).append("` (mod: ").append(e.modId()).append(")\n");
                }
            }

            if (vectorResults != null && !vectorResults.isEmpty()) {
                ctx.append("\nAvailable knowledge snippets (may be low relevance):\n");
                for (int i = 0; i < Math.min(5, vectorResults.size()); i++) {
                    var sr = vectorResults.get(i);
                    String snippet = sr.chunkText();
                    if (snippet.length() > 400) snippet = snippet.substring(0, 400);
                    ctx.append("  [").append(sr.modName()).append(" score=")
                            .append(String.format("%.3f", sr.score())).append("] ")
                            .append(snippet.replace("\n", " ")).append("\n");
                }
            }

            if (recipeResults != null && !recipeResults.isEmpty()) {
                ctx.append("\nRelevant recipes: ").append(recipeResults.size()).append("\n");
                for (int i = 0; i < Math.min(3, recipeResults.size()); i++) {
                    ctx.append("  - ").append(recipeResults.get(i).outputItem())
                            .append(" (").append(recipeResults.get(i).sourceMod()).append(")\n");
                }
            }

            String prompt = String.format("""
                    %s

                    [Question]
                    %s

                    Answer the question in Chinese.
                    The local knowledge base returned very sparse data (above).
                    Use your Minecraft expertise to provide the best possible answer.
                    IF the sparse data contains entity names or mod IDs, use them as anchors
                    and supplement with your knowledge.

                    IMPORTANT DISCLAIMER at the TOP of your answer:
                    > ⚠️ 本地知识库数据稀疏，以下回答结合了 AI 通用知识。具体内容可能因模组版本而异。

                    If you truly cannot provide useful information, suggest the user:
                    "尝试更具体地描述你的问题，例如指定模组名称、使用英文物品名，或询问特定方面（如合成配方、驯服方法、掉落物等）。"
                    """, ctx.toString(), question);

            String answer = llm.rawAsk(prompt);
            return answer != null && !answer.isBlank() ? answer : null;
        } catch (Exception e) {
            log.warn("LLM fallback failed: {}", e.getMessage());
            return null;
        }
    }

    // ==================== Recipe search ====================

    private List<RecipeMatch> findRecipes(List<ResolvedEntity> entities) {
        Set<String> foundOutputs = new LinkedHashSet<>();
        List<RecipeMatch> matches = new ArrayList<>();

        for (ResolvedEntity e : entities) {
            searchRecipe("output_item LIKE ?", new String[]{"%" + e.registry() + "%"}, foundOutputs, matches);
            if (e.registry().contains(":")) {
                String itemPart = e.registry().substring(e.registry().indexOf(':') + 1);
                searchRecipe("output_item LIKE ? OR recipe_data LIKE ?",
                        new String[]{"%" + itemPart + "%", "%" + itemPart + "%"}, foundOutputs, matches);
            }
        }

        if (matches.isEmpty() && !entities.isEmpty()) {
            for (ResolvedEntity e : entities) {
                searchRecipe("recipe_data LIKE ?",
                        new String[]{"%" + e.modId() + "%"}, foundOutputs, matches);
            }
        }
        return matches;
    }

    private List<RecipeMatch> findRecipesByText(String question) {
        Set<String> foundOutputs = new LinkedHashSet<>();
        List<RecipeMatch> matches = new ArrayList<>();
        List<String> keywords = extractKeywords(question);

        for (String kw : keywords) {
            searchRecipe("output_item LIKE ? OR recipe_data LIKE ?",
                    new String[]{"%" + kw + "%", "%" + kw + "%"}, foundOutputs, matches);
        }
        for (String en : new String[]{"compass", "sword", "pickaxe", "axe", "shovel", "hoe",
                "helmet", "chestplate", "leggings", "boots", "dragon", "ghost"}) {
            searchRecipe("output_item LIKE ? OR recipe_data LIKE ?",
                    new String[]{"%" + en + "%", "%" + en + "%"}, foundOutputs, matches);
        }

        return matches;
    }

    private List<String> extractKeywords(String question) {
        List<String> keywords = new ArrayList<>();
        String cleaned = question.replaceAll("[?？!！。，,、]", " ")
                .replaceAll("(怎么做|如何制作|怎么合成|合成配方|合成方法|合成方式|的配方|的制作|怎么搞|怎么弄|怎样做|如何做|在哪里|在哪|怎么去|怎么找|怎么打|如何获取|怎么获得|有什么|是什么|有什么用)", " ")
                .replaceAll("(有谁|是谁|哪位|哪个|什么|哪些|多少|几个|怎么|如何|为何|为啥|为什么)", " ")
                .trim();

        StringBuilder chinese = new StringBuilder();
        for (char c : cleaned.toCharArray()) {
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) {
                chinese.append(c);
            } else {
                if (chinese.length() >= 2) keywords.add(chinese.toString());
                chinese.setLength(0);
            }
        }
        if (chinese.length() >= 2) keywords.add(chinese.toString());

        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[a-z_]{2,}").matcher(cleaned.toLowerCase());
        while (m.find()) { String w = m.group(); if (w.length() >= 3) keywords.add(w); }

        return keywords.stream().distinct().toList();
    }

    private List<RecipeMatch> findRecipesByLlmExtraction(String question) {
        if (llm == null) return List.of();
        try {
            String prompt = "Extract Minecraft item English registry name(s) from: \""
                    + question + "\"\nReply with ONLY the English name(s), comma-separated. "
                    + "Example: compass, crafting_table, diamond_sword";
            String resp = llm.rawAsk(prompt);
            if (resp == null || resp.isBlank()) return List.of();

            Set<String> foundOutputs = new LinkedHashSet<>();
            List<RecipeMatch> matches = new ArrayList<>();
            for (String term : resp.split("[,，\\s]+")) {
                term = term.trim().toLowerCase().replaceAll("[^a-z_]", "");
                if (term.length() >= 3) {
                    searchRecipe("output_item LIKE ? OR recipe_data LIKE ?",
                            new String[]{"%" + term + "%", "%" + term + "%"}, foundOutputs, matches);
                }
            }
            return matches;
        } catch (Exception e) { return List.of(); }
    }

    private void searchRecipe(String where, String[] params, Set<String> found, List<RecipeMatch> matches) {
        String sql = "SELECT recipe_data, output_item, source_mod FROM rag_recipe WHERE " + where + " LIMIT 20";
        try (Connection c = baseDb.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setString(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String out = rs.getString("output_item");
                    if (found.add(out)) {
                        matches.add(new RecipeMatch(rs.getString("recipe_data"), out, rs.getString("source_mod")));
                    }
                }
            }
        } catch (Exception e) { log.warn("Recipe search failed", e); }
    }

    // ==================== LLM Reranker ====================

    private List<VectorStore.SearchResult> rerankWithLLM(String question, List<VectorStore.SearchResult> candidates) {
        if (llm == null) return candidates.subList(0, Math.min(8, candidates.size()));
        try {
            StringBuilder ctx = new StringBuilder();
            ctx.append("Question: ").append(question).append("\n\n");
            for (int i = 0; i < Math.min(20, candidates.size()); i++) {
                var c = candidates.get(i);
                String preview = c.chunkText();
                if (preview.length() > 200) preview = preview.substring(0, 200);
                ctx.append("[").append(i).append("] ").append(preview.replace("\n", " ")).append("\n");
            }
            String prompt = ctx + "\nList ONLY the indices of DIRECTLY relevant items. Reply with numbers separated by commas (e.g. 2,5,7). If none: NONE";
            String resp = llm.rawAsk(prompt);
            if (resp != null && !resp.trim().equalsIgnoreCase("NONE")) {
                List<VectorStore.SearchResult> filtered = new ArrayList<>();
                for (String part : resp.split("[,\\s]+")) {
                    part = part.replaceAll("[^0-9]", "");
                    if (!part.isEmpty()) {
                        try {
                            int idx = Integer.parseInt(part);
                            if (idx >= 0 && idx < candidates.size()) filtered.add(candidates.get(idx));
                        } catch (NumberFormatException ignored) {}
                    }
                }
                if (filtered.size() >= 2 && filtered.size() < candidates.size()) {
                    log.info("Reranker: {} -> {} relevant", candidates.size(), filtered.size());
                    return filtered;
                }
            }
        } catch (Exception e) { log.debug("Rerank failed: {}", e.getMessage()); }
        return candidates.subList(0, Math.min(8, candidates.size()));
    }

    // ==================== Helpers ====================

    public void setIncrementalDB(IncrementalDB db) { incremental.setCurrentDB(db); }

    public int getConversationTurn() { return conversationTurn; }

    /** Clear conversation history (e.g., when switching sessions) */
    public void clearHistory() {
        conversationHistory.clear();
        conversationTurn = 0;
        QaMetrics.cumulative.reset();
    }

    /** Access the metrics history service for the dashboard */
    public MetricsHistoryService getMetricsHistory() { return metricsHistory; }

    /** Count incremental chunks from info string like "Acquired: ghost (3 chunks)" */
    private static int countChunksFromInfo(String info) {
        if (info == null) return 0;
        int total = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\((\\d+) chunks\\)").matcher(info);
        while (m.find()) {
            try { total += Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
        }
        return total;
    }

    private static String sha256hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.substring(0, 12);
        } catch (Exception e) { return Integer.toHexString(text.hashCode()); }
    }

    // ==================== Token estimation helpers ====================

    private static int estimatePromptTokens(String text) {
        if (text == null) return 0;
        int cjk = 0, ascii = 0;
        for (char c : text.toCharArray()) {
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) cjk++;
            else if (c > 127) cjk++;
            else if (!Character.isWhitespace(c)) ascii++;
        }
        return (int) (cjk * 0.6 + ascii * 0.3);
    }

    private int estimateModListTokens() {
        try { return baseDb.findAllModEntries().size() * 5; } catch (Exception ignored) { return 50; }
    }

    private static int estimateContextTokens(AnswerContext ctx) {
        int tok = 0;
        if (ctx.recipeMatches() != null) tok += ctx.recipeMatches().size() * 80;
        if (ctx.vectorResults() != null) tok += ctx.vectorResults().size() * 60;
        return tok + 200;
    }

    private static int estimateAnswerTokens(String answer) {
        if (answer == null) return 0;
        return (int) (answer.length() * 0.4);
    }

    // ==================== Data classes ====================

    public record RecipeMatch(String recipeJson, String outputItem, String sourceMod) {}
    private record QAPair(String question, String answer) {}

    public static class QaResult {
        public ClassificationResult.QuestionType questionType = ClassificationResult.QuestionType.GENERAL;
        public McmodCategory mcmodCategory;
        public List<ResolvedEntity> resolvedEntities = new ArrayList<>();
        public Map<String, String> subPageUrls = new LinkedHashMap<>();
        public List<RecipeMatch> recipeResults = new ArrayList<>();
        public List<VectorStore.SearchResult> vectorResults = new ArrayList<>();
        public List<RecipeMatch> recipesToRender = new ArrayList<>();
        public String incrementalInfo;
        public String answer;
        public QaMetrics metrics;
    }
}
