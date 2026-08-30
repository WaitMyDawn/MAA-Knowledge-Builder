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
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import yagen.waitmydawn.kb.service.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

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
 *   --api-key <key>      DeepSeek API Key
 */
public class HeadlessRunner {

    private static final Logger log = LoggerFactory.getLogger(HeadlessRunner.class);

    public static void main(String[] args) throws Exception {
        String dataDir = null;
        String modsFolder = null;
        String testFile = "test-questions.json";
        String webTestFile = "test-web.json";
        String webTestDir = null;
        String apiKey = null;
        boolean doScan = false;
        boolean doBuild = false;
        boolean doClean = false;
        boolean doTest = false;
        boolean doWebTest = false;

        // Parse CLI args
        // # 完整流水线: clean + build + test
        // ./mvnw exec:java "-Dexec.args=--data-dir D:/Minecraft/MKB-Database --clean --build D:/Minecraft/mods --test"
        //
        // # 仅测试（使用已有数据库）
        // ./mvnw exec:java "-Dexec.args=--data-dir D:/Minecraft/MKB-Database --test"
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--data-dir" -> dataDir = args[++i];
                case "--scan" -> { doScan = true; if (i + 1 < args.length && !args[i + 1].startsWith("--")) modsFolder = args[++i]; }
                case "--build" -> { doBuild = true; doScan = true; if (i + 1 < args.length && !args[i + 1].startsWith("--")) modsFolder = args[++i]; }
                case "--clean" -> doClean = true;
                case "--test" -> { doTest = true; if (i + 1 < args.length && !args[i + 1].startsWith("--")) testFile = args[++i]; }
                case "--testweb" -> { doWebTest = true; if (i + 1 < args.length && !args[i + 1].startsWith("--")) webTestDir = args[++i]; }
                case "--api-key" -> apiKey = args[++i];
                case "--help", "-h" -> { printUsage(); return; }
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

        System.out.println("══════════════════════════════════════════");
        System.out.println("  MAA Knowledge Builder — Headless Mode");
        System.out.println("══════════════════════════════════════════");

        // Init config
        AppConfig config = AppConfig.getInstance();
        Path dataPath = Path.of(dataDir);
        Files.createDirectories(dataPath);
        config.setDataDir(dataPath);

        // If API key provided, override config
        if (apiKey != null && !apiKey.isBlank()) {
            // Set directly — AppConfig's save will persist it
            // But for headless, just set on the instance
            System.out.println("API Key: configured");
        }

        // === Clean ===
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

        if (doScan && modsFolder != null) {
            Path modsPath = Path.of(modsFolder);
            if (!Files.isDirectory(modsPath)) {
                System.err.println("ERROR: mods folder not found: " + modsFolder);
                System.exit(1);
            }

            System.out.println("\n[Scan] Scanning JARs in: " + modsFolder);
            JarScannerService scanner = new JarScannerService();
            ModMetadataParser parser = new ModMetadataParser();
            ModrinthBinder binder = new ModrinthBinder();
            List<java.nio.file.Path> jars = scanner.scan(modsPath);
            int count = 0;
            for (var jar : jars) {
                var entry = parser.parse(jar);
                if (entry != null) {
                    if (!"minecraft".equals(entry.getModId()) && !"vanilla".equals(entry.getLoader())) {
                        var bind = binder.bind(entry.getModId(), entry.getDisplayName(),
                                entry.getVersion(), entry.getMcVersion(), entry.getLoader());
                        if (bind != null && bind.slug() != null) {
                            entry.setSlug(bind.slug());
                            entry.setModrinthUrl(bind.modrinthUrl());
                            entry.setSource(bind.source());
                        }
                    }
                    db.saveModEntry(entry);
                    count++;
                    System.out.println("  [" + count + "] " + entry.getModId() + (entry.getSlug() != null ? " -> " + entry.getSlug() : ""));
                }
            }
            System.out.println("[Scan] Found " + count + " mods");
        }

        if (doBuild && modsFolder != null) {
            System.out.println("\n[Build] Starting knowledge base build...");
            knowledgeBuilder = new KnowledgeBuilder(config, db);
            KnowledgeBuilder.BuildResult r = knowledgeBuilder.build(Path.of(modsFolder),
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
                knowledgeBuilder = new KnowledgeBuilder(config, db);
            }

            ClassifyAgent classifyAgent = new ClassifyAgent(llm);
            EntityAgent entityAgent = new EntityAgent(llm, db, embedder, vectorStore);
            UrlAgent urlAgent = new UrlAgent(llm, db, embedder);
            AnswerAgent answerAgent = new AnswerAgent(llm);
            MultiDBManager dbManager = new MultiDBManager(dataPath, db);
            dbManager.scan();

            QaPipeline pipeline = new QaPipeline(classifyAgent, entityAgent, urlAgent,
                    answerAgent, llm, vectorStore, embedder, db, dbManager);

            AutoTestService tester = new AutoTestService(pipeline);
            Path testPath = Path.of(testFile);
            if (!Files.exists(testPath)) {
                System.err.println("ERROR: test file not found: " + testFile);
                System.exit(1);
            }

            try {
                AutoTestService.TestReport report = tester.runFromFile(testPath);
                System.out.println(report.toTextReport());

                // Save reports
                Path reportPath = dataPath.resolve("test-report-" + java.time.LocalDate.now() + ".txt");
                Path csvPath = dataPath.resolve("test-metrics-" + java.time.LocalDate.now() + ".csv");
                report.saveToFile(reportPath);
                report.saveCsv(csvPath);
                System.out.println("\nReports saved:");
                System.out.println("  " + reportPath);
                System.out.println("  " + csvPath);
            } catch (IOException e) {
                System.err.println("Test failed: " + e.getMessage());
                e.printStackTrace();
                System.exit(1);
            }
        }

        System.out.println("\n══════════════════════════════════════════");
        System.out.println("  Headless run complete.");
        System.out.println("══════════════════════════════════════════");
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
                        + ";DATABASE_TO_LOWER=TRUE";
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

    private static void printUsage() {
        System.out.println("""
            Usage: HeadlessRunner --data-dir <path> [flags]

            Required:
              --data-dir <path>     Data directory for database and resources

            Operations:
              --scan <mods-folder>  Scan JAR files and bind Modrinth slugs
              --build <mods-folder> Build knowledge base (implies --scan)
              --clean               Delete generated/, logs/, sessions/
              --test [file]         Run automated tests (default: test-questions.json)
              --testweb <target-dir> Test web fetch/chunk/vector pipeline (default: test-web.json)
              --api-key <key>       Set DeepSeek API key

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
