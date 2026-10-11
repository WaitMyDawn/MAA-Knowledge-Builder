package yagen.waitmydawn.kb.eval;

import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import yagen.waitmydawn.kb.agent.AnswerAgent;
import yagen.waitmydawn.kb.agent.ClassifyAgent;
import yagen.waitmydawn.kb.agent.EntityAgent;
import yagen.waitmydawn.kb.agent.UrlAgent;
import yagen.waitmydawn.kb.config.AppConfig;
import yagen.waitmydawn.kb.dto.QaMetrics;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import yagen.waitmydawn.kb.service.ChatHistoryService;
import yagen.waitmydawn.kb.service.EmbeddingService;
import yagen.waitmydawn.kb.service.IncrementalDB;
import yagen.waitmydawn.kb.service.MultiDBManager;
import yagen.waitmydawn.kb.service.QaPipeline;
import yagen.waitmydawn.kb.service.RagAgentService;
import yagen.waitmydawn.kb.service.RendererService;
import yagen.waitmydawn.kb.service.VectorStore;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 黄金评测集执行器。
 *
 * <p>与 GUI 一致地按会话跑题（写会话 JSON + 独立增量库），但整个 run 隔离在
 * {dataDir}/eval-runs/{runId}/ 下，不污染真实会话。每条题：
 * 规则判分（主判） + LLM 裁判（复核，可关） → turns.jsonl；run 结束汇总为 summary/report/bad-cases。
 *
 * <p>稳定性：默认只跑 1 次；题级 stabilityRuns>1 时，额外重复执行「前缀重放 + 目标轮」，
 * 用于 pass@N 与方差统计。
 */
