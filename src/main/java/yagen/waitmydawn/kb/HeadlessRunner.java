package yagen.waitmydawn.kb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yagen.waitmydawn.kb.agent.AnswerAgent;
import yagen.waitmydawn.kb.agent.ClassifyAgent;
import yagen.waitmydawn.kb.agent.EntityAgent;
import yagen.waitmydawn.kb.agent.UrlAgent;
import yagen.waitmydawn.kb.config.AppConfig;
import yagen.waitmydawn.kb.config.DataDirLock;
import yagen.waitmydawn.kb.eval.EvalRunner;
import yagen.waitmydawn.kb.eval.RunComparator;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import yagen.waitmydawn.kb.model.BuildRequest;
import yagen.waitmydawn.kb.model.ModEntry;
import yagen.waitmydawn.kb.service.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Headless / CLI runner — 无需 JavaFX UI。
 * 用于自动化构建和测试流水线。
 *
 * Usage:
 *   java -cp ... HeadlessRunner [flags]
 *
 * Flags:
 *   --data-dir <path>    数据目录 (必需)
 *   --scan <mods-folder> 扫描 JAR 文件
 *   --build <mods-folder> 构建知识库 (隐含 --scan)
 *   --clean              清理 generated/, logs/, sessions/
 *   --test [file]        运行自动测试 (默认 test-questions.json)
 *   --dialogue <1,2,...> 只运行指定对话（test 文件中 1-based 下标，如 --dialogue 4,5）
 *   --api-key <key>      本次运行临时 DeepSeek API Key（不写入配置，缺省回退已保存 Key）
 */
public class HeadlessRunner {

    private static final Logger log = LoggerFactory.getLogger(HeadlessRunner.class);

