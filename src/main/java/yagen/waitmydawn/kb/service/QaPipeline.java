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
 *   Agent 3 — AnswerAgent:   LLM composes evidence-bound Markdown; failures retain local evidence
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
    private final RuntimeEntityMedia entityMedia;
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
    private yagen.waitmydawn.kb.model.AnswerArtifacts.TurnState dialogueFocus;
    private int conversationTurn = 0;
    private String lastKnowledgeScope;
    /** 上一轮是否请求过图片，用于“改成绿色/只看…”这类跟进问题继承配图意图。 */
    private boolean lastTurnImages;
    private final KnowledgeRetrievalService knowledge;
    private final Path mediaDirectory;
    private Path assetDirectory(){return mediaDirectory;}

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
        urlAgent.setMediaDirectory(assetDir);
        this.metricsHistory = new MetricsHistoryService(dataDir);
        this.knowledge = new KnowledgeRetrievalService(baseDb, embedder, assetDir);
        this.entityMedia=new RuntimeEntityMedia(baseDb,assetDir);this.mediaDirectory=assetDir;
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
        if(llm!=null&&llm.toolLoopAvailable())return processToolTurn(question,selectedScope);

        for (var database : dbManager.getActiveDatabases())
            VectorStore.verifyProfile(database, embedder.profile(), false);
        long t0 = System.nanoTime();
        conversationTurn++;
        QaMetrics m = new QaMetrics();
        m.timestamp = Instant.now();
        m.conversationTurn = conversationTurn;
        m.questionHash = sha256hex(question);
        m.sessionId = dbManager.getCurrentSessionId();
        m.runtimeEvidence=RuntimeEvidencePolicy.snapshot(baseDb);

        var resolution = knowledge.resolve(question, selectedScope, lastKnowledgeScope);
        KnowledgeRetrievalService.Retrieval imported = null;
        String retrievalQuestion = question;
        String resolvedScope=resolution.scope()!=null?resolution.scope().id():resolution.documentId()==null?null:"document:"+resolution.documentId();
        if(lastKnowledgeScope!=null && lastKnowledgeScope.equals(resolvedScope) && !conversationHistory.isEmpty()
                && question.matches("(?s).*(它|这个|那个|上面|刚才|继续|还有|第[0-9]+页).*"))
            retrievalQuestion=conversationHistory.getLast().question()+"；"+question;
        var localDecision=LocalQuestionPolicy.decide(question);
        String scopeMod=resolution.scope()==null?null:knowledge.boundModId(resolution.scope());
        boolean installedScope=scopeMod!=null&&baseDb.findAllModEntries().stream().anyMatch(mod->scopeMod.equals(mod.getModId()));
        boolean selectedDocument=resolution.documentId()!=null || resolution.scope()!=null&&!resolution.scope().id().startsWith("runtime:")
                &&(selectedScope!=null&&!selectedScope.isBlank()||!installedScope);
        if(localDecision.localOnly()&&!selectedDocument&&resolution.warning()==null&&(resolution.scope()==null||RuntimeKnowledgeStore.active(baseDb)!=null)) {
            var source=RuntimeKnowledgeStore.activeSource(baseDb);
            var runtime=source==null?null:knowledge.scopes().stream().filter(s->s.id().startsWith("runtime:")&&s.sourceIds().contains(source)).findFirst().orElse(null);
            var localScope=runtime==null?new KnowledgeRetrievalService.Resolution(null,null,"当前没有可用的游戏快照，请先导入完整的 MKB-Export 导出包。",true)
                    :knowledge.resolve(question,runtime.id(),null);
            String lookupQuestion=question;
            if(runtime!=null&&!conversationHistory.isEmpty()&&question.matches("(?s).*(它|这个|那个|上面|刚才).*")) {
                var previous=MediaRequestPlanner.plan(conversationHistory.getLast().question(),MediaCatalog.load(baseDb,RuntimeKnowledgeStore.active(baseDb)),Set.of());
                var subjects=previous.requests().stream().map(request->request.subject().registry()).distinct().toList();
                if(subjects.size()==1)lookupQuestion=subjects.getFirst()+" "+question;
                else if(subjects.size()>1)localScope=new KnowledgeRetrievalService.Resolution(runtime,null,"上一轮包含多个对象，请明确本轮要查询的名称或注册名。",true);
            }
            var local=runtime==null||localScope.warning()!=null?null:knowledge.retrieve(lookupQuestion,localScope,false);
            m.addTrace("NetworkPolicy",0,localDecision.reason());
            return processKnowledgeTurn(question,localScope,local,m,t0,lookupQuestion);
        }
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
            if(imported!=null&&!localDecision.localOnly()) imported=knowledge.hydrate(imported,retrievalQuestion);
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

        long recipeProbeStarted=System.nanoTime();
        var localRecipeProbe=findScoredRecipes(resolvedEntities);
        if(localRecipeProbe.isEmpty()&&hasCraftingIntent(question))localRecipeProbe=findSnapshotRecipes(question).stream().map(recipe->new ScoredRecipe(recipe,100)).toList();
        long recipeProbeMs=(System.nanoTime()-recipeProbeStarted)/1_000_000;
        m.addTrace("LocalRecipeProbe",recipeProbeMs,"联网前命中本地配方 "+localRecipeProbe.size()+" 条");

        // 网页别名是候选来源，先检索已导入正文，再决定是否获取候选页。
        long t25 = System.nanoTime();
        urlAgent.resetUsage();
        IncrementalDB incDb = dbManager.getCurrentIncDB();
        urlAgent.setIncrementalDB(incDb);
        UrlAgent.UrlFetchResult fetchResult=fetchForReason(List.of(),List.of(),entityResolution.subQueries(),"LOCAL_KNOWLEDGE_SEARCH");
        boolean localText=imported!=null&&!imported.hits().isEmpty()&&imported.bestLexical()>=0.6
                || !fetchResult.subQueryResults().isEmpty()&&fetchResult.subQueryResults().stream().allMatch(sub->sub.chunks().stream().anyMatch(hit->hit.score()>0.3&&KnowledgeSearchText.overlap(sub.subQuery(),hit.chunkText())>=0.6));
        List<VectorStore.SearchResult> runtimeAttributes;
        Set<String> attributeCovered;
        long structuredStarted=System.nanoTime();
        try{var attributes=RuntimeAttributes.queryEvidence(baseDb,resolvedEntities.stream().map(ResolvedEntity::registry).toList(),question);attributeCovered=attributes.coveredIds();var direct=new ArrayList<>(attributes.hits());direct.addAll(RuntimeRuleEvidence.read(baseDb,resolvedEntities.stream().map(ResolvedEntity::registry).toList(),question));direct.addAll(RuntimeStructures.evidence(baseDb,resolvedEntities.stream().map(ResolvedEntity::registry).toList(),question));direct.addAll(RuntimeSpawns.evidence(baseDb,resolvedEntities.stream().map(ResolvedEntity::registry).toList(),question));direct.addAll(RuntimeMagic.evidence(baseDb,resolvedEntities.stream().map(ResolvedEntity::registry).toList(),question));direct.addAll(AcquisitionIndex.evidence(baseDb,resolvedEntities.stream().map(ResolvedEntity::registry).toList(),question));runtimeAttributes=List.copyOf(direct);}catch(Exception invalid){throw new IllegalStateException("实机规则证据读取失败",invalid);}
        m.addTrace("StructuredQuery",(System.nanoTime()-structuredStarted)/1_000_000,"直接读取当前快照属性与规则证据 "+runtimeAttributes.size()+" 条；属性覆盖 "+attributeCovered.size()+" 个对象");
        if(RuntimeSpawns.configurationOnly(question)&&RuntimeSpawns.covered(resolvedEntities.stream().map(ResolvedEntity::registry).toList(),runtimeAttributes))localText=true;
        boolean attributesOnly=RuntimeAttributes.requested(question)&&!question.matches("(?s).*(获取|获得|掉落|交易|打法|怎么打|攻略|合成|制作|生成|用途|作用|驯服|繁殖|来源|哪里|在哪|推荐|机制|恢复|治疗|提升|强化|升级|为什么).*" );
        if(attributesOnly&&!runtimeAttributes.isEmpty()&&!resolvedEntities.isEmpty()&&resolvedEntities.stream().allMatch(entity->attributeCovered.contains(entity.registry())))localText=true;
        boolean enumerateOnly=plan!=null&&plan.enumerate()&&!plan.needsDetails()&&entityResolution.wikiTopics().isEmpty();
        if(!localText&&!enumerateOnly && (localRecipeProbe.isEmpty()||localDecision.externalKnowledge()) || localDecision.explicitWeb()) {
            fetchResult=fetchForReason(entityResolution.aliasHits(),entityResolution.wikiTopics(),entityResolution.subQueries(),localDecision.explicitWeb()?"EXPLICIT_WEB_REQUEST":"ENTITY_DETAILS_GAP");
            m.addTrace("NetworkPolicy",0,"已检索本地正文；候选资料需补充或用户明确请求网页");
        } else m.addTrace("NetworkPolicy",0,"复用本地正文或目录，未获取候选网页");
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

        if(!knowledge.scopes().isEmpty())imported=knowledge.retrieve(retrievalQuestion,resolution,false);
        // === Vector semantic search（合并 UrlAgent 按子问题检索的结果）===
        long t4 = System.nanoTime();
        List<VectorStore.SearchResult> vectorResults = fetchResult.subQueryResults().stream()
                .flatMap(sub -> sub.chunks().stream())
                .distinct()
                .sorted(Comparator.comparingDouble(VectorStore.SearchResult::score).reversed())
                .limit(180)
                .toList();
        if(!runtimeAttributes.isEmpty()){var combined=new ArrayList<>(runtimeAttributes);combined.addAll(vectorResults);vectorResults=combined.stream().distinct().toList();}
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
        List<ScoredRecipe> scoredRecipes = localRecipeProbe.isEmpty()?findScoredRecipes(resolvedEntities):localRecipeProbe;
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
        m.recipeSearchTimeMs = recipeProbeMs+(System.nanoTime() - t5) / 1_000_000;
        m.recipeResultCount = recipeResults.size();
        m.dbQueryCount += Math.max(1, resolvedEntities.size() * 2);
        m.addTrace("RecipeSearch", m.recipeSearchTimeMs,
                String.format("%d recipes found", m.recipeResultCount));

        // JAR 自定义配方通常只描述机器输入输出，工作站正文补充原料来源及交互步骤。
        if (hasCraftingIntent(question) && localDecision.externalKnowledge() && !localText && !recipeResults.isEmpty()) {
            var support = RecipeWorkstationEvidence.select(question, recipeResults,
                    new WebCatalogStore(baseDb), subPageUrls.values().stream().collect(java.util.stream.Collectors.toSet()));
            if (!support.isEmpty()) {
                long supportStart = System.nanoTime();
                var extra = fetchForReason(support,List.of(),support.stream().map(EntityAgent.AliasHit::subQuery).toList(),"WORKSTATION_KNOWLEDGE_GAP");
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

        var oneHop = localDecision.externalKnowledge()&&!localText||localDecision.explicitWeb()
                ? OneHopEvidence.select(question, plan == null ? null : plan.modId(), vectorResults,
                    recipeResults, new WebCatalogStore(baseDb), new HashSet<>(subPageUrls.values()), llm)
                : new OneHopEvidence.Selection(List.of(),List.of());
        if (!oneHop.pages().isEmpty() || !oneHop.vanillaTopics().isEmpty()) {
            long supportStart = System.nanoTime();
            var queries = new ArrayList<>(oneHop.pages().stream().map(EntityAgent.AliasHit::subQuery).toList());
            queries.addAll(oneHop.vanillaTopics());
            var extra = fetchForReason(oneHop.pages(),oneHop.vanillaTopics(),queries,"DIRECT_PREREQUISITE_GAP");
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
        if(!runtimeAttributes.isEmpty())finalSubQueries.add(new UrlAgent.SubQueryResult("当前快照属性与获取规则；默认实例不证明未采样状态，候选来源不保证获得",runtimeAttributes.stream().filter(v->selectedIds.contains(v.sourcePath()+"\n"+v.chunkId())).toList(),List.of()));
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

        String premiseWarning = buildPremiseWarning(question, resolvedEntities);
        String lootWarning=RuntimeLootSummary.capability(baseDb,question);
        if(lootWarning!=null)premiseWarning=premiseWarning==null?lootWarning:premiseWarning+"；"+lootWarning;
        var relevantMods=new HashSet<String>();
        if(plan!=null && plan.modId()!=null) relevantMods.add(plan.modId());
        resolvedEntities.forEach(e->relevantMods.add(e.modId()));
        vectorResults.forEach(v->{ if(v.modId()!=null && !v.modId().isBlank()) relevantMods.add(v.modId()); });
        result.sourceScope=baseDb.findAllModEntries().stream().filter(mod->relevantMods.isEmpty() || relevantMods.contains(mod.getModId())).map(mod -> mod.getModId()+": modVersion="+mod.getVersion()
                +", Minecraft="+mod.getMcVersion()+", loader="+mod.getLoader()+", "+KnowledgeSourcePolicy.describe(mod)).collect(java.util.stream.Collectors.joining("\n"))
                +"\nnull 表示 JAR 元数据未提供版本。移植版本默认沿用原模组的百科机制知识；实际配方、注册名、纹理由安装 JAR 优先确定。仅证据指出具体差异时披露该差异，不把缺少移植版独立百科视为知识缺失。";
        result.sourceScope=runtimeScope(result.sourceScope)+"\n属性回答：匹配当前快照与状态的实机属性证据优先于网页；区分基础值和采样有效值，物品修饰器不得称为最终伤害。缺失实机字段时明确披露，网页只能作为标注来源的补充。交易工厂入选率不是具体随机物品的最终概率；PARTIAL与UNSUPPORTED不得解释成没有内容。铁砧基础规则不证明给定输入的最终费用；模板容器关系不证明自然生成。结构注册定义、模板资源、多方块工作示例不等于世界中已生成或已运行的实例。不能从模板文件名猜测结构分布；布局图不是真实世界截图，未指定单元不自动代表空气。METADATA_ONLY与UNSUPPORTED须说明缺口。材料表不是战利品表；具体加工输入与产物须核对关联实机配方。生成配置中的权重不是实际遭遇概率；生成池数量不是每区块保证数量，已加载维度引用不是唯一分布范围。放置谓词未解析，空池或未注册不表示无法生成。自然生成、结构池覆盖、刷怪笼、特殊事件和召唤不可混同，缺少动态入口须披露并使用有来源的补充证据。药水组成、效果参数与附魔规则优先采用匹配环境的实机元数据；基础药水定义不覆盖堆叠自定义效果，随机附魔候选不等于固定输出，能量成本不等于铁砧费用或交易价格；旧版本未提供的数据驱动规则不得推断为不存在。获取索引的 unverified 标记只表示输出预览候选，必须核对原始定义，不能作为动态配方最终结果的保证。";
        if(question.matches("(?s).*(结构|多方块|布局|搭建|锻炉|生成|刷怪|群系|召唤|交易|酿造|药水|效果|附魔|掉落|战利品|铁砧).*"))result.sourceScope+="\n"+RuntimeRules.capabilities(baseDb);
        var importedContext = imported;
        if (importedContext != null) {
            var ids = vectorResults.stream().map(VectorStore.SearchResult::chunkId).collect(java.util.stream.Collectors.toSet());
            var contextSnapshot = importedContext;
            var sourceIds=contextSnapshot.hits().stream().filter(h -> ids.contains(h.chunkId())).map(h -> KnowledgeRetrievalService.sourceId(contextSnapshot,h.chunkId())).collect(java.util.stream.Collectors.toSet());
            var sources = contextSnapshot.artifacts().sources().stream().filter(s -> sourceIds.contains(s.id())).toList();
            var pics = importedContext.artifacts().pictures().stream().filter(p -> p.chunkIds().stream().anyMatch(ids::contains)).toList();
            result.artifacts = new yagen.waitmydawn.kb.model.AnswerArtifacts(null, null, sources, pics);
            var kept=contextSnapshot.hits().stream().filter(h -> ids.contains(h.chunkId())).toList();
            importedContext=new KnowledgeRetrievalService.Retrieval(kept,result.artifacts,"",contextSnapshot.bestLexical(),contextSnapshot.diagnostics());
            if(!localText||localDecision.explicitWeb())importedContext=knowledge.hydrate(importedContext,question);
            result.artifacts=importedContext.artifacts();m.knowledgeRetrieval=importedContext.diagnostics();
        }
        AnswerMedia.attachVectorSources(result,vectorResults);
        prepareMedia(result,question);
        AnswerContext ctx = new AnswerContext(
                question, intent, resolvedEntities, answerRecipeMatches,
                vectorResults, incInfo, finalSubQueries,
                premiseWarning,result.sourceScope, entityMedia.evidence(result,importedContext == null ? null : filteredKnowledgeEvidence(result.artifacts,importedContext.diagnostics())));

        long answerLlmStart = llm != null ? llm.usageTrace().latencyMs : 0;
        result.answer = answerAgent.compose(ctx);
        m.answerGeneration=answerAgent.lastGenerationMode();
        m.answerLlmMs = llm != null ? llm.usageTrace().latencyMs - answerLlmStart : 0;
        TokenUsage answerUsage = answerAgent.lastUsage();
        if (answerUsage != null) recordUsage(m, answerUsage);
        else m.recordLlmCall(estimateContextTokens(ctx), estimateAnswerTokens(result.answer));

        m.fallbackUsed = "MODEL_ERROR".equals(m.answerGeneration);
        if (premiseWarning != null && result.answer != null) {
            // 前提校验结果强制前置：不依赖 LLM 是否愿意改口（反幻觉硬约束）
            result.answer = "> ⚠️ " + premiseWarning + "\n\n" + result.answer;
        }
        guardInstallationClaims(result, m);
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
        m.resourceAccess=RetrievalTelemetry.resources();m.workAudit=RetrievalTelemetry.work();
        m.workAudit.forEach((name,work)->m.addTrace(name,work.millis(),"数量="+work.count()));
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

        result.metrics = m;



        if (Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("Question processing cancelled");

        // Persist metrics
        

        // Save to conversation history
        lastKnowledgeScope = null;
        long mediaStarted=System.nanoTime();entityMedia.finish(result,question);m.guideIllustrations=GuideIllustrationCoverage.finish(result,question);
        m.mediaPerformance=result.mediaPerformance;m.mediaAudit=MediaAudit.capture(question,result.answer,result.artifacts,assetDirectory(),result.entityImageAudit);m.answerLength=result.answer.length();
        long mediaDuration=(System.nanoTime()-mediaStarted)/1_000_000; m.addTrace("MediaPreparation",result.mediaPreparationMs,"已提供给回答模型的配图证据");m.totalTimeMs+=mediaDuration;m.addTrace("MediaSelection",mediaDuration,"最终图片与资产审计");m.accumulate();log.info("QA {}",m.toLogString());
        try{metricsHistory.save(m);}catch(Exception failure){log.warn("Media metrics save failed",failure);}
        conversationHistory.add(new QAPair(question, result.answer));
        while (conversationHistory.size() > MAX_CONTEXT_PAIRS) {
            conversationHistory.remove(0);
        }

        return result;
    }

    private QaResult processToolTurn(String question,String selectedScope) {
        long started=System.nanoTime();conversationTurn++;var m=new QaMetrics();m.timestamp=Instant.now();m.conversationTurn=conversationTurn;m.sessionId=dbManager.getCurrentSessionId();m.questionHash=sha256hex(question);m.runtimeEvidence=RuntimeEvidencePolicy.snapshot(baseDb);
        var result=new QaResult();result.sourceScope=selectedScope==null?"当前快照与本地资料":selectedScope;
        urlAgent.reset();urlAgent.resetUsage();urlAgent.setIncrementalDB(dbManager.getCurrentIncDB());
        String effectiveQuestion=DialogueFocus.expand(question,dialogueFocus,m.runtimeEvidence==null?null:m.runtimeEvidence.profile(),selectedScope);
        result.mediaQuestion=effectiveQuestion;
        if(!effectiveQuestion.equals(question)&&question.matches("(?s).*(介绍|展示|改成|只看|外观).*")){
            var ids=java.util.regex.Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+").matcher(effectiveQuestion.substring(0,effectiveQuestion.length()-question.length()));
            while(ids.find())if(ids.start()==0||effectiveQuestion.charAt(ids.start()-1)!='=')result.conversationTargets.add(ids.group());
        }
        var turn=new QaAgentTurn(baseDb,baseVectorStore,knowledge,urlAgent,result,effectiveQuestion,selectedScope,lastKnowledgeScope,()->prepareMedia(result,result.mediaQuestion));
        turn.inheritImages=lastTurnImages;
        turn.rawQuestion=question;
        String toolContext="Selected scope: "+result.sourceScope+RuntimeEvidencePolicy.context(m.runtimeEvidence,turn.documentScope())+"\nCurrent object/state task: "+effectiveQuestion+"\n"+buildConversationContext();
        var outcome=new AgentToolLoop().run(llm::toolChat,turn,effectiveQuestion,toolContext);result.toolTrace=outcome;m.answerGeneration=outcome.reason().startsWith("MODEL_ERROR")?"MODEL_ERROR":"NO_EVIDENCE";m.fallbackUsed=outcome.reason().startsWith("MODEL_ERROR");
        for(var decision:outcome.decisions())m.addTrace("ToolModel:"+decision.round(),decision.elapsedMs(),decision.status()+" · 请求工具 "+decision.toolRequests());
        for(var event:outcome.events())m.addTrace("Tool:"+event.tool(),event.elapsedMs(),event.status()+" · 返回字符 "+event.returnedChars());m.addTrace("ToolLoop",(System.nanoTime()-started)/1_000_000,outcome.reason()+" · 决策轮数 "+outcome.rounds());
        try{var directory=mediaDirectory.resolve("tool-runs");java.nio.file.Files.createDirectories(directory);new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(directory.resolve(java.util.UUID.randomUUID()+".json").toFile(),java.util.Map.of("questionHash",m.questionHash,"outcome",outcome,"sources",result.vectorResults.stream().map(hit->BuildTrace.safeLabel(hit.sourcePath())).distinct().toList()));}catch(java.io.IOException failure){result.mediaWarnings.add("工具审计保存失败："+failure.getClass().getSimpleName());}
        BuildCancellation.check();AnswerMedia.attachVectorSources(result,result.vectorResults);turn.hydrateImages();turn.prepareFinalMedia();
        if(turn.clarification!=null)result.answer=turn.clarification;
        else if(result.vectorResults.isEmpty()&&result.recipeResults.isEmpty()&&result.artifacts.pictures().isEmpty()) {
            if(turn.imagesRequested&&!result.mediaWarnings.isEmpty()){result.answer="本轮没有找到符合指定对象与状态的可用图片，不能用默认外观或其他对象的图替代。";m.answerGeneration="LOCAL_MEDIA_LIMITATION";}
            else result.answer=outcome.reason().startsWith("MODEL_ERROR")?"工具决策模型未成功返回，本轮尚未完成取证。请检查模型服务或稍后重试。":"本轮没有取得足够证据，无法确认。工具执行状态："+outcome.reason();
        }
        else {
            var context=new AnswerContext(question,QuestionType.GENERAL,result.resolvedEntities,result.recipeResults.stream().map(recipe->new AnswerAgent.RecipeMatch(recipe.recipeJson(),recipe.outputItem(),recipe.sourceMod(),recipe.sourceType())).toList(),result.vectorResults,null,List.of(),null,result.sourceScope,RuntimeEvidencePolicy.context(m.runtimeEvidence,turn.documentScope())+"\nCurrent task: "+effectiveQuestion+"\n"+turn.coverageContext()+"\n名称口径：物品、方块与工作站的中文名必须使用证据里的 name 字段，不要自行改写或换成俗称。"+"\n面向玩家的组织规则：先给结论与可执行做法，再补机制和数值；不写内部字段名、状态键、哈希或 appearanceHash/FULL_CARTESIAN/SAFE_DETACHED_SETUP 这类导出元数据；表格没有来源就去掉来源列，不留空列；没有证据的“材料准备（一跳）”不写；对象没有装备槽位时不必说明；不要整段写“本轮无法确认的部分”，只在确实缺关键事实时用一句话点明。"+"\n属性口径：AttributeMap 中等于 1.0 的法术强度、法术抗性等数值通常是工厂默认值，不代表模组实际战斗数值；若网页或源码给出不同数值（例如 125%、25%），引用来源并说明差异，不要用默认值覆盖来源。"+entityMedia.evidence(result,KnowledgeRetrievalService.evidence(result.artifacts,null)));
            long answerStarted=System.nanoTime();result.answer=answerAgent.composeSinglePass(context);m.answerGeneration=answerAgent.lastGenerationMode();m.addTrace("ToolAnswer",(System.nanoTime()-answerStarted)/1_000_000,m.answerGeneration);
            guardInstallationClaims(result,m);
            result.answer=NameNormalizer.apply(baseDb,m.runtimeEvidence==null?null:m.runtimeEvidence.profile(),result.answer);
            result.answer=AnswerPostProcess.apply(result.answer,question);
            if(!outcome.reason().equals("READY"))result.answer+="\n\n本轮取证已停止（"+outcome.reason()+"），以上内容仅覆盖已取得的证据。";
        }
        BuildCancellation.check();result.artifacts=AnswerMedia.snapshotCitations(result.answer,result.artifacts);result.answer=AnswerMedia.stripUnresolvedPlaceholders(AnswerMedia.bind(result.answer,result.artifacts));entityMedia.finish(result,Objects.toString(result.mediaQuestion,question));m.guideIllustrations=GuideIllustrationCoverage.finish(result,question);m.mediaPerformance=result.mediaPerformance;m.mediaAudit=MediaAudit.capture(question,result.answer,result.artifacts,assetDirectory(),result.entityImageAudit);
        var io=RetrievalTelemetry.current();m.resourceAccess=RetrievalTelemetry.resources();m.workAudit=RetrievalTelemetry.work();m.httpRequestCount=io.httpRequests;m.httpErrorCount=io.httpErrors;m.documentEmbeddingCount=io.documentEmbeddings;m.queryEmbeddingCount=io.queryEmbeddings;m.reusedEmbeddingCount=io.reusedEmbeddings;m.embeddingTimeMs=io.embeddingNanos/1_000_000;
        m.dataTier=!result.recipeResults.isEmpty()?"TIER A":result.vectorResults.isEmpty()?"TIER D":"TIER C";m.contextChunksUsed=result.vectorResults.size();m.knowledgeCitedSourceIds=result.artifacts.citedSourceIds();
        var usage=llm.usageTrace();m.llmCallCount=usage.calls;m.tokenEstimateInput=usage.inputTokens;m.tokenEstimateOutput=usage.outputTokens;m.tokenTotalCount=usage.inputTokens+usage.outputTokens;m.unknownUsageCalls=usage.unknownUsageCalls;m.recipeResultCount=result.recipeResults.size();m.answerLength=result.answer.length();m.totalTimeMs=(System.nanoTime()-started)/1_000_000;m.computeTtft();m.computeQualityIndicators();m.accumulate();result.metrics=m;
        try{metricsHistory.save(m);}catch(Exception failure){log.warn("Tool metrics save failed",failure);}if(!outcome.reason().startsWith("MODEL_ERROR")){lastKnowledgeScope=turn.scopeId();dialogueFocus=DialogueFocus.capture(m.runtimeEvidence==null?null:m.runtimeEvidence.profile(),lastKnowledgeScope,result.mediaPlan,turn.focusObjects());}
        lastTurnImages=turn.imagesRequested||!result.artifacts.pictures().isEmpty();
        result.artifacts=new yagen.waitmydawn.kb.model.AnswerArtifacts(result.artifacts.scopeId(),result.artifacts.scopeLabel(),result.artifacts.sources(),result.artifacts.pictures(),result.artifacts.citedSourceIds(),dialogueFocus);conversationHistory.add(new QAPair(question,result.answer));while(conversationHistory.size()>MAX_CONTEXT_PAIRS)conversationHistory.remove(0);return result;
    }

    public List<KnowledgeRetrievalService.Scope> knowledgeScopes() { return knowledge.scopes(); }
    private UrlAgent.UrlFetchResult fetchForReason(List<EntityAgent.AliasHit> aliases,List<String> topics,List<String> queries,String cause) {
        try(var scope=RetrievalTelemetry.resourceCause(cause)){return urlAgent.fetchAndRetrieve(aliases,topics,queries);}
    }
        /** 安装结论必须与快照成员一致；网页来源不能证明安装。 */
    private void guardInstallationClaims(QaResult result,QaMetrics m){
        var snapshot=m.runtimeEvidence;if(snapshot==null||result.answer==null||result.answer.isBlank())return;
        var conflicts=InstallationClaims.conflicts(result.answer,snapshot.mods().keySet());if(conflicts.isEmpty())return;
        result.answer=InstallationClaims.repair(result.answer,conflicts);
        String detail=conflicts.stream().map(conflict->conflict.token()+"→"+conflict.truth()).distinct().collect(java.util.stream.Collectors.joining("、"));
        m.addTrace("InstallationGuard",0,"按快照校正 "+conflicts.size()+" 处安装结论："+detail);
        log.warn("Answer installation claim corrected against snapshot: {}",detail);
    }
    /** 在任意嵌套里找带 pattern 数组的配方节点，避免依赖外层包装结构。 */
    /** 从任意层级找 recipeId（退而求其次找 id），用于图注与缓存键。 */
    /** 找带 result/results 的配方节点（通用卡用）。 */
    private static com.fasterxml.jackson.databind.JsonNode findResultNode(com.fasterxml.jackson.databind.JsonNode node){
        if(node==null)return null;
        if(node.isObject()){
            if(node.has("result")||node.has("results")||node.has("output")||node.has("outputs"))return node;
            for(var it=node.elements();it.hasNext();){var found=findResultNode(it.next());if(found!=null)return found;}
        }else if(node.isArray()){
            for(var child:node){var found=findResultNode(child);if(found!=null)return found;}
        }
        return null;
    }    private static String recipeId(com.fasterxml.jackson.databind.JsonNode node){
        if(node==null)return "recipe";
        if(node.isObject()){
            if(node.path("recipeId").isTextual())return node.path("recipeId").asText();
            if(node.path("id").isTextual())return node.path("id").asText();
            for(var it=node.elements();it.hasNext();){String found=recipeId(it.next());if(!"recipe".equals(found))return found;}
        }else if(node.isArray()){
            for(var child:node){String found=recipeId(child);if(!"recipe".equals(found))return found;}
        }
        return "recipe";
    }    private static com.fasterxml.jackson.databind.JsonNode findPatternNode(com.fasterxml.jackson.databind.JsonNode node){
        if(node==null)return null;
        if(node.isObject()){
            if(node.has("pattern")&&node.path("pattern").isArray())return node;
            for(var it=node.elements();it.hasNext();){var found=findPatternNode(it.next());if(found!=null)return found;}
        }else if(node.isArray()){
            for(var child:node){var found=findPatternNode(child);if(found!=null)return found;}
        }
        return null;
    }    /** 未适配的机械合成配方按 pattern 实际尺寸出卡。 */
    private void attachMechanicalCrafting(QaResult result){
        if(result.recipesToRender.isEmpty()&&result.toolRecipes.isEmpty())return;
        String profile=RuntimeKnowledgeStore.active(baseDb);if(profile==null)return;
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        for(var match:result.recipesToRender){
            try{
                var json=mapper.readTree(match.recipeJson());
                var render=findPatternNode(json);if(render==null){var genericNode=findResultNode(json);if(genericNode==null)continue;String genericId=recipeId(json);String genericKey="g"+yagen.waitmydawn.kb.model.BuildRequest.fingerprint(profile+genericId).substring(0,20);if(result.artifacts.pictures().stream().anyMatch(p->p.id().equals(genericKey)))continue;var genericImage=yagen.waitmydawn.kb.service.GenericRecipeCard.draw(genericNode,genericId,new yagen.waitmydawn.kb.service.ItemIconService(baseDb,mediaDirectory).snapshot());var genericFolder=mediaDirectory.resolve("generated/recipes");java.nio.file.Files.createDirectories(genericFolder);var genericFile=genericFolder.resolve(genericKey+".png");javax.imageio.ImageIO.write(genericImage,"PNG",genericFile.toFile());var genericAsset=new yagen.waitmydawn.kb.service.ImageAssetStore(mediaDirectory).put(java.nio.file.Files.readAllBytes(genericFile));String genericSource="s"+yagen.waitmydawn.kb.model.BuildRequest.fingerprint("generic:"+profile+genericId).substring(0,20);var genericSources=new ArrayList<>(result.artifacts.sources());var genericPictures=new ArrayList<>(result.artifacts.pictures());genericSources.add(new yagen.waitmydawn.kb.model.AnswerArtifacts.Source(genericSource,"mkb-runtime:"+profile+"/recipes/"+genericId,genericId,genericId,"实机配方",null,"RUNTIME_SNAPSHOT",genericId));genericPictures.add(new yagen.waitmydawn.kb.model.AnswerArtifacts.Picture(genericKey,genericAsset.id(),genericAsset.relativePath(),"通用配方："+genericId,genericSource,"RECIPE",java.util.List.of(),genericId,null,null,null));result.artifacts=new yagen.waitmydawn.kb.model.AnswerArtifacts(result.artifacts.scopeId(),result.artifacts.scopeLabel(),genericSources,genericPictures,result.artifacts.citedSourceIds());continue;}
                if(!MechanicalCraftingCard.supports(render))continue;
                String id=recipeId(json);
                String key="m"+yagen.waitmydawn.kb.model.BuildRequest.fingerprint(profile+id).substring(0,20);
                if(result.artifacts.pictures().stream().anyMatch(p->p.id().equals(key)))continue;
                var image=MechanicalCraftingCard.draw(render,new yagen.waitmydawn.kb.service.ItemIconService(baseDb,mediaDirectory).snapshot());
                var folder=mediaDirectory.resolve("generated/mechanical");java.nio.file.Files.createDirectories(folder);
                var file=folder.resolve(key+".png");javax.imageio.ImageIO.write(image,"PNG",file.toFile());
                var asset=new yagen.waitmydawn.kb.service.ImageAssetStore(mediaDirectory).put(java.nio.file.Files.readAllBytes(file));
                String source="s"+yagen.waitmydawn.kb.model.BuildRequest.fingerprint("mechanical:"+profile+id).substring(0,20);
                var sources=new ArrayList<>(result.artifacts.sources());var pictures=new ArrayList<>(result.artifacts.pictures());
                sources.add(new yagen.waitmydawn.kb.model.AnswerArtifacts.Source(source,"mkb-runtime:"+profile+"/recipes/"+id,id,id,"实机配方",null,"RUNTIME_SNAPSHOT",id));
                pictures.add(new yagen.waitmydawn.kb.model.AnswerArtifacts.Picture(key,asset.id(),asset.relativePath(),"动力合成："+id,source,"RECIPE",java.util.List.of(),id,null,null,null));
                result.artifacts=new yagen.waitmydawn.kb.model.AnswerArtifacts(result.artifacts.scopeId(),result.artifacts.scopeLabel(),sources,pictures,result.artifacts.citedSourceIds());
            }catch(Exception failure){result.mediaWarnings.add("机械合成图 · "+failure.getMessage());}
        }
    }    private void prepareMedia(QaResult result,String question){
        long started=System.nanoTime();String saved=result.answer;result.answer="";
        try{
            boolean crafting=question.matches("(?is).*(合成|配方|制作|烧炼|锻造|加工|怎么做|如何做|如何获得|怎么获得|craft|recipe).*" );
            if(crafting&&!result.recipesToRender.isEmpty()){
                var config=yagen.waitmydawn.kb.config.AppConfig.forIsolatedRun(false).forBuildTarget(mediaDirectory);
                result.recipeRenderBatch=new RendererService(config,baseDb).renderDetailed(result.recipesToRender);
                AnswerMedia.attachRecipes(result,result.recipeRenderBatch,mediaDirectory);
            }else result.recipeRenderBatch=new RendererService.RenderBatch(Map.of(),List.of(),List.of());
            attachMechanicalCrafting(result);
            entityMedia.prepare(result,question.replaceAll("https?://[^\\s<>]+"," "));
            new LootCardService(baseDb,mediaDirectory).attach(result,question);
            new RuleCardService(baseDb,mediaDirectory).attach(result,question);
            int tradeCards=0;for(String tradeId:result.tradeIds){if(tradeCards++>=2)break;try{new RuleCardService(baseDb,mediaDirectory).attachTrade(result,tradeId);}catch(Exception failure){result.mediaWarnings.add("交易图："+tradeId+" · "+failure.getMessage());}}
            new LayoutCardService(baseDb,mediaDirectory).attach(result,question);
            String layoutKey="i"+yagen.waitmydawn.kb.model.BuildRequest.fingerprint("layout:"+result.multiblockId).substring(0,20);if(result.multiblockId!=null&&result.artifacts.pictures().stream().noneMatch(p->p.id().equals(layoutKey)))try{new LayoutCardService(baseDb,mediaDirectory).attachOne(result,result.multiblockId);}catch(Exception failure){result.mediaWarnings.add("布局图："+failure.getMessage());}
        }catch(java.io.IOException failure){result.mediaWarnings.add("配方图片归档失败："+failure.getClass().getSimpleName());}
        finally{result.answer=saved;}
        result.mediaPreparationMs=(System.nanoTime()-started)/1_000_000;
    }

    public synchronized void setKnowledgeImageFetching(boolean enabled) {knowledge.setImageFetching(enabled);}

    private static String filteredKnowledgeEvidence(yagen.waitmydawn.kb.model.AnswerArtifacts artifacts,KnowledgeRetrievalService.Diagnostics diagnostics) {
        return KnowledgeRetrievalService.evidence(artifacts,diagnostics);
    }

    private QaResult processKnowledgeTurn(String question, KnowledgeRetrievalService.Resolution scope,
                                          KnowledgeRetrievalService.Retrieval retrieval, QaMetrics m, long started) {
        return processKnowledgeTurn(question,scope,retrieval,m,started,question);
    }

    private QaResult processKnowledgeTurn(String question, KnowledgeRetrievalService.Resolution scope,
                                          KnowledgeRetrievalService.Retrieval retrieval, QaMetrics m, long started,String lookupQuestion) {
        var result = new QaResult();
        m.vectorSearchTimeMs=(System.nanoTime()-started)/1_000_000;
        result.sourceScope = scope.scope() == null ? "已导入资料" : scope.scope().label();
        result.artifacts = retrieval == null ? yagen.waitmydawn.kb.model.AnswerArtifacts.empty() : retrieval.artifacts();
        result.vectorResults = retrieval == null ? List.of() : retrieval.hits();
        m.knowledgeRetrieval=retrieval==null?null:retrieval.diagnostics();
        if(scope.warning()==null&&(scope.scope()==null||scope.scope().id().startsWith("runtime:"))&&RuntimeKnowledgeStore.active(baseDb)!=null)try {
            var resolved=new RuntimeNameIndex(baseDb,RuntimeKnowledgeStore.active(baseDb)).mentions(lookupQuestion,null);
            var ambiguous=resolved.stream().filter(found->found.status().equals("AMBIGUOUS")).toList();
            if(!ambiguous.isEmpty())scope=new KnowledgeRetrievalService.Resolution(scope.scope(),scope.documentId(),"名称存在歧义，请确认具体模组或对象类型："+ambiguous,true);
            else {
                var ids=resolved.stream().flatMap(found->found.candidates().stream()).map(RuntimeNameIndex.Candidate::registry).distinct().toList();
                result.resolvedEntities=ids.stream().map(id->new ResolvedEntity(id,id.split(":")[0],1,"snapshot-name-index")).toList();
                var evidence=new ArrayList<>(result.vectorResults);evidence.addAll(RuntimeRuleEvidence.read(baseDb,ids,lookupQuestion));evidence.addAll(RuntimeAttributes.evidence(baseDb,ids,lookupQuestion));evidence.addAll(AcquisitionIndex.evidence(baseDb,ids,lookupQuestion));evidence.addAll(RuntimeStructures.evidence(baseDb,ids,lookupQuestion));evidence.addAll(RuntimeMagic.evidence(baseDb,ids,lookupQuestion));evidence.addAll(RuntimeSpawns.evidence(baseDb,ids,lookupQuestion));result.vectorResults=List.copyOf(evidence);
            }
        }catch(Exception failure){throw new IllegalStateException("Runtime evidence query failed",failure);}

        m.knowledgeContextChunkIds=KnowledgeRetrievalService.contextIds(retrieval,result.artifacts);
        String boundMod=knowledge.boundModId(scope.scope());
        if(scope.warning()==null && RuntimeKnowledgeStore.active(baseDb)!=null && hasCraftingIntent(question) && (scope.scope()==null || scope.scope().id().startsWith("runtime:"))) {
            result.recipeResults=findSnapshotRecipes(lookupQuestion);
            if(result.recipeResults.isEmpty())result.mediaWarnings.add("当前快照未命中目标配方；请核对物品名称或注册名。这不表示游戏中无法合成，也未使用百科默认配方替代。");
            var recipes=result.recipeResults.stream().map(recipe->new ScoredRecipe(recipe,100)).toList();
            result.recipesToRender=selectRecipesToRender(recipes,true);m.recipeResultCount=result.recipeResults.size();
        } else if(boundMod!=null && hasCraftingIntent(question)) {
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
        if(scope.warning()==null){AnswerMedia.attachVectorSources(result,result.vectorResults);prepareMedia(result,lookupQuestion);}
        long answerStart = System.nanoTime();
        m.answerGeneration="NO_EVIDENCE";
        if (scope.warning() != null) result.answer = scope.warning();
        else if (result.vectorResults.isEmpty() && result.recipeResults.isEmpty() && result.artifacts.pictures().isEmpty()) result.answer = "在当前资料范围内没有找到足够证据，无法确认这个问题。可更换资料范围或补充相关文档。";
        else {
            var recipes=result.recipeResults.stream().map(r -> new AnswerAgent.RecipeMatch(r.recipeJson(),r.outputItem(),r.sourceMod(),r.sourceType())).toList();
            var ctx = new AnswerContext(question, QuestionType.GENERAL, result.resolvedEntities, recipes, result.vectorResults, null, List.of(),
                    RuntimeLootSummary.capability(baseDb,question), runtimeScope("本轮资料范围："+result.sourceScope,scope.documentId()!=null||scope.scope()!=null&&!scope.scope().id().startsWith("runtime:")),
                    entityMedia.evidence(result,filteredKnowledgeEvidence(result.artifacts,retrieval==null?null:retrieval.diagnostics())));
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
        var io=RetrievalTelemetry.current();m.resourceAccess=RetrievalTelemetry.resources();m.workAudit=RetrievalTelemetry.work();
        m.workAudit.forEach((name,work)->m.addTrace(name,work.millis(),"数量="+work.count())); m.queryEmbeddingCount=io.queryEmbeddings; m.documentEmbeddingCount=io.documentEmbeddings;
        m.httpRequestCount=io.httpRequests;m.httpErrorCount=io.httpErrors;m.httpResponseBytes=io.responseBytes;
        m.memoryPageCacheHits=io.memoryPageHits;m.persistentPageCacheHits=io.persistentPageHits;
        m.embeddingTimeMs=io.embeddingNanos/1_000_000; m.addTrace("KnowledgeRetrieval", m.totalTimeMs-m.answerGenTimeMs, result.sourceScope);
 result.metrics=m;
        if(Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Question cancelled");
        
        lastKnowledgeScope=result.artifacts.scopeId();
        long mediaStarted=System.nanoTime();if(scope.warning()==null){entityMedia.finish(result,lookupQuestion);m.guideIllustrations=GuideIllustrationCoverage.finish(result,question);}
        m.mediaPerformance=result.mediaPerformance;m.mediaAudit=MediaAudit.capture(question,result.answer,result.artifacts,assetDirectory(),result.entityImageAudit);m.answerLength=result.answer.length();
        long mediaDuration=(System.nanoTime()-mediaStarted)/1_000_000; m.addTrace("MediaPreparation",result.mediaPreparationMs,"已提供给回答模型的配图证据");m.totalTimeMs+=mediaDuration;m.addTrace("MediaSelection",mediaDuration,"最终图片与资产审计");m.accumulate();log.info("QA {}",m.toLogString());
        try{metricsHistory.save(m);}catch(Exception failure){log.warn("Media metrics save failed",failure);}
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
        String sql = "SELECT recipe_data, output_item, source_mod, source_type FROM "+RuntimeKnowledgeStore.recipeRelation(baseDb)+" WHERE " + where
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

    private String runtimeScope(String scope) {
        return runtimeScope(scope,false);
    }
    private String runtimeScope(String scope,boolean documentScope) {
        if(RuntimeKnowledgeStore.active(baseDb)==null)return scope;
        return scope.replace("实际配方、注册名、纹理由安装 JAR 优先确定。","实际配方、注册名与名称以所选游戏快照为准。")+RuntimeEvidencePolicy.context(RuntimeEvidencePolicy.snapshot(baseDb),documentScope)+"\n快照不覆盖任意脚本事件与玩家阶段限制。";
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

    private List<RecipeMatch> findSnapshotRecipes(String question) {
        var outputs=new LinkedHashSet<String>();var matches=new ArrayList<RecipeMatch>();
        for(String id:new RecipeResourceStore(baseDb).findItemIds(question))searchRecipe("output_item=?",new String[]{id},outputs,matches);
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
        String sql = "SELECT recipe_data, output_item, source_mod,source_type FROM "+RuntimeKnowledgeStore.recipeRelation(baseDb)+" WHERE " + where + " AND COALESCE(source_type,'UNKNOWN')<>'SEED' LIMIT 20";
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
        dialogueFocus=null;
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
                dialogueFocus=message.artifacts()==null?null:message.artifacts().turnState();
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
        public AgentToolLoop.Outcome toolTrace;
        String mediaQuestion;
        /** 明确要求的状态键；只用于单对象选图，命中不了就报缺图，不回退到代表图。 */

        public String mediaStateKey;
        /** 用户问题里显式点名的状态轴（成长阶段/性别/变体）；快照里没有组合时不配图。 */
        public Map<String,String> mediaAxes = Map.of();
        /** 本轮结构化查询命中的多方块 id，用它在最终组织前渲染分层图卡。 */
        public String multiblockId;
        /** 本轮结构化查询命中的交易记录 id，用于在最终组织前渲染交易卡。 */
        public final Set<String> tradeIds = new LinkedHashSet<>();
        /** 工具查询读到的配方原文，供未适配类型的图卡使用。 */
        public final List<String> toolRecipes = new ArrayList<>();
        final Set<String> mediaTargets=new LinkedHashSet<>();
        final Set<String> conversationTargets=new LinkedHashSet<>();
        public MediaAudit.Performance mediaPerformance;
        public MediaAudit.EntityTrace entityImageAudit;
        public boolean runtimeMediaPrepared;
        final Map<String,com.fasterxml.jackson.databind.JsonNode> runtimeMediaStates=new HashMap<>();
        public final List<String> mediaWarnings=new ArrayList<>();
        public MediaRequestPlanner.Plan mediaPlan;
        public RendererService.RenderBatch recipeRenderBatch;
        public long mediaPreparationMs;
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