public final class EvalRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);
    private static final DateTimeFormatter RUN_ID_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public record Config(Path dataDir, Path goldenSetFile, Set<Integer> dialogueIndexes,
                         String tag, int defaultRepeat, boolean judgeEnabled) {}

    public record Outcome(String runId, Path runDir, int totalTurns, int passedTurns,
                          double passRate, String reportMarkdown, Map<String, Object> summary) {}

    private final AppConfig appConfig;
    private final DatabaseBuilder baseDb;
    private final EmbeddingService embedder;
    private final VectorStore baseVectorStore;
    private final RagAgentService llm;
    private final Config cfg;
    /** modId → (中文名(英文名) → 网页URL)，来自 rag_web_cache.subWebPage，按需加载缓存。 */
    private final Map<String, Map<String, String>> webCacheByMod = new java.util.HashMap<>();

    public EvalRunner(AppConfig appConfig, DatabaseBuilder baseDb, EmbeddingService embedder,
                      VectorStore baseVectorStore, RagAgentService llm, Config cfg) {
        this.appConfig = appConfig;
        this.baseDb = baseDb;
        this.embedder = embedder;
        this.baseVectorStore = baseVectorStore;
        this.llm = llm;
        this.cfg = cfg;
    }

    public Outcome run() throws IOException {
        GoldenSet.Spec spec = GoldenSet.load(cfg.goldenSetFile());
        String runId = buildRunId(cfg.tag());
        Path runDir = cfg.dataDir().resolve("eval-runs").resolve(runId);
        Files.createDirectories(runDir);
        Files.copy(cfg.goldenSetFile(), runDir.resolve("golden-set.json"), StandardCopyOption.REPLACE_EXISTING);
        baseVectorStore.verifyProfile(embedder.profile(), false);

        MultiDBManager evalDbManager = new MultiDBManager(runDir, baseDb);
        evalDbManager.scan();
        ChatHistoryService chatHistory = new ChatHistoryService(runDir);

        ClassifyAgent classifyAgent = new ClassifyAgent(llm);
        EntityAgent entityAgent = new EntityAgent(llm, baseDb, embedder, baseVectorStore);
        UrlAgent urlAgent = new UrlAgent(llm, baseDb, embedder);
        // 会话知识独立持久化；HTTP 响应缓存不改变各会话的数据归属。
        urlAgent.setRunScopeDedup(false);
        AnswerAgent answerAgent = new AnswerAgent(llm);
        RendererService renderer = new RendererService(appConfig, baseDb);
        QaPipeline pipeline = new QaPipeline(classifyAgent, entityAgent, urlAgent, answerAgent,
                llm, baseVectorStore, embedder, baseDb, evalDbManager, runDir);

        ChatModel judgeModel = cfg.judgeEnabled() ? llm.getJudgeModel() : null;
        List<Map<String, Object>> records = new ArrayList<>();

        try (evalDbManager; RunStore store = new RunStore(runDir)) {
            store.writeConfig(configMap(spec, runId, runDir));
            int dialogueIndex = 0;
            for (GoldenSet.Conversation conv : spec.conversations()) {
                dialogueIndex++;
                if (cfg.dialogueIndexes() != null && !cfg.dialogueIndexes().isEmpty()
                        && !cfg.dialogueIndexes().contains(dialogueIndex)) {
                    log.info("Eval: skip dialogue #{} {}", dialogueIndex, conv.name());
                    continue;
                }
                // 每个对话开始前做一次健康检查：库在运行中损坏时立即中止，避免产出无效数据
                assertDbHealthy(conv.name());
                runConversation(conv, dialogueIndex, runId, pipeline, evalDbManager,
                        chatHistory, store, judgeModel, renderer, records);
            }

            Map<String, Object> summary = EvalReport.summarize(runId, spec, records, cfg, runDir);
            store.writeSummary(summary);
            store.writeText("report.md", EvalReport.markdown(summary, records));
            store.writeText("bad-cases.md", EvalReport.badCases(records));
            store.writeText("disagreements.md", EvalReport.disagreements(records));
            store.writeText("human-review.md", EvalReport.humanReview(records));
            store.appendIndex(summary);

            int total = records.size();
            int passed = (int) records.stream()
                    .filter(r -> booleanOf(mapOf(r.get("rule")).get("passed"))).count();
            double rate = total > 0 ? (double) passed / total : 0;
            log.info("Eval run {} finished: {}/{} passed ({}) → {}", runId, passed, total,
                    String.format("%.1f%%", rate * 100), runDir);
            return new Outcome(runId, runDir, total, passed, rate,
                    EvalReport.markdown(summary, records), summary);
        }
    }

    // ==================== conversation / turn execution ====================

    private void runConversation(GoldenSet.Conversation conv, int dialogueIndex, String runId,
                                 QaPipeline pipeline, MultiDBManager evalDbManager,
                                 ChatHistoryService chatHistory, RunStore store,
                                 ChatModel judgeModel, RendererService renderer,
                                 List<Map<String, Object>> records) throws IOException {
        List<GoldenSet.Turn> turns = conv.turns();
        if (turns.isEmpty()) return;

        // Pass 1：按会话顺序跑一遍（多轮上下文真实生效），记录每轮结果
        ChatHistoryService.ChatSession session = chatHistory.newSession("Eval: " + conv.name(), cfg.dataDir());
        startSession(evalDbManager, pipeline, session);
        for (GoldenSet.Turn turn : turns) {
            Map<String, Object> rec = executeTurn(pipeline, chatHistory, session, turn, conv,
                    dialogueIndex, 1, runId, store, judgeModel, renderer);
            records.add(rec);
            store.appendTurn(rec);
        }

        // Pass 2：需要稳定性的题，额外重复「前缀重放 + 目标轮」
        for (int i = 0; i < turns.size(); i++) {
            GoldenSet.Turn turn = turns.get(i);
            int runs = turn.effectiveRepeat(cfg.defaultRepeat());
            for (int r = 2; r <= runs; r++) {
                ChatHistoryService.ChatSession repSession =
                        chatHistory.newSession("Eval: " + conv.name(), cfg.dataDir());
                startSession(evalDbManager, pipeline, repSession);
                for (int k = 0; k <= i; k++) {
                    GoldenSet.Turn current = turns.get(k);
                    if (k < i) {
                        replayTurn(pipeline, current, conv, k + 1, runId);
                    } else {
                        Map<String, Object> rec = executeTurn(pipeline, chatHistory, repSession, current,
                                conv, dialogueIndex, r, runId, store, judgeModel, renderer);
                        records.add(rec);
                        store.appendTurn(rec);
                    }
                }
            }
        }
    }

    private void startSession(MultiDBManager evalDbManager, QaPipeline pipeline,
                              ChatHistoryService.ChatSession session) {
        IncrementalDB inc = evalDbManager.createIncrementalDB(session.sessionId, embedder.getDimension());
        evalDbManager.setCurrentSessionId(session.sessionId);
        pipeline.setIncrementalDB(inc);
        pipeline.clearHistory();
    }

    /** 稳定性重复时重放前缀轮次，只用于恢复多轮上下文，不记录结果。 */
    private void replayTurn(QaPipeline pipeline, GoldenSet.Turn turn,
                            GoldenSet.Conversation conv, int turnIndex, String runId) {
        putMdc(runId, conv.id(), turn.id(), turnIndex, 0);
        try {
            pipeline.process(turn.question());
        } catch (Exception e) {
            log.warn("Eval: prefix replay failed at {}: {}", turn.id(), e.getMessage());
        } finally {
            clearMdc();
        }
    }

    private Map<String, Object> executeTurn(QaPipeline pipeline, ChatHistoryService chatHistory,
                                            ChatHistoryService.ChatSession session, GoldenSet.Turn turn,
                                            GoldenSet.Conversation conv, int dialogueIndex, int repeat,
                                            String runId, RunStore store, ChatModel judgeModel,
                                            RendererService renderer) throws IOException {
        putMdc(runId, conv.id(), turn.id(), dialogueIndex, repeat);
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("runId", runId);
        rec.put("dialogueIndex", dialogueIndex);
        rec.put("dialogueId", conv.id());
        rec.put("dialogueName", conv.name());
        rec.put("modId", conv.modId());
        rec.put("turnId", turn.id());
        rec.put("repeat", repeat);
        rec.put("question", turn.question());
        rec.put("type", turn.type());
        rec.put("category", turn.category());

        chatHistory.addMessage(session, "user", turn.question());
        long t0 = System.currentTimeMillis();
        QaPipeline.QaResult result = null;
        boolean error = false;
        String errorMessage = null;
        try {
            result = pipeline.process(turn.question());
        } catch (Exception e) {
            error = true;
            errorMessage = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("Eval turn failed: {} {}", turn.id(), errorMessage, e);
        }
        long elapsed = System.currentTimeMillis() - t0;

        long renderStart = System.nanoTime();
        List<RuleJudge.RenderedImage> renderedImages = renderIfNeeded(result, renderer, store.runDir());
        long renderMs = (System.nanoTime() - renderStart) / 1_000_000;
        RuleJudge.Observation observation = toObservation(result, error, errorMessage, elapsed,
                renderedImages, webCacheHits(turn, conv));
        RuleJudge.Verdict verdict = RuleJudge.judge(turn, observation);
        long judgeStart = System.nanoTime();
        LlmJudge.Outcome judgeOutcome = judgeModel != null
                ? LlmJudge.judge(judgeModel, turn, observation.answer(), retrievalEvidence(result)) : null;
        long judgeMs = (System.nanoTime() - judgeStart) / 1_000_000;

        if (result != null) {
            chatHistory.addMessage(session, "maa", result.answer == null ? "" : result.answer);
            try {
                chatHistory.save(session);
            } catch (Exception e) {
                log.warn("Eval session save failed for {}: {}", session.sessionId, e.getMessage());
            }
        }

        rec.put("answer", observation.answer());
        rec.put("error", error);
        rec.put("errorMessage", errorMessage);
        rec.put("elapsedMs", elapsed);
        rec.put("renderMs", renderMs);
        rec.put("judgeMs", judgeMs);
        // 端到端耗时：管道 + 渲染 + 裁判（评测口径），与纯管道 metrics.totalTimeMs 区分
        rec.put("evalTotalMs", elapsed + renderMs + judgeMs);
        rec.put("rule", ruleMap(verdict));
        rec.put("judge", judgeMap(judgeOutcome));
        rec.put("observation", observationMap(observation));
        rec.put("metrics", metricsMap(result == null ? null : result.metrics));
        rec.put("retrievalPlan", result == null ? null : result.retrievalPlan);
        rec.put("retrievalEvidence", retrievalEvidence(result));

        if (judgeOutcome != null) {
            Map<String, Object> judgeFile = new LinkedHashMap<>();
            judgeFile.put("turnId", turn.id());
            judgeFile.put("repeat", repeat);
            judgeFile.put("prompt", judgeOutcome.prompt());
            judgeFile.put("result", judgeMap(judgeOutcome));
            store.writeJudge(turn.id() + "-r" + repeat, judgeFile);
        }

        log.info("Eval turn {} r{} rule={} score={} judge={} | {}ms",
                turn.id(), repeat, verdict.passed() ? "PASS" : "FAIL",
                String.format("%.2f", verdict.score()),
                judgeOutcome == null || judgeOutcome.result() == null ? "-" : judgeOutcome.result().verdict(),
                elapsed);
        clearMdc();
        return rec;
    }

    private static String retrievalEvidence(QaPipeline.QaResult result) {
        if (result == null) return "本轮没有可用检索结果";
        StringBuilder evidence = new StringBuilder("检索计划：" + result.retrievalPlan + "\n");
        evidence.append("JAR 版本范围：\n").append(result.sourceScope).append('\n');
        if (result.vectorResults != null) for (var item : result.vectorResults) {
            String text = item.chunkText();
            int limit = "category_catalog".equals(item.type()) ? 12000 : 1800;
            evidence.append("\n来源：").append(item.sourcePath()).append("；模组：")
                    .append(item.modId()).append("；类型：").append(item.type()).append('\n')
                    .append(text.length() > limit ? text.substring(0, limit) + "[证据截断]" : text);
        }
        if (result.recipeResults != null) for (var recipe : result.recipeResults) {
            evidence.append("\n本地配方表，来源类型：").append(recipe.sourceType()).append("；来源模组：").append(recipe.sourceMod()).append('\n')
                    .append(yagen.waitmydawn.kb.service.RecipeSummary.describe(recipe.recipeJson(), recipe.outputItem()));
        }
        return evidence.toString();
    }

    private static RuleJudge.Observation toObservation(QaPipeline.QaResult result, boolean error,
                                                        String errorMessage, long elapsedMs,
                                                        List<RuleJudge.RenderedImage> renderedImages,
                                                        List<String> webCacheHitLabels) {
        if (result == null) return RuleJudge.Observation.error(errorMessage);
        QaMetrics m = result.metrics;
        List<String> recipeOutputs = result.recipeResults == null ? List.of()
                : result.recipeResults.stream().map(r -> r.outputItem())
                        .filter(Objects::nonNull).distinct().toList();
        List<String> registries = result.resolvedEntities == null ? List.of()
                : result.resolvedEntities.stream().map(e -> e.registry())
                        .filter(Objects::nonNull).distinct().toList();
        int renderCount = result.recipesToRender == null ? 0 : result.recipesToRender.size();
        String answer = result.answer == null ? "" : result.answer;
        List<String> subPageLabels = result.subPageUrls == null ? List.of()
                : new ArrayList<>(result.subPageUrls.keySet());
        List<String> subPageUrls = result.subPageUrls == null ? List.of()
                : new ArrayList<>(result.subPageUrls.values());
        return new RuleJudge.Observation(answer, error, errorMessage, recipeOutputs, registries,
                renderCount, renderedImages, subPageLabels, subPageUrls, webCacheHitLabels,
                m == null ? null : m.dataTier,
                m == null ? elapsedMs : m.totalTimeMs,
                m == null ? 0 : m.tokenEstimateInput,
                m == null ? 0 : m.tokenEstimateOutput,
                m != null && m.retrievalHit, catalogMembers(result.catalogEvidence));
    }

    private static Map<String,String> catalogMembers(String evidence) {
        Map<String,String> members=new LinkedHashMap<>();
        if(evidence==null) return members;
        var matcher=java.util.regex.Pattern.compile("(?m)^- (.+?) \\|.*?详情：(https://www\\.mcmod\\.cn/item/\\d+\\.html)").matcher(evidence);
        while(matcher.find()) members.put(matcher.group(1).split("\\(",2)[0].trim(),matcher.group(2));
        return members;
    }

    /**
     * 过程层节点探针：题目声明 web_cache_has_label 时，直接查 rag_web_cache.subWebPage，
     * 判断期望的「中文名(英文名)」是否真的在库里（与运行时别名检索相互独立）。
     */
    private List<String> webCacheHits(GoldenSet.Turn turn, GoldenSet.Conversation conv) {
        List<String> wanted = new ArrayList<>();
        for (GoldenSet.Assertion a : turn.assertions()) {
            if ("web_cache_has_label".equals(a.kind()) && a.values() != null) wanted.addAll(a.values());
        }
        if (wanted.isEmpty() || conv.modId() == null || conv.modId().isBlank()) return List.of();
        Map<String, String> sub = webCacheByMod.computeIfAbsent(conv.modId(), this::loadSubPages);
        List<String> hits = new ArrayList<>();
        for (String label : sub.keySet()) {
            for (String w : wanted) {
                if (w != null && !w.isBlank() && label.toLowerCase().contains(w.toLowerCase())) {
                    hits.add(label + " -> " + sub.get(label));
                    break;
                }
            }
        }
        return hits;
    }

    private Map<String, String> loadSubPages(String modId) {
        Map<String, String> all = new LinkedHashMap<>();
        try {
            for (DatabaseBuilder.WebCacheEntry entry : baseDb.loadWebPages(modId)) {
                if (entry.subWebPage() != null) all.putAll(entry.subWebPage());
            }
        } catch (Exception e) {
            log.debug("Eval web-cache probe failed for {}: {}", modId, e.getMessage());
        }
        return all;
    }

    /** 库健康检查失败时中止本次评测（避免把坏库产出的数据当成基线）。 */
    private void assertDbHealthy(String context) throws IOException {
        try {
            baseDb.healthCheck();
        } catch (Exception e) {
            throw new IOException("数据库健康检查失败（对话 " + context + " 之前）：" + e.getMessage()
                    + "\n请从 backup-* 目录恢复备份，或用 --build <mods-folder> 重建基础库后重跑。", e);
        }
    }

    /**
     * P0-4：按 GUI 同等行为渲染 recipesToRender，并把校验结果与图片证据归档到 run 目录。
     * 返回每张图的 outputItem/type/path/bytes/valid 供 render_image_valid 断言使用。
     */
    private List<RuleJudge.RenderedImage> renderIfNeeded(QaPipeline.QaResult result,
                                                         RendererService renderer, Path runDir) {
        if (result == null || result.recipesToRender == null || result.recipesToRender.isEmpty()) {
            return List.of();
        }
        Map<String, String> paths;
        try {
            paths = renderer.renderRecipes(result.recipesToRender);
        } catch (Exception e) {
            log.warn("Eval render failed for {} recipes: {}", result.recipesToRender.size(), e.getMessage());
            return List.of();
        }
        List<RuleJudge.RenderedImage> images = new ArrayList<>();
        Path archiveDir = runDir.resolve("rendered");
        for (Map.Entry<String, String> e : paths.entrySet()) {
            Path src = Path.of(e.getValue());
            boolean exists = Files.exists(src);
            long bytes = exists ? src.toFile().length() : 0;
            boolean valid = exists && bytes > 0;
            if (valid) {
                try (var in = Files.newInputStream(src)) {
                    valid = ImageIO.read(in) != null;
                } catch (Exception ex) {
                    valid = false;
                }
            }
            String type = src.getParent() != null ? src.getParent().getFileName().toString() : "unknown";
            String archivedPath = src.toAbsolutePath().toString();
            if (valid) {
                try {
                    Files.createDirectories(archiveDir);
                    Path dst = archiveDir.resolve(src.getFileName());
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    archivedPath = dst.toAbsolutePath().toString();
                } catch (Exception ex) {
                    log.debug("Eval render archive failed for {}: {}", src, ex.getMessage());
                }
            }
            images.add(new RuleJudge.RenderedImage(e.getKey().split("#", 2)[0], type, archivedPath, bytes, valid));
        }
        log.info("Eval render: {} recipes -> {} images (valid={})", result.recipesToRender.size(),
                images.size(), images.stream().filter(RuleJudge.RenderedImage::valid).count());
        return images;
    }

    private static Map<String, Object> ruleMap(RuleJudge.Verdict verdict) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("score", verdict.score());
        map.put("passed", verdict.passed());
        map.put("allRequiredPassed", verdict.allRequiredPassed());
        map.put("hasAssertions", verdict.hasAssertions());
        List<Map<String, Object>> outcomes = new ArrayList<>();
        for (RuleJudge.Outcome o : verdict.outcomes()) {
            Map<String, Object> om = new LinkedHashMap<>();
            om.put("kind", o.kind());
            om.put("passed", o.passed());
            om.put("weight", o.weight());
            om.put("required", o.required());
            om.put("detail", o.detail());
            outcomes.add(om);
        }
        map.put("outcomes", outcomes);
        return map;
    }

    private static Map<String, Object> judgeMap(LlmJudge.Outcome outcome) {
        if (outcome == null || outcome.result() == null) return null;
        LlmJudge.Result r = outcome.result();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("verdict", r.verdict());
        map.put("faithfulness", r.faithfulness());
        map.put("completeness", r.completeness());
        map.put("correctness", r.correctness());
        map.put("citationOk", r.citationOk());
        map.put("hallucination", r.hallucination());
        map.put("refusalCorrect", r.refusalCorrect());
        map.put("reasons", r.reasons());
        map.put("error", r.error());
        return map;
    }

    private static Map<String, Object> observationMap(RuleJudge.Observation obs) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("tier", obs.tier());
        map.put("retrievalHit", obs.retrievalHit());
        map.put("renderCount", obs.renderCount());
        List<Map<String, Object>> imgs = new ArrayList<>();
        if (obs.renderedImages() != null) {
            for (RuleJudge.RenderedImage img : obs.renderedImages()) {
                Map<String, Object> im = new LinkedHashMap<>();
                im.put("item", img.outputItem());
                im.put("type", img.type());
                im.put("path", img.path());
                im.put("bytes", img.bytes());
                im.put("valid", img.valid());
                imgs.add(im);
            }
        }
        map.put("renderedImages", imgs);
        map.put("recipeOutputs", obs.recipeOutputs());
        map.put("resolvedRegistries", obs.resolvedRegistries());
        map.put("subPageLabels", obs.subPageLabels());
        map.put("subPageUrls", obs.subPageUrls());
        map.put("webCacheHitLabels", obs.webCacheHitLabels());
        map.put("catalogMembers", obs.catalogMembers());
        return map;
    }

    private static Map<String, Object> metricsMap(QaMetrics m) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (m == null) return map;
        map.put("httpRequestCount",m.httpRequestCount); map.put("httpErrorCount",m.httpErrorCount);
        map.put("httpResponseBytes",m.httpResponseBytes);
        map.put("memoryPageCacheHits",m.memoryPageCacheHits); map.put("persistentPageCacheHits",m.persistentPageCacheHits);
        map.put("documentEmbeddingCount",m.documentEmbeddingCount); map.put("queryEmbeddingCount",m.queryEmbeddingCount);
        map.put("reusedEmbeddingCount",m.reusedEmbeddingCount); map.put("embeddingTimeMs",m.embeddingTimeMs);
        map.put("totalTimeMs", m.totalTimeMs);
        map.put("ttftMs", m.ttftMs);
        map.put("classifyTimeMs", m.classifyTimeMs);
        map.put("entityResolveTimeMs", m.entityResolveTimeMs);
        map.put("urlMatchTimeMs", m.urlMatchTimeMs);
        map.put("vectorSearchTimeMs", m.vectorSearchTimeMs);
        map.put("recipeSearchTimeMs", m.recipeSearchTimeMs);
        map.put("rerankTimeMs", m.rerankTimeMs);
        map.put("answerGenTimeMs", m.answerGenTimeMs);
        map.put("dataTier", m.dataTier);
        map.put("retrievalHit", m.retrievalHit);
        map.put("entityCount", m.entityCount);
        map.put("entityResolved", m.entityResolved);
        map.put("vectorResultCount", m.vectorResultCount);
        map.put("vectorRelevantCount", m.vectorRelevantCount);
        map.put("vectorMaxScore", m.vectorMaxScore);
        map.put("vectorAvgScore", m.vectorAvgScore);
        map.put("recipeResultCount", m.recipeResultCount);
        map.put("incrementalChunksAcquired", m.incrementalChunksAcquired);
        map.put("subPageUrlCount", m.subPageUrlCount);
        map.put("contextChunksUsed", m.contextChunksUsed);
        map.put("contextUtilization", m.contextUtilization);
        map.put("llmCallCount", m.llmCallCount);
        map.put("tokenIn", m.tokenEstimateInput);
        map.put("tokenOut", m.tokenEstimateOutput);
        map.put("unknownUsageCalls", m.unknownUsageCalls);
        map.put("tokenUsageSource", m.unknownUsageCalls > 0 ? "partial-provider-usage" : "provider-usage");
        map.put("ttftObserved", false);
        map.put("fallbackUsed", m.fallbackUsed);
        map.put("classifyCategory", m.classifyCategory);
        map.put("classifySource", m.classifySource);
        map.put("answerLength", m.answerLength);
        map.put("sessionId", m.sessionId);
        map.put("conversationTurn", m.conversationTurn);
        map.put("questionHash", m.questionHash);
        return map;
    }

    private Map<String, Object> configMap(GoldenSet.Spec spec, String runId, Path runDir) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("runId", runId);
        map.put("tag", cfg.tag());
        map.put("startedAt", LocalDateTime.now().toString());
        map.put("dataDir", cfg.dataDir().toString());
        map.put("runDir", runDir.toString());
        map.put("goldenSet", cfg.goldenSetFile().toString());
        map.put("goldenSetVersion", spec.version());
        map.put("goldenSetName", spec.name());
        map.put("conversations", spec.conversations().size());
        map.put("dialogueFilter", cfg.dialogueIndexes());
        map.put("defaultRepeat", cfg.defaultRepeat());
        map.put("judgeEnabled", cfg.judgeEnabled());
        map.put("embeddingModel", appConfig.getEmbeddingModelName());
        map.put("embeddingProfile", embedder.profile());
        map.put("baseBuildGeneration", baseDb.buildGeneration());
        map.put("baseContentSha256", baseDb.contentFingerprint());
        map.put("cachePolicy", "session-isolated-knowledge; shared-http-response-cache-30min");
        try {
            map.put("goldenSetSha256", yagen.waitmydawn.kb.service.ArtifactFingerprint.sha256(cfg.goldenSetFile()));
            map.put("sourceSha256", yagen.waitmydawn.kb.service.ArtifactFingerprint.sourceTree(Path.of("src/main/java")));
            if (Files.exists(Path.of("pom.xml"))) map.put("pomSha256", yagen.waitmydawn.kb.service.ArtifactFingerprint.sha256(Path.of("pom.xml")));
        } catch (IOException e) { throw new IllegalStateException("Evaluation input fingerprint failed", e); }
        return map;
    }

    private static String buildRunId(String tag) {
        String base = "run-" + LocalDateTime.now().format(RUN_ID_FMT) + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        if (tag == null || tag.isBlank()) return base;
        return base + "-" + tag.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static void putMdc(String runId, String dialogueId, String turnId,
                               int dialogueIndex, int repeat) {
        MDC.put("runId", runId);
        MDC.put("dialogueId", dialogueId);
        MDC.put("turnId", turnId + (repeat > 1 ? "#r" + repeat : ""));
    }

    private static void clearMdc() {
        MDC.remove("runId");
        MDC.remove("dialogueId");
        MDC.remove("turnId");
    }

    private static Map<String, Object> mapOf(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return Map.of();
    }

    private static boolean booleanOf(Object value) {
        return value instanceof Boolean b && b;
    }
}
