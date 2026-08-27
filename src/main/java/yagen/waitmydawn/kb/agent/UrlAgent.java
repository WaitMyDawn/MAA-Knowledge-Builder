package yagen.waitmydawn.kb.agent;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import yagen.waitmydawn.kb.model.ModEntry;
import yagen.waitmydawn.kb.service.EmbeddingService;
import yagen.waitmydawn.kb.service.IncrementalDB;
import yagen.waitmydawn.kb.service.IncrementalKnowledgeService;
import yagen.waitmydawn.kb.service.RagAgentService;
import yagen.waitmydawn.kb.service.TextChunker;
import yagen.waitmydawn.kb.service.VectorStore;
import yagen.waitmydawn.kb.service.WikiScraperService;

import java.util.*;

/**
 * Agent 2.5 — UrlAgent: 基于 EntityAgent 解析出的实体名 + modId，
 * 从 rag_web_cache 的 subWebPage 映射（cn(en)→URL）中利用 LLM 匹配最佳子网页 URL。
 *
 * 为什么需要这个 Agent：
 *   subWebPage 的键是 "秘银矿石(Mithril Ore)" 这种中文名(英文名)格式，
 *   用户问题中的实体描述不一定精确匹配（比如用户说"秘银矿"而非"秘银矿石"），
 *   因此需要 LLM 从候选键中推理选出最匹配的 URL。
 *
 * 输入: 实体中文名 + modId + 该 modId 下所有 subWebPage 键
 * 输出: 匹配到的 URL (可为 null)
 */
public class UrlAgent {

    private static final Logger log = LoggerFactory.getLogger(UrlAgent.class);

    private static final String MATCH_SYSTEM_PROMPT = """
            You are matching a user's entity description to the most likely MC百科 item page.

            [Rules]
            1. Pick the item(s) that BEST match the entity description.
               Consider: Chinese name, English name, partial matches, synonyms.
               E.g. "秘银矿" matches "秘银矿石(Mithril Ore)".
               "火龙" matches "火龙(Fire Dragon)".
            2. Reply with ONLY a JSON array, no extra text. Copy the matched keys EXACTLY from the candidate list:
               ["秘银矿石(Mithril Ore)","火龙(Fire Dragon)"]
            3. If nothing matches, reply: []
            """;

    private static final String MATCH_PROMPT = """
            You are matching a user's entity description to the most likely MC百科 item page.

            [Entity to find]
            %s

            [Candidate items for mod: %s]
            %s

            [Rules]
            1. Pick the item(s) that BEST match the entity description.
               Consider: Chinese name, English name, partial matches, synonyms.
               E.g. "秘银矿" matches "秘银矿石(Mithril Ore)".
               "火龙" matches "火龙(Fire Dragon)".
            2. Reply with EXACTLY ONE item key per line. Only copy from the candidate list.
            3. If nothing matches, reply: NONE
            4. NO extra text, NO explanations.
            """;

    /** AiServices 结构化匹配接口：LLM 直接返回 JSON 数组（包一层 record 以保证反序列化稳定）。 */
    public interface UrlMatcher {
        @SystemMessage(MATCH_SYSTEM_PROMPT)
        @UserMessage("{{it}}")
        Result<UrlMatches> match(String prompt);
    }

    /** 匹配到的候选键列表。 */
    public record UrlMatches(List<String> keys) {}

    /** 单个子问题的检索结果：对应主体、相关文本块、从网页提取的注册名。 */
    public record SubQueryResult(String subQuery,
                                 List<VectorStore.SearchResult> chunks,
                                 List<String> registries) {}

    /** UrlAgent 抓取+检索的总体结果。 */
    public record UrlFetchResult(List<SubQueryResult> subQueryResults, String incrementalInfo) {}

    private final RagAgentService llm;
    private final DatabaseBuilder db;
    private final EmbeddingService embedder;
    private final WikiScraperService scraper = new WikiScraperService();
    private final IncrementalKnowledgeService incremental;
    private final UrlMatcher matcher;
    private volatile TokenUsage lastUsage;
    private IncrementalDB currentIncDb;
    private final Set<String> visitedUrls = new HashSet<>();
    private final Set<String> visitedWikiTopics = new HashSet<>();

