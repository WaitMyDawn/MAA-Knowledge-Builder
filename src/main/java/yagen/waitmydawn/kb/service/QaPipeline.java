package yagen.waitmydawn.kb.service;

import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yagen.waitmydawn.kb.agent.AnswerAgent;
import yagen.waitmydawn.kb.agent.AnswerAgent.AnswerContext;
import yagen.waitmydawn.kb.agent.AnswerAgent.RecipeMatch;
import yagen.waitmydawn.kb.agent.ClassifyAgent;
import yagen.waitmydawn.kb.agent.EntityAgent;
import yagen.waitmydawn.kb.agent.EntityAgent.ResolvedEntity;
import yagen.waitmydawn.kb.agent.UrlAgent;
import yagen.waitmydawn.kb.dto.QaMetrics;
import yagen.waitmydawn.kb.dto.QuestionType;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import yagen.waitmydawn.kb.model.ModEntry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.*;

/**
 * Agentic RAG QA Pipeline with comprehensive metrics collection.
 *
 *   Agent 1 — ClassifyAgent: LLM classifies question -> QuestionType
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
    private final MetricsHistoryService metricsHistory;

    // Conversation history: last N Q&A pairs for context
    private static final int MAX_CONTEXT_PAIRS = 5;
    /** 答案上下文最多携带的配方数（按相关性排序后取前 N） */
    private static final int MAX_RECIPE_CONTEXT = 10;
    /** 渲染成图片的配方上限，避免"过度发散"的图片刷屏 */
    private static final int MAX_RECIPES_TO_RENDER = 4;
    /** SQL LIKE 模板：注册名含 `_`，必须显式转义，避免 `_` 被当作单字符通配符。 */
    private static final String LIKE_WHERE_ONE = "output_item LIKE ? ESCAPE '\\'";
    private static final String LIKE_WHERE_OR = "output_item LIKE ? ESCAPE '\\' OR recipe_data LIKE ? ESCAPE '\\'";
    private static final String LIKE_WHERE_DATA = "recipe_data LIKE ? ESCAPE '\\'";
    private final List<QAPair> conversationHistory = new ArrayList<>();
    private int conversationTurn = 0;
    private String lastKnowledgeScope;
    private final KnowledgeRetrievalService knowledge;

    public QaPipeline(ClassifyAgent classifyAgent, EntityAgent entityAgent,
                      UrlAgent urlAgent, AnswerAgent answerAgent,
                      RagAgentService llm, VectorStore baseVectorStore,
                      EmbeddingService embedder, DatabaseBuilder baseDb,
                      MultiDBManager dbManager, Path dataDir) {
        this(classifyAgent,entityAgent,urlAgent,answerAgent,llm,baseVectorStore,embedder,baseDb,dbManager,dataDir,dataDir);
    }

    public QaPipeline(ClassifyAgent classifyAgent,EntityAgent entityAgent,UrlAgent urlAgent,AnswerAgent answerAgent,
                      RagAgentService llm,VectorStore baseVectorStore,EmbeddingService embedder,DatabaseBuilder baseDb,
                      MultiDBManager dbManager,Path dataDir,Path assetDir) {
        this.classifyAgent = classifyAgent;
        this.entityAgent = entityAgent;
        this.urlAgent = urlAgent;
        this.answerAgent = answerAgent;
        this.llm = llm;
        this.baseVectorStore = baseVectorStore;
        this.embedder = embedder;
        this.baseDb = baseDb;
        this.dbManager = dbManager;
        urlAgent.setActiveDatabases(dbManager::getActiveDatabases);
        this.metricsHistory = new MetricsHistoryService(dataDir);
        this.knowledge = new KnowledgeRetrievalService(baseDb, embedder, assetDir);
    }

    // ==================== Main entry point ====================

    public synchronized QaResult process(String question) { return process(question, null); }

    public synchronized QaResult process(String question, String selectedScope) {
        if (llm != null) llm.beginTrace();
        RetrievalTelemetry.begin();
        try { return processTurn(question, selectedScope); }
        finally { RetrievalTelemetry.end(); if (llm != null) llm.endTrace(); }
    }

    private QaResult processTurn(String question, String selectedScope) {
        for (var database : dbManager.getActiveDatabases())
            VectorStore.verifyProfile(database, embedder.profile(), false);
        long t0 = System.nanoTime();
        conversationTurn++;
        QaMetrics m = new QaMetrics();
        m.timestamp = Instant.now();
        m.conversationTurn = conversationTurn;
        m.questionHash = sha256hex(question);
        m.sessionId = dbManager.getCurrentSessionId();

        var resolution = knowledge.resolve(question, selectedScope, lastKnowledgeScope);
        KnowledgeRetrievalService.Retrieval imported = null;
        String retrievalQuestion = question;
        String resolvedScope=resolution.scope()!=null?resolution.scope().id():resolution.documentId()==null?null:"document:"+resolution.documentId();
        if(lastKnowledgeScope!=null && lastKnowledgeScope.equals(resolvedScope) && !conversationHistory.isEmpty()
                && question.matches("(?s).*(它|这个|那个|上面|刚才|继续|还有|第[0-9]+页).*"))
            retrievalQuestion=conversationHistory.getLast().question()+"；"+question;
        if (resolution.explicit() || !knowledge.scopes().isEmpty()) imported = knowledge.retrieve(retrievalQuestion, resolution, false);
        if(imported!=null) m.knowledgeRetrieval=imported.diagnostics();
        boolean hasImportedSources = !knowledge.scopes().isEmpty();
        boolean mentionsInstalledMod = baseDb.findAllModEntries().stream().anyMatch(mod -> {
            String q=KnowledgeSearchText.normalize(question);
            String name=KnowledgeSearchText.normalize(mod.getDisplayName());
            String id=KnowledgeSearchText.normalize(mod.getModId());
            return name.length()>=3 && q.contains(name) || id.length()>=3 && q.contains(id);
        });
        if (resolution.explicit() || (hasImportedSources && baseDb.findAllModEntries().isEmpty())
                || imported != null && !imported.hits().isEmpty() && imported.bestLexical()>=0.6 && !mentionsInstalledMod) {
            if(imported!=null) imported=knowledge.hydrate(imported,retrievalQuestion);
            return processKnowledgeTurn(question, resolution, imported, m, t0);
        }

        urlAgent.reset();
        QaResult result = new QaResult();

        // Build conversation context string
        String convContext = buildConversationContext();

        // 规划器优先同时产生意图与检索对象，缺少有效意图时再使用分类器兜底。
        ClassifyAgent.Classification cls = new ClassifyAgent.Classification(QuestionType.GENERAL,"planner");

        QuestionType intent = cls.intent();
        result.questionType = intent;
        m.classifyCategory = intent != null ? intent.getName() : "GENERAL";
        m.classifySource = cls.source();

        // === Agent 2: 实体别名向量检索 + 子问题拆分 ===
        long t2 = System.nanoTime();
        long entityLlmStart = llm != null ? llm.usageTrace().latencyMs : 0;
        EntityAgent.EntityResolutionResult entityResolution = entityAgent.resolveEntities(question, convContext);
        m.entityResolveTimeMs = (System.nanoTime() - t2) / 1_000_000;
        m.entityLlmMs = llm != null ? llm.usageTrace().latencyMs - entityLlmStart : 0;
        TokenUsage entityUsage = entityAgent.lastUsage();
        if (entityUsage != null) recordUsage(m, entityUsage);
        else m.recordLlmCall(estimatePromptTokens(question) + estimateModListTokens(), 20);

        List<ResolvedEntity> resolvedEntities = new ArrayList<>(entityResolution.inferredEntities());
        var plan=entityResolution.plan();
        if(plan!=null && plan.intent()!=QuestionType.GENERAL) {
            intent=plan.enumerate()?QuestionType.ENUMERATE:plan.intent();
            result.questionType=intent; m.classifyCategory=intent.getName();
        } else {
            long classifyStart=System.nanoTime();
            long classifyLlmStart=llm==null?0:llm.usageTrace().latencyMs;
            cls=classifyAgent.classify(question); intent=cls.intent(); result.questionType=intent;
            m.classifyLlmMs=llm==null?0:llm.usageTrace().latencyMs-classifyLlmStart;
            m.classifyTimeMs=(System.nanoTime()-classifyStart)/1_000_000;
            m.classifySource=cls.source(); m.classifyCategory=intent==null?"GENERAL":intent.getName();
        }
        m.addTrace("Classify",m.classifyTimeMs,m.classifyCategory+" ("+m.classifySource+")");
        urlAgent.setRetrievalPlan(plan);
        result.retrievalPlan=plan;
        result.catalogEvidence=entityResolution.catalogEvidence();
        if(plan!=null && plan.enumerate()) { intent=QuestionType.ENUMERATE; result.questionType=intent; m.classifyCategory=intent.getName(); }
        log.info("QA[Registries] initial inferred registries: {}",
                resolvedEntities.stream().map(ResolvedEntity::registry).toList());
        Map<String, String> subPageUrls = new LinkedHashMap<>();
        for (EntityAgent.AliasHit hit : entityResolution.aliasHits()) {
            subPageUrls.putIfAbsent(hit.label(), hit.url());
        }
        m.subPageUrlCount = subPageUrls.size();

        // === Agent 2.5: UrlAgent 抓取网页 + 提取注册名 + 构建增量库 + 按子问题检索 ===
        long t25 = System.nanoTime();
        urlAgent.resetUsage();
        IncrementalDB incDb = dbManager.getCurrentIncDB();
        urlAgent.setIncrementalDB(incDb);
        UrlAgent.UrlFetchResult fetchResult = plan!=null && plan.enumerate() && !plan.needsDetails() && entityResolution.wikiTopics().isEmpty()
                ? new UrlAgent.UrlFetchResult(List.of(),null)
                : urlAgent.fetchAndRetrieve(entityResolution.aliasHits(), entityResolution.wikiTopics(), entityResolution.subQueries());
        m.urlMatchTimeMs = (System.nanoTime() - t25) / 1_000_000;
        m.urlLlmMs = 0;

        Set<String> seenRegistries = new LinkedHashSet<>();
        for (ResolvedEntity e : resolvedEntities) seenRegistries.add(e.registry());
        for (UrlAgent.SubQueryResult sub : fetchResult.subQueryResults()) {
            for (String reg : sub.registries()) {
                if(!entityResolution.inferredEntities().isEmpty()) continue;
                if(plan!=null && plan.enumerate()) continue;
                boolean directPageId=sub.registries().size()==1 && entityResolution.aliasHits().stream().anyMatch(h ->
                        reg!=null && reg.startsWith(h.modId()+":") && sub.chunks().stream().anyMatch(c -> h.url().equals(c.sourcePath())));
                if(!directPageId && !isTargetRegistry(reg,entityResolution.aliasHits())) continue;
                if (reg != null && reg.matches("[a-z_][a-z0-9_]*:[a-z_][a-z0-9_]*")
                        && seenRegistries.add(reg)) {
                    String modId = reg.substring(0, reg.indexOf(':'));
                    resolvedEntities.add(new ResolvedEntity(reg, modId, 0.8f, "extracted from page"));
                }
            }
        }
        m.entityCount = resolvedEntities.size();
        log.info("QA[Registries] final registry list ({}): {}",
                resolvedEntities.size(),
                resolvedEntities.stream().map(ResolvedEntity::registry).toList());

        m.addTrace("EntityResolve", m.entityResolveTimeMs + m.urlMatchTimeMs,
                String.format("%d entities, %d URLs, %d sub-queries", m.entityCount, m.subPageUrlCount,
                        entityResolution.subQueries().size()));

        log.info("QA[Entity] {} entities, {} alias URLs, {} wiki topics [entity={}ms url={}ms]",
                resolvedEntities.size(), subPageUrls.size(), entityResolution.wikiTopics().size(),
                m.entityResolveTimeMs, m.urlMatchTimeMs);

        result.resolvedEntities = resolvedEntities;
        result.subPageUrls = subPageUrls;

        // === Incremental fetch（已由 UrlAgent 完成）===
        String incInfo = fetchResult.incrementalInfo();
        if (incInfo != null) {
            log.info("QA[Incremental] {}", incInfo);
            m.incrementalChunksAcquired = countChunksFromInfo(incInfo);
        }
        m.incrementalFetchTimeMs = Math.max(0, m.urlMatchTimeMs - urlAgent.lastSearchTimeMs());

        // === Vector semantic search（合并 UrlAgent 按子问题检索的结果）===
        long t4 = System.nanoTime();
        List<VectorStore.SearchResult> vectorResults = fetchResult.subQueryResults().stream()
                .flatMap(sub -> sub.chunks().stream())
                .distinct()
                .sorted(Comparator.comparingDouble(VectorStore.SearchResult::score).reversed())
                .limit(180)
                .toList();
        if (imported != null && (plan == null || plan.modId() == null) && !imported.hits().isEmpty()) {
            var merged = new LinkedHashMap<String,VectorStore.SearchResult>();
            vectorResults.forEach(v -> merged.put(v.chunkId(), v)); imported.hits().forEach(v -> merged.put(v.chunkId(), v));
            vectorResults = new ArrayList<>(merged.values());
        }
        if(result.catalogEvidence!=null) {
            var withCatalog=new ArrayList<>(vectorResults);
            withCatalog.addFirst(new VectorStore.SearchResult("catalog/"+(plan==null?"unknown":plan.modId()),result.catalogEvidence,
                    plan==null?"":plan.modId(),plan==null?"":plan.modId(),
                    subPageUrls.isEmpty()?"category_status":"category_catalog",subPageUrls.isEmpty()?0.11:1,"catalog://local"));
            vectorResults=withCatalog;
        }
        m.vectorSearchTimeMs = urlAgent.lastSearchTimeMs();
        m.vectorResultCount = vectorResults.size();
        m.computeVectorRelevance(vectorResults, 0.2);
        m.dbQueryCount += urlAgent.lastDbQueryCount();
        m.addTrace("VectorSearch", m.vectorSearchTimeMs,
                String.format("%d results, maxScore=%.3f", m.vectorResultCount, m.vectorMaxScore));

        // === Recipe search ===
        long t5 = System.nanoTime();
        List<ScoredRecipe> scoredRecipes = findScoredRecipes(resolvedEntities);
        List<RecipeMatch> recipeResults = scoredRecipes.stream().map(ScoredRecipe::match).toList();
        if (recipeResults.isEmpty() && resolvedEntities.isEmpty() && plan==null) {
            log.info("QA[Fallback] EntityAgent returned 0 entities, searching recipes by text");
            recipeResults = findRecipesByText(question);
            if (recipeResults.isEmpty() && llm != null) {
                long recipeLlmStart = llm.usageTrace().latencyMs;
                recipeResults = findRecipesByLlmExtraction(question);
                m.recordLlmCall(50, 20);
                m.recipeLlmMs = llm.usageTrace().latencyMs - recipeLlmStart;
            }
            scoredRecipes = recipeResults.stream()
                    .map(rm -> new ScoredRecipe(rm, 60))
                    .limit(MAX_RECIPE_CONTEXT)
                    .toList();
        }
        m.recipeSearchTimeMs = (System.nanoTime() - t5) / 1_000_000;
        m.recipeResultCount = recipeResults.size();
        m.dbQueryCount += Math.max(1, resolvedEntities.size() * 2);
        m.addTrace("RecipeSearch", m.recipeSearchTimeMs,
                String.format("%d recipes found", m.recipeResultCount));

        // JAR 自定义配方通常只描述机器输入输出，工作站正文补充原料来源及交互步骤。
        if (hasCraftingIntent(question) && !recipeResults.isEmpty()) {
            var support = RecipeWorkstationEvidence.select(question, recipeResults,
                    new WebCatalogStore(baseDb), subPageUrls.values().stream().collect(java.util.stream.Collectors.toSet()));
            if (!support.isEmpty()) {
                long supportStart = System.nanoTime();
                var extra = urlAgent.fetchAndRetrieve(support, List.of(), support.stream().map(EntityAgent.AliasHit::subQuery).toList());
                var allSubs = new ArrayList<>(fetchResult.subQueryResults());
                allSubs.addAll(extra.subQueryResults());
                fetchResult = new UrlAgent.UrlFetchResult(allSubs, fetchResult.incrementalInfo());
                var allVectors = new ArrayList<>(vectorResults);
                extra.subQueryResults().forEach(sub -> allVectors.addAll(sub.chunks()));
                vectorResults = allVectors.stream().distinct().toList();
                support.forEach(hit -> subPageUrls.putIfAbsent(hit.label(), hit.url()));
                m.subPageUrlCount = subPageUrls.size();
                m.incrementalChunksAcquired += countChunksFromInfo(extra.incrementalInfo());
                long elapsed = (System.nanoTime() - supportStart) / 1_000_000;
                m.urlMatchTimeMs += elapsed;
                m.incrementalFetchTimeMs += elapsed;
                m.addTrace("RecipeWorkstation", elapsed, support.size() + " bounded support pages");
            }
        }

        var oneHop = OneHopEvidence.select(question, plan == null ? null : plan.modId(), vectorResults,
                recipeResults, new WebCatalogStore(baseDb), new HashSet<>(subPageUrls.values()), llm);
        if (!oneHop.pages().isEmpty() || !oneHop.vanillaTopics().isEmpty()) {
            long supportStart = System.nanoTime();
            var queries = new ArrayList<>(oneHop.pages().stream().map(EntityAgent.AliasHit::subQuery).toList());
            queries.addAll(oneHop.vanillaTopics());
            var extra = urlAgent.fetchAndRetrieve(oneHop.pages(), oneHop.vanillaTopics(), queries);
            var allSubs = new ArrayList<>(fetchResult.subQueryResults());
            allSubs.addAll(extra.subQueryResults());
            fetchResult = new UrlAgent.UrlFetchResult(allSubs, fetchResult.incrementalInfo());
            var allVectors = new ArrayList<>(vectorResults);
            extra.subQueryResults().forEach(sub -> allVectors.addAll(sub.chunks()));
            vectorResults = allVectors.stream().distinct().toList();
            oneHop.pages().forEach(hit -> subPageUrls.putIfAbsent(hit.label(), hit.url()));
            m.incrementalChunksAcquired += countChunksFromInfo(extra.incrementalInfo());
            m.subPageUrlCount = subPageUrls.size();
            m.addTrace("OneHopEvidence", (System.nanoTime() - supportStart) / 1_000_000,
                    (oneHop.pages().size() + oneHop.vanillaTopics().size()) + " direct prerequisite/source pages");
        }

        // === LLM Rerank ===
        m.rerankTimeMs = 0;
        if (vectorResults.size() > 10 && llm != null && (plan==null || !plan.enumerate()) && subPageUrls.isEmpty()
                && entityResolution.wikiTopics().isEmpty()) {
            long tr = System.nanoTime();
            long rerankLlmStart = llm.usageTrace().latencyMs;
            RerankResult rerank = rerankWithLLM(question, vectorResults);
            m.rerankLlmMs = llm.usageTrace().latencyMs - rerankLlmStart;
            m.rerankTimeMs = (System.nanoTime() - tr) / 1_000_000;
            if (rerank.usage() != null) recordUsage(m, rerank.usage());
            else m.recordLlmCall(vectorResults.size() * 100, 10);
            if (rerank.results() != null) {
                vectorResults = rerank.results();
            }
            m.addTrace("Rerank", m.rerankTimeMs, String.format("%d -> %d", m.vectorResultCount, vectorResults.size()));
        }

        Set<String> selectedIds = new HashSet<>();
        for (var evidence : vectorResults) selectedIds.add(evidence.sourcePath() + "\n" + evidence.chunkId());
        List<UrlAgent.SubQueryResult> finalSubQueries = new ArrayList<>();
        for (var sub : fetchResult.subQueryResults()) {
            var selected = sub.chunks().stream()
                    .filter(c -> selectedIds.contains(c.sourcePath() + "\n" + c.chunkId())).toList();
            finalSubQueries.add(new UrlAgent.SubQueryResult(sub.subQuery(), selected, sub.registries()));
        }
        if(result.catalogEvidence!=null) finalSubQueries.add(new UrlAgent.SubQueryResult(question,
                vectorResults.stream().filter(v -> v.type().startsWith("category_")).toList(),List.of()));
        if (imported != null) {
            var kept = imported.hits().stream().filter(v -> selectedIds.contains(v.sourcePath() + "\n" + v.chunkId())).toList();
            if (!kept.isEmpty()) finalSubQueries.add(new UrlAgent.SubQueryResult(question, kept, List.of()));
        }
        m.computeVectorRelevance(vectorResults, 0.2);
        m.vectorResultCount = vectorResults.size();
        result.recipeResults = recipeResults;
        result.vectorResults = vectorResults;
        result.incrementalInfo = incInfo;
        boolean recipeIntent = hasCraftingIntent(question);
        result.recipesToRender = selectRecipesToRender(scoredRecipes, recipeIntent);

        // === Detect data tier (matching AnswerAgent's logic) ===
        boolean hasRecipes = !recipeResults.isEmpty();
        boolean hasRelevantVectors = vectorResults.stream().anyMatch(v -> v.score() > 0.3);
        boolean hasEntities = !resolvedEntities.isEmpty();
        boolean hasAnyVectors = !vectorResults.isEmpty();

        if (hasRecipes && hasRelevantVectors) m.dataTier = "TIER A";
        else if (hasEntities && (hasAnyVectors || hasRecipes)) m.dataTier = "TIER B";
        else if (hasEntities || hasAnyVectors) m.dataTier = "TIER C";
        else m.dataTier = "TIER D";

        // 与 AnswerAgent 的每页证据预算保持一致，不能把未发送的块计入上下文利用率。
        m.contextChunksUsed = (int) finalSubQueries.stream().flatMap(sub -> sub.chunks().stream().filter(v -> v.score() > 0.1).limit(10)).map(v->v.sourcePath()+"\n"+v.chunkId()).distinct().count();

        // === Agent 3: Compose answer (with LLM fallback) ===
        long t6 = System.nanoTime();
        List<AnswerAgent.RecipeMatch> answerRecipeMatches = recipeResults.stream()
                .map(rm -> new AnswerAgent.RecipeMatch(rm.recipeJson(), rm.outputItem(), rm.sourceMod(),rm.sourceType()))
                .toList();

        boolean hasRelevantData = !recipeResults.isEmpty()
                || vectorResults.stream().anyMatch(v -> v.score() > 0.2);

        String premiseWarning = buildPremiseWarning(question, resolvedEntities);
        var relevantMods=new HashSet<String>();
        if(plan!=null && plan.modId()!=null) relevantMods.add(plan.modId());
        resolvedEntities.forEach(e->relevantMods.add(e.modId()));
        vectorResults.forEach(v->{ if(v.modId()!=null && !v.modId().isBlank()) relevantMods.add(v.modId()); });
        result.sourceScope=baseDb.findAllModEntries().stream().filter(mod->relevantMods.isEmpty() || relevantMods.contains(mod.getModId())).map(mod -> mod.getModId()+": modVersion="+mod.getVersion()
                +", Minecraft="+mod.getMcVersion()+", loader="+mod.getLoader()+", "+KnowledgeSourcePolicy.describe(mod.getModId(), mod.getMcVersion())).collect(java.util.stream.Collectors.joining("\n"))
                +"\nnull 表示 JAR 元数据未提供版本。移植版本默认沿用原模组的百科机制知识；实际配方、注册名、纹理由安装 JAR 优先确定。仅证据指出具体差异时披露该差异，不把缺少移植版独立百科视为知识缺失。";
        var importedContext = imported;
        if (importedContext != null) {
            var ids = vectorResults.stream().map(VectorStore.SearchResult::chunkId).collect(java.util.stream.Collectors.toSet());
            var contextSnapshot = importedContext;
            var sourceIds=contextSnapshot.hits().stream().filter(h -> ids.contains(h.chunkId())).map(h -> KnowledgeRetrievalService.sourceId(contextSnapshot,h.chunkId())).collect(java.util.stream.Collectors.toSet());
            var sources = contextSnapshot.artifacts().sources().stream().filter(s -> sourceIds.contains(s.id())).toList();
            var pics = importedContext.artifacts().pictures().stream().filter(p -> p.chunkIds().stream().anyMatch(ids::contains)).toList();
            result.artifacts = new yagen.waitmydawn.kb.model.AnswerArtifacts(null, null, sources, pics);
            var kept=contextSnapshot.hits().stream().filter(h -> ids.contains(h.chunkId())).toList();
            importedContext=knowledge.hydrate(new KnowledgeRetrievalService.Retrieval(kept,result.artifacts,"",contextSnapshot.bestLexical(),contextSnapshot.diagnostics()),question);
            result.artifacts=importedContext.artifacts();m.knowledgeRetrieval=importedContext.diagnostics();
        }
        AnswerContext ctx = new AnswerContext(
                question, intent, resolvedEntities, answerRecipeMatches,
                vectorResults, incInfo, finalSubQueries,
                premiseWarning,result.sourceScope, importedContext == null ? null : filteredKnowledgeEvidence(result.artifacts,importedContext.diagnostics()));

        long answerLlmStart = llm != null ? llm.usageTrace().latencyMs : 0;
        result.answer = answerAgent.compose(ctx);
        m.answerGeneration=answerAgent.lastGenerationMode();
        m.answerLlmMs = llm != null ? llm.usageTrace().latencyMs - answerLlmStart : 0;
        TokenUsage answerUsage = answerAgent.lastUsage();
        if (answerUsage != null) recordUsage(m, answerUsage);
        else m.recordLlmCall(estimateContextTokens(ctx), estimateAnswerTokens(result.answer));

        // If data is sparse (Tier C/D), supplement with LLM fallback
        m.fallbackUsed = "MODEL_ERROR".equals(m.answerGeneration);
        if (!hasRelevantData && llm != null && (plan == null || (!plan.enumerate()
                && (plan.modId() == null || "minecraft".equals(plan.modId()))))) {
            long fallbackLlmStart = llm.usageTrace().latencyMs;
            FallbackAnswer fallback = answerWithFallback(question, intent, resolvedEntities,
                    vectorResults, recipeResults, convContext);
            m.fallbackLlmMs = llm.usageTrace().latencyMs - fallbackLlmStart;
            if (fallback.text() != null) {
                result.answer = fallback.text();
                m.answerGeneration="MODEL_FALLBACK";
                m.fallbackUsed = true;
                if (fallback.usage() != null) recordUsage(m, fallback.usage());
            }
        }
        if (premiseWarning != null && result.answer != null) {
            // 前提校验结果强制前置：不依赖 LLM 是否愿意改口（反幻觉硬约束）
            result.answer = "> ⚠️ " + premiseWarning + "\n\n" + result.answer;
        }
        m.answerGenTimeMs = (System.nanoTime() - t6) / 1_000_000;
        result.answer = AnswerSourceLinks.enrich(result.answer, result.vectorResults);
        result.artifacts=AnswerMedia.snapshotCitations(result.answer,result.artifacts);
        result.answer = AnswerMedia.bind(result.answer, result.artifacts);
        m.knowledgeContextChunkIds=KnowledgeRetrievalService.contextIds(importedContext,result.artifacts);
        m.knowledgeCitedSourceIds=result.artifacts.citedSourceIds();
        m.answerLength = result.answer != null ? result.answer.length() : 0;

        m.addTrace("AnswerGen", m.answerGenTimeMs, m.fallbackUsed ? "LLM fallback" : "RAG compose");

        // === Compute composite indicators ===
        m.computeTtft();
        m.computeQualityIndicators();

        // === Totals ===
        m.totalTimeMs = (System.nanoTime() - t0) / 1_000_000;
        var io=RetrievalTelemetry.current();
        m.httpRequestCount=io.httpRequests; m.httpErrorCount=io.httpErrors; m.httpResponseBytes=io.responseBytes;
        m.memoryPageCacheHits=io.memoryPageHits; m.persistentPageCacheHits=io.persistentPageHits;
        m.documentEmbeddingCount=io.documentEmbeddings; m.queryEmbeddingCount=io.queryEmbeddings;
        m.reusedEmbeddingCount=io.reusedEmbeddings; m.embeddingTimeMs=io.embeddingNanos/1_000_000;

        if (llm != null) {
            var actual = llm.usageTrace();
            m.llmCallCount = actual.calls;
            m.tokenEstimateInput = actual.inputTokens;
            m.tokenEstimateOutput = actual.outputTokens;
            m.tokenTotalCount = actual.inputTokens + actual.outputTokens;
            m.unknownUsageCalls = actual.unknownUsageCalls;
        } else {
            m.llmCallCount = 0; m.tokenEstimateInput = 0; m.tokenEstimateOutput = 0; m.tokenTotalCount = 0;
        }
        // Accumulate session stats
        m.accumulate();
        result.metrics = m;

        log.info("QA {}", m.toLogString());

        if (Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("Question processing cancelled");

        // Persist metrics
        try { metricsHistory.save(m); } catch (Exception e) {
            log.debug("Metrics save failed: {}", e.getMessage());
        }

        // Save to conversation history
        lastKnowledgeScope = null;
        conversationHistory.add(new QAPair(question, result.answer));
        while (conversationHistory.size() > MAX_CONTEXT_PAIRS) {
            conversationHistory.remove(0);
        }

        return result;
    }

    public List<KnowledgeRetrievalService.Scope> knowledgeScopes() { return knowledge.scopes(); }
    public synchronized void setKnowledgeImageFetching(boolean enabled) {knowledge.setImageFetching(enabled);}

    private static String filteredKnowledgeEvidence(yagen.waitmydawn.kb.model.AnswerArtifacts artifacts,KnowledgeRetrievalService.Diagnostics diagnostics) {
        return KnowledgeRetrievalService.evidence(artifacts,diagnostics);
    }

    private QaResult processKnowledgeTurn(String question, KnowledgeRetrievalService.Resolution scope,
                                          KnowledgeRetrievalService.Retrieval retrieval, QaMetrics m, long started) {
        var result = new QaResult();
        m.vectorSearchTimeMs=(System.nanoTime()-started)/1_000_000;
        result.sourceScope = scope.scope() == null ? "已导入资料" : scope.scope().label();
        result.artifacts = retrieval == null ? yagen.waitmydawn.kb.model.AnswerArtifacts.empty() : retrieval.artifacts();
        result.vectorResults = retrieval == null ? List.of() : retrieval.hits();
        m.knowledgeRetrieval=retrieval==null?null:retrieval.diagnostics();
        m.knowledgeContextChunkIds=KnowledgeRetrievalService.contextIds(retrieval,result.artifacts);
        String boundMod=knowledge.boundModId(scope.scope());
        if(boundMod!=null && hasCraftingIntent(question)) {
            var entities=entityAgent.resolveEntities(question,"本轮仅查询已选资料对应模组："+boundMod);
            result.resolvedEntities=entities.inferredEntities().stream().filter(e -> boundMod.equals(e.modId())).toList();
            var recipes=findScoredRecipes(result.resolvedEntities).stream().filter(r -> boundMod.equals(r.match().sourceMod())).toList();
            result.recipeResults=recipes.stream().map(ScoredRecipe::match).toList();
            result.recipesToRender=selectRecipesToRender(recipes,true);
            m.entityCount=result.resolvedEntities.size();m.recipeResultCount=result.recipeResults.size();
        }
        m.classifySource = "knowledge_source"; m.classifyCategory = "资料问答";
        m.vectorResultCount = result.vectorResults.size();
        var snapshot=retrieval;
        m.contextChunksUsed=snapshot==null?0:(int)snapshot.hits().stream().filter(h -> KnowledgeRetrievalService.sourceId(snapshot,h.chunkId())!=null).count();
        m.computeVectorRelevance(result.vectorResults, 0.2);
        m.dataTier = !result.recipeResults.isEmpty() ? result.vectorResults.isEmpty()?"TIER B":"TIER A"
                : result.vectorResults.isEmpty() ? "TIER D" : "TIER C";
        long answerStart = System.nanoTime();
        m.answerGeneration="NO_EVIDENCE";
        if (scope.warning() != null) result.answer = scope.warning();
        else if (result.vectorResults.isEmpty() && result.recipeResults.isEmpty()) result.answer = "在当前资料范围内没有找到足够证据，无法确认这个问题。可更换资料范围或补充相关文档。";
        else {
            var recipes=result.recipeResults.stream().map(r -> new AnswerAgent.RecipeMatch(r.recipeJson(),r.outputItem(),r.sourceMod(),r.sourceType())).toList();
            var ctx = new AnswerContext(question, QuestionType.GENERAL, result.resolvedEntities, recipes, result.vectorResults, null, List.of(),
                    null, "本轮仅使用已导入资料及已绑定同模组的原始 JAR 配方：" + result.sourceScope + "；不代表整合包运行时生效规则。",
                    filteredKnowledgeEvidence(result.artifacts,retrieval==null?null:retrieval.diagnostics()));
            result.answer = answerAgent.compose(ctx);
            m.answerGeneration=answerAgent.lastGenerationMode();m.fallbackUsed="MODEL_ERROR".equals(m.answerGeneration);
            recordUsage(m, answerAgent.lastUsage());
        }
        result.artifacts=AnswerMedia.snapshotCitations(result.answer,result.artifacts);
        result.answer = AnswerMedia.bind(result.answer, result.artifacts);
        m.knowledgeCitedSourceIds=result.artifacts.citedSourceIds();
        m.answerGenTimeMs = (System.nanoTime()-answerStart)/1_000_000; m.answerLength=result.answer.length();
        m.totalTimeMs=(System.nanoTime()-started)/1_000_000; m.computeTtft(); m.computeQualityIndicators();
        if (llm != null) {
            var usage=llm.usageTrace(); m.llmCallCount=usage.calls; m.tokenEstimateInput=usage.inputTokens;
            m.tokenEstimateOutput=usage.outputTokens; m.tokenTotalCount=usage.inputTokens+usage.outputTokens; m.unknownUsageCalls=usage.unknownUsageCalls;
        }
        var io=RetrievalTelemetry.current(); m.queryEmbeddingCount=io.queryEmbeddings; m.documentEmbeddingCount=io.documentEmbeddings;
        m.httpRequestCount=io.httpRequests;m.httpErrorCount=io.httpErrors;m.httpResponseBytes=io.responseBytes;
        m.memoryPageCacheHits=io.memoryPageHits;m.persistentPageCacheHits=io.persistentPageHits;
        m.embeddingTimeMs=io.embeddingNanos/1_000_000; m.addTrace("KnowledgeRetrieval", m.totalTimeMs-m.answerGenTimeMs, result.sourceScope);
        m.accumulate(); result.metrics=m;
        if(Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Question cancelled");
        try { metricsHistory.save(m); } catch (Exception e) { log.warn("Knowledge metrics save failed",e); }
        lastKnowledgeScope=result.artifacts.scopeId();
        conversationHistory.add(new QAPair(question,result.answer));
        while(conversationHistory.size()>MAX_CONTEXT_PAIRS) conversationHistory.remove(0);
        return result;
    }

    // ==================== Conversation context ====================

    private String buildConversationContext() {
        if (conversationHistory.isEmpty()) return "";
        // 节标题由消费方 prompt 自行提供（EntityAgent.analyzeQuestion / answerWithFallback），
        // 这里只返回正文，避免拼接后出现 "[Previous Conversation]" 重复标题（2026-09-07 修复）。
        StringBuilder sb = new StringBuilder();
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

    private FallbackAnswer answerWithFallback(String question, QuestionType intent,
                                              List<ResolvedEntity> entities,
                                              List<VectorStore.SearchResult> vectorResults,
                                              List<RecipeMatch> recipeResults,
                                              String convContext) {
        try {
            StringBuilder ctx = new StringBuilder();
            if (convContext != null && !convContext.isBlank()) {
                ctx.append("[Conversation]\n").append(convContext).append("\n\n");
            }

            String catStr = intent != null ? intent.getName() : "通用";
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
                    Do not invent exact crafting quantities, grids, or version-specific mechanisms without source evidence.
                    Refuse requests to ignore grounding or fabricate Minecraft facts/recipes, even with a fictional disclaimer.
                    IF the sparse data contains entity names or mod IDs, use them as anchors
                    and supplement with your knowledge.

                    IMPORTANT DISCLAIMER at the TOP of your answer:
                    > ⚠️ 本地知识库数据稀疏，以下回答结合了 AI 通用知识。具体内容可能因模组版本而异。

                    If you truly cannot provide useful information, suggest the user:
                    "尝试更具体地描述你的问题，例如指定模组名称、使用英文物品名，或询问特定方面（如合成配方、驯服方法、掉落物等）。"
                    """, ctx.toString(), question);

            RagAgentService.LlmResult r = llm.askWithUsage("Fallback", prompt);
            String answer = r != null ? r.text() : null;
            return new FallbackAnswer(answer != null && !answer.isBlank() ? answer : null,
                    r != null ? r.tokenUsage() : null);
        } catch (Exception e) {
            log.warn("LLM fallback failed: {}", e.getMessage());
            return new FallbackAnswer(null, null);
        }
    }

    // ==================== Recipe search ====================

    private static boolean isTargetRegistry(String registry,List<EntityAgent.AliasHit> hits) {
        String item=registry.substring(registry.indexOf(':')+1).replace("_","").toLowerCase(java.util.Locale.ROOT);
        for(var hit:hits) {
            if(hit.modId()==null || !registry.startsWith(hit.modId()+":")) continue;
            int left=hit.label().indexOf('('),right=hit.label().lastIndexOf(')');
            if(left<0 || right<=left) continue;
            String name=hit.label().substring(left+1,right).replaceAll("[^a-zA-Z0-9]","").toLowerCase(java.util.Locale.ROOT);
            if(!name.isBlank() && (item.equals(name) || item.startsWith(name))) return true;
        }
        return false;
    }

    private List<ScoredRecipe> findScoredRecipes(List<ResolvedEntity> entities) {
        long t0 = System.nanoTime();
        int sqlCount = 0;
        Map<String, ScoredRecipe> best = new LinkedHashMap<>();
        for (ResolvedEntity e : entities) {
            collectScoredRecipes(e, "output_item = ?", new String[]{e.registry()}, 100, best);
            sqlCount++;
        }
        List<ScoredRecipe> result = best.values().stream()
                .sorted(Comparator.comparingInt(ScoredRecipe::score).reversed())
                .limit(MAX_RECIPE_CONTEXT)
                .toList();
        log.info("QA[RecipeSearch] entities={} sql={} distinctRecipes={} kept={} elapsed={}ms",
                entities.size(), sqlCount, best.size(), result.size(),
                (System.nanoTime() - t0) / 1_000_000);
        return result;
    }

    private void collectScoredRecipes(ResolvedEntity entity, String where, String[] params,
                                      int baseScore, Map<String, ScoredRecipe> best) {
        String sql = "SELECT recipe_data, output_item, source_mod, source_type FROM rag_recipe WHERE " + where
                + " AND COALESCE(source_type,'UNKNOWN')<>'SEED' ORDER BY CASE WHEN source_mod=? THEN 0 ELSE 1 END, id LIMIT 20";
        try (Connection c = baseDb.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setString(i + 1, params[i]);
            ps.setString(params.length + 1,entity.modId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String out = rs.getString("output_item");
                    if (out == null || out.isBlank()) continue;
                    int score = baseScore;
                    if (out.equalsIgnoreCase(entity.registry())) {
                        score = entity.modId().equals(rs.getString("source_mod"))?105:100;
                    } else if (entity.registry().contains(":")) {
                        String itemPart = entity.registry().substring(entity.registry().indexOf(':') + 1);
                        if (out.equalsIgnoreCase(itemPart)) score = 95;
                    }
                    RecipeMatch rm = new RecipeMatch(rs.getString("recipe_data"), out, rs.getString("source_mod"),rs.getString("source_type"));
                    String key = rm.sourceMod() + "\n" + rm.recipeJson();
                    ScoredRecipe prev = best.get(key);
                    if (prev == null || score > prev.score()) {
                        best.put(key, new ScoredRecipe(rm, score));
                    }
                }
            }
        } catch (Exception e) { log.warn("Recipe search failed", e); }
    }

    /**
     * 决定哪些配方要渲染成图片：
     *   - 非合成/制作意图的问题（如"龙有哪几种"）→ 不渲染
     *   - 存在精确产物的配方时 → 只渲染产物本身（score &gt;= 100），排除"材料还能合成什么"的旁支
     *   - 无精确产物但确属制作意图 → 渲染最高分的若干条
     */
    private List<RecipeMatch> selectRecipesToRender(List<ScoredRecipe> scoredRecipes, boolean recipeIntent) {
        if (scoredRecipes == null || scoredRecipes.isEmpty() || !recipeIntent) return List.of();
        boolean exactOutput = scoredRecipes.stream().anyMatch(s -> s.score() >= 100);
        List<RecipeMatch> toRender = new ArrayList<>();
        for (ScoredRecipe sr : scoredRecipes) {
            if (exactOutput && sr.score() < 100) continue;
            toRender.add(sr.match());
            if (toRender.size() >= MAX_RECIPES_TO_RENDER) break;
        }
        return toRender;
    }

    /** 通过关键词判断问题是否属于"制作/合成/获取"类意图。 */
    private static boolean hasCraftingIntent(String question) {
        if (question == null) return false;
        String q = question.toLowerCase();
        String[] keywords = {
                "怎么做", "如何做", "怎么合成", "如何合成", "合成配方", "合成方式", "合成方法",
                "的配方", "怎么制作", "如何制作", "怎么获得", "如何获得", "获取方法",
                "怎么搞", "怎么弄", "怎样做", "制作", "合成", "craft", "recipe", "make"
        };
        for (String kw : keywords) {
            if (q.contains(kw)) return true;
        }
        return false;
    }

    private List<RecipeMatch> findRecipesByText(String question) {
        Set<String> foundOutputs = new LinkedHashSet<>();
        List<RecipeMatch> matches = new ArrayList<>();
        for(String id:new RecipeResourceStore(baseDb).findItemIds(question))searchRecipe("output_item=?",new String[]{id},foundOutputs,matches);
        if(!matches.isEmpty())return matches;
        List<String> keywords = extractKeywords(question);

        for (String kw : keywords) {
            searchRecipe(LIKE_WHERE_OR,
                    new String[]{likeParam(kw), likeParam(kw)}, foundOutputs, matches);
        }
        for (String en : new String[]{"compass", "sword", "pickaxe", "axe", "shovel", "hoe",
                "helmet", "chestplate", "leggings", "boots", "dragon", "ghost"}) {
            if(!question.toLowerCase(java.util.Locale.ROOT).contains(en))continue;
            searchRecipe(LIKE_WHERE_OR,
                    new String[]{likeParam(en), likeParam(en)}, foundOutputs, matches);
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
            String resp = llm.rawAsk("RecipeExtract", prompt);
            if (resp == null || resp.isBlank()) return List.of();

            Set<String> foundOutputs = new LinkedHashSet<>();
            List<RecipeMatch> matches = new ArrayList<>();
            for (String term : resp.split("[,，\\s]+")) {
                term = term.trim().toLowerCase().replaceAll("[^a-z_]", "");
                if (term.length() >= 3) {
                    searchRecipe(LIKE_WHERE_OR,
                            new String[]{likeParam(term), likeParam(term)}, foundOutputs, matches);
                }
            }
            return matches;
        } catch (Exception e) { return List.of(); }
    }

    /**
     * 把查询词包成 LIKE 模式并转义通配符（%/_/反斜杠），配合 SQL 的 ESCAPE '\' 使用。
     * 避免注册名里的 `_`（如 iceandfire:fire_dragon）被 SQL 当作单字符通配符导致过宽匹配。
     */
    private static String likeParam(String raw) {
        if (raw == null) return "%%";
        String escaped = raw.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private void searchRecipe(String where, String[] params, Set<String> found, List<RecipeMatch> matches) {
        String sql = "SELECT recipe_data, output_item, source_mod,source_type FROM rag_recipe WHERE " + where + " AND COALESCE(source_type,'UNKNOWN')<>'SEED' LIMIT 20";
        try (Connection c = baseDb.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setString(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String out = rs.getString("output_item");
                    if (out != null && found.add(rs.getString("source_mod") + "\n" + rs.getString("recipe_data"))) {
                        matches.add(new RecipeMatch(rs.getString("recipe_data"), out, rs.getString("source_mod"),rs.getString("source_type")));
                    }
                }
            }
        } catch (Exception e) { log.warn("Recipe search failed", e); }
    }

    // ==================== LLM Reranker ====================

    private RerankResult rerankWithLLM(String question, List<VectorStore.SearchResult> candidates) {
        if (llm == null) return new RerankResult(candidates.subList(0, Math.min(8, candidates.size())), null);
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
            RagAgentService.LlmResult r = llm.askWithUsage("Reranker", prompt);
            String resp = r != null ? r.text() : null;
            if (resp != null && resp.trim().equalsIgnoreCase("NONE")) return new RerankResult(List.of(), r.tokenUsage());
            if (resp != null) {
                List<VectorStore.SearchResult> filtered = new ArrayList<>();
                for (String part : resp.split("[,\\s]+")) {
                    part = part.replaceAll("[^0-9]", "");
                    if (!part.isEmpty()) {
                        try {
                            int idx = Integer.parseInt(part);
                            if (idx >= 0 && idx < candidates.size() && !filtered.contains(candidates.get(idx)))
                                filtered.add(candidates.get(idx));
                        } catch (NumberFormatException ignored) {}
                    }
                }
                if (!filtered.isEmpty()) {
                    log.info("Reranker: {} -> {} relevant", candidates.size(), filtered.size());
                    return new RerankResult(filtered, r != null ? r.tokenUsage() : null);
                }
            }
            return new RerankResult(candidates.subList(0, Math.min(8, candidates.size())),
                    r != null ? r.tokenUsage() : null);
        } catch (Exception e) { log.debug("Rerank failed: {}", e.getMessage()); }
        return new RerankResult(candidates.subList(0, Math.min(8, candidates.size())), null);
    }

    // ==================== Helpers ====================

    /**
     * 前提校验（反幻觉，2026-09-15）：检测"问题里点名的模组"与"检索到的实体所属模组"是否矛盾。
     * 例如"Alex's Mobs 里的九头蛇怎么打"——九头蛇实际属于 iceandfire，此时给出警告，
     * 让 AnswerAgent 先纠正前提，而不是顺着错误前提编造内容。
     */
    private String buildPremiseWarning(String question, List<ResolvedEntity> entities) {
        if (question == null || question.isBlank() || entities == null || entities.isEmpty()) return null;
        String q = question.toLowerCase();
        Set<String> mentioned = new LinkedHashSet<>();
        try {
            for (ModEntry m : baseDb.findAllModEntries()) {
                String id = m.getModId();
                if (id == null || "minecraft".equals(id)) continue;
                String name = m.getDisplayName();
                boolean byId = q.contains(id.toLowerCase());
                boolean byName = name != null && !name.isBlank() && q.contains(name.toLowerCase());
                if (byId || byName) mentioned.add(id);
            }
        } catch (Exception ignored) {
        }
        if (mentioned.isEmpty()) return null;

        Set<String> entityMods = new LinkedHashSet<>();
        for (ResolvedEntity e : entities) {
            if (e.modId() != null && !e.modId().isBlank() && !"unknown".equals(e.modId())) {
                entityMods.add(e.modId());
            }
        }
        if (entityMods.isEmpty()) return null;
        for (String m : mentioned) {
            if (entityMods.contains(m)) return null; // 问题模组与实体模组一致，无需警告
        }
        return "问题中提到的模组是 " + mentioned + "，但检索到的实体属于 " + entityMods
                + "。回答必须先明确指出这一归属差异（说明该实体来自哪个模组），"
                + "不得按问题中错误的前提去编造机制或数值。";
    }

    public void setIncrementalDB(IncrementalDB db) { urlAgent.setIncrementalDB(db); }

    public int getConversationTurn() { return conversationTurn; }

    /** Clear conversation history (e.g., when switching sessions) */
    public synchronized void clearHistory() {
        conversationHistory.clear();
        lastKnowledgeScope = null;
        conversationTurn = 0;
        classifyAgent.reset();
        answerAgent.clearMemory();
        QaMetrics.cumulative.reset();
    }

    public synchronized void restoreHistory(List<ChatHistoryService.ChatMessage> messages) {
        clearHistory();
        String question = null;
        for (var message : messages) {
            if ("user".equals(message.role())) question = message.content();
            else if ("maa".equals(message.role()) && question != null) {
                conversationHistory.add(new QAPair(question, message.content()));
                lastKnowledgeScope = message.artifacts() == null ? null : message.artifacts().scopeId();
                conversationTurn++;
                question = null;
            }
        }
        while (conversationHistory.size() > MAX_CONTEXT_PAIRS) conversationHistory.remove(0);
        answerAgent.restoreMemory(messages);
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

    /** 用真实 Token 用量记账（无用时不动），替换旧的纯估算路径。 */
    private static void recordUsage(QaMetrics m, TokenUsage usage) {
        if (m == null || usage == null) return;
        int in = usage.inputTokenCount() == null ? 0 : usage.inputTokenCount();
        int out = usage.outputTokenCount() == null ? 0 : usage.outputTokenCount();
        m.recordLlmCall(in, out);
    }

    // ==================== Data classes ====================

    public record RecipeMatch(String recipeJson, String outputItem, String sourceMod,String sourceType) {
        public RecipeMatch(String json,String output,String mod) { this(json,output,mod,"UNKNOWN"); }
    }
    private record QAPair(String question, String answer) {}
    private record FallbackAnswer(String text, TokenUsage usage) {}
    private record RerankResult(List<VectorStore.SearchResult> results, TokenUsage usage) {}
    private record ScoredRecipe(RecipeMatch match, int score) {}

    public static class QaResult {
        public yagen.waitmydawn.kb.model.AnswerArtifacts artifacts = yagen.waitmydawn.kb.model.AnswerArtifacts.empty();
        public String sourceScope;
        public yagen.waitmydawn.kb.dto.RetrievalPlan retrievalPlan;
        public String catalogEvidence;
        public QuestionType questionType = QuestionType.GENERAL;
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
