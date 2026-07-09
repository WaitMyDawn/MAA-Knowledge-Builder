package yagen.waitmydawn.kb;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
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
import yagen.waitmydawn.kb.dto.ClassificationResult;
import yagen.waitmydawn.kb.dto.RetrievalResult;
import yagen.waitmydawn.kb.model.*;
import yagen.waitmydawn.kb.renderer.TemplateManager;
import yagen.waitmydawn.kb.service.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class MaaKnowledgeBuilderApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(MaaKnowledgeBuilderApp.class);

    private AppConfig config;
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
    private VBox builderPage, qaPage, seedPage, settingsPage;
    private TextField folderField, apiKeyField, dataDirField;
    private TextArea builderLog, qaLog;
    private ListView<String> modListView;
    private VBox chatHistory;
    private TextField questionField;
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
            Files.createDirectories(config.getTexturesDir());
            Files.createDirectories(config.getGeneratedDir());
            db = new DatabaseBuilder(config);
            db.initDatabase();
            knowledgeBuilder = new KnowledgeBuilder(config, db);
            ragAgent = new RagAgentService(config);
            embedder = new EmbeddingService(config);
            vectorStore = new VectorStore(db, embedder.getDimension());
            // Load saved model preference
            String savedModel = config.getEmbeddingModelName();
            if (savedModel == null || savedModel.isBlank() || "deepseek-embedding".equals(savedModel)) {
                savedModel = null;
            }
            embedder.initModel(savedModel);
            // Create agents
            classifyAgent = new ClassifyAgent(ragAgent);
            entityAgent = new EntityAgent(ragAgent, db);
            UrlAgent urlAgent = new UrlAgent(ragAgent, db);
            answerAgent = new AnswerAgent(ragAgent);
            // Multi-DB manager
            dbManager = new MultiDBManager(config.getDataDir(), db);
            dbManager.scan();
            // Pipeline with agents
            qaPipeline = new QaPipeline(classifyAgent, entityAgent, urlAgent,
                    answerAgent, ragAgent, vectorStore, embedder, db, dbManager);
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

        Button browse = new Button("Browse...");
        browse.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Select Data Directory");
            File f = dc.showDialog(dialog);
            if (f != null) dirField.setText(f.getAbsolutePath());
        });

        HBox dirRow = new HBox(8, dirField, browse);
        dirRow.setAlignment(Pos.CENTER);

        Button confirm = new Button("Start");
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
        settingsPage = buildSettingsPage(stage);

        contentArea = new StackPane(builderPage); // default page

        // Status bar
        statusText = new Label("Ready");
        statusText.setFont(Font.font(12));
        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(120);
        progressBar.setVisible(false);
        HBox statusBar = new HBox(8, statusText, progressBar);
        statusBar.setPadding(new Insets(4, 12, 4, 12));
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setStyle("-fx-background-color: #f8f9fa; -fx-border-color: #dee2e6; -fx-border-width: 1 0 0 0;");

        BorderPane root = new BorderPane();
        root.setLeft(sidebar);
        root.setCenter(contentArea);
        root.setBottom(statusBar);

        Scene scene = new Scene(root, 1000, 600);
        root.setStyle("-fx-font-family: 'Segoe UI', 'Microsoft YaHei', sans-serif;");
        stage.setTitle("MAA Knowledge Builder");
        stage.setScene(scene);
        stage.show();
    }

    // =================== Sidebar ===================

    private List<Button> navButtons = new java.util.ArrayList<>();

    private VBox buildSidebar(Stage stage) {
        VBox sidebar = new VBox(0);
        sidebar.setPrefWidth(220);
        sidebar.setStyle("-fx-background-color: #2b2d42;");

        // Logo
        Label logo = new Label("MAA  KB");
        logo.setFont(Font.font("Segoe UI", FontWeight.BOLD, 20));
        logo.setTextFill(Color.WHITE);
        logo.setPadding(new Insets(20, 16, 20, 16));
        logo.setMaxWidth(Double.MAX_VALUE);
        logo.setStyle("-fx-background-color: #1e1f2e;");

        // Nav items
        VBox nav = new VBox(0);
        navButtons.clear();

        navButtons.add(navItem("Builder", () -> switchPage(builderPage, 0)));
        navButtons.add(navItem("Q&A", () -> switchPage(qaPage, 1)));
        navButtons.add(navItem("Seed Mgr", () -> switchPage(seedPage, 2)));
        navButtons.add(navItem("Settings", () -> switchPage(settingsPage, 3)));

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
            String bg = isActive ? "#6C63FF" : "transparent";
            String border = isActive ? "-fx-border-width: 0 0 0 3; -fx-border-color: #a78bfa;" : "";
            btn.setStyle("-fx-background-color: " + bg + "; -fx-text-fill: white; -fx-padding: 12 16; "
                    + border + " -fx-cursor: hand;");
        }
    }

    private Button navItem(String text, Runnable action) {
        Button btn = new Button("  " + text);
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setAlignment(Pos.CENTER_LEFT);
        btn.setFont(Font.font("Segoe UI", 14));
        btn.setStyle("-fx-background-color: transparent; -fx-text-fill: white; -fx-padding: 12 16; "
                + "-fx-border-width: 0; -fx-cursor: hand;");
        btn.setOnMouseEntered(e -> {
            if (!isActiveNav(btn))
                btn.setStyle("-fx-background-color: #3a3b50; -fx-text-fill: white; -fx-padding: 12 16; -fx-cursor: hand;");
        });
        btn.setOnMouseExited(e -> {
            if (!isActiveNav(btn))
                btn.setStyle("-fx-background-color: transparent; -fx-text-fill: white; -fx-padding: 12 16; -fx-border-width: 0; -fx-cursor: hand;");
        });
        btn.setOnAction(e -> action.run());
        return btn;
    }

    private boolean isActiveNav(Button btn) {
        return btn.getStyle().contains("#6C63FF");
    }

    // =================== Builder Page ===================

    private VBox buildBuilderPage(Stage stage) {
        VBox page = new VBox(12);
        page.setPadding(new Insets(20));
        page.setStyle("-fx-background-color: #f8f9fa;");

        Label title = pageTitle("Knowledge Builder");

        // Step 1: Folder
        folderField = new TextField();
        folderField.setPromptText("Select your mods folder...");
        folderField.setPrefWidth(400);
        Button browseMods = new Button("Browse");
        Button scanBtn = styledButton("Scan JARs", "#6C63FF");
        scanBtn.setOnAction(e -> onScan());
        browseMods.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            File f = dc.showDialog(stage);
            if (f != null) folderField.setText(f.getAbsolutePath());
        });
        HBox folderRow = new HBox(8, folderField, browseMods, scanBtn);
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
        Button buildBtn = styledButton("Build Knowledge Base", "#10b981");
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
                card("Mod Folder", folderRow),
                card("Detected Mods", modListView));
        VBox.setVgrow(modListView, Priority.ALWAYS);
        VBox bottomSection = new VBox(8,
                card("Build", buildRow),
                card("Log", builderLog));
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
        setStatus("Scanning...", true);
        modListView.getItems().clear();

        new Thread(() -> {
            JarScannerService scanner = new JarScannerService();
            ModMetadataParser parser = new ModMetadataParser();
            ModrinthBinder binder = new ModrinthBinder();
            List<Path> jars = scanner.scan(dir);
            java.util.List<ModEntry> entries = new java.util.ArrayList<>();

            for (Path jar : jars) {
                ModEntry modEntry = parser.parse(jar);
                if (modEntry != null) {
                    // Don't search slug for vanilla or already-known
                    if (!"minecraft".equals(modEntry.getModId()) && !"vanilla".equals(modEntry.getLoader())) {
                        ModrinthBinder.BindResult bind = binder.bind(
                                modEntry.getModId(),
                                modEntry.getDisplayName(),
                                modEntry.getVersion(),
                                modEntry.getMcVersion(),
                                modEntry.getLoader());
                        if (bind != null && bind.slug() != null) {
                            modEntry.setSlug(bind.slug());
                            modEntry.setModrinthUrl(bind.modrinthUrl());
                            modEntry.setSource(bind.source());
                        } else if (bind != null) {
                            modEntry.setSource(bind.source()); // "non-modrinth"
                        }
                    }
                    db.saveModEntry(modEntry); // persist slug immediately
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
                setStatus("Ready", false);
            });
        }, "mod-scan").start();
    }

    private String formatEntry(ModEntry e) {
        StringBuilder sb = new StringBuilder(e.getSlug() != null ? "[V] " : "[?] ");
        sb.append(e.getModId());
        if (e.getDisplayName() != null && !e.getDisplayName().equals(e.getModId()))
            sb.append(" - ").append(e.getDisplayName());
        if (e.getVersion() != null) sb.append(" v").append(e.getVersion());
        if (e.getMcVersion() != null) sb.append(" [MC ").append(e.getMcVersion()).append("]");
        if (e.getLoader() != null) sb.append(" (").append(e.getLoader()).append(")");
        if (e.getSlug() != null) sb.append("  <<slug=").append(e.getSlug()).append(">>");
        else sb.append("  <<no slug - click to set>>");
        return sb.toString();
    }

    private void onBuild() {
        String folderPath = folderField.getText().trim();
        if (folderPath.isEmpty() || !Files.isDirectory(Path.of(folderPath))) {
            logText("Select valid mod folder first.");
            return;
        }
        logText("Building knowledge base (JAR extraction + embed)...");
        setStatus("Building...", true);
        new Thread(() -> {
            KnowledgeBuilder.BuildResult r = knowledgeBuilder.build(Path.of(folderPath),
                    (phase, cur, total, msg) -> Platform.runLater(() -> {
                        if (total > 0) progressBar.setProgress((double) cur / total);
                        logText("[" + phase + "] " + msg);
                    }));
            vectorStore = knowledgeBuilder.getVectorStore();
            // Refresh agents after build (new mods + subWebPages in DB)
            entityAgent = new EntityAgent(ragAgent, db);
            UrlAgent newUrlAgent = new UrlAgent(ragAgent, db);
            qaPipeline = new QaPipeline(classifyAgent, entityAgent, newUrlAgent,
                    answerAgent, ragAgent, vectorStore, embedder, db, dbManager);
            Platform.runLater(() -> {
                logText("=== Complete ===");
                logText("Mods: " + r.parsed + "  Textures: " + r.textures
                        + "  Recipes: " + r.recipes);
                logText("Chunks: " + r.textChunks + "  Vectors: " + r.embeddings);
                logText("Duration: " + (r.durationMs / 1000.0) + "s");
                setStatus("Ready", false);
            });
        }, "kb-build").start();
    }

    // =================== QA Page ===================

    private VBox sessionListView; // for refreshSessionList

    private VBox buildQAPage() {
        HBox page = new HBox(0);
        page.setStyle("-fx-background-color: #f8f9fa;");

        // --- Left: History sidebar ---
        VBox historySidebar = new VBox(8);
        historySidebar.setPrefWidth(240);
        historySidebar.setPadding(new Insets(12));
        historySidebar.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e5e7eb; -fx-border-width: 0 1 0 0;");

        qaDataDirLabel = new Label("DB: " + config.getDataDir().getFileName());
        qaDataDirLabel.setFont(Font.font(10));
        qaDataDirLabel.setTextFill(Color.web("#9ca3af"));

        Button newChatBtn = styledButton("+ New Chat", "#6C63FF");
        newChatBtn.setMaxWidth(Double.MAX_VALUE);
        newChatBtn.setOnAction(e -> {
            currentSession = null;
            chatHistory.getChildren().clear();
            qaPipeline.clearHistory();
            refreshSessionList(historySidebar);
        });

        Button clearBtn = new Button("Clear All");
        clearBtn.setStyle("-fx-text-fill: #ef4444; -fx-background-color: transparent; -fx-font-size: 11px;");
        clearBtn.setOnAction(e -> {
            try {
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
        Button refreshDbBtn = new Button("Refresh DBs");
        refreshDbBtn.setFont(Font.font(10));
        refreshDbBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #6C63FF;");
        refreshDbBtn.setOnAction(ev -> refreshDBCheckboxes(dbCheckboxList));

        historySidebar.getChildren().addAll(qaDataDirLabel, newChatBtn, clearBtn,
                new Separator(), new Label("Databases:"), dbScroll, refreshDbBtn,
                new Separator(), new Label("History:"), sessionScroll);

        // --- Right: Chat area ---
        VBox chatArea = new VBox(12);
        chatArea.setPadding(new Insets(20));

        chatHistory = new VBox(8);
        chatHistory.setPadding(new Insets(8));
        ScrollPane chatScroll = new ScrollPane(chatHistory);
        chatScroll.setFitToWidth(true);
        chatScroll.setPrefHeight(440);
        chatScroll.setStyle("-fx-background: #ffffff; -fx-border-color: #e5e7eb; -fx-border-radius: 8;");

        questionField = new TextField();
        questionField.setPromptText("Ask about Minecraft recipes, structures, mechanics...");
        questionField.setPrefWidth(500);
        questionField.setOnAction(e -> onAsk());
        Button askBtn = styledButton("Ask", "#6C63FF");
        askBtn.setOnAction(e -> onAsk());
        HBox inputRow = new HBox(8, questionField, askBtn);

        qaLog = new TextArea();
        qaLog.setEditable(false);
        qaLog.setPrefRowCount(3);
        qaLog.setStyle("-fx-font-family: 'Consolas'; -fx-font-size: 11px;");

        SplitPane qaSplit = new SplitPane();
        qaSplit.setOrientation(javafx.geometry.Orientation.VERTICAL);
        VBox chatSection = new VBox(4, chatScroll, inputRow);
        VBox.setVgrow(chatScroll, Priority.ALWAYS);
        VBox detailSection = new VBox(4, card("Details", qaLog));
        qaSplit.getItems().addAll(chatSection, detailSection);
        qaSplit.setDividerPositions(0.8);
        VBox.setVgrow(qaSplit, Priority.ALWAYS);

        chatArea.getChildren().addAll(pageTitle("MC Knowledge Q&A"), qaSplit);
        VBox.setVgrow(qaSplit, Priority.ALWAYS);
        HBox.setHgrow(chatArea, Priority.ALWAYS);

        page.getChildren().addAll(historySidebar, chatArea);
        refreshSessionList(historySidebar);

        VBox wrapper = new VBox(page);
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
                    try {
                        Files.deleteIfExists(
                                config.getDataDir().resolve("sessions").resolve(sess.sessionId + ".json"));
                        // Also delete associated incremental DB from sessions/incremental/
                        dbManager.deleteBySessionId(sess.sessionId);
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
        currentSession = sess;
        // Switch incremental DB for this session
        dbManager.setCurrentSessionId(sess.sessionId);
        IncrementalDB incDb = dbManager.getCurrentIncDB();
        if (incDb == null) {
            incDb = dbManager.createIncrementalDB(sess.sessionId, embedder.getDimension());
        }
        qaPipeline.setIncrementalDB(incDb);
        qaPipeline.clearHistory();  // Reset conversation context for new session
        refreshDBCheckboxes(dbCheckboxList);

        chatHistory.getChildren().clear();
        for (ChatHistoryService.ChatMessage msg : sess.messages) {
            String bg = msg.role().equals("user") ? "#e8f0fe" : "#f0fdf4";
            addChatBubble(msg.role().equals("user") ? "You" : "MAA", msg.content(), bg, null);
        }
        qaLog.appendText("Loaded: " + sess.title + " (" + sess.messages.size() + " msgs)\n");
    }

    private void onAsk() {
        String q = questionField.getText().trim();
        if (q.isEmpty()) return;
        questionField.clear();
        addChatBubble("You", q, "#e8f0fe", null);
        setStatus("Thinking...", true);

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

        new Thread(() -> {
            try {
                QaPipeline.QaResult result = qaPipeline.process(q);

                // Render images from found recipes
                List<String> imagePaths = new java.util.ArrayList<>();
                if (!result.recipesToRender.isEmpty()) {
                    TemplateManager tm = new TemplateManager(config);
                    RendererService renderer = new RendererService(config, tm);
                    // Build a minimal retrieval result for the renderer
                    var ret = new RetrievalResult();
                    ret.setFound(true);
                    for (QaPipeline.RecipeMatch rm : result.recipesToRender) {
                        ret.getRecipeJsons().add(rm.recipeJson());
                    }
                    var dummyClass = new ClassificationResult();
                    dummyClass.setQuestionType(result.questionType != null ? result.questionType : ClassificationResult.QuestionType.RECIPE);
                    Map<String, String> images = renderer.preRender(dummyClass, ret);
                    imagePaths = new java.util.ArrayList<>();
                    for (String p : images.values()) {
                        java.io.File f = new java.io.File(p);
                        if (f.exists() && f.length() > 2000) imagePaths.add(p);
                    }
                }

                final String srcInfo = (result.metrics != null ? result.metrics.toUiSummary() : "")
                        + "Category: " + (result.mcmodCategory != null ? result.mcmodCategory.getName() : result.questionType)
                        + " | Entities: " + result.resolvedEntities.size()
                        + " | Recipes: " + result.recipeResults.size()
                        + " | Vectors: " + result.vectorResults.size()
                        + (result.incrementalInfo != null ? "\n" + result.incrementalInfo : "");
                final List<String> finalImagePaths = imagePaths;

                Platform.runLater(() -> {
                    String display = result.answer + "\n\n---\n" + srcInfo;
                    addChatBubble("MAA", display, "#f0fdf4", finalImagePaths);
                    qaLog.setText(srcInfo);
                    if (result.metrics != null) {
                        log.info("QA Metrics: total={}ms classify={}ms entity={}ms vec={}ms recipe={}ms answer={}ms entities={} recipes={}",
                                result.metrics.totalTimeMs, result.metrics.classifyTimeMs,
                                result.metrics.entityResolveTimeMs, result.metrics.vectorSearchTimeMs,
                                result.metrics.recipeSearchTimeMs, result.metrics.answerGenTimeMs,
                                result.metrics.entityCount, result.metrics.recipeResultCount);
                    }
                    chatHistoryService.addMessage(currentSession, "maa", result.answer);
                    try {
                        chatHistoryService.save(currentSession);
                    } catch (Exception ignored) {
                    }
                    setStatus("Ready", false);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    addChatBubble("Error", ex.getMessage(), "#fee2e2", null);
                    setStatus("Error", false);
                });
            }
        }, "qa").start();
    }

    private void addChatBubble(String sender, String msg, String bg, List<String> imagePaths) {
        VBox b = new VBox(4);
        b.setStyle("-fx-background-color: " + bg + "; -fx-padding: 10; -fx-background-radius: 8; -fx-max-width: 850;");

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
            Button copyBtn = new Button("Copy");
            copyBtn.setFont(Font.font(10));
            copyBtn.setStyle("-fx-text-fill: #6b7280; -fx-background-color: #e5e7eb; -fx-padding: 2 6; -fx-background-radius: 3;");
            String raw = msg;
            copyBtn.setOnAction(ev -> {
                javafx.scene.input.Clipboard.getSystemClipboard()
                        .setContent(Map.of(javafx.scene.input.DataFormat.PLAIN_TEXT, raw));
                copyBtn.setText("Copied!");
                new Thread(() -> {
                    try {
                        Thread.sleep(1500);
                    } catch (Exception ignored) {
                    }
                    Platform.runLater(() -> copyBtn.setText("Copy"));
                }).start();
            });
            header.getChildren().add(copyBtn);

            b.getChildren().add(header);
            b.getChildren().add(yagen.waitmydawn.kb.ui.MarkdownRenderer.render(msg));
        }

        // Image thumbnails
        if (imagePaths != null && !imagePaths.isEmpty()) {
            HBox imgRow = new HBox(6);
            imgRow.setPadding(new Insets(6, 0, 0, 0));
            for (int i = 0; i < Math.min(6, imagePaths.size()); i++) {
                try {
                    File f = new File(imagePaths.get(i));
                    if (f.exists() && f.length() > 0) {
                        Image img = new Image(f.toURI().toString(), 120, 120, true, true);
                        ImageView iv = new ImageView(img);
                        iv.setFitWidth(120);
                        iv.setFitHeight(120);
                        iv.setStyle("-fx-border-color: #d1d5db; -fx-border-width: 1; -fx-background-radius: 4;");
                        imgRow.getChildren().add(iv);
                    }
                } catch (Exception ignored) {
                }
            }
            if (!imgRow.getChildren().isEmpty()) b.getChildren().add(imgRow);
        }
        chatHistory.getChildren().add(b);
    }

    // =================== Seed Page ===================

    private VBox buildSeedPage(Stage stage) {
        VBox page = new VBox(12);
        page.setPadding(new Insets(20));
        page.setStyle("-fx-background-color: #f8f9fa;");

        // Export
        Label exportTitle = pageTitle("Export Training Seed");
        TextField nameF = new TextField();
        nameF.setPromptText("Knowledge base name");
        nameF.setPrefWidth(350);
        TextField descF = new TextField();
        descF.setPromptText("Description");
        descF.setPrefWidth(350);
        Button exportBtn = styledButton("Export .maa-seed.json", "#10b981");
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
        VBox exportCard = card("Export", new VBox(8,
                new HBox(8, new Label("Name:"), nameF),
                new HBox(8, new Label("Desc:"), descF),
                exportBtn));

        // Import
        Label importTitle = pageTitle("Import Training Seed");
        HBox importRow = new HBox(8);
        TextField seedFile = new TextField();
        seedFile.setPromptText("Select .maa-seed.json...");
        seedFile.setPrefWidth(350);
        Button browseSeed = new Button("Browse...");
        browseSeed.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("MAA Seed", "*.maa-seed.json"));
            File f = fc.showOpenDialog(stage);
            if (f != null) seedFile.setText(f.getAbsolutePath());
        });
        importRow.getChildren().addAll(seedFile, browseSeed);

        Button importBtn = styledButton("Import & Match", "#6C63FF");
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
        VBox importCard = card("Import", new VBox(8, importRow, importBtn, importResult));

        page.getChildren().addAll(exportTitle, exportCard, importTitle, importCard);
        return page;
    }

    // =================== Settings Page ===================

    private VBox buildSettingsPage(Stage stage) {
        VBox page = new VBox(12);
        page.setPadding(new Insets(20));
        page.setStyle("-fx-background-color: #f8f9fa;");

        // Data Directory
        Label dirTitle = pageTitle("Data Directory");
        dataDirField = new TextField(config.getDataDir().toString());
        dataDirField.setPrefWidth(400);
        Button changeDir = new Button("Change...");
        changeDir.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Select New Data Directory");
            File f = dc.showDialog(stage);
            if (f != null) {
                Path newDir = Path.of(f.getAbsolutePath());
                // Move or just switch? For simplicity, switch and warn
                config.setDataDir(newDir);
                dataDirField.setText(newDir.toString());
                // Re-init DB
                db = new DatabaseBuilder(config);
                db.initDatabase();
                knowledgeBuilder = new KnowledgeBuilder(config, db);
                seedExport = new SeedExportService(db);
                seedImport = new SeedImportService(db);
                logText("Data directory changed to: " + newDir);
            }
        });
        HBox dirRow = new HBox(8, dataDirField, changeDir);
        VBox dirCard = card("Storage Location", dirRow);
        Label dirNote = new Label("All data (database, textures, generated images) is stored in this folder. "
                + "You can move this folder to another computer.");
        dirNote.setWrapText(true);
        dirNote.setFont(Font.font(12));
        dirNote.setTextFill(Color.web("#6b7280"));

        // Author
        Label authorTitle = pageTitle("Profile");
        TextField authorField = new TextField(config.getUsername());
        authorField.setPrefWidth(200);
        authorField.textProperty().addListener((o, old, val) -> config.setUsername(val));
        HBox authorRow = new HBox(8, new Label("Username:"), authorField);
        VBox authorCard = card("Author Name", authorRow);

        // API Key
        Label apiTitle = pageTitle("API Key");
        apiKeyField = new PasswordField();
        apiKeyField.setPromptText("sk-...");
        apiKeyField.setPrefWidth(400);
        if (config.getApiKey() != null) apiKeyField.setText(config.getApiKey());
        apiKeyField.textProperty().addListener((o, old, val) -> config.setApiKey(val));
        HBox apiRow = new HBox(8, new Label("DeepSeek Key:"), apiKeyField);
        VBox apiCard = card("LLM Configuration", apiRow);
        Label apiNote = new Label("API key is stored in %USERPROFILE%/.maa_kb/settings.properties. "
                + "Without a key, the Q&A will use offline mode showing raw retrieval results.");
        apiNote.setWrapText(true);
        apiNote.setFont(Font.font(12));
        apiNote.setTextFill(Color.web("#6b7280"));

        // Embedding model selector
        Label embedLabel = pageTitle("Embedding Model");
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
            String selected = modelCombo.getValue();
            if (selected == null) return;
            config.setEmbeddingModelName(selected);
            String loaded = embedder.initModel(selected);
            String status;
            if (selected.equals(loaded)) {
                status = "Applied: " + loaded + " (will take effect on next Build)";
                modelStatus.setTextFill(Color.web("#10b981"));
            } else if ("none".equals(loaded)) {
                status = "Using: n-gram TF-IDF (place model files in models/ folder first)";
                modelStatus.setTextFill(Color.web("#f59e0b"));
            } else {
                status = "Active: " + loaded;
                modelStatus.setTextFill(Color.web("#10b981"));
            }
            modelStatus.setText(status);
        });
        // Show current status on page load
        updateModelStatus(modelStatus, modelCombo);

        VBox embedCard = card("Vectorization",
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
        l.setFont(Font.font("Segoe UI", FontWeight.BOLD, 18));
        l.setTextFill(Color.web("#1f2937"));
        l.setPadding(new Insets(0, 0, 4, 0));
        return l;
    }

    private VBox card(String title, javafx.scene.Node content) {
        VBox card = new VBox(8);
        card.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e5e7eb; -fx-border-radius: 8; "
                + "-fx-background-radius: 8; -fx-padding: 14;");
        card.setEffect(new javafx.scene.effect.DropShadow(2, 2, 2, Color.web("#00000018")));
        Label t = new Label(title);
        t.setFont(Font.font("Segoe UI", FontWeight.BOLD, 13));
        t.setTextFill(Color.web("#374151"));
        card.getChildren().addAll(t, content);
        return card;
    }

    private Button styledButton(String text, String color) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color: " + color + "; -fx-text-fill: white; -fx-padding: 8 16; "
                + "-fx-background-radius: 6; -fx-cursor: hand; -fx-font-size: 13px;");
        return b;
    }

    private void logText(String msg) {
        builderLog.appendText(msg + "\n");
    }

    private void setStatus(String msg, boolean showProgress) {
        statusText.setText(msg);
        progressBar.setVisible(showProgress);
        if (!showProgress) progressBar.setProgress(0);
    }

    private void showError(String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR);
        a.setTitle("Error");
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

        Button openModrinth = new Button("Open Modrinth Page");
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
        Button save = styledButton("Save", "#10b981");
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
        Button cancel = new Button("Cancel");
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
            cb.setFont(Font.font(10));
            cb.setOnAction(ev -> {
                dbManager.setEnabled(info.filename(), cb.isSelected());
            });
            // Add delete button for non-base DBs
            if (!info.isBase()) {
                Button delBtn = new Button("Del");
                delBtn.setFont(Font.font(9));
                delBtn.setStyle("-fx-text-fill: #ef4444; -fx-background-color: transparent; -fx-padding: 0 4;");
                delBtn.setOnAction(ev -> {
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
        launch(args);
    }
}