    public UrlAgent(RagAgentService llm, DatabaseBuilder db, EmbeddingService embedder) {
        this.llm = llm;
        this.db = db;
        this.embedder = embedder;
        this.incremental = new IncrementalKnowledgeService(db);
        ChatModel model = llm.getChatModel();
        UrlMatcher m = null;
        if (model != null) {
            try {
                m = AiServices.builder(UrlMatcher.class).chatModel(model).build();
            } catch (Exception e) {
                log.warn("AiServices init failed, url match falls back to text parsing: {}", e.getMessage());
            }
        }
        this.matcher = m;
    }

    /** 每个问题处理开始时重置访问去重与累计用量。 */
    public void reset() {
        lastUsage = null;
        visitedUrls.clear();
        visitedWikiTopics.clear();
        incremental.resetCache();
    }

    /** 设置当前会话的增量库。 */
    public void setIncrementalDB(IncrementalDB db) {
        this.currentIncDb = db;
        incremental.setCurrentDB(db);
    }

    /**
     * 新工作流：根据 EntityAgent 命中的别名 URL 和 MC Wiki 主题抓取网页，
     * 提取注册名、构建增量库，并按子问题做向量检索，返回分组结果。
     */
    public UrlFetchResult fetchAndRetrieve(List<EntityAgent.AliasHit> aliasHits,
                                            List<String> wikiTopics,
                                            List<String> subQueries) {
        Map<String, List<String>> registriesBySubQuery = new LinkedHashMap<>();
        Map<String, List<VectorStore.SearchResult>> pageChunksBySubQuery = new LinkedHashMap<>();
        List<String> acquired = new ArrayList<>();

        for (EntityAgent.AliasHit hit : aliasHits) {
            if (hit.url() == null || hit.url().isBlank() || visitedUrls.contains(hit.url())) continue;
            visitedUrls.add(hit.url());
            log.info("UrlAgent: fetching alias '{}' from {}", hit.label(), hit.url());
            WikiScraperService.FetchedPage fetched = scraper.fetchPageWithRegistries(hit.url(), "mcmod");
            if (fetched == null || fetched.page() == null) {
                log.info("UrlAgent: alias '{}' fetch failed or empty", hit.label());
                continue;
            }
            log.info("UrlAgent: alias '{}' -> {} registries {}", hit.label(),
                    fetched.registries().size(), fetched.registries());
            String modName = resolveModName(hit.modId());
            List<TextChunker.TextChunk> stored = incremental.acquireFetchedPage(
                    hit.label(), hit.url(), fetched.page().content(), "mcmod",
                    hit.modId(), modName, embedder);
            if (!stored.isEmpty()) {
                acquired.add(hit.label() + " (" + stored.size() + " chunks)");
                pageChunksBySubQuery.computeIfAbsent(hit.subQuery(), k -> new ArrayList<>())
                        .addAll(toSearchResults(stored));
            }
            registriesBySubQuery.computeIfAbsent(hit.subQuery(), k -> new ArrayList<>())
                    .addAll(fetched.registries());
        }

        for (String topic : wikiTopics) {
            if (topic == null || topic.isBlank() || visitedWikiTopics.contains(topic)) continue;
            visitedWikiTopics.add(topic);
            log.info("UrlAgent: fetching MC Wiki topic '{}'", topic);
            WikiScraperService.FetchedPage fetched = scraper.fetchMcWikiWithFallback(topic);
            if (fetched == null || fetched.page() == null) {
                log.info("UrlAgent: wiki topic '{}' fetch failed or empty", topic);
                continue;
            }
            log.info("UrlAgent: wiki topic '{}' -> {} registries {}", topic,
                    fetched.registries().size(), fetched.registries());
            List<TextChunker.TextChunk> stored = incremental.acquireFetchedPage(
                    topic, fetched.page().url(), fetched.page().content(), "mcwiki",
                    "minecraft", "Minecraft", embedder);
            if (!stored.isEmpty()) {
                acquired.add(topic + " (" + stored.size() + " chunks)");
                pageChunksBySubQuery.computeIfAbsent(topic, k -> new ArrayList<>())
                        .addAll(toSearchResults(stored));
            }
            registriesBySubQuery.computeIfAbsent(topic, k -> new ArrayList<>())
                    .addAll(fetched.registries());
        }

        List<DatabaseBuilder> activeDbs = new ArrayList<>();
        if (db != null) activeDbs.add(db);
        if (currentIncDb != null && currentIncDb.getDb() != null) activeDbs.add(currentIncDb.getDb());

        List<SubQueryResult> results = new ArrayList<>();
        for (String subQuery : subQueries) {
            List<VectorStore.SearchResult> chunks = pageChunksBySubQuery.get(subQuery);
            if (chunks == null || chunks.isEmpty()) {
                // 路径 3：没有抓到真实网页时才做通用向量检索
                float[] queryVec = embedder.embed(subQuery);
                chunks = VectorStore.searchAcross(activeDbs, queryVec, 8);
                log.info("UrlAgent: sub-query '{}' has no page content, fallback to vector search -> {} chunks",
                        subQuery, chunks.size());
            } else {
                chunks = chunks.stream().limit(8).toList();
            }
            List<String> regs = dedupe(registriesBySubQuery.get(subQuery));
            log.info("UrlAgent: sub-query '{}' -> {} chunks, registries={}", subQuery, chunks.size(), regs);
            for (VectorStore.SearchResult chunk : chunks) {
                String preview = chunk.chunkText();
                if (preview != null && preview.length() > 300) preview = preview.substring(0, 300);
                log.debug("UrlAgent chunk[{}] score={:.3f}: {}", chunk.modName(), chunk.score(), preview);
            }
            results.add(new SubQueryResult(subQuery, chunks, regs));
        }

        String incInfo = acquired.isEmpty() ? null : "Acquired: " + String.join(", ", acquired);
        return new UrlFetchResult(results, incInfo);
    }