    public static void main(String[] args) throws Exception {
        yagen.waitmydawn.kb.config.NetworkBootstrap.configure();
        String dataDir = null;
        String modsFolder = null;
        String gameJar=null,gameLoader=null,gameVersion=null;
        String testFile = "test-questions.json";
        String webTestFile = "test-web.json";
        String webTestDir = null;
        String apiKey = null;
        String dialogueSpec = null;
        boolean isolated = false;
        boolean useSavedKey = false;
        boolean doScan = false;
        boolean doBindUrls = false;
        boolean doBuild = false;
        boolean appendBuild = false;
        var additionalSources=new java.util.ArrayList<BuildRequest.Source>();
        boolean doClean = false;
        boolean doTest = false;
        boolean doWebTest = false;
        String evalFile = "golden-set.json";
        String evalTag = null;
        int evalRepeat = 1;
        boolean doEval = false;
        boolean judgeEnabled = true;
        double failUnder = -1;
        String compareA = null;
        String compareB = null;
        String webDumpPattern = null;
        boolean webDumpFetch = false;

        // Parse CLI args
        // # 完整流水线: clean + build + test
        // ./mvnw exec:java "-Dexec.args=--data-dir D:/Minecraft/MKB-Database --clean --build D:/Minecraft/mods --test"
        //
        // # 仅测试（使用已有数据库）
        // ./mvnw exec:java "-Dexec.args=--data-dir D:/Minecraft/MKB-Database --test"
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--isolated" -> isolated = true;
                case "--use-saved-key" -> useSavedKey = true;
                case "--data-dir" -> dataDir = args[++i];
                case "--game-jar" -> gameJar=args[++i];
                case "--game-loader" -> gameLoader=args[++i];
                case "--game-version" -> gameVersion=args[++i];
                case "--scan" -> { doScan = true; if (i + 1 < args.length && !args[i + 1].startsWith("--")) modsFolder = args[++i]; }
                case "--build" -> { doBuild = true; doScan = true; if (i + 1 < args.length && !args[i + 1].startsWith("--")) modsFolder = args[++i]; }
                case "--bind-urls" -> {doBindUrls=true;doScan=true;if(i+1<args.length&&!args[i+1].startsWith("--"))modsFolder=args[++i];}
                case "--append" -> appendBuild=true;
                case "--build-url" -> {doBuild=true;additionalSources.add(BuildRequest.Source.of(BuildRequest.Kind.URL,args[++i]));}
                case "--build-documents" -> {doBuild=true;additionalSources.add(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,args[++i]));}
                case "--clean" -> doClean = true;
                case "--test" -> { doTest = true; if (i + 1 < args.length && !args[i + 1].startsWith("--")) testFile = args[++i]; }
                case "--eval" -> { doEval = true; if (i + 1 < args.length && !args[i + 1].startsWith("--")) evalFile = args[++i]; }
                case "--tag" -> { if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    System.err.println("ERROR: --tag requires a value (e.g. --tag baseline)");
                    System.exit(1);
                } evalTag = args[++i]; }
                case "--repeat" -> { if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    System.err.println("ERROR: --repeat requires a number (e.g. --repeat 5)");
                    System.exit(1);
                } evalRepeat = Integer.parseInt(args[++i]); }
                case "--judge" -> judgeEnabled = true;
                case "--no-judge" -> judgeEnabled = false;
                case "--fail-under" -> { if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    System.err.println("ERROR: --fail-under requires a rate (e.g. --fail-under 0.85)");
                    System.exit(1);
                } failUnder = Double.parseDouble(args[++i]); }
                case "--compare" -> { if (i + 2 >= args.length) {
                    System.err.println("ERROR: --compare requires two run ids (e.g. --compare run-A run-B)");
                    System.exit(1);
                } compareA = args[++i]; compareB = args[++i]; }
                case "--webdump" -> { if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    System.err.println("ERROR: --webdump requires a label regex (e.g. --webdump \"弹跳机兵|糖果独角兽\")");
                    System.exit(1);
                } webDumpPattern = args[++i]; }
                case "--fetch" -> webDumpFetch = true;
                case "--dialogue" -> { if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    System.err.println("ERROR: --dialogue requires a value like 4,5 (comma-separated 1-based dialogue indexes)");
                    System.exit(1);
                } dialogueSpec = args[++i]; }
                case "--testweb" -> { doWebTest = true; if (i + 1 < args.length && !args[i + 1].startsWith("--")) webTestDir = args[++i]; }
                case "--api-key" -> apiKey = args[++i];
                case "--help", "-h" -> { printUsage(); return; }
            }
        }

        // 解析 --dialogue（conversations 数组的 1-based 下标，逗号分隔）
        Set<Integer> dialogueIndexes = null;
        if (dialogueSpec != null) {
            try {
                dialogueIndexes = parseDialogueIndexes(dialogueSpec);
            } catch (IllegalArgumentException ex) {
                System.err.println("ERROR: " + ex.getMessage());
                printUsage();
                System.exit(1);
            }
            if (!doTest && !doEval) {
                System.out.println("Note: --dialogue is ignored because neither --test nor --eval was requested");
            }
        }

        if (doWebTest) {
            if (webTestDir == null) {
                System.err.println("ERROR: --testweb <target-dir> is required");
                return;
            }
            runWebTest(Path.of(webTestDir).toAbsolutePath(), webTestFile);
            return;
        }

        if (dataDir == null) {
            System.err.println("ERROR: --data-dir is required");
            printUsage();
            System.exit(1);
        }

        // === 评测版本对比（只读，不初始化配置/LLM）===
        if (compareA != null) {
            Path evalRoot = Path.of(dataDir).resolve("eval-runs");
            Path dirA = evalRoot.resolve(compareA);
            Path dirB = evalRoot.resolve(compareB);
            if (!Files.exists(dirA.resolve("summary.json")) || !Files.exists(dirB.resolve("summary.json"))) {
                System.err.println("ERROR: run summary.json not found. A=" + dirA + " B=" + dirB);
                System.exit(1);
            }
            String markdown = RunComparator.compare(dirA, dirB);
            System.out.println(markdown);
            Files.writeString(dirB.resolve("compare-" + compareA + "__" + compareB + ".md"), markdown);
            return;
        }

        System.out.println("══════════════════════════════════════════");
        System.out.println("  MAA Knowledge Builder — Headless Mode");
        System.out.println("══════════════════════════════════════════");

        // Init config
        if (useSavedKey && !isolated) throw new IllegalArgumentException("--use-saved-key requires --isolated");
        AppConfig config = isolated ? AppConfig.forIsolatedRun(useSavedKey) : AppConfig.getInstance();
        Path dataPath = Path.of(dataDir);
        Files.createDirectories(dataPath);
        config.setDataDir(dataPath);

        // 独占 dataDir：防止 GUI/CLI 同时写同一个嵌入式 H2 文件（2026-09-15 损坏事故教训）
        try {
            DataDirLock dirLock = DataDirLock.acquire(dataPath);
            Runtime.getRuntime().addShutdownHook(new Thread(dirLock::close));
        } catch (IOException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(3);
            return;
        }

        // --api-key 只作为本次运行的临时 Key：不写入 settings.properties，
        // 也不覆盖已保存的 Key；未提供时回退到 config 中已记录的 Key。
        if (apiKey != null && !apiKey.isBlank()) {
            config.setApiKeyForRun(apiKey);
            System.out.println("API Key: --api-key provided (runtime override, not persisted)");
        }

        // --test / --eval（自动问答）依赖 LLM：若既无 --api-key 也无已保存 Key，则不执行并说明原因。
        if ((doTest || doEval) && !config.hasUsableApiKey()) {
            System.err.println("ERROR: --test/--eval require a DeepSeek API Key, but neither --api-key was provided"
                    + " nor an apiKey is saved in settings.properties.");
            System.err.println("  - Pass --api-key <key> for a runtime-only key (NOT persisted); or");
            System.err.println("  - Configure the key in the GUI Settings / settings.properties first.");
            System.exit(1);
        }

        // === Clean ===
        if(appendBuild && (!doBuild || doClean))
            throw new IllegalArgumentException("--append 需要构建来源，且不能与 --clean 同时使用");
        if (doClean) {
            System.out.println("\n[Clean] Removing generated/, logs/, sessions/ ...");
            cleanDirectory(dataPath.resolve("generated"));
            cleanDirectory(dataPath.resolve("logs"));
            cleanDirectory(dataPath.resolve("sessions"));
            System.out.println("[Clean] Done.");
        }

        // Init database
        DatabaseBuilder db = new DatabaseBuilder(config);
        db.initDatabase();
        System.out.println("Database: " + db.getJdbcUrl());
        try {
            db.healthCheck();
        } catch (Exception e) {
            System.err.println("ERROR: 数据库健康检查失败（可能已损坏或被其它进程写入）：" + e.getMessage());
            System.err.println("  处理建议：① 从 backup-*/ 目录恢复备份；② 或用 --build <mods-folder> 重建基础库。");
            System.exit(4);
            return;
        }

        // === WebDump（只读）：检验 rag_web_cache 的 subWebPage 映射，可选抓取页面正文 ===
        if (webDumpPattern != null) {
            runWebDump(db, dataPath, webDumpPattern, webDumpFetch);
            return;
        }

        // Init embedding service
        EmbeddingService embedder = new EmbeddingService(config);
        String savedModel = config.getEmbeddingModelName();
        if (savedModel != null && !savedModel.isBlank() && !"deepseek-embedding".equals(savedModel)) {
            embedder.initModel(savedModel);
        }
        System.out.println("Embedding: " + embedder.activeModel() + " (dim=" + embedder.getDimension() + ")");

        // Init LLM service
        RagAgentService llm = new RagAgentService(config);
        System.out.println("LLM: " + (config.getApiKey() != null && !config.getApiKey().isBlank() ? "deepseek-chat" : "offline"));

        // === Scan + Build ===
        KnowledgeBuilder knowledgeBuilder = null;
        VectorStore vectorStore = null;
        var scannedEntries=new java.util.ArrayList<ModEntry>();

        if (doScan && modsFolder != null) {
            Path modsPath = Path.of(modsFolder);
            if (!Files.isDirectory(modsPath)) {
                System.err.println("ERROR: mods folder not found: " + modsFolder);
                System.exit(1);
            }

            var detector=new GameEnvironmentDetector();
            var candidates=gameJar==null?detector.discover(modsPath):detector.inspect(Path.of(gameJar)).map(List::of).orElse(List.of());
            if(candidates.size()!=1)throw new IllegalArgumentException("请用 --game-jar 指定唯一的 Minecraft 本体 JAR，或将本体 JAR 放在模组目录／直属上级目录");
            var candidate=candidates.getFirst();
            var environment=new yagen.waitmydawn.kb.model.GameEnvironment(candidate.jar().toString(),gameLoader==null?candidate.loader():gameLoader,gameVersion==null?candidate.version():gameVersion);
            new ScanEnvironmentStore(db).save(modsPath,environment);
            System.out.println("[Scan environment] "+environment.summary());
            System.out.println("\n[Scan] Scanning JARs in: " + modsFolder);
            var scan=new ModScanService().scan(modsPath,environment,db,appendBuild,new ModrinthBinder(),false,(done,total,entry,message)->System.out.println("[Scan "+done+"/"+total+"] "+message));
            scannedEntries.addAll(scan.mods());System.out.println(scan.metrics().summary());if(scan.warning()!=null)System.out.println(scan.warning());

        }

        if(doBindUrls) {
            if(modsFolder==null)throw new IllegalArgumentException("--bind-urls requires a mods folder");
            var scraper=new WikiScraperService();scraper.setResourceDatabase(db);var binder=new ModUrlBinder(scraper);var store=new ModBindingStore(db);var scanCache=new JarScanCache(Path.of(modsFolder));
            try{for(var entry:scannedEntries){
                var previous=entry.getMcmodBinding();
                if(previous==null||!Set.of("FOUND","NOT_FOUND","DISABLED","BUILTIN").contains(previous.status()))entry.setMcmodBinding(binder.bind(entry));
                store.save(entry);scanCache.updateBinding(entry);System.out.println("[Bind Url] "+entry.getModId()+" "+entry.getMcmodBinding());
            }}finally{if(scanCache.environment()!=null)scanCache.save(scanCache.environment());}
        }

        if (doBuild) {
            System.out.println("\n[Build] Starting knowledge base build...");
            knowledgeBuilder = new KnowledgeBuilder(config, db, embedder);
            knowledgeBuilder.setScannedMods(scannedEntries);
            if(modsFolder!=null) additionalSources.add(BuildRequest.Source.of(BuildRequest.Kind.JAR_DIRECTORY,modsFolder));
            var request=new BuildRequest(additionalSources,appendBuild?BuildRequest.Mode.APPEND:BuildRequest.Mode.REBUILD);
            KnowledgeBuilder.BuildResult r = knowledgeBuilder.build(request,
                    (phase, cur, total, msg) -> {
                        if ("done".equals(phase) || cur == total || total <= 1) {
                            System.out.println("  [" + phase + "] " + msg);
                        }
                    });
            vectorStore = knowledgeBuilder.getVectorStore();
            System.out.println("[Build] Complete! " + r.parsed + " mods, "
                    + r.recipes + " recipes, " + r.embeddings + " vectors, "
                    + (r.durationMs / 1000.0) + "s");
        }

        // === Test ===
        if (doTest) {
            System.out.println("\n[Test] Running automated Q&A tests...");

            // Re-init services needed for QA
            if (vectorStore == null) {
                vectorStore = new VectorStore(db, embedder.getDimension());
            }
            if (knowledgeBuilder == null) {
                knowledgeBuilder = new KnowledgeBuilder(config, db, embedder);
            }

            ClassifyAgent classifyAgent = new ClassifyAgent(llm);
            EntityAgent entityAgent = new EntityAgent(llm, db, embedder, vectorStore);
            UrlAgent urlAgent = new UrlAgent(llm, db, embedder);
            AnswerAgent answerAgent = new AnswerAgent(llm);
            MultiDBManager dbManager = new MultiDBManager(dataPath, db);
            dbManager.scan();

            QaPipeline pipeline = new QaPipeline(classifyAgent, entityAgent, urlAgent,
                    answerAgent, llm, vectorStore, embedder, db, dbManager, dataPath);

            // 会话化：每个对话模拟一个 GUI 会话（sessions/*.json + 会话增量库）
            ChatHistoryService chatHistoryService = new ChatHistoryService(dataPath);
            AutoTestService tester = new AutoTestService(
                    pipeline, chatHistoryService, dbManager, embedder.getDimension());
            Path testPath = Path.of(testFile);
            if (!Files.exists(testPath)) {
                System.err.println("ERROR: test file not found: " + testFile);
                System.exit(1);
            }

            AutoTestService.TestReport report = null;
            try {
                report = tester.runFromFile(testPath, dialogueIndexes);
                System.out.println(report.toTextReport());
            } catch (Exception e) {
                System.err.println("Test failed: " + e.getMessage());
                e.printStackTrace();
                System.exit(1);
            }

            // 报告落盘失败只告警（例如 CSV 被 Excel 占用），不影响测试结论与退出码
            Path reportPath = dataPath.resolve("test-report-" + java.time.LocalDate.now() + ".txt");
            Path csvPath = dataPath.resolve("test-metrics-" + java.time.LocalDate.now() + ".csv");
            try {
                report.saveToFile(reportPath);
                System.out.println("  " + reportPath);
            } catch (IOException e) {
                System.err.println("Warning: could not save report " + reportPath + ": " + e.getMessage());
            }
            try {
                report.saveCsv(csvPath);
                System.out.println("  " + csvPath);
            } catch (IOException e) {
                System.err.println("Warning: could not save CSV " + csvPath + ": " + e.getMessage());
            }
        }

        // === Eval（黄金评测集：规则判分 + LLM 裁判 + run 产物隔离）===
        if (doEval) {
            Path evalPath = Path.of(evalFile);
            if (!Files.exists(evalPath)) {
                System.err.println("ERROR: eval file not found: " + evalFile);
                System.exit(1);
            }
            System.out.println("\n[Eval] Golden set: " + evalPath.toAbsolutePath());
            System.out.println("[Eval] judge=" + (judgeEnabled ? "on" : "off")
                    + " defaultRepeat=" + evalRepeat
                    + " dialogues=" + (dialogueIndexes == null ? "all" : dialogueIndexes));
            VectorStore evalVectorStore = vectorStore != null
                    ? vectorStore : new VectorStore(db, embedder.getDimension());
            EvalRunner.Config evalConfig = new EvalRunner.Config(dataPath, evalPath, dialogueIndexes,
                    evalTag, evalRepeat, judgeEnabled);
            try {
                EvalRunner.Outcome outcome = new EvalRunner(config, db, embedder,
                        evalVectorStore, llm, evalConfig).run();
                System.out.println(outcome.reportMarkdown());
                System.out.printf("EVAL RUN %s: passRate=%.1f%% (%d/%d) runDir=%s%n",
                        outcome.runId(), outcome.passRate() * 100, outcome.passedTurns(),
                        outcome.totalTurns(), outcome.runDir());
                if (failUnder > 0 && outcome.passRate() < failUnder) {
                    System.err.printf("ERROR: pass rate %.1f%% < --fail-under %.1f%%%n",
                            outcome.passRate() * 100, failUnder * 100);
                    System.exit(2);
                }
            } catch (Exception e) {
                System.err.println("Eval failed: " + e.getMessage());
                e.printStackTrace();
                System.exit(1);
            }
        }

        System.out.println("\n══════════════════════════════════════════");
        System.out.println("  Headless run complete.");
        System.out.println("══════════════════════════════════════════");
    }

    /**
     * 检验 rag_web_cache 的 subWebPage 映射（实体/物品 → 网页 URL），可选抓取页面正文。
     * 用途：① 评估"主体 → 网页映射"节点的有效性；② 反向构建黄金集参考答案。
     */
    private static void runWebDump(DatabaseBuilder db, Path dataDir, String pattern, boolean fetch) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        List<DatabaseBuilder.WebCacheEntry> entries = db.loadAllWebPages();
        Map<String, Integer> perMod = new java.util.TreeMap<>();
        int totalMappings = 0;
        int matched = 0;
        WikiScraperService scraper = new WikiScraperService();
        StringBuilder report = new StringBuilder();
        report.append("[WebDump] pattern=").append(pattern).append(" fetch=").append(fetch)
                .append(" cachedPages=").append(entries.size()).append('\n');
        for (DatabaseBuilder.WebCacheEntry entry : entries) {
            Map<String, String> sub = entry.subWebPage();
            if (sub == null || sub.isEmpty()) continue;
            perMod.merge(entry.modId(), sub.size(), Integer::sum);
            for (Map.Entry<String, String> kv : sub.entrySet()) {
                totalMappings++;
                if (!p.matcher(kv.getKey()).find()) continue;
                matched++;
                report.append(String.format("  [%s] %s -> %s%n", entry.modId(), kv.getKey(), kv.getValue()));
                if (fetch && matched <= 40) {
                    try {
                        WikiScraperService.WebPage page = scraper.fetchMcmodPage(kv.getValue());
                        String text = page == null ? null : page.content();
                        report.append("    --- page chars=").append(text == null ? 0 : text.length()).append(" ---\n");
                        if (text != null) {
                            String snippet = text.replaceAll("\\s+", " ");
                            report.append("    ").append(snippet.length() > 1500
                                    ? snippet.substring(0, 1500) + "..." : snippet).append('\n');
                        }
                    } catch (Exception e) {
                        report.append("    fetch failed: ").append(e.getMessage()).append('\n');
                    }
                }
            }
        }
        report.append("[WebDump] total mappings=").append(totalMappings).append(" matched=").append(matched).append('\n');
        report.append("[WebDump] per-mod mappings: ").append(perMod).append('\n');
        Path out = dataDir.resolve("webdump.txt");
        try {
            Files.writeString(out, report.toString(), java.nio.charset.StandardCharsets.UTF_8);
            System.out.println("[WebDump] cachedPages=" + entries.size() + " matched=" + matched
                    + " → report written to " + out);
        } catch (IOException e) {
            System.out.println(report);
            System.err.println("[WebDump] failed to write report: " + e.getMessage());
        }
    }

    /** 仅测试网页抓取 → 正文清洗 → 分块 → 向量入库流程。 */
    private static void runWebTest(Path targetDir, String webTestFile) throws Exception {
        Files.createDirectories(targetDir);
        System.out.println("\n[WebTest] Target DB dir: " + targetDir);
        System.out.println("[WebTest] Test file: " + webTestFile);

        AppConfig config = AppConfig.getInstance();
        EmbeddingService embedder = new EmbeddingService(config);

        DatabaseBuilder db = new DatabaseBuilder(null) {
            @Override
            public String getJdbcUrl() {
                return "jdbc:h2:file:" + targetDir.resolve("rag_data").toString().replace('\\', '/')
                        + ";DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
            }
        };
        db.initDatabase();
        VectorStore vectorStore = new VectorStore(db, embedder.getDimension());

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(Path.of(webTestFile).toFile());
        JsonNode urls = root.path("urls");
        if (!urls.isArray()) {
            System.err.println("ERROR: " + webTestFile + " must contain an 'urls' array");
            return;
        }

        WikiScraperService scraper = new WikiScraperService();
        TextChunker chunker = new TextChunker();

        for (JsonNode item : urls) {
            String url = item.path("url").asText(null);
            if (url == null || url.isBlank()) continue;
            String modId = item.path("modId").asText("unknown");
            String modName = item.path("modName").asText(modId);
            String source = (url.contains("minecraft.wiki") || url.contains("wiki.biligame.com/mc"))
                    ? "mcwiki" : "mcmod";

            System.out.println("\n=== [" + source + "] " + url);
            WikiScraperService.FetchedPage fetched = scraper.fetchPageWithRegistries(url, source);
            if (fetched == null || fetched.page() == null) {
                System.out.println("  fetch failed or empty");
                continue;
            }
            System.out.println("  registries: " + fetched.registries());

            List<TextChunker.TextChunk> chunks = chunker.fromWebPage(
                    fetched.page().content(), modId, modName, source, url);
            vectorStore.storeBatch(chunks, embedder);
            System.out.println("  chunks: " + chunks.size());
            for (TextChunker.TextChunk c : chunks) {
                System.out.println("  --- [" + c.modId() + "/" + c.modName() + "/"
                        + c.type() + "] " + c.sourcePath());
                System.out.println(c.text());
            }
        }
        System.out.println("\n[WebTest] vectors in DB: " + vectorStore.count());
    }

    private static void cleanDirectory(Path dir) {
        try {
            if (Files.exists(dir)) {
                Files.walk(dir)
                        .sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                        });
            }
        } catch (Exception e) {
            System.err.println("  Warning: failed to clean " + dir + " - " + e.getMessage());
        }
    }

    /** 解析 --dialogue 值：如 "4,5" → {4,5}；非法或非正数抛 IllegalArgumentException。 */
    static Set<Integer> parseDialogueIndexes(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("--dialogue value is empty");
        }
        Set<Integer> indexes = new LinkedHashSet<>();
        for (String part : spec.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            try {
                int idx = Integer.parseInt(trimmed);
                if (idx < 1) {
                    throw new IllegalArgumentException(
                            "--dialogue indexes are 1-based and must be >= 1: '" + spec + "'");
                }
                indexes.add(idx);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Invalid --dialogue value: '" + spec + "' (expected comma-separated 1-based indexes like 4,5)");
            }
        }
        if (indexes.isEmpty()) {
            throw new IllegalArgumentException("--dialogue value is empty: '" + spec + "'");
        }
        return indexes;
    }

    private static void printUsage() {
        System.out.println("""
            Usage: HeadlessRunner --data-dir <path> [flags]

            Required:
              --data-dir <path>     Data directory for database and resources

            Operations:
              --game-jar <file>     Select Minecraft client JAR for scan environment
              --game-loader <id>    neoforge / forge / fabric (override detected loader)
              --game-version <id>   Override detected Minecraft version
              --scan <mods-folder>  Scan JAR files using one game environment
              --bind-urls [folder]  Bind MC百科 candidate URLs after scan; save review drafts
              --build <mods-folder> Build knowledge base from saved bindings (implies --scan)
              --build-url <url>    Import a URL (repeatable)
              --build-documents <path> Import a document directory (repeatable)
              --append            Preserve existing knowledge; import new content only
              --clean               Delete generated/, logs/, sessions/
              --isolated           Do not read/write global settings or change GUI data directory
              --use-saved-key      With --isolated, read the saved API key for this run only
              --test [file]         Run automated tests (default: test-questions.json)
              --dialogue <4,5>      Run only the listed dialogues (1-based index in the test
                                    file, comma-separated; e.g. --dialogue 4,5)
              --eval [file]         Run golden-set evaluation (default: golden-set.json):
                                    rule scoring + LLM judge, isolated under {data}/eval-runs/
              --tag <name>          Tag for the eval run id (e.g. --tag baseline)
              --repeat <n>          Default repeats per turn for stability (default 1)
              --judge / --no-judge  Enable/disable LLM judge (default: enabled)
              --fail-under <rate>   Exit code 2 when eval pass rate is below rate (e.g. 0.85)
              --compare <a> <b>     Compare two eval runs and print the ablation table
              --webdump <regex>     Inspect rag_web_cache subWebPage mappings (entity -> URL);
                                    with --fetch also crawl the matched pages (rate-limited)
              --fetch               Fetch matched pages in --webdump mode
              --testweb <target-dir> Test web fetch/chunk/vector pipeline (default: test-web.json)
              --api-key <key>       Runtime-only DeepSeek API key for this run (not persisted;
                                    falls back to the saved key when omitted)

            Examples:
              # Full pipeline: clean -> build -> test
              java ... HeadlessRunner --data-dir D:/data --clean --build D:/mods --test

              # Just test with existing database
              java ... HeadlessRunner --data-dir D:/data --test my-questions.json

              # Test web page processing into a separate vector DB
              java ... HeadlessRunner --testweb D:/web-test
            """);
    }
}
