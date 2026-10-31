package yagen.waitmydawn.kb.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yagen.waitmydawn.kb.model.DatabaseBuilder;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
    private final PublicKnowledgeCache publicCache;
    private final Set<String> fetchedCache = new HashSet<>(); // URL → fetched

    private IncrementalDB currentDb;

    public IncrementalKnowledgeService(DatabaseBuilder baseDb) {
        this.baseDb = baseDb;
        this.publicCache = new PublicKnowledgeCache(baseDb);
        this.scraper.setPublicCache(publicCache);
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
                    fetchedCache.add(url);
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
     * 将已经抓取好的网页正文分块并写入当前增量库。
     * 供 UrlAgent 在获取页面并提取注册名后复用同一套增量知识流程。
     */
    public List<TextChunker.TextChunk> acquireFetchedPage(String topic, String url, String content,
                                                           String source, String modId, String modName,
                                                           EmbeddingService embedder) {
        if (currentDb == null) {
            log.warn("No incremental DB set — cannot store fetched page");
            return List.of();
        }
        if (content == null || content.isBlank()) return List.of();

        try {
            List<TextChunker.TextChunk> chunks = chunker.fromWebPage(
                    content, modId, modName, source, url);
            if (chunks.isEmpty()) return List.of();
            currentDb.getVectorStore().storeBatch(chunks, publicCache.embeddings(chunks,embedder), embedder.profile());
            fetchedCache.add(url);
            log.info("Incremental: acquired {} → {} chunks from {}", topic, chunks.size(), url);
            return chunks;
        } catch (Exception e) {
            throw new IllegalStateException("Incremental page persistence failed: " + url, e);
        }
    }

    public Map<String,float[]> pageEmbeddings(List<VectorStore.SearchResult> chunks, String profile) {
        var vectors=new java.util.HashMap<String,float[]>();var databases=new ArrayList<DatabaseBuilder>();databases.add(baseDb);if(currentDb!=null)databases.add(currentDb.getDb());
        for(var database:databases){var present=new HashSet<String>();
            try(var c=database.getConnection()){try(var tables=c.getMetaData().getTables(null,null,"rag_vectors",null)){if(!tables.next())continue;}for(int start=0;start<chunks.size();start+=100){var batch=chunks.subList(start,Math.min(start+100,chunks.size()));try(var p=c.prepareStatement("SELECT chunk_id FROM rag_vectors WHERE chunk_id IN ("+String.join(",",Collections.nCopies(batch.size(),"?"))+")")){for(int i=0;i<batch.size();i++)p.setString(i+1,batch.get(i).chunkId());try(var r=p.executeQuery()){while(r.next())present.add(r.getString(1));}}}}
            catch(java.sql.SQLException e){throw new IllegalStateException("页面向量身份读取失败",e);}
            var selected=chunks.stream().filter(chunk->present.contains(chunk.chunkId())&&!vectors.containsKey(chunk.chunkId())).toList();
            if(!selected.isEmpty())new VectorStore(database,384).loadEmbeddings(selected,profile).forEach(vectors::putIfAbsent);
        }
        if(chunks.stream().anyMatch(chunk->!vectors.containsKey(chunk.chunkId())))throw new IllegalStateException("Stored page embeddings missing");return vectors;
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
                Use https://wiki.biligame.com/mc/ for URLs (中文 Minecraft Wiki 镜像).
                Example: "How to craft a beacon?" → 信标|https://wiki.biligame.com/mc/信标
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

                if (!url.startsWith("https://wiki.biligame.com/mc/")) continue;
                if (fetchedCache.contains(url)) continue;

                String pageName = URLDecoder.decode(
                        url.substring(url.lastIndexOf('/') + 1), StandardCharsets.UTF_8);
                WikiScraperService.WebPage page = scraper.fetchMcWiki(pageName);
                if (page == null) continue;

                List<TextChunker.TextChunk> chunks = chunker.fromDescription(
                        page.content(), "mcwiki", topic);
                if (!chunks.isEmpty()) {
                    currentDb.getVectorStore().storeBatch(chunks, embedder);
                    fetchedCache.add(url);
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