    /** 把刚抓取并写入增量库的 chunk 转成可直接交给 AnswerAgent 的检索结果。 */
    private static List<VectorStore.SearchResult> toSearchResults(List<TextChunker.TextChunk> chunks) {
        List<VectorStore.SearchResult> results = new ArrayList<>();
        for (TextChunker.TextChunk c : chunks) {
            results.add(new VectorStore.SearchResult(
                    c.id(), c.text(), c.modId(), c.modName(), c.type(), 1.0));
        }
        return results;
    }

    private static List<String> dedupe(List<String> values) {
        if (values == null) return List.of();
        Set<String> seen = new LinkedHashSet<>(values);
        return new ArrayList<>(seen);
    }

    /** 从基础库查询模组显示名，查不到就用 modId。 */
    private String resolveModName(String modId) {
        try {
            ModEntry entry = db.findModEntry(modId);
            if (entry != null && entry.getDisplayName() != null && !entry.getDisplayName().isBlank()) {
                return entry.getDisplayName();
            }
        } catch (Exception ignored) {}
        return modId;
    }

    /**
     * Match entity names to subWebPage URLs.
     *
     * @param entityDescriptions entity descriptions from user question (Chinese names)
     * @param modId              the mod this entity belongs to
     * @return map of entity description → URL (may be empty if no match)
     */
    public Map<String, String> matchUrls(List<String> entityDescriptions, String modId) {
        Map<String, String> results = new LinkedHashMap<>();
        if (llm == null || entityDescriptions.isEmpty()) return results;

        // Collect all subWebPage keys for this modId from rag_web_cache
        Map<String, String> allSubPages = getSubPagesForMod(modId);
        if (allSubPages.isEmpty()) {
            log.debug("UrlAgent: no subWebPage entries for modId={}", modId);
            return results;
        }

        // Build the list of candidate keys
        StringBuilder candidateList = new StringBuilder();
        int count = 0;
        for (String key : allSubPages.keySet()) {
            if (count++ >= 200) { candidateList.append("  ... and more\n"); break; }
            candidateList.append("  ").append(key).append("\n");
        }

        // Send to LLM for matching — one call with all entities
        String entitiesStr = String.join(", ", entityDescriptions);
        try {
            // 优先使用结构化输出（AiServices）
            if (matcher != null) {
                try {
                    // 结构化路径的用户消息只提供上下文，输出格式完全交给 @SystemMessage
                    String structuredPrompt = "[Entity to find]\n" + entitiesStr
                            + "\n\n[Candidate items for mod: " + modId + "]\n"
                            + candidateList;
                    log.debug("UrlAgent structured prompt ({} chars):\n{}",
                            structuredPrompt.length(), structuredPrompt);
                    Result<UrlMatches> result = matcher.match(structuredPrompt);
                    lastUsage = accumulateUsage(lastUsage, result.tokenUsage());
                    UrlMatches matches = result.content();
                    if (matches != null && matches.keys() != null && !matches.keys().isEmpty()) {
                        for (String key : matches.keys()) {
                            String url = allSubPages.get(key);
                            if (url == null) url = fuzzyMatch(key, allSubPages);
                            if (url != null) {
                                results.put(key, url);
                                log.info("UrlAgent: '{}' → {} → {}", entitiesStr, key, url);
                            }
                        }
                        return results;
                    }
                    // 模型明确返回 []：无匹配，避免重复调用 LLM
                    if (matches != null) {
                        log.info("UrlAgent: no match for '{}' in mod {} (structured)", entitiesStr, modId);
                        return results;
                    }
                } catch (Exception e) {
                    log.warn("UrlAgent structured call failed ({}), falling back to text parse: {}",
                            e.getClass().getSimpleName(), e.getMessage());
                    log.debug("UrlAgent structured failure detail", e);
                }
            }

            // 回退：旧版逐行文本解析
            String prompt = MATCH_PROMPT.formatted(entitiesStr, modId, candidateList.toString());
            log.debug("UrlAgent fallback prompt ({} chars):\n{}", prompt.length(), prompt);
            String response = llm.rawAsk(prompt);
            if (response == null || response.isBlank() || response.trim().equals("NONE")) {
                log.info("UrlAgent: no match for '{}' in mod {}", entitiesStr, modId);
                return results;
            }

            // Parse response: each line is a matched key
            for (String line : response.split("\n")) {
                line = line.trim();
                if (line.isBlank() || line.equals("NONE")) continue;

                // Try exact key match first, then fuzzy
                String url = allSubPages.get(line);
                if (url == null) {
                    // Fuzzy: find the closest key
                    url = fuzzyMatch(line, allSubPages);
                }
                if (url != null) {
                    results.put(line, url);
                    log.info("UrlAgent: '{}' → {} → {}", entitiesStr, line, url);
                }
            }
        } catch (Exception e) {
            log.warn("UrlAgent failed: {}", e.getMessage());
        }

        return results;
    }

