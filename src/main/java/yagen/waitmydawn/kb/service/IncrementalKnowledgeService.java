package yagen.waitmydawn.kb.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yagen.waitmydawn.kb.model.DatabaseBuilder;

import java.util.*;

/**
 * 增量知识获取 — 对话时按需从 MC百科子网页抓取并嵌入。
 *
 * 工作流:
 *   EntityAgent 解析出 iceandfire:ghost
 *   → 从 rag_web_cache.subWebPage 查找 URL
 *   → 抓取网页内容 → 分块 → 嵌入到当前增量 DB
 *
 * 后续相同话题直接命中增量 DB，无需重复抓取。
 */
public class IncrementalKnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(IncrementalKnowledgeService.class);

    private final WikiScraperService scraper = new WikiScraperService();
    private final TextChunker chunker = new TextChunker();
    private final DatabaseBuilder baseDb;
    private final Set<String> fetchedCache = new HashSet<>(); // URL → fetched

    private IncrementalDB currentDb;

    public IncrementalKnowledgeService(DatabaseBuilder baseDb) {
        this.baseDb = baseDb;
    }

    /** Set the current incremental DB to write into */
    public void setCurrentDB(IncrementalDB db) { this.currentDb = db; }

    /**
     * Acquire knowledge for resolved entities via their subWebPage URLs.
     *
     * @param subPageUrls  Map of registryName → mcmod.cn URL (from EntityAgent.lookupSubPages)
     * @param embedder     Embedding service
     * @return summary of acquired pages
     */
    public String acquireFromSubPages(Map<String, String> subPageUrls, EmbeddingService embedder) {
        if (subPageUrls == null || subPageUrls.isEmpty()) return null;
        if (currentDb == null) {
            log.warn("No incremental DB set — cannot store fetched pages");
            return null;
        }

        List<String> acquired = new ArrayList<>();

        for (Map.Entry<String, String> entry : subPageUrls.entrySet()) {
            String registryName = entry.getKey();
            String url = entry.getValue();

            if (fetchedCache.contains(url)) continue;
            fetchedCache.add(url);

            try {
                // Fetch the sub-page
                WikiScraperService.WebPage page = scraper.fetchMcmodPage(url);
                if (page == null) {
                    log.debug("Incremental: fetch failed for {} → {}", registryName, url);
                    continue;
                }

                // Chunk + embed into incremental DB
                List<TextChunker.TextChunk> chunks = chunker.fromDescription(
                        page.content(), "mcmod", registryName);
                if (!chunks.isEmpty()) {
                    currentDb.getVectorStore().storeBatch(chunks, embedder);
                    acquired.add(registryName + " (" + chunks.size() + " chunks)");
                    log.info("Incremental: acquired {} → {} chunks from {}", registryName, chunks.size(), url);
                }
            } catch (Exception e) {
                log.warn("Incremental acquire failed for {}: {}", url, e.getMessage());
            }
        }

        return acquired.isEmpty() ? null : "Acquired: " + String.join(", ", acquired);
    }

    /**
     * Legacy: fetch from MC Wiki (for vanilla Minecraft questions).
     */
    public String acquireFromWiki(String question, RagAgentService llm,
                                  EmbeddingService embedder) {
        if (llm == null || currentDb == null) return null;

        try {
            String prompt = """
                Given the Minecraft question below, identify the key topic(s) and generate Chinese Minecraft Wiki URLs.
                Reply in format: TOPIC_NAME|URL
                Use https://zh.minecraft.wiki/w/ for URLs.
                Example: "How to craft a beacon?" → 信标|https://zh.minecraft.wiki/w/信标
                Only include valid wiki page URLs. One per line.
                Question: %s
                """.formatted(question);

            String response = llm.rawAsk(prompt);
            if (response == null || response.isBlank()) return null;

            List<String> acquired = new ArrayList<>();
            for (String line : response.split("\n")) {
                String[] parts = line.split("\\|");
                if (parts.length < 2) continue;
                String topic = parts[0].trim();
                String url = parts[1].trim();

                if (!url.startsWith("https://zh.minecraft.wiki/w/")) continue;
                if (fetchedCache.contains(url)) continue;
                fetchedCache.add(url);

                String pageName = url.substring(url.lastIndexOf('/') + 1);
                WikiScraperService.WebPage page = scraper.fetchMcWiki(pageName);
                if (page == null) continue;

                List<TextChunker.TextChunk> chunks = chunker.fromDescription(
                        page.content(), "mcwiki", topic);
                if (!chunks.isEmpty()) {
                    currentDb.getVectorStore().storeBatch(chunks, embedder);
                    acquired.add(topic + " (" + chunks.size() + " chunks)");
                    log.info("Incremental wiki: {} → {} chunks", topic, chunks.size());
                }
            }
            return acquired.isEmpty() ? null : "Wiki: " + String.join(", ", acquired);
        } catch (Exception e) {
            log.warn("Incremental wiki fetch failed: {}", e.getMessage());
            return null;
        }
    }

    /** Clear the fetch cache (reset between questions/sessions) */
    public void resetCache() { fetchedCache.clear(); }
}
