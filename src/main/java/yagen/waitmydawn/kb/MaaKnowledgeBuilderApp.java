package yagen.waitmydawn.kb;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yagen.waitmydawn.kb.agent.AnswerAgent;
import yagen.waitmydawn.kb.agent.ClassifyAgent;
import yagen.waitmydawn.kb.agent.EntityAgent;
import yagen.waitmydawn.kb.agent.UrlAgent;
import yagen.waitmydawn.kb.config.AppConfig;
import yagen.waitmydawn.kb.config.DataDirLock;
import yagen.waitmydawn.kb.dto.ClassificationResult;
import yagen.waitmydawn.kb.dto.QaMetrics;
import yagen.waitmydawn.kb.dto.RetrievalResult;
import yagen.waitmydawn.kb.model.*;
import yagen.waitmydawn.kb.renderer.TemplateManager;
import yagen.waitmydawn.kb.service.*;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MaaKnowledgeBuilderApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(MaaKnowledgeBuilderApp.class);

    private AppConfig config;
    private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "knowledge-worker");
        thread.setDaemon(true);
        return thread;
    });
    private DatabaseBuilder db;
    private KnowledgeBuilder knowledgeBuilder;
    private RagAgentService ragAgent;
    private SeedExportService seedExport;
    private SeedImportService seedImport;
    private ChatHistoryService chatHistoryService;
    private ChatHistoryService.ChatSession currentSession;
    private Label qaDataDirLabel;

    // UI state
    private StackPane contentArea;
    private VBox builderPage, qaPage, seedPage, settingsPage, metricsPage, knowledgePage, evaluationPage;
    private TextField folderField, apiKeyField, dataDirField;
    private TextArea builderLog, qaLog;
    private ListView<String> modListView;
    private VBox chatHistory;
    private TextArea questionField;
    private Button cancelButton;
    private volatile Thread activeWorker;
    private Button askBtn;
    /** QA 是否正在处理中：防止连点/回车造成并发 process() 共享 Agent 状态。 */
    private volatile boolean qaInFlight;
    private volatile boolean operationInFlight;
    private Label statusText;
    private ProgressBar progressBar;
    private List<ModEntry> scannedMods;
    private VectorStore vectorStore;
    private EntityResolver entityResolver = new EntityResolver();
    private QaPipeline qaPipeline;
    private MultiDBManager dbManager;
    private EmbeddingService embedder;
    private ClassifyAgent classifyAgent;
    private EntityAgent entityAgent;
    private AnswerAgent answerAgent;
    private VBox dbCheckboxList;
    private MetricsHistoryService metricsHistoryService;
    private TextArea metricsDisplay;
    private RendererService rendererService;
    /** dataDir 独占锁：防止 GUI 与 CLI/IDE 同时写同一个 H2 文件。 */
    private DataDirLock dataDirLock;

    @Override
    public void init() {
        config = AppConfig.getInstance();
    }

    @Override
    public void start(Stage stage) {
        if (!config.isInitialized()) {
            showSetupDialog(stage);
            if (!config.isInitialized()) {
                Platform.exit();
                return;
            }
        }

        // Init services now that data dir is set
        try {
            config.getDataDir(); // force NPE check
            dataDirLock = DataDirLock.acquire(config.getDataDir());
            Files.createDirectories(config.getTexturesDir());
            Files.createDirectories(config.getGeneratedDir());
            db = new DatabaseBuilder(config);
            db.initDatabase();
            ragAgent = new RagAgentService(config);
            embedder = new EmbeddingService(config);
            vectorStore = new VectorStore(db, embedder.getDimension());
            // Load saved model preference
            String savedModel = config.getEmbeddingModelName();
            if (savedModel == null || savedModel.isBlank() || "deepseek-embedding".equals(savedModel)) {
                savedModel = null;
            }
            embedder.initModel(savedModel);
            knowledgeBuilder = new KnowledgeBuilder(config, db, embedder);
            // Create agents
            classifyAgent = new ClassifyAgent(ragAgent);
            entityAgent = new EntityAgent(ragAgent, db, embedder, vectorStore);
            UrlAgent urlAgent = new UrlAgent(ragAgent, db, embedder);
            answerAgent = new AnswerAgent(ragAgent);
            // Multi-DB manager
            dbManager = new MultiDBManager(config.getDataDir(), db);
            dbManager.scan();
            // Pipeline with agents
            qaPipeline = new QaPipeline(classifyAgent, entityAgent, urlAgent,
                    answerAgent, ragAgent, vectorStore, embedder, db, dbManager,
                    config.getDataDir());
            metricsHistoryService = qaPipeline.getMetricsHistory();
            rendererService = new RendererService(config, db);
            entityResolver.rebuildFromDB(db);
            log.info("Existing DB: {} vectors, {} mods, {} recipes",
                    vectorStore.count(), db.findAllModEntries().size(),
                    countRecipes());
            seedExport = new SeedExportService(db);
            seedImport = new SeedImportService(db);
            chatHistoryService = new ChatHistoryService(config.getDataDir());
        } catch (Exception e) {
            log.error("Init failed", e);
            showError("Initialization failed: " + e.getMessage());
            Platform.exit();
            return;
        }

        buildMainUI(stage);
    }

    @Override
    public void stop() {
        worker.shutdownNow();
        boolean terminated = false;
        try { terminated = worker.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (!terminated) {
            log.warn("Background operation still stopping; resources retained until process exit");
            return;
        }
        try { if (dbManager != null) dbManager.close(); }
        catch (Exception e) { log.warn("Incremental database shutdown failed", e); }
        try { if (db != null) db.shutdown(); }
        catch (Exception e) { log.warn("Base database shutdown failed", e); }
        try { if (embedder != null) embedder.close(); }
        catch (Exception e) { log.warn("Embedding shutdown failed", e); }
        if (dataDirLock != null) { dataDirLock.close(); dataDirLock = null; }
    }

    // =================== Setup Dialog ===================

    private void showSetupDialog(Stage owner) {
        Stage dialog = new Stage();
        dialog.setTitle("MAA Knowledge Builder — Setup");
        dialog.initOwner(owner);

        VBox root = new VBox(16);
        root.setPadding(new Insets(30));
        root.setAlignment(Pos.CENTER);
        root.setStyle("-fx-background-color: #f5f3ff;");

        Label title = new Label("Welcome to MAA Knowledge Builder");
        title.setFont(Font.font("Segoe UI", FontWeight.BOLD, 20));
        title.setTextFill(Color.web("#6C63FF"));

        Label desc = new Label("Choose a folder to store all knowledge base data.\n"
                + "This includes the database, textures, generated images, and settings.");
        desc.setWrapText(true);
        desc.setFont(Font.font(14));

        TextField dirField = new TextField(Path.of(System.getProperty("user.home"), "MAA-Knowledge-Builder").toString());
        dirField.setPrefWidth(400);

        Button browse = new Button("浏览…");
        browse.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Select Data Directory");
            File f = dc.showDialog(dialog);
            if (f != null) dirField.setText(f.getAbsolutePath());
        });

        HBox dirRow = new HBox(8, dirField, browse);
        dirRow.setAlignment(Pos.CENTER);

        Button confirm = new Button("开始");
        confirm.setStyle("-fx-background-color: #6C63FF; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 8 30;");
        confirm.setOnAction(e -> {
            Path p = Path.of(dirField.getText().trim());
            try {
                Files.createDirectories(p);
                config.setDataDir(p);
                dialog.close();
            } catch (Exception ex) {
                showError("Cannot create directory: " + ex.getMessage());
            }
        });

        root.getChildren().addAll(title, desc, dirRow, confirm);

        Scene scene = new Scene(root, 500, 300);
        dialog.setScene(scene);
        dialog.showAndWait();
    }

    // =================== Main UI ===================

    private void buildMainUI(Stage stage) {
        // Sidebar
        VBox sidebar = buildSidebar(stage);

        // Content pages
        builderPage = buildBuilderPage(stage);
        qaPage = buildQAPage();
        seedPage = buildSeedPage(stage);
        metricsPage = buildMetricsPage();
        settingsPage = buildSettingsPage(stage);
        knowledgePage = new yagen.waitmydawn.kb.ui.KnowledgeBrowser(db,worker);
        evaluationPage = new yagen.waitmydawn.kb.ui.EvaluationBrowser(config.getDataDir());

        contentArea = new StackPane(builderPage); // default page

        // Status bar
        statusText = new Label("就绪");
        statusText.setFont(Font.font(12));
        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(120);
        progressBar.setVisible(false);
        progressBar.setManaged(false);
        cancelButton=new Button("取消当前任务"); cancelButton.setDisable(true); cancelButton.getStyleClass().add("danger-button");
        cancelButton.setOnAction(e->{ var running=activeWorker; if(running!=null) { cancelButton.setDisable(true); running.interrupt(); setStatus("正在取消，等待当前请求退出…",true); } });
        var statusSpacer=new Region(); HBox.setHgrow(statusSpacer,Priority.ALWAYS);
        HBox statusBar = new HBox(12, statusText, progressBar,statusSpacer,cancelButton);
        statusBar.setPadding(new Insets(4, 12, 4, 12));
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.getStyleClass().add("status-bar");

        BorderPane root = new BorderPane();
        root.setLeft(sidebar);
        root.setCenter(contentArea);
        root.setBottom(statusBar);

        Scene scene = new Scene(root, 1280, 800);
        scene.getStylesheets().add(java.util.Objects.requireNonNull(getClass().getResource("/ui/workspace.css")).toExternalForm());
        stage.setMinWidth(1060); stage.setMinHeight(680);
        stage.setTitle("MAA Knowledge Builder");
        stage.setScene(scene);
        stage.show();
    }

    // =================== Sidebar ===================

    private List<Button> navButtons = new java.util.ArrayList<>();

    private VBox buildSidebar(Stage stage) {
        VBox sidebar = new VBox(10);
        sidebar.setPrefWidth(196);
        sidebar.getStyleClass().add("sidebar");

        // Logo
        Label logo = new Label("MAA 知识库");
        logo.setFont(Font.font("Segoe UI", FontWeight.BOLD, 20));
        logo.setTextFill(Color.WHITE);
        logo.setPadding(new Insets(20, 16, 20, 16));
        logo.setMaxWidth(Double.MAX_VALUE);
        logo.getStyleClass().add("sidebar-brand");

        // Nav items
        VBox nav = new VBox(8);
        navButtons.clear();

        navButtons.add(navItem("构建知识库", () -> switchPage(builderPage, 0)));
        navButtons.add(navItem("知识问答", () -> switchPage(qaPage, 1)));
        navButtons.add(navItem("知识种子", () -> switchPage(seedPage, 2)));
        navButtons.add(navItem("性能统计", () -> switchPage(metricsPage, 3)));
        navButtons.add(navItem("设置", () -> switchPage(settingsPage, 4)));
        navButtons.add(navItem("知识目录", () -> switchPage(knowledgePage, 5)));
        navButtons.add(navItem("评测与复核", () -> switchPage(evaluationPage, 6)));

        nav.getChildren().addAll(navButtons);
        setActiveNav(0); // Default: Builder active

        VBox.setVgrow(nav, Priority.ALWAYS);
        sidebar.getChildren().addAll(logo, nav);
        return sidebar;
    }

    private void switchPage(VBox page, int index) {
        contentArea.getChildren().setAll(page);
        setActiveNav(index);
    }

    private void setActiveNav(int activeIdx) {
        for (int i = 0; i < navButtons.size(); i++) {
            Button btn = navButtons.get(i);
            boolean isActive = (i == activeIdx);
            btn.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("active"),isActive);
        }
    }

    private Button navItem(String text, Runnable action) {
        Button btn = new Button("  " + text);
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setAlignment(Pos.CENTER_LEFT);
        btn.setFont(Font.font("Segoe UI", 14));
        btn.getStyleClass().add("nav-button");
        btn.setOnAction(e -> action.run());
        return btn;
    }

    // =================== Builder Page ===================

    private VBox buildBuilderPage(Stage stage) {
        VBox page = new VBox(12);
        page.setPadding(new Insets(20));
        page.getStyleClass().add("workspace-page");

        Label title = pageTitle("构建知识库");

        // Step 1: Folder
        folderField = new TextField();
        folderField.setPromptText("选择包含模组 JAR 的目录…");
        folderField.setPrefWidth(400);
        Button browseMods = new Button("浏览");
        Button scanBtn = styledButton("扫描 JAR", "#6C63FF");
        scanBtn.setOnAction(e -> onScan());
        browseMods.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            File f = dc.showDialog(stage);
            if (f != null) folderField.setText(f.getAbsolutePath());
        });
        HBox folderRow = new HBox(8, folderField, browseMods, scanBtn);
        HBox.setHgrow(folderField,Priority.ALWAYS);
        folderRow.setAlignment(Pos.CENTER_LEFT);

        // Step 2: Mod list
        modListView = new ListView<>();
        modListView.setPrefHeight(220);
        modListView.setOnMouseClicked(ev -> {
            if (ev.getClickCount() == 2) {
                int idx = modListView.getSelectionModel().getSelectedIndex();
                if (idx >= 0 && idx < scannedMods.size()) {
                    ModEntry e = scannedMods.get(idx);
                    showSlugEditor(e, stage);
                }
            }
        });

        // Step 3: Build
        Button buildBtn = styledButton("构建知识库", "#10b981");
        buildBtn.setFont(Font.font(14));
        buildBtn.setOnAction(e -> onBuild());
        HBox buildRow = new HBox(12, buildBtn);
        buildRow.setAlignment(Pos.CENTER_LEFT);

        // Log
        builderLog = new TextArea();
        builderLog.setEditable(false);
        builderLog.setPrefRowCount(6);
        builderLog.setStyle("-fx-font-family: 'Consolas'; -fx-font-size: 11px;");

        SplitPane splitPane = new SplitPane();
        VBox topSection = new VBox(8,
                card("模组目录", folderRow),
                card("已识别模组", modListView));
        VBox.setVgrow(modListView, Priority.ALWAYS);
        VBox bottomSection = new VBox(8,
                card("开始构建", buildRow),
                card("构建日志", builderLog));
        VBox.setVgrow(builderLog, Priority.ALWAYS);
        splitPane.setOrientation(javafx.geometry.Orientation.VERTICAL);
        splitPane.getItems().addAll(topSection, bottomSection);
        splitPane.setDividerPositions(0.6);
        VBox.setVgrow(splitPane, Priority.ALWAYS);

        page.getChildren().addAll(title, splitPane);
        VBox.setVgrow(splitPane, Priority.ALWAYS);
        return page;
    }

    private void onScan() {
        if (busy()) return;
        String folderPath = folderField.getText().trim();
        if (folderPath.isEmpty()) {
            logText("Select a mod folder first.");
            return;
        }
        Path dir = Path.of(folderPath);
        if (!Files.isDirectory(dir)) {
            logText("Not a directory: " + folderPath);
            return;
        }

        logText("Scanning: " + folderPath);
        setStatus("正在扫描 JAR…", true);
        modListView.getItems().clear();
        operationInFlight = true;

        submitOperation(() -> {
            try {
            JarScannerService scanner = new JarScannerService();
            ModMetadataParser parser = new ModMetadataParser();
            ModrinthBinder binder = new ModrinthBinder();
            List<Path> jars = scanner.scan(dir);
            java.util.List<ModEntry> entries = new java.util.ArrayList<>();

            for (Path jar : jars) {
                if(Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("扫描已取消");
                ModEntry modEntry = parser.parse(jar);
                if (modEntry != null) {
                    // Don't search slug for vanilla or already-known
                    if (!"minecraft".equals(modEntry.getModId()) && !"vanilla".equals(modEntry.getLoader())) {
                        ModrinthBinder.BindResult bind = binder.bind(
                                jar, modEntry.getModId(),
                                modEntry.getDisplayName(),
                                modEntry.getVersion(),
                                modEntry.getMcVersion(),
                                modEntry.getLoader());
                        if (bind != null && bind.slug() != null) {
                            modEntry.setSlug(bind.slug());
                            modEntry.setModrinthUrl(bind.modrinthUrl());
                            modEntry.setSource(bind.source());
                        } else if (bind != null) {
                            modEntry.setSource(bind.source());
                        }
                    }
                    ModrinthBinder.preserveKnownBinding(modEntry, db.findModEntry(modEntry.getModId()));
                    db.saveModEntry(modEntry);
                    entries.add(modEntry);
                    ModEntry fe = modEntry;
                    Platform.runLater(() -> modListView.getItems().add(formatEntry(fe)));
                } else {
                    String name = jar.getFileName().toString();
                    Platform.runLater(() -> modListView.getItems().add("[?] " + name));
                }
            }
            scannedMods = entries;
            List<ModEntry> fEntries = entries;
            Platform.runLater(() -> {
                logText("Scanned " + fEntries.size() + " mods.");
                setStatus("就绪", false);
            });
            } catch (Exception ex) {
                Platform.runLater(() -> showError("Scan failed: " + ex.getMessage()));
            } finally { Platform.runLater(() -> { operationInFlight = false; setStatus("就绪", false); }); }
        });
    }

    private String formatEntry(ModEntry e) {
        StringBuilder sb = new StringBuilder(e.getSlug() != null ? "[V] " : "[?] ");
        sb.append(e.getModId());
        if (e.getDisplayName() != null && !e.getDisplayName().equals(e.getModId()))
            sb.append(" - ").append(e.getDisplayName());
        if (e.getVersion() != null) sb.append(" v").append(e.getVersion());
        if (e.getMcVersion() != null) sb.append(" [MC ").append(e.getMcVersion()).append("]");
        if (e.getLoader() != null) sb.append(" (").append(e.getLoader()).append(")");
        if ("modrinth-unavailable".equals(e.getSource())) sb.append("  [Modrinth 网络不可用，待重试]");
        else if ("modrinth-no-match".equals(e.getSource())) sb.append("  [未找到精确版本匹配]");
        if (e.getSlug() != null) sb.append("  <<slug=").append(e.getSlug()).append(">>");
        else sb.append("  <<no slug - click to set>>");
        return sb.toString();
    }

    private void onBuild() {
        if (busy()) return;
        String folderPath = folderField.getText().trim();
        if (folderPath.isEmpty() || !Files.isDirectory(Path.of(folderPath))) {
            logText("Select valid mod folder first.");
            return;
        }
        logText("Building knowledge base (JAR extraction + embed)...");
        setStatus("正在构建知识库…", true);
        operationInFlight = true;
        submitOperation(() -> {
            try {
            KnowledgeBuilder.BuildResult r = knowledgeBuilder.build(Path.of(folderPath),
                    (phase, cur, total, msg) -> Platform.runLater(() -> {
                        if (total > 0) progressBar.setProgress((double) cur / total);
                        logText("[" + phase + "] " + msg);
                    }));
            vectorStore = knowledgeBuilder.getVectorStore();
            rendererService.clearCache();
            // Refresh agents after build (new mods + subWebPages in DB)
            entityAgent = new EntityAgent(ragAgent, db, embedder, vectorStore);
            UrlAgent newUrlAgent = new UrlAgent(ragAgent, db, embedder);
            qaPipeline = new QaPipeline(classifyAgent, entityAgent, newUrlAgent,
                    answerAgent, ragAgent, vectorStore, embedder, db, dbManager,
                    config.getDataDir());
            metricsHistoryService = qaPipeline.getMetricsHistory();
            if (currentSession != null) qaPipeline.restoreHistory(currentSession.messages);
            Platform.runLater(() -> {
                logText("=== Complete ===");
                logText("Mods: " + r.parsed + "  Textures: " + r.textures
                        + "  Recipes: " + r.recipes);
                logText("Chunks: " + r.textChunks + "  Vectors: " + r.embeddings);
                logText("Duration: " + (r.durationMs / 1000.0) + "s");
                setStatus("就绪", false);
            });
            } catch (Exception ex) {
                Platform.runLater(() -> { if(ex instanceof java.util.concurrent.CancellationException) logText("构建已取消，保留原基础知识库。"); else showError("构建失败，保留原基础知识库："+ex.getMessage()); });
            } finally { Platform.runLater(() -> { operationInFlight = false; setStatus("就绪", false); }); }
        });
    }

    // =================== QA Page ===================

    private VBox sessionListView; // for refreshSessionList

    private VBox buildQAPage() {
        HBox page = new HBox(0);
        page.getStyleClass().add("workspace-page");

        // --- Left: History sidebar ---
        VBox historySidebar = new VBox(8);
        historySidebar.setPrefWidth(240);
        historySidebar.setPadding(new Insets(12));
        historySidebar.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e5e7eb; -fx-border-width: 0 1 0 0;");

        qaDataDirLabel = new Label("DB: " + config.getDataDir().getFileName());
        qaDataDirLabel.setFont(Font.font(10));
        qaDataDirLabel.setTextFill(Color.web("#9ca3af"));

        Button newChatBtn = styledButton("+ 新对话", "#6C63FF");
        newChatBtn.setMaxWidth(Double.MAX_VALUE);
        newChatBtn.setOnAction(e -> {
            if (busy()) return;
            dbManager.setCurrentSessionId(null);
            currentSession = null;
            chatHistory.getChildren().clear();
            qaPipeline.clearHistory();
            refreshSessionList(historySidebar);
        });

        Button clearBtn = new Button("清空对话");
        clearBtn.setStyle("-fx-text-fill: #ef4444; -fx-background-color: transparent; -fx-font-size: 11px;");
        clearBtn.setOnAction(e -> {
            if (busy()) return;
            try {
                dbManager.close();
                // Delete session JSON files
                java.io.File[] files = config.getDataDir().resolve("sessions").toFile()
                        .listFiles(f2 -> f2.getName().endsWith(".json"));
                if (files != null) for (java.io.File f : files) f.delete();
                // Delete incremental DB directory
                java.io.File incDir = dbManager.getIncDir().toFile();
                if (incDir.exists()) {
                    java.io.File[] incFiles = incDir.listFiles();
                    if (incFiles != null) for (java.io.File f : incFiles) f.delete();
                    incDir.delete();
                }
            } catch (Exception ex) {
                log.warn("Clear failed", ex);
            }
            currentSession = null;
            qaPipeline.clearHistory();
            chatHistory.getChildren().clear();
            refreshSessionList(historySidebar);
        });

        sessionListView = new VBox(4);
        ScrollPane sessionScroll = new ScrollPane(sessionListView);
        sessionScroll.setFitToWidth(true);
        sessionScroll.setPrefHeight(400);
        VBox.setVgrow(sessionScroll, Priority.ALWAYS);

        // DB management checkboxes
        dbCheckboxList = new VBox(4);
        dbCheckboxList.setPadding(new Insets(4, 0, 4, 0));
        ScrollPane dbScroll = new ScrollPane(dbCheckboxList);
        dbScroll.setFitToWidth(true);
        dbScroll.setPrefHeight(100);
        Button refreshDbBtn = new Button("刷新知识库");
        refreshDbBtn.setFont(Font.font(10));
        refreshDbBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #6C63FF;");
        refreshDbBtn.setOnAction(ev -> refreshDBCheckboxes(dbCheckboxList));

        historySidebar.getChildren().addAll(qaDataDirLabel, newChatBtn, clearBtn,
                new Separator(), new Label("检索数据库："), dbScroll, refreshDbBtn,
                new Separator(), new Label("最近对话："), sessionScroll);

        // --- Right: Chat area ---
        VBox chatArea = new VBox(12);
        chatArea.setPadding(new Insets(20));

        chatHistory = new VBox(8);
        chatHistory.setPadding(new Insets(8));
        ScrollPane chatScroll = new ScrollPane(chatHistory);
        chatScroll.setFitToWidth(true);
        chatScroll.setPrefHeight(440);
        chatScroll.setStyle("-fx-background: #ffffff; -fx-border-color: #e5e7eb; -fx-border-radius: 8;");

        questionField = new TextArea();
        questionField.setWrapText(true); questionField.setPrefRowCount(2); questionField.setMaxHeight(100);
        questionField.setPromptText("询问模组、生物、配方或机制 · Ctrl + Enter 发送");
        questionField.setPrefWidth(500);
        questionField.setOnKeyPressed(e -> { if(e.isControlDown() && e.getCode()==javafx.scene.input.KeyCode.ENTER) { onAsk(); e.consume(); } });
        askBtn = styledButton("发送", "#6C63FF");
        askBtn.setOnAction(e -> onAsk());
        HBox inputRow = new HBox(8, questionField, askBtn);
        HBox.setHgrow(questionField,Priority.ALWAYS); inputRow.setAlignment(Pos.BOTTOM_CENTER);

        qaLog = new TextArea();
        qaLog.setEditable(false);
        qaLog.setPrefRowCount(3);
        qaLog.setStyle("-fx-font-family: 'Consolas'; -fx-font-size: 11px;");

        VBox chatSection = new VBox(4, chatScroll, inputRow);
        VBox.setVgrow(chatScroll, Priority.ALWAYS);
        var detailSection = new TitledPane("检索与性能详情",qaLog); detailSection.setExpanded(false);
        VBox qaLayout = new VBox(12,chatSection,detailSection); VBox.setVgrow(chatSection,Priority.ALWAYS);

        chatArea.getChildren().addAll(pageTitle("知识问答"), qaLayout);
        VBox.setVgrow(qaLayout, Priority.ALWAYS);
        HBox.setHgrow(chatArea, Priority.ALWAYS);

        page.getChildren().addAll(historySidebar, chatArea);
        refreshSessionList(historySidebar);
        refreshDBCheckboxes(dbCheckboxList);

        VBox wrapper = new VBox(page);
        VBox.setVgrow(page,Priority.ALWAYS);
        return wrapper;
    }

    private void refreshSessionList(VBox historySidebar) {
        sessionListView.getChildren().clear();
        try {
            List<ChatHistoryService.ChatSession> sessions = chatHistoryService.listSessions();
            for (ChatHistoryService.ChatSession sess : sessions) {
                HBox row = new HBox(4);
                row.setPadding(new Insets(4));
                row.setStyle("-fx-background-color: #f9fafb; -fx-background-radius: 4; -fx-cursor: hand;");

                String labelText = sess.title + " (" + sess.messages.size() + " msgs)";
                if (labelText.length() > 28) labelText = labelText.substring(0, 28) + "...";
                Label lbl = new Label(labelText);
                lbl.setFont(Font.font(11));

                Button delBtn = new Button("X");
                delBtn.setFont(Font.font(9));
                delBtn.setStyle("-fx-text-fill: #ef4444; -fx-background-color: transparent; -fx-padding: 0 4;");
                delBtn.setOnAction(ev -> {
                    if (busy()) return;
                    try {
                        Files.deleteIfExists(
                                config.getDataDir().resolve("sessions").resolve(sess.sessionId + ".json"));
                        // Also delete associated incremental DB from sessions/incremental/
                        if (!dbManager.deleteBySessionId(sess.sessionId)) throw new IllegalStateException("Incremental database deletion failed");
                        if (currentSession != null && currentSession.sessionId.equals(sess.sessionId)) {
                            currentSession = null; qaPipeline.clearHistory(); chatHistory.getChildren().clear();
                        }
                    } catch (Exception ignored) {
                    }
                    refreshSessionList(historySidebar);
                });

                lbl.setOnMouseClicked(ev -> {
                    loadSession(sess);
                    refreshSessionList(historySidebar);
                });

                row.getChildren().addAll(lbl, delBtn);
                sessionListView.getChildren().add(row);
            }
        } catch (Exception e) {
            log.warn("List sessions failed", e);
        }
    }

    private void loadSession(ChatHistoryService.ChatSession sess) {
        if (busy()) return;
        currentSession = sess;
        // Switch incremental DB for this session
        dbManager.setCurrentSessionId(sess.sessionId);
        IncrementalDB incDb = dbManager.getCurrentIncDB();
        if (incDb == null) {
            incDb = dbManager.createIncrementalDB(sess.sessionId, embedder.getDimension());
        }
        qaPipeline.setIncrementalDB(incDb);
        qaPipeline.restoreHistory(sess.messages);
        refreshDBCheckboxes(dbCheckboxList);

        chatHistory.getChildren().clear();
        for (ChatHistoryService.ChatMessage msg : sess.messages) {
            String bg = msg.role().equals("user") ? "#e8f0fe" : "#f0fdf4";
            addChatBubble(msg.role().equals("user") ? "You" : "MAA", msg.content(), bg);
        }
        qaLog.appendText("Loaded: " + sess.title + " (" + sess.messages.size() + " msgs)\n");
    }

    private void onAsk() {
        String q = questionField.getText().trim();
        if (q.isEmpty()) return;
        // 单会话管道非线程安全：上一问未结束时忽略重复提交（批次 A 修复）。
        if (busy()) return;
        qaInFlight = true;
        questionField.setDisable(true);
        askBtn.setDisable(true);
        questionField.clear();
        addChatBubble("You", q, "#e8f0fe");
        setStatus("正在检索与生成回答…", true);

        // Init session if needed
        if (currentSession == null) {
            currentSession = chatHistoryService.newSession("Q&A " + java.time.LocalTime.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES), config.getDataDir());
            // Create incremental DB for this session
            dbManager.createIncrementalDB(currentSession.sessionId, embedder.getDimension());
            dbManager.setCurrentSessionId(currentSession.sessionId);
            qaPipeline.setIncrementalDB(dbManager.getCurrentIncDB());
            refreshDBCheckboxes(dbCheckboxList);
        }
        chatHistoryService.addMessage(currentSession, "user", q);
        final var submittedSession = currentSession;
        final var submittedPipeline = qaPipeline;

        submitOperation(() -> {
            try {
                QaPipeline.QaResult result = submittedPipeline.process(q);

                // Render recipe images on demand (skip if already exists)
                final Map<String, String> recipeImages;
                final List<String> imagePaths = new java.util.ArrayList<>();
                if (!result.recipesToRender.isEmpty()) {
                    recipeImages = rendererService.renderRecipes(result.recipesToRender);
                    for (String p : recipeImages.values()) {
                        java.io.File f = new java.io.File(p);
                        // 不再用字节大小粗筛（合法小图如 1KB 熔炉图会被误丢），
                        // 改为校验文件存在且可被解码为图片（2026-09-07 修复）。
                        boolean valid = f.exists() && f.length() > 0;
                        if (valid) {
                            try (var in = new java.io.FileInputStream(f)) {
                                valid = ImageIO.read(in) != null;
                            } catch (Exception ex) {
                                valid = false;
                            }
                        }
                        if (valid) {
                            imagePaths.add(p);
                        } else {
                            log.warn("Recipe image invalid or unreadable, skipping: {} ({} bytes)",
                                    p, f.exists() ? f.length() : 0);
                        }
                    }
                } else {
                    recipeImages = Map.of();
                }

                // Embed image references into the answer at the end
                final String answerWithImages;
                if (!recipeImages.isEmpty()) {
                    StringBuilder imgs = new StringBuilder("\n\n## 配方图\n\n");
                    for (var e : recipeImages.entrySet()) {
                        String outputLabel = e.getKey().split("#", 2)[0];
                        String name = outputLabel.contains(":")
                                ? outputLabel.substring(outputLabel.indexOf(':') + 1) : outputLabel;
                        imgs.append("![recipe:").append(name).append("](")
                                .append(e.getValue().replace('\\', '/')).append(")\n\n");
                    }
                    answerWithImages = result.answer + imgs;
                } else {
                    answerWithImages = result.answer;
                }

                Platform.runLater(() -> {
                    addChatBubble("MAA", answerWithImages, "#f0fdf4");
                    appendEvidence(result);
                    if (result.metrics != null) {
                        log.info("QA Metrics: total={}ms answers={} imgs={}",
                                result.metrics.totalTimeMs, result.metrics.answerLength,
                                imagePaths.size());
                    }
                    chatHistoryService.addMessage(submittedSession, "maa", answerWithImages);
                    try {
                        chatHistoryService.save(submittedSession);
                    } catch (Exception ignored) {}
                    setStatus("就绪", false);
                    qaInFlight = false;
                    questionField.setDisable(false);
                    askBtn.setDisable(false);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    addChatBubble("失败", ex.getMessage(), "#fee2e2");
                    setStatus("失败", false);
                    qaInFlight = false;
                    questionField.setDisable(false);
                    askBtn.setDisable(false);
                });
            }
        });
    }

    private void addChatBubble(String sender, String msg, String bg) {
        VBox b = new VBox(4);
        b.getStyleClass().add("You".equals(sender)?"chat-user":"chat-assistant");

        // Header row
        HBox header = new HBox(8);
        Label s = new Label(sender);
        s.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12));
        header.getChildren().add(s);

        // Content
        if ("You".equals(sender)) {
            TextArea m = new TextArea(msg);
            m.setEditable(false);
            m.setWrapText(true);
            m.setPrefRowCount(Math.min(15, msg.split("\n").length + 1));
            m.setStyle("-fx-background-color: transparent; -fx-font-size: 13px;");
            b.getChildren().addAll(header, m);
        } else {
            // Copy button for MAA responses
            Button copyBtn = new Button("复制");
            copyBtn.setFont(Font.font(10));
            copyBtn.setStyle("-fx-text-fill: #6b7280; -fx-background-color: #e5e7eb; -fx-padding: 2 6; -fx-background-radius: 3;");
            String raw = msg;
            copyBtn.setOnAction(ev -> {
                javafx.scene.input.Clipboard.getSystemClipboard()
                        .setContent(Map.of(javafx.scene.input.DataFormat.PLAIN_TEXT, raw));
                copyBtn.setText("Copied!");
                var delay = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(1.5));
                delay.setOnFinished(event -> copyBtn.setText("复制"));
                delay.play();
            });
            header.getChildren().add(copyBtn);

            b.getChildren().add(header);
            b.getChildren().add(yagen.waitmydawn.kb.ui.MarkdownRenderer.render(msg, java.util.List.of(config.getGeneratedDir())));
        }
        chatHistory.getChildren().add(b);
    }

    // =================== Seed Page ===================

    private VBox buildSeedPage(Stage stage) {
        VBox page = new VBox(12);
        page.setPadding(new Insets(20));
        page.getStyleClass().add("workspace-page");

        // Export
        Label exportTitle = pageTitle("导出知识种子");
        TextField nameF = new TextField();
        nameF.setPromptText("知识库名称");
        nameF.setPrefWidth(350);
        TextField descF = new TextField();
        descF.setPromptText("描述");
        descF.setPrefWidth(350);
        Button exportBtn = styledButton("导出知识种子", "#10b981");
        exportBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("MAA Seed", "*.maa-seed.json"));
            fc.setInitialFileName((nameF.getText().isEmpty() ? "seed" : nameF.getText()) + ".maa-seed.json");
            File f = fc.showSaveDialog(stage);
            if (f != null) {
                try {
                    seedExport.exportToFile(nameF.getText().isEmpty() ? "Untitled" : nameF.getText(), descF.getText(), config.getUsername(), f);
                    logText("Exported: " + f.getAbsolutePath());
                } catch (Exception ex) {
                    logText("Export failed: " + ex.getMessage());
                }
            }
        });
        VBox exportCard = card("导出", new VBox(8,
                new HBox(8, new Label("名称："), nameF),
                new HBox(8, new Label("描述："), descF),
                exportBtn));

        // Import
        Label importTitle = pageTitle("导入知识种子");
        HBox importRow = new HBox(8);
        TextField seedFile = new TextField();
        seedFile.setPromptText("选择 .maa-seed.json 文件…");
        seedFile.setPrefWidth(350);
        Button browseSeed = new Button("浏览…");
        browseSeed.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("MAA Seed", "*.maa-seed.json"));
            File f = fc.showOpenDialog(stage);
            if (f != null) seedFile.setText(f.getAbsolutePath());
        });
        importRow.getChildren().addAll(seedFile, browseSeed);

        Button importBtn = styledButton("导入与匹配", "#6C63FF");
        TextArea importResult = new TextArea();
        importResult.setEditable(false);
        importResult.setPrefRowCount(8);
        importResult.setStyle("-fx-font-family: 'Consolas'; -fx-font-size: 11px;");
        importBtn.setOnAction(e -> {
            String sp = seedFile.getText().trim();
            String md = folderField.getText().trim();
            if (sp.isEmpty() || md.isEmpty()) {
                importResult.setText("Select both seed file and mod folder.");
                return;
            }
            try {
                TrainingSeed seed = seedImport.readSeed(new File(sp));
                SeedImportService.MatchResult mr = seedImport.match(seed, Path.of(md));
                StringBuilder sb = new StringBuilder("=== " + seed.getName() + " ===\n");
                sb.append(mr.matched.size()).append(" matched, ");
                sb.append(mr.missingDownloadable.size()).append(" downloadable, ");
                sb.append(mr.missingManual.size()).append(" manual\n");
                importResult.setText(sb.toString());
            } catch (Exception ex) {
                importResult.setText("Error: " + ex.getMessage());
            }
        });
        VBox importCard = card("导入", new VBox(8, importRow, importBtn, importResult));

        page.getChildren().addAll(exportTitle, exportCard, importTitle, importCard);
        return page;
    }

    // =================== Metrics Dashboard Page ===================

    // Quick-card value labels (updated on refresh)
    private Label cardRequests, cardHitRate, cardTtft, cardLatency;
    private ComboBox<String> metricsFilterMode;
    private TextField metricsFilterValue;
    private static final int DEFAULT_METRICS_DAYS = 7;
    private static final int DEFAULT_METRICS_COUNT = 100;

    private VBox buildMetricsPage() {
        VBox page = new VBox(12);
        page.setPadding(new Insets(20));
        page.getStyleClass().add("workspace-page");

        Label title = pageTitle("性能统计");

        // --- Filter row ---
        Label filterLabel = new Label("筛选：");
        filterLabel.setFont(Font.font(13));

        metricsFilterMode = new ComboBox<>();
        metricsFilterMode.getItems().addAll("Recent Days", "Recent Count");
        metricsFilterMode.setValue("Recent Days");
        metricsFilterMode.setPrefWidth(130);
        metricsFilterMode.setOnAction(e -> {
            // Update default value when mode switches
            if ("Recent Days".equals(metricsFilterMode.getValue())) {
                metricsFilterValue.setText(String.valueOf(DEFAULT_METRICS_DAYS));
            } else {
                metricsFilterValue.setText(String.valueOf(DEFAULT_METRICS_COUNT));
            }
        });

        metricsFilterValue = new TextField(String.valueOf(DEFAULT_METRICS_DAYS));
        metricsFilterValue.setPrefWidth(60);
        metricsFilterValue.setStyle("-fx-font-size: 13px;");
        // Only allow digits
        metricsFilterValue.textProperty().addListener((o, old, val) -> {
            if (!val.matches("\\d*")) metricsFilterValue.setText(val.replaceAll("[^\\d]", ""));
        });

        Button refreshBtn = styledButton("刷新", "#6C63FF");
        refreshBtn.setOnAction(e -> refreshMetricsDisplay());

        Button exportCsvBtn = new Button("导出 CSV");
        exportCsvBtn.setStyle("-fx-text-fill: #6C63FF; -fx-background-color: transparent;");
        exportCsvBtn.setOnAction(e -> exportMetricsCsv());

        HBox filterRow = new HBox(8, filterLabel, metricsFilterMode, metricsFilterValue, refreshBtn, exportCsvBtn);
        filterRow.setAlignment(Pos.CENTER_LEFT);

        // --- Quick-cards ---
        cardRequests = new Label("0");
        cardHitRate = new Label("0%");
        cardTtft = new Label("0ms");
        cardLatency = new Label("0ms");

        HBox quickCards = new HBox(12);
        quickCards.setPadding(new Insets(8, 0, 8, 0));
        quickCards.getChildren().addAll(
                metricCard("Requests", cardRequests, "#6C63FF"),
                metricCard("Hit Rate", cardHitRate, "#10b981"),
                metricCard("Avg TTFT", cardTtft, "#f59e0b"),
                metricCard("Avg Latency", cardLatency, "#ef4444")
        );

        VBox quickSection = card("当前筛选范围概览", quickCards);

        // --- Detailed report ---
        metricsDisplay = new TextArea();
        metricsDisplay.setEditable(false);
        metricsDisplay.setPrefRowCount(22);
        metricsDisplay.setStyle("-fx-font-family: 'Consolas', 'Microsoft YaHei', monospace; -fx-font-size: 12px;");

        VBox.setVgrow(metricsDisplay, Priority.ALWAYS);

        page.getChildren().addAll(title, filterRow, quickSection,
                card("指标明细", metricsDisplay));

        // Initial load
        refreshMetricsDisplay();

        return page;
    }

    private VBox metricCard(String label, Label valueLabel, String color) {
        VBox card = new VBox(4);
        card.setPadding(new Insets(10));
        card.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e5e7eb; -fx-border-radius: 8; "
                + "-fx-background-radius: 8; -fx-min-width: 140;");
        valueLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 22));
        valueLabel.setTextFill(Color.web(color));
        Label descLabel = new Label(label);
        descLabel.setFont(Font.font(11));
        descLabel.setTextFill(Color.web("#6b7280"));
        card.getChildren().addAll(valueLabel, descLabel);
        return card;
    }

    private void refreshMetricsDisplay() {
        List<QaMetrics> filtered;

        if (metricsHistoryService == null) {
            metricsDisplay.setText("Metrics service not initialized.");
            return;
        }

        String mode = metricsFilterMode.getValue();
        int val;
        try {
            val = Integer.parseInt(metricsFilterValue.getText().trim());
            if (val <= 0) val = ("Recent Days".equals(mode)) ? DEFAULT_METRICS_DAYS : DEFAULT_METRICS_COUNT;
        } catch (NumberFormatException e) {
            val = ("Recent Days".equals(mode)) ? DEFAULT_METRICS_DAYS : DEFAULT_METRICS_COUNT;
            metricsFilterValue.setText(String.valueOf(val));
        }

        if ("Recent Days".equals(mode)) {
            filtered = metricsHistoryService.loadRecentDays(val);
        } else {
            filtered = metricsHistoryService.loadRecent(val);
        }

        // Update quick-cards
        if (filtered.isEmpty()) {
            cardRequests.setText("0");
            cardHitRate.setText("-");
            cardTtft.setText("-");
            cardLatency.setText("-");
        } else {
            var s = MetricsHistoryService.summarize(filtered);
            cardRequests.setText(String.valueOf(s.totalRequests));
            cardHitRate.setText(String.format("%.0f%%", s.hitRate * 100));
            cardTtft.setText(s.avgTtftMs + "ms");
            cardLatency.setText(s.avgTotalMs + "ms");
        }

        // Build detailed report
        StringBuilder sb = new StringBuilder();
        sb.append("Filter: ").append(mode).append(" = ").append(val).append("\n");

        if (filtered.isEmpty()) {
            sb.append("\n(No metrics found for this filter — try increasing the range)\n\n");
            sb.append("Available data files:\n");
            for (String date : metricsHistoryService.listDates()) {
                java.nio.file.Path f = config.getDataDir().resolve("metrics")
                        .resolve("qa_metrics_" + date + ".jsonl");
                try {
                    long lines = java.nio.file.Files.lines(f).count();
                    sb.append("  ").append(date).append(": ").append(lines).append(" entries\n");
                } catch (Exception ignored) {}
            }
        } else {
            var summary = MetricsHistoryService.summarize(filtered);
            sb.append(summary.toTextReport());

            // Per-tier breakdown
            sb.append("\n--- Per-Tier Latency ---\n");
            var tierStats = new LinkedHashMap<String, java.util.LongSummaryStatistics>();
            for (QaMetrics m : filtered) {
                String tier = m.dataTier != null ? m.dataTier : "TIER D";
                tierStats.computeIfAbsent(tier, k -> new java.util.LongSummaryStatistics())
                        .accept(m.totalTimeMs);
            }
            for (var e : tierStats.entrySet()) {
                var stats = e.getValue();
                sb.append(String.format("  %s: avg=%dms min=%dms max=%dms count=%d\n",
                        e.getKey(), (long) stats.getAverage(), stats.getMin(), stats.getMax(), stats.getCount()));
            }

            // Latency distribution histogram (text-based)
            sb.append("\n--- Latency Distribution ---\n");
            int[] buckets = new int[10]; // 0-1s, 1-2s, ..., 9-10s, 10s+
            for (QaMetrics m : filtered) {
                int bucketIdx = Math.min((int) (m.totalTimeMs / 1000), 9);
                buckets[bucketIdx]++;
            }
            int maxBucket = java.util.Arrays.stream(buckets).max().orElse(1);
            for (int i = 0; i < buckets.length; i++) {
                String range = i < 9 ? String.format("%d-%ds", i, i + 1) : "10s+";
                int barLen = maxBucket > 0 ? (int) (20.0 * buckets[i] / maxBucket) : 0;
                String bar = "█".repeat(barLen);
                sb.append(String.format("  %5s: %s %d\n", range, bar, buckets[i]));
            }
        }

        metricsDisplay.setText(sb.toString());
    }

    private void exportMetricsCsv() {
        if (metricsHistoryService == null) return;
        try {
            List<QaMetrics> all = metricsHistoryService.loadAll();
            if (all.isEmpty()) {
                metricsDisplay.setText("No metrics to export.");
                return;
            }
            java.nio.file.Path csvPath = config.getDataDir()
                    .resolve("metrics_export_" + java.time.LocalDate.now() + ".csv");
            try (java.io.BufferedWriter w = java.nio.file.Files.newBufferedWriter(csvPath)) {
                w.write(QaMetrics.csvHeader());
                w.newLine();
                for (QaMetrics m : all) {
                    w.write(m.toCsvRow());
                    w.newLine();
                }
            }
            metricsDisplay.setText("Exported " + all.size() + " entries to:\n" + csvPath);
        } catch (Exception e) {
            metricsDisplay.setText("Export failed: " + e.getMessage());
        }
    }

    // =================== Settings Page ===================

    private VBox buildSettingsPage(Stage stage) {
        VBox page = new VBox(12);
        page.setPadding(new Insets(20));
        page.getStyleClass().add("workspace-page");

        // Data Directory
        Label dirTitle = pageTitle("数据目录");
        dataDirField = new TextField(config.getDataDir().toString());
        dataDirField.setPrefWidth(400);
        Button changeDir = new Button("更换…");
        changeDir.setOnAction(e -> {
            if (busy()) return;
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Select New Data Directory");
            File f = dc.showDialog(stage);
            if (f != null) {
                Path newDir = Path.of(f.getAbsolutePath());
                config.setDataDirForNextLaunch(newDir);
                showError("数据目录已保存，下次启动生效。请重启应用。");
            }
        });
        HBox dirRow = new HBox(8, dataDirField, changeDir);
        VBox dirCard = card("存储位置", dirRow);
        Label dirNote = new Label("All data (database, textures, generated images) is stored in this folder. "
                + "You can move this folder to another computer.");
        dirNote.setWrapText(true);
        dirNote.setFont(Font.font(12));
        dirNote.setTextFill(Color.web("#6b7280"));

        // Author
        Label authorTitle = pageTitle("个人信息");
        TextField authorField = new TextField(config.getUsername());
        authorField.setPrefWidth(200);
        authorField.textProperty().addListener((o, old, val) -> config.setUsername(val));
        HBox authorRow = new HBox(8, new Label("署名："), authorField);
        VBox authorCard = card("种子作者", authorRow);

        // API Key
        Label apiTitle = pageTitle("API Key");
        apiKeyField = new PasswordField();
        apiKeyField.setPromptText("sk-...");
        apiKeyField.setPrefWidth(400);
        if (config.getApiKey() != null) apiKeyField.setText(config.getApiKey());
        apiKeyField.textProperty().addListener((o, old, val) -> config.setApiKey(val));
        HBox apiRow = new HBox(8, new Label("DeepSeek Key:"), apiKeyField);
        VBox apiCard = card("模型连接", apiRow);
        Label apiNote = new Label("API key is stored in %USERPROFILE%/.maa_kb/settings.properties. "
                + "Without a key, the Q&A will use offline mode showing raw retrieval results.");
        apiNote.setWrapText(true);
        apiNote.setFont(Font.font(12));
        apiNote.setTextFill(Color.web("#6b7280"));

        // Embedding model selector
        Label embedLabel = pageTitle("本地向量模型");
        ComboBox<String> modelCombo = new ComboBox<>();
        modelCombo.setPrefWidth(400);
        Map<String, String> models = embedder.listModels();
        modelCombo.getItems().addAll(models.keySet());
        modelCombo.setValue(embedder.activeModel());
        // Display name converter
        modelCombo.setCellFactory(lv -> new javafx.scene.control.ListCell<String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : models.getOrDefault(item, item));
            }
        });
        modelCombo.setButtonCell(modelCombo.getCellFactory().call(null));

        // Saved model preference
        Label modelStatus = new Label("");
        modelStatus.setFont(Font.font(12));

        Button applyModelBtn = styledButton("Apply Model", "#6C63FF");
        applyModelBtn.setOnAction(e -> {
            if (busy()) return;
            String selected = modelCombo.getValue();
            if (selected == null) return;
            config.setEmbeddingModelName(selected);
            String loaded = embedder.initModel(selected);
            String status;
            if (selected.equals(loaded)) {
                status = "Applied: " + loaded + " (will take effect on next Build)";
                modelStatus.setTextFill(Color.web("#10b981"));
            } else if ("hash-ngram-v2".equals(loaded)) {
                status = "Using: stable hash n-gram (place model files in models/ folder first)";
                modelStatus.setTextFill(Color.web("#f59e0b"));
            } else {
                status = "Active: " + loaded;
                modelStatus.setTextFill(Color.web("#10b981"));
            }
            modelStatus.setText(status);
        });
        // Show current status on page load
        updateModelStatus(modelStatus, modelCombo);

        VBox embedCard = card("向量化设置",
                new VBox(6,
                        new Label("ONNX model: all-MiniLM-L6-v2 (bundled, 384-dim)"),
                        new HBox(8, modelCombo, applyModelBtn), modelStatus));
        page.getChildren().addAll(dirTitle, dirCard, dirNote, authorTitle, authorCard,
                apiTitle, apiCard, apiNote, embedLabel, embedCard);
        return page;
    }

    // =================== Helpers ===================

    private Label pageTitle(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("page-title");
        l.setTextFill(Color.web("#1f2937"));
        l.setPadding(new Insets(0, 0, 4, 0));
        return l;
    }

    private VBox card(String title, javafx.scene.Node content) {
        VBox card = new VBox(8);
        card.getStyleClass().add("card");
        Label t = new Label(title);
        t.getStyleClass().add("card-title");
        t.setFont(Font.font("Segoe UI", FontWeight.BOLD, 13));
        t.setTextFill(Color.web("#374151"));
        card.getChildren().addAll(t, content);
        return card;
    }

    private Button styledButton(String text, String color) {
        Button b = new Button(text);
        b.getStyleClass().add("#10b981".equals(color)?"success-button":"primary-button");
        return b;
    }

    private void submitOperation(Runnable operation) {
        worker.submit(()->{
            activeWorker=Thread.currentThread();
            Platform.runLater(()->cancelButton.setDisable(false));
            try { operation.run(); }
            finally { activeWorker=null; Thread.interrupted(); Platform.runLater(()->cancelButton.setDisable(true)); }
        });
    }

    private void appendEvidence(QaPipeline.QaResult result) {
        StringBuilder text=new StringBuilder();
        if(result.catalogEvidence!=null) text.append(result.catalogEvidence).append("\n\n");
        result.subPageUrls.forEach((label,url)->text.append(label).append("\n").append(url).append("\n\n"));
        if(result.metrics!=null) text.append(result.metrics.toUiSummary());
        var detail=new TextArea(text.toString()); detail.setEditable(false); detail.setWrapText(true); detail.setPrefRowCount(8);
        var evidence=new TitledPane("来源、覆盖范围与性能",detail); evidence.setExpanded(false);
        chatHistory.getChildren().add(evidence);
    }

    private void logText(String msg) {
        builderLog.appendText(msg + "\n");
    }

    private boolean busy() {
        if (!qaInFlight && !operationInFlight) return false;
        setStatus("请等待当前任务完成", true);
        return true;
    }

    private void setStatus(String msg, boolean showProgress) {
        statusText.setText(msg);
        progressBar.setVisible(showProgress);
        progressBar.setManaged(showProgress);
        if(showProgress && progressBar.getProgress()==0) progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        if (!showProgress) progressBar.setProgress(0);
    }

    private void showError(String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR);
        a.setTitle("失败");
        a.setHeaderText(null);
        a.setContentText(msg);
        a.showAndWait();
    }

    private int countRecipes() {
        try (var c = db.getConnection();
             var s = c.createStatement();
             var r = s.executeQuery("SELECT COUNT(*) FROM rag_recipe")) {
            return r.next() ? r.getInt(1) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private void showSlugEditor(ModEntry entry, Stage owner) {
        Stage d = new Stage();
        d.initOwner(owner);
        d.setTitle("Edit Slug - " + entry.getModId());

        VBox root = new VBox(10);
        root.setPadding(new Insets(16));
        root.getChildren().add(new Label("Mod: " + entry.getModId() + " - " + entry.getDisplayName()));

        TextField slugField = new TextField(entry.getSlug() != null ? entry.getSlug() : "");
        slugField.setPrefWidth(350);
        root.getChildren().add(new HBox(8, new Label("Slug:"), slugField));

        Button openModrinth = new Button("打开 Modrinth 页面");
        openModrinth.setStyle("-fx-text-fill: #6C63FF; -fx-background-color: transparent;");
        openModrinth.setOnAction(ev -> {
            String slug = slugField.getText().trim();
            if (!slug.isEmpty()) {
                try {
                    java.awt.Desktop.getDesktop().browse(java.net.URI.create("https://modrinth.com/mod/" + slug));
                } catch (Exception ignored) {
                }
            }
        });
        root.getChildren().add(openModrinth);

        HBox btns = new HBox(8);
        Button save = styledButton("保存", "#10b981");
        save.setOnAction(ev -> {
            String newSlug = slugField.getText().trim();
            entry.setSlug(newSlug.isEmpty() ? null : newSlug);
            if (entry.getSlug() != null) {
                entry.setModrinthUrl("https://modrinth.com/mod/" + entry.getSlug());
                entry.setSource("modrinth");
            }
            db.saveModEntry(entry);
            // Refresh list display
            int idx = scannedMods.indexOf(entry);
            if (idx >= 0) modListView.getItems().set(idx, formatEntry(entry));
            d.close();
        });
        Button cancel = new Button("取消");
        cancel.setOnAction(ev -> d.close());
        btns.getChildren().addAll(save, cancel);
        root.getChildren().add(btns);

        d.setScene(new Scene(root, 420, 200));
        d.showAndWait();
    }

    /**
     * Load entity name mappings — now uses EntityResolver.rebuildFromDB
     */
    private void loadEntityMappings() {
        if (vectorStore == null || vectorStore.count() == 0) return;
        entityResolver.rebuildFromDB(db);
    }

    private void refreshDBCheckboxes(VBox list) {
        this.dbCheckboxList = list;
        list.getChildren().clear();
        List<MultiDBManager.DBInfo> dbs = dbManager.scan();
        for (MultiDBManager.DBInfo info : dbs) {
            String label = info.displayName()
                    + " (" + (info.sizeBytes() / 1024 / 1024) + "MB)"
                    + (info.isBase() ? " [base]" : "");
            CheckBox cb = new CheckBox(label);
            cb.setSelected(info.enabled());
            cb.setFont(Font.font(12));
            cb.setOnAction(ev -> {
                if (busy()) { cb.setSelected(info.enabled()); return; }
                dbManager.setEnabled(info.filename(), cb.isSelected());
            });
            // Add delete button for non-base DBs
            if (!info.isBase()) {
                Button delBtn = new Button("删除");
                delBtn.setFont(Font.font(9));
                delBtn.setStyle("-fx-text-fill: #ef4444; -fx-background-color: transparent; -fx-padding: 0 4;");
                delBtn.setOnAction(ev -> {
                    if (busy()) return;
                    dbManager.delete(info.filename());
                    refreshDBCheckboxes(list);
                });
                HBox row = new HBox(4, cb, delBtn);
                row.setAlignment(Pos.CENTER_LEFT);
                list.getChildren().add(row);
            } else {
                list.getChildren().add(cb);
            }
        }
    }

    private void updateModelStatus(Label status, ComboBox<String> combo) {
        String active = embedder.activeModel();
        int vecCount = (vectorStore != null) ? vectorStore.count() : 0;
        if ("none".equals(active)) {
            status.setText("Using: built-in n-gram TF-IDF | " + vecCount + " vectors in DB");
            status.setTextFill(Color.web("#6b7280"));
        } else {
            status.setText("Active: " + active + " | " + vecCount + " vectors in DB");
            status.setTextFill(Color.web("#10b981"));
        }
    }

    public static void main(String[] args) {
        yagen.waitmydawn.kb.config.NetworkBootstrap.configure();
        // Check for headless mode before launching JavaFX
        boolean headless = false;
        for (String arg : args) {
            if ("--headless".equals(arg)) { headless = true; break; }
        }
        if (headless) {
            try {
                HeadlessRunner.main(args);
            } catch (Exception e) {
                System.err.println("Headless run failed: " + e.getMessage());
                e.printStackTrace();
                System.exit(1);
            }
            return;
        }
        launch(args);
    }
}