    /** 最近一次匹配调用的真实 Token 用量；回退路径或未配置 Key 时为 null。 */
    public TokenUsage lastUsage() {
        return lastUsage;
    }

    /** 清空累计用量（QaPipeline 在每个问题处理开始时调用）。 */
    public void resetUsage() {
        lastUsage = null;
    }

    private static TokenUsage accumulateUsage(TokenUsage existing, TokenUsage next) {
        if (next == null) return existing;
        if (existing == null) return next;
        try {
            return existing.add(next);
        } catch (Exception e) {
            return next;
        }
    }

    /** Get all subWebPage keys→URLs for a given modId from rag_web_cache */
    private Map<String, String> getSubPagesForMod(String modId) {
        Map<String, String> all = new LinkedHashMap<>();
        try {
            List<DatabaseBuilder.WebCacheEntry> entries = db.loadWebPages(modId);
            for (var entry : entries) {
                if (entry.subWebPage() != null) {
                    all.putAll(entry.subWebPage());
                }
            }
        } catch (Exception e) {
            log.debug("UrlAgent: load subPages failed for {}: {}", modId, e.getMessage());
        }
        return all;
    }

    /** Fuzzy key match: find the key in the map that is closest to the LLM's output */
    private String fuzzyMatch(String target, Map<String, String> allSubPages) {
        String bestKey = null;
        int bestScore = 0;
        String lower = target.toLowerCase();

        for (String key : allSubPages.keySet()) {
            int score = 0;
            String keyLower = key.toLowerCase();
            if (keyLower.equals(lower)) score = 100;
            else if (keyLower.contains(lower) || lower.contains(keyLower)) score = 80;
            else {
                // Character overlap
                int overlap = 0;
                for (int i = 0; i < Math.min(lower.length(), keyLower.length()); i++) {
                    if (lower.charAt(i) == keyLower.charAt(i)) overlap++;
                }
                score = overlap;
            }
            if (score > bestScore) { bestScore = score; bestKey = key; }
        }

        return bestScore > 0 ? allSubPages.get(bestKey) : null;
    }
}
