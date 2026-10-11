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
    /** debug 日志中完整 prompt 的最大长度，超出截断，避免每问写数万字符日志。 */
    private static final int MAX_PROMPT_LOG_CHARS = 1500;

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
    private java.util.function.Supplier<List<DatabaseBuilder>> activeDatabaseSupplier;
    private long lastSearchTimeMs;
    private int lastDbQueryCount;
    private yagen.waitmydawn.kb.dto.RetrievalPlan retrievalPlan;
    public void setRetrievalPlan(yagen.waitmydawn.kb.dto.RetrievalPlan plan) { retrievalPlan=plan; }

    public void setActiveDatabases(java.util.function.Supplier<List<DatabaseBuilder>> supplier) { activeDatabaseSupplier = supplier; }
    public long lastSearchTimeMs() { return lastSearchTimeMs; }
    public int lastDbQueryCount() { return lastDbQueryCount; }
    private volatile TokenUsage lastUsage;
    private IncrementalDB currentIncDb;
    private final Set<String> visitedUrls = new HashSet<>();
    private final Set<String> visitedWikiTopics = new HashSet<>();
    /** true = 整个评测 run 共享抓取去重与页面缓存（跨会话），用于减少重复抓取。 */
    private boolean runScopeDedup;
    /** 会话级“已抓页面”缓存：key=URL（wiki 用 "topic:" 前缀），visited 命中时直接复用块与注册名。 */
    private final Map<String, CachedPage> fetchedPageCache = new LinkedHashMap<>();

    /** 已抓页面的可复用结果：块（score=1.0 直接来自抓取）与从页面提取的注册名。 */
    private record CachedPage(String label, String subQuery,
                              List<VectorStore.SearchResult> chunks,
                              List<String> registries) {}

    public UrlAgent(RagAgentService llm, DatabaseBuilder db, EmbeddingService embedder) {
        this.llm = llm;
        this.db = db;
        this.embedder = embedder;
        this.incremental = new IncrementalKnowledgeService(db);
        this.scraper.setPublicCache(new yagen.waitmydawn.kb.service.PublicKnowledgeCache(db));
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

    /**
     * 每个问题处理开始时重置累计用量；URL/主题去重仅在无会话增量库时逐问清空，
     * 有增量库时由 {@link #setIncrementalDB(IncrementalDB)} 按会话切换清空，
     * 使同一会话内的重复主题直接命中增量库而不重复抓取。
     */
    public void reset() {
        lastUsage = null;
        if (currentIncDb == null && !runScopeDedup) {
            visitedUrls.clear();
            visitedWikiTopics.clear();
            fetchedPageCache.clear();
        }
    }

    /**
     * 设置当前会话的增量库。检测到会话（增量库）切换时清空抓取去重集合，
     * 避免上一个会话已抓页面阻止新会话抓取。
     */
    public void setIncrementalDB(IncrementalDB db) {
        boolean sessionChanged = currentIncDb == null || db == null || !currentIncDb.getId().equals(db.getId());
        this.currentIncDb = db;
        incremental.setCurrentDB(db);
        if (sessionChanged) {
            visitedUrls.clear();
            visitedWikiTopics.clear();
            fetchedPageCache.clear();
            incremental.resetCache();
        }
    }

    /**
     * 评测模式使用：把抓取去重与页面缓存提升到整个 run（跨会话共享），
     * 同一 URL 在 run 内只抓一次；GUI/普通会话保持按会话隔离。
     */
    public void setRunScopeDedup(boolean enabled) {
        this.runScopeDedup = enabled;
        if (enabled) {
            visitedUrls.clear();
            visitedWikiTopics.clear();
            fetchedPageCache.clear();
            incremental.resetCache();
        }
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
            if (hit.url() == null || hit.url().isBlank()) continue;
            if (visitedUrls.contains(hit.url())) {
                // 同会话已抓过：复用缓存中的页面块与注册名，不重复抓取（2026-09-07 会话级页块缓存）
                CachedPage cached = fetchedPageCache.get(hit.url());
                if (cached != null) {
                    log.info("UrlAgent: reuse cached page '{}' from {}", hit.label(), hit.url());
                    pageChunksBySubQuery.computeIfAbsent(hit.subQuery(), k -> new ArrayList<>())
                            .addAll(cached.chunks());
                    registriesBySubQuery.computeIfAbsent(hit.subQuery(), k -> new ArrayList<>())
                            .addAll(cached.registries());
                }
                continue;
            }
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
            visitedUrls.add(hit.url());
            fetchedPageCache.put(hit.url(), new CachedPage(hit.label(), hit.subQuery(),
                    toSearchResults(stored), fetched.registries()));
            if (!stored.isEmpty()) {
                acquired.add(hit.label() + " (" + stored.size() + " chunks)");
                pageChunksBySubQuery.computeIfAbsent(hit.subQuery(), k -> new ArrayList<>())
                        .addAll(toSearchResults(stored));
            }
            registriesBySubQuery.computeIfAbsent(hit.subQuery(), k -> new ArrayList<>())
                    .addAll(fetched.registries());
        }

        for (String topic : wikiTopics) {
            if (topic == null || topic.isBlank()) continue;
            String cacheKey = "topic:" + topic;
            if (visitedWikiTopics.contains(topic)) {
                // 同会话已抓过该 wiki 主题：直接复用缓存块与注册名
                CachedPage cached = fetchedPageCache.get(cacheKey);
                if (cached != null) {
                    log.info("UrlAgent: reuse cached wiki topic '{}'", topic);
                    pageChunksBySubQuery.computeIfAbsent(topic, k -> new ArrayList<>())
                            .addAll(cached.chunks());
                    registriesBySubQuery.computeIfAbsent(topic, k -> new ArrayList<>())
                            .addAll(cached.registries());
                }
                continue;
            }
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
            visitedWikiTopics.add(topic);
            fetchedPageCache.put(cacheKey, new CachedPage(topic, topic,
                    toSearchResults(stored), fetched.registries()));
            if (!stored.isEmpty()) {
                acquired.add(topic + " (" + stored.size() + " chunks)");
                pageChunksBySubQuery.computeIfAbsent(topic, k -> new ArrayList<>())
                        .addAll(toSearchResults(stored));
            }
            registriesBySubQuery.computeIfAbsent(topic, k -> new ArrayList<>())
                    .addAll(fetched.registries());
        }

        List<DatabaseBuilder> activeDbs = activeDatabaseSupplier != null
                ? activeDatabaseSupplier.get() : new ArrayList<>();
        if (activeDatabaseSupplier == null) {
            if (db != null) activeDbs.add(db);
            if (currentIncDb != null) activeDbs.add(currentIncDb.getDb());
        }
        List<SubQueryResult> results = new ArrayList<>();
        long searchStart = System.nanoTime();
        var retrievalQueries = new LinkedHashSet<>(subQueries);
        retrievalQueries.addAll(wikiTopics);
        for (String subQuery : retrievalQueries) {
            String query=retrievalPlan==null?subQuery:retrievalPlan.queryFor(subQuery);
            float[] queryVec = embedder.embed(query);
            Set<String> sourceUrls=new LinkedHashSet<>();
            for(var chunk:pageChunksBySubQuery.getOrDefault(subQuery,List.of())) if(!chunk.sourcePath().isBlank()) sourceUrls.add(chunk.sourcePath());
            String scope=retrievalPlan==null?null:retrievalPlan.modId();
            if(!sourceUrls.isEmpty()) {
                for(String sourceUrl:sourceUrls) {
                    var alias=aliasHits.stream().filter(h -> sourceUrl.equals(h.url())).findFirst().orElse(null);
                    String pageMod=alias==null?pageChunksBySubQuery.getOrDefault(subQuery,List.of()).stream().filter(c->sourceUrl.equals(c.sourcePath())).map(VectorStore.SearchResult::modId).findFirst().orElse(scope):alias.modId();
                    var pageHits=rankPageEvidence(pageChunksBySubQuery.getOrDefault(subQuery,List.of()).stream()
                            .filter(c -> sourceUrl.equals(c.sourcePath()) && (pageMod==null || pageMod.equals(c.modId()))).toList(),query,queryVec,10);
                    String label=aliasHits.stream().filter(h -> sourceUrl.equals(h.url())).map(EntityAgent.AliasHit::label).findFirst().orElse(subQuery);
                    String purpose=alias!=null && scope!=null && pageMod!=null && !scope.equals(pageMod)?"[跨模组归属校验候选] ":"";
                    if(alias==null && "minecraft".equals(pageMod) && scope!=null && !"minecraft".equals(scope)) purpose="[原版机制支撑页] ";
                    if(alias!=null && alias.role().equals("name_candidate")) purpose+="[相近名称候选，对应关系未确认] ";
                    if(alias!=null && alias.role().equals("support")) purpose+="[机制支撑页，不代表查询主体] ";
                    var cached=fetchedPageCache.get(sourceUrl);
                    var regs=cached==null?dedupe(registriesBySubQuery.get(subQuery)):cached.registries();
                    results.add(new SubQueryResult(purpose+label+"："+query,pageHits,regs));
                }
                continue;
            }
            List<VectorStore.SearchResult> chunks=VectorStore.searchScoped(activeDbs,queryVec,8,embedder.profile(),scope,Set.of());
            if(scope!=null) chunks=chunks.stream().filter(c -> scope.equals(c.modId())).toList();
            List<String> regs = dedupe(registriesBySubQuery.get(subQuery));
            log.info("UrlAgent: sub-query '{}' -> {} chunks, registries={}", subQuery, chunks.size(), regs);
            if (log.isDebugEnabled()) {
                StringBuilder previews = new StringBuilder();
                for (VectorStore.SearchResult chunk : chunks) {
                    String preview = chunk.chunkText();
                    if (preview != null) {
                        preview = preview.replace('\n', ' ');
                        if (preview.length() > 100) preview = preview.substring(0, 100) + "...";
                    }
                    if (previews.length() > 0) previews.append(" | ");
                    previews.append(String.format("[%s/%s score=%.3f] %s",
                            chunk.modName(), chunk.type(), chunk.score(), preview));
                }
                log.debug("UrlAgent sub-query '{}' chunk previews ({}): {}", subQuery, chunks.size(), previews);
            }
            results.add(new SubQueryResult(subQuery, chunks, regs));
        }

        lastSearchTimeMs = (System.nanoTime() - searchStart) / 1_000_000;
        lastDbQueryCount = activeDbs.size() * subQueries.size();
        String incInfo = acquired.isEmpty() ? null : "Acquired: " + String.join(", ", acquired);
        return new UrlFetchResult(results, incInfo);
    }

    /** 页面内语义排序结合操作关键词，缓存复用时仍按当前问题选择正文。 */
    private List<VectorStore.SearchResult> rankPageEvidence(List<VectorStore.SearchResult> chunks,String query,float[] queryVector,int limit) {
        if(chunks.isEmpty()) return List.of();
        List<String> actions=new ArrayList<>();
        if(query.matches("(?s).*(驯服|骑乘|繁殖|操作).*")) actions.addAll(List.of("驯服","骑乘","骑上","鞍","右键","右击","孵化","龙蛋","繁殖","认主","主人","野生","生长阶段"));
        boolean acquisition=query.matches("(?s).*(获得|获取|掉落|掉什么|奖励|战利品).*" );
        boolean crafting=query.matches("(?s).*(合成|制作|配方|酿造|做出|怎么做|怎么造|建造|搭建).*" );
        if(acquisition) actions.addAll(List.of("获得","获取","搜刮","尸体","掉落","击杀","死亡","获取 表格","生物掉落物","经验","龙蛋","不能合成","无法合成","自然生成","物品展示框","末地船","最后","骨架"));
        if(crafting) actions.addAll(List.of("合成","制作","配方","酿造","材料","输入","原料","创建","建造","搭建","框架","最小"));
        if(query.matches("(?s).*(生成|召唤).*")) actions.addAll(List.of("生成","召唤","铁块","南瓜","灵魂沙","灵魂土","凋灵骷髅头","T形","T字","摆放","放置"));
        if(query.matches("(?s).*(怎么打|攻略|打法|击败).*")) actions.addAll(List.of("水晶","治疗","摧毁","攻击","战斗","弓","近战","床","爆炸","躲避","龙息","飞行","栖息","远程"));
        if(query.matches("(?s).*(秒杀|一击|杀死|伤害).*")) actions.addAll(List.of("生命值","血量","伤害","受伤","免疫","只能","爆炸","玩家"));
        if(query.matches("(?s).*(升级|强化|提升|等级|强度).*")) actions.addAll(List.of("升级","法球","铁砧","抄写","卷轴","强化","墨水","法术强度"));
        for(String property:List.of("中立","被动","敌对","生命值","血量","陆地","水生")) if(query.contains(property)) actions.add(property);
        var vectors=incremental.pageEmbeddings(chunks,embedder.profile());
        var ranked=new ArrayList<VectorStore.SearchResult>();
        for(int i=0;i<chunks.size();i++) {
            var chunk=chunks.get(i); float[] vector=vectors.get(chunk.chunkId());
            double dot=0,left=0,right=0;
            for(int j=0;j<Math.min(vector.length,queryVector.length);j++) { dot+=vector[j]*queryVector[j];left+=vector[j]*vector[j];right+=queryVector[j]*queryVector[j]; }
            double score=left==0 || right==0?0:dot/Math.sqrt(left*right);
            long actionHits=actions.stream().filter(chunk.chunkText()::contains).count();
            score+=Math.min(0.6,actionHits*0.15);
            String section=yagen.waitmydawn.kb.service.PageEvidenceBudget.section(chunk);
            if(acquisition && section.matches(".*(获取|获得|掉落|死亡|自然生成).*")) score+=0.4;
            if(crafting && section.matches(".*(合成|制作|酿造|创建|建造|搭建).*")) score+=section.contains("酿造材料")?0.1:0.4;
            if(query.contains("驯服") && section.matches(".*(驯服|孵化|繁殖).*")) score+=0.4;
            if(chunk.chunkText().matches("(?s).*(章节：(历史|更新历史|音效|进度)|\\[(历史|更新历史|音效|进度) 表格).*")) score-=0.25;
            ranked.add(new VectorStore.SearchResult(chunk.chunkId(),chunk.chunkText(),chunk.modId(),chunk.modName(),chunk.type(),Math.max(0.11,Math.min(1,score/1.6)),chunk.sourcePath()));
        }
        var selected=new ArrayList<>(yagen.waitmydawn.kb.service.PageEvidenceBudget.select(ranked,limit));
        // 页面引言承载对象身份与原型关系，不能被大量操作段落完全挤出上下文。
        if(!selected.contains(ranked.getFirst())) { if(selected.size()>=limit) selected.removeLast(); selected.addFirst(ranked.getFirst()); }
        if (query.matches("(?s).*(怎么打|如何打|打法|攻略|怎么玩|如何玩).*"))
            return yagen.waitmydawn.kb.service.PageEvidenceBudget.withNeighbors(chunks, selected);
        return selected;
    }

    /** 把刚抓取并写入增量库的 chunk 转成可直接交给 AnswerAgent 的检索结果。 */
    private static List<VectorStore.SearchResult> toSearchResults(List<TextChunker.TextChunk> chunks) {
        List<VectorStore.SearchResult> results = new ArrayList<>();
        for (TextChunker.TextChunk c : chunks) {
            results.add(new VectorStore.SearchResult(
                    c.id(), c.text(), c.modId(), c.modName(), c.type(), 1.0,c.sourcePath()));
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
                            structuredPrompt.length(), truncatePrompt(structuredPrompt));
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
            log.debug("UrlAgent fallback prompt ({} chars):\n{}", prompt.length(), truncatePrompt(prompt));
            String response = llm.rawAsk("UrlAgent.match", prompt);
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

    /** 截断完整 prompt 日志：超长部分省略并注明被截断字符数。 */
    private static String truncatePrompt(String prompt) {
        if (prompt == null || prompt.length() <= MAX_PROMPT_LOG_CHARS) return prompt;
        return prompt.substring(0, MAX_PROMPT_LOG_CHARS)
                + "\n...[truncated " + (prompt.length() - MAX_PROMPT_LOG_CHARS) + " chars]";
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
