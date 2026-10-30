package yagen.waitmydawn.kb;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
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
import yagen.waitmydawn.kb.service.*;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class MaaKnowledgeBuilderApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(MaaKnowledgeBuilderApp.class);

    private AppConfig config;
    private yagen.waitmydawn.kb.ui.ThemeService themes;
    private final yagen.waitmydawn.kb.ui.WorkspaceLayout workspaceLayout=new yagen.waitmydawn.kb.ui.WorkspaceLayout();
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
    private TextArea buildUrlsField, buildDocumentsField;
    private TextField buildTargetField;
    private ComboBox<BuildRequest.Mode> buildModeChoice;
    private ComboBox<KnowledgeRetrievalService.Scope> knowledgeScopeChoice;
    private CheckBox fetchKnowledgeImages;
    private TextArea builderLog, qaLog;
    private ListView<ModEntry> modListView;
    private Label scanCount,bindCount,buildCount;
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
    private JarScanCache.Snapshot scannedPreparation;
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
        themes = new yagen.waitmydawn.kb.ui.ThemeService(config);
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
        if (themes != null) themes.close();
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
        root.getStyleClass().add("workspace-page");

        Label title = new Label("欢迎使用 MAA 知识库");
        title.setFont(Font.font("Segoe UI", FontWeight.BOLD, 20));
        title.getStyleClass().add("page-title");

        Label desc = new Label("请选择知识库数据目录，用于保存数据库、纹理及生成的图片。");
        desc.setWrapText(true);
        desc.setFont(Font.font(14));

        TextField dirField = new TextField(Path.of(System.getProperty("user.home"), "MAA-Knowledge-Builder").toString());
        dirField.setPrefWidth(400);

        Button browse = new Button("浏览…");
        browse.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("选择数据目录");
            File f = dc.showDialog(dialog);
            if (f != null) dirField.setText(f.getAbsolutePath());
        });

        HBox dirRow = new HBox(8, dirField, browse);
        dirRow.setAlignment(Pos.CENTER);

        Button confirm = new Button("开始");
        confirm.getStyleClass().add("primary-button");
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
        themes.prepare(scene);
        dialog.setScene(scene);
        dialog.showAndWait();
    }

    // =================== Main UI ===================

    private void buildMainUI(Stage stage) {
        // Sidebar
        VBox sidebar = buildSidebar(stage);

        // Content pages
        builderPage = buildBuilderPage(stage);
        if(dataDirLock!=null&&!dataDirLock.recoverySummary().isBlank())logText(dataDirLock.recoverySummary());
        qaPage = buildQAPage();
        seedPage = buildSeedPage(stage);
        metricsPage = buildMetricsPage();
        settingsPage = buildSettingsPage(stage);
        var catalogTab=new Tab("分类目录",new yagen.waitmydawn.kb.ui.KnowledgeBrowser(db,worker));catalogTab.setClosable(false);
        var sourcesTab=new Tab("来源管理",new yagen.waitmydawn.kb.ui.SourceManagerPane(config,db,embedder,this::runSourceMaintenance,this::reloadAfterSourceMaintenance));sourcesTab.setClosable(false);
        var knowledgeTabs=new TabPane(catalogTab,sourcesTab);VBox.setVgrow(knowledgeTabs,Priority.ALWAYS);knowledgePage=new VBox(knowledgeTabs);
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
        cancelButton.setOnAction(e->{ var running=activeWorker; if(running!=null) { cancelButton.setDisable(true);boolean requested=BuildTaskJournal.requestCancellation(running);setStatus(requested?"正在取消，等待当前请求退出…":"正在发布构建结果，请等待完成。",true); } });
        var statusSpacer=new Region(); HBox.setHgrow(statusSpacer,Priority.ALWAYS);
        HBox statusBar = new HBox(12, statusText, progressBar,statusSpacer,cancelButton);
        statusBar.setPadding(new Insets(4, 12, 4, 12));
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.getStyleClass().add("status-bar");

        BorderPane root = new BorderPane();
        root.setCenter(workspaceLayout.horizontal("navigation",sidebar,contentArea,.17,160,640));
        root.setBottom(statusBar);

        Scene scene = new Scene(root, 1280, 800);
        themes.prepare(scene);
        stage.setMinWidth(1060); stage.setMinHeight(680);
        stage.setTitle("MAA Knowledge Builder");
        yagen.waitmydawn.kb.ui.AppIcons.apply(stage);
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
        if (page == settingsPage) {
            var scroll = new ScrollPane(page); scroll.setFitToWidth(true);
            contentArea.getChildren().setAll(scroll);
        } else contentArea.getChildren().setAll(page);
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

        buildTargetField=new TextField(config.getDataDir().toString());
        buildTargetField.setEditable(false);
        buildModeChoice=new ComboBox<>();
        buildModeChoice.getItems().setAll(BuildRequest.Mode.values());
        buildModeChoice.setValue(vectorStore.count()>0?BuildRequest.Mode.APPEND:BuildRequest.Mode.REBUILD);
        Button chooseTarget=new Button("选择已有知识库");
        chooseTarget.setOnAction(event -> {
            if(busy()) return;
            var chooser=new DirectoryChooser();chooser.setTitle("选择包含 rag_data.mv.db 的知识库目录");
            var selected=chooser.showDialog(stage);
            if(selected==null) return;
            if(!Files.isRegularFile(selected.toPath().resolve("rag_data.mv.db"))) {showError("该目录没有基础数据库 rag_data.mv.db");return;}
            buildTargetField.setText(selected.getAbsolutePath());buildModeChoice.setValue(BuildRequest.Mode.APPEND);
            scannedMods=null;scannedPreparation=null;modListView.getItems().clear();
        });
        Button currentTarget=new Button("使用当前知识库");
        currentTarget.setOnAction(event -> {
            if(busy()) return;
            buildTargetField.setText(config.getDataDir().toString());scannedMods=null;scannedPreparation=null;modListView.getItems().clear();
        });
        var targetRow=new HBox(8,buildTargetField,chooseTarget,currentTarget,buildModeChoice);
        HBox.setHgrow(buildTargetField,Priority.ALWAYS);

        // Step 1: Folder
        folderField = new TextField();
        folderField.setPromptText("选择包含模组 JAR 的目录…");
        folderField.setPrefWidth(400);
        Button browseMods = new Button("浏览");
        Button scanBtn = styledButton("扫描 JAR", "#6C63FF");
        Button bindUrlBtn=styledButton("Bind Url", "#2563eb");
        bindUrlBtn.setOnAction(e->onBindUrls());
        scanBtn.setOnAction(e -> onScan());
        browseMods.setOnAction(e -> {
            if(busy())return;
            DirectoryChooser dc = new DirectoryChooser();
            File f = dc.showDialog(stage);
            if (f != null) {folderField.setText(f.getAbsolutePath());chooseSiblingExport(f.toPath(),stage);}
        });
        var restoreScanBtn=new Button("读取预处理记录");restoreScanBtn.setOnAction(e->onRestoreScan());
        var findExportBtn=new Button("查找导出目录");findExportBtn.setOnAction(e->{if(!busy())try{chooseSiblingExport(Path.of(folderField.getText()),stage);}catch(java.nio.file.InvalidPathException invalid){showError("请先选择有效的模组文件夹。");}});
        HBox folderRow = new HBox(8, folderField, browseMods, scanBtn,bindUrlBtn,restoreScanBtn,findExportBtn);
        HBox.setHgrow(folderField,Priority.ALWAYS);
        folderRow.setAlignment(Pos.CENTER_LEFT);

        buildUrlsField = new TextArea();
        buildUrlsField.setPromptText("每行一个网址；支持百科模组、整合包和普通网页");
        buildUrlsField.setPrefRowCount(2);
        buildDocumentsField = new TextArea();
        buildDocumentsField.setPromptText("必须包含一个完整 MKB-Export；其余每行可添加 TXT、DOC、DOCX、PDF、图片资料目录");
        buildDocumentsField.setPrefRowCount(2);
        Button browseDocuments = new Button("添加资料目录");
        browseDocuments.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            File selected = chooser.showDialog(stage);
            if (selected != null) buildDocumentsField.appendText((buildDocumentsField.getText().isBlank() ? "" : "\n") + selected.getAbsolutePath());
        });
        var snapshotHint=new Label(Objects.toString(RuntimeBuildContract.migrationWarning(db),"必须选择单人世界 /mkb_export all 的完整导出目录。JAR 只用于 Scan、Bind Url 与环境核对。"));snapshotHint.setWrapText(true);snapshotHint.setId("snapshot-build-status");
        var chooseSnapshot=new Button("选择游戏快照…");chooseSnapshot.setOnAction(event->{
            if(busy())return;var chooser=new DirectoryChooser();chooser.setTitle("选择完整 MKB-Export 导出目录");var selected=chooser.showDialog(stage);
            if(selected!=null)buildDocumentsField.setText(ExportDirectoryDiscovery.selectSnapshot(buildDocumentsField.getText(),selected.toPath()));
        });
        var reuseSnapshot=new Button("使用已关联快照");reuseSnapshot.setOnAction(event->{
            if(busy())return;
            try(var target=BuildTarget.open(Path.of(buildTargetField.getText()),config,db)){
                var source=RuntimeBuildContract.associated(target.database());buildDocumentsField.setText(ExportDirectoryDiscovery.selectSnapshot(buildDocumentsField.getText(),Path.of(source.location())));
            }catch(Exception failure){showError(failure.getMessage());}
        });
        var inspectSnapshot=new Button("校验快照");inspectSnapshot.setOnAction(event->{
            if(busy())return;final BuildRequest request;try{request=readBuildRequest();}catch(Exception invalid){showError(invalid.getMessage());return;}
            Path targetPath=Path.of(buildTargetField.getText());var prep=scannedPreparation;String folder=folderField.getText();operationInFlight=true;setStatus("正在校验游戏快照…",true);
            submitOperation(()->{try(var target=BuildTarget.open(targetPath,config,db)){
                var memory=prep==null?Map.<Path,JarScanCache.Snapshot>of():Map.of(Path.of(folder).toAbsolutePath().normalize(),prep);
                var prepared=RuntimeBuildContract.prepare(request,target.database(),memory);
                Platform.runLater(()->snapshotHint.setText("校验通过："+prepared.pack().loader()+" "+prepared.pack().minecraftVersion()+"；"+prepared.pack().icons().size()+" 个物品图标记录。"));
            }catch(Exception failure){Platform.runLater(()->{snapshotHint.setText("校验失败："+failure.getMessage());showError(failure.getMessage());});}
            finally{Platform.runLater(()->{operationInFlight=false;setStatus("就绪",false);});}});
        });
        var sourceStore = new KnowledgeContentStore(db);
        var savedSources = sourceStore.draft().or(sourceStore::publishedRequest);
        savedSources.ifPresent(request -> {
            for (var source : request.sources()) {
                switch (source.kind()) {
                    case JAR_DIRECTORY -> folderField.setText(source.location());
                    case URL -> buildUrlsField.appendText(source.location() + "\n");
                    case DOCUMENT_DIRECTORY -> buildDocumentsField.appendText(source.location() + "\n");
                }
            }
        });

        if(folderField.getText().isBlank()){var last=new ScanEnvironmentStore(db).lastFolder();if(last!=null&&Files.isDirectory(last))folderField.setText(last.toString());}
        // Step 2: Mod list
        modListView = new ListView<>();
        modListView.setPrefHeight(180);
        modListView.setCellFactory(view->new yagen.waitmydawn.kb.ui.ModListCell(entry->{if(!busy())showSlugEditor(entry,stage);}));
        folderField.textProperty().addListener((o,a,b)->{scannedMods=null;scannedPreparation=null;modListView.getItems().clear();resetCounts();try{if(!busy()&&JarScanCache.exists(Path.of(b)))Platform.runLater(this::onRestoreScan);}catch(Exception ignored){}});
        buildTargetField.textProperty().addListener((o,a,b)->{scannedMods=null;scannedPreparation=null;modListView.getItems().clear();resetCounts();});
        modListView.setOnMouseClicked(ev -> {
            if (ev.getClickCount() == 2 && !busy()) {
                ModEntry selected=modListView.getSelectionModel().getSelectedItem();
                if(selected!=null)showSlugEditor(selected,stage);
            }
        });

        // Step 3: Build
        Button buildBtn = styledButton("构建知识库", "#10b981");
        buildBtn.setFont(Font.font(14));
        buildBtn.setOnAction(e -> onBuild());
        Button saveSourcesBtn = new Button("保存来源配置");
        saveSourcesBtn.setOnAction(e -> {
            if (busy()) return;
            try(var target=BuildTarget.open(Path.of(buildTargetField.getText()),config,db)) {
                new KnowledgeContentStore(target.database()).saveDraft(readBuildRequest());
                logText("来源配置已保存；已发布知识库未改变。");
            } catch (Exception ex) { showError(ex.getMessage()); }
        });
        HBox buildRow = new HBox(12, buildBtn, saveSourcesBtn);
        buildRow.setAlignment(Pos.CENTER_LEFT);

        // Log
        builderLog = new TextArea();
        builderLog.setEditable(false);
        builderLog.setPrefRowCount(6);
        builderLog.setStyle("-fx-font-family: 'Consolas'; -fx-font-size: 11px;");

        scanCount=new Label("Scan 0/0");scanCount.setId("scan-count");scanCount.getStyleClass().add("scan-progress-label");
        bindCount=new Label("Bind Url 0/0");bindCount.setId("bind-count");bindCount.getStyleClass().add("bind-progress-label");
        buildCount=new Label("Build 0/0");buildCount.setId("build-count");buildCount.getStyleClass().add("build-progress-label");
        var modsCard=card("已识别模组",modListView);var modsTitle=modsCard.getChildren().getFirst();modsCard.getChildren().removeFirst();modsCard.getChildren().addFirst(new HBox(18,modsTitle,scanCount,bindCount,buildCount));
        VBox topSection = new VBox(8,
                card("目标知识库与构建方式", targetRow),
                card("模组目录", folderRow),
                modsCard,
                card("网址来源", buildUrlsField),
                card("资料目录与必选游戏快照", new VBox(6, snapshotHint,buildDocumentsField,new HBox(8,browseDocuments,chooseSnapshot,reuseSnapshot,inspectSnapshot))));
        VBox.setVgrow(modListView, Priority.ALWAYS);
        VBox bottomSection = new VBox(8,
                card("开始构建", new VBox(6, new Label("补充保留网址与资料；游戏快照整体更新，已有模组移除或内容变化须重建。旧库迁移须完整重建，历史会话和图片保留。"), buildRow)),
                card("构建日志", builderLog));
        VBox.setVgrow(builderLog, Priority.ALWAYS);
        ScrollPane sourcesScroll = new ScrollPane(topSection);
        sourcesScroll.setFitToWidth(true);
        var splitPane=workspaceLayout.vertical("builder",sourcesScroll,bottomSection,.6,160,145);
        try{if(!folderField.getText().isBlank()&&JarScanCache.exists(Path.of(folderField.getText())))Platform.runLater(this::onRestoreScan);}catch(Exception ignored){}
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
        Path targetDirectory=Path.of(buildTargetField.getText());
        boolean append=buildModeChoice.getValue()==BuildRequest.Mode.APPEND;
        if (!Files.isDirectory(dir)) {
            logText("Not a directory: " + folderPath);
            return;
        }

        scannedMods=null;scannedPreparation=null;modListView.getItems().clear();
        operationInFlight=true;setStatus("正在定位 Minecraft 本体 JAR…",true);
        submitOperation(()->{
            try{
                var candidates=new GameEnvironmentDetector().discover(dir);
                Platform.runLater(()->{
                    operationInFlight=false;setStatus("就绪",false);
                    if(!folderField.getText().trim().equals(folderPath)||!Path.of(buildTargetField.getText()).equals(targetDirectory))return;
                    if(candidates.isEmpty()){showError("未找到 Minecraft 客户端本体 JAR。请将本体 JAR 放在所选文件夹，或选择准确的游戏实例 mods 文件夹（程序也会检查其直属上级目录）。");return;}
                    var dialog=new yagen.waitmydawn.kb.ui.ScanEnvironmentDialog(folderField.getScene().getWindow(),candidates);themes.prepare(dialog.getDialogPane().getScene());
                    dialog.showAndWait().ifPresent(environment->scanConfirmed(dir,targetDirectory,append,environment));
                });
            }catch(Exception failure){Platform.runLater(()->{operationInFlight=false;setStatus("就绪",false);showError("游戏环境识别失败："+failure.getMessage());});}
        });
    }

    private void resetCounts(){if(scanCount!=null)scanCount.setText("Scan 0/0");if(bindCount!=null)bindCount.setText("Bind Url 0/0");if(buildCount!=null)buildCount.setText("Build 0/0");}
    private void scanConfirmed(Path dir,Path targetDirectory,boolean append,GameEnvironment environment){
        runScan(dir,targetDirectory,append,environment,false);
    }
    private void onRestoreScan(){
        if(busy())return;
        try{
            Path dir=Path.of(folderField.getText().strip());var cache=new JarScanCache(dir);var environment=cache.environment();
            if(environment==null)throw new IllegalStateException("没有可用的 Scan 记录，请先 Scan。");
            if(!Files.isRegularFile(Path.of(environment.jarPath())))throw new IllegalStateException("记录中的本体 JAR 已不存在，请重新 Scan 确认环境。");
            runScan(dir,Path.of(buildTargetField.getText()),buildModeChoice.getValue()==BuildRequest.Mode.APPEND,environment,true);
        }catch(Exception failure){showError(failure.getMessage());}
    }
    private void runScan(Path dir,Path targetDirectory,boolean append,GameEnvironment environment,boolean restoreOnly){
        folderField.setDisable(true);logText((restoreOnly?"恢复 Scan 记录：":"确认游戏环境：")+environment.summary());
        scanCount.setText("Scan 0/0");bindCount.setText("Bind Url 0/0");buildCount.setText("Build 0/0");
        setStatus(restoreOnly?"正在校验 Scan 记录…":"正在扫描 JAR…",true);modListView.getItems().clear();scannedMods=null;scannedPreparation=null;operationInFlight=true;
        submitOperation(()->{
            try(var target=BuildTarget.open(targetDirectory,config,db)){
                var environments=new ScanEnvironmentStore(target.database());var existing=environments.forFolder(dir);
                if(restoreOnly&&existing!=null&&!existing.equals(environment))throw new IllegalStateException("记录与当前来源的确认环境不同，请重新 Scan。");
                environments.save(dir,environment);
                var retryScan=new java.util.concurrent.atomic.AtomicInteger();var scanCache=new JarScanCache(dir);
                var result=new ModScanService().scan(dir,environment,target.database(),append,new ModrinthBinder(),restoreOnly,(done,total,entry,message)->Platform.runLater(()->{
                    if(entry!=null&&!JarScanCache.scanReady(entry))retryScan.incrementAndGet();
                    scanCount.setText("Scan "+(done-retryScan.get())+"/"+total);scanCount.setTooltip(new Tooltip(message));progressBar.setProgress(total==0?0:(double)done/total);
                    if(entry!=null){modListView.getItems().add(entry);modListView.getItems().sort(ModUrlBinding.reviewOrder());}
                    if(entry==null&&done>0)logText(message);
                }),scanCache);
                Platform.runLater(()->{
                    if(result.completed()==result.total()){scannedMods=result.mods();scannedPreparation=scanCache.snapshot();}
                    else logText("记录校验有效 "+result.completed()+"/"+result.total()+"；存在新增或变化文件，请重新 Scan 补齐后再 Bind Url。");
                    scanCount.setText("Scan "+result.scanPrepared()+"/"+result.total());
                    long bound=JarScanCache.bindingCompleted(result.mods());bindCount.setText("Bind Url "+bound+"/"+result.mods().size());
                    if(result.scanPrepared()<result.completed())logText("Scan 有 "+(result.completed()-result.scanPrepared())+" 项网络查询失败，可再次 Scan 重试；已识别模组仍可用于 Bind Url。");
                    if(result.scanPrepared()==result.total())logText(bound==result.mods().size()?"Scan 与 Bind Url 均已完成，可继续 Build。":"预处理进度：Bind Url 尚有 "+(result.mods().size()-bound)+" 项待处理或重试。");
                    logText(result.metrics().summary());if(result.warning()!=null)logText(result.warning());
                    logText(restoreOnly?"已恢复 "+result.mods().size()+" 个模组；未发起网络查询。":"Scan 完成，已识别 "+result.mods().size()+" 个模组。记录位置："+dir.resolve(JarScanCache.FILE_NAME));
                });
            }catch(Exception failure){Platform.runLater(()->showError("Scan 失败："+failure.getMessage()));}
            finally{Platform.runLater(()->{operationInFlight=false;folderField.setDisable(false);setStatus("就绪",false);});}
        });
    }

    private void onBindUrls(){
        if(busy())return;
        if(scannedMods==null||scannedMods.isEmpty()){showError("请先 Scan 扫描 JAR。");return;}
        var entries=List.copyOf(scannedMods);Path targetDirectory=Path.of(buildTargetField.getText());
        operationInFlight=true;folderField.setDisable(true);bindCount.setText("Bind Url "+JarScanCache.bindingCompleted(entries)+"/"+entries.size());setStatus("正在绑定百科候选…",true);
        submitOperation(()->{
            try(var target=BuildTarget.open(targetDirectory,config,db)) {
                var scraper=new WikiScraperService();scraper.setResourceDatabase(target.database());var binder=new ModUrlBinder(scraper);var store=new ModBindingStore(target.database());int done=0;var cache=new JarScanCache(Path.of(folderField.getText()));
                try{
                for(var entry:entries){
                    if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("绑定已取消，已完成的草稿已保留");
                    var previous=entry.getMcmodBinding();
                    if(previous==null||!java.util.Set.of("FOUND","NOT_FOUND","DISABLED","BUILTIN").contains(previous.status()))entry.setMcmodBinding(binder.bind(entry));
                    store.save(entry);cache.updateBinding(entry);int current=++done;
                    if(done%10==0&&cache.environment()!=null)cache.save(cache.environment());
                    Platform.runLater(()->{bindCount.setText("Bind Url "+JarScanCache.bindingCompleted(entries)+"/"+entries.size());bindCount.setTooltip(new Tooltip("已处理 "+current+"/"+entries.size()+"；未找到计为完成，网络失败保留为待重试"));modListView.getItems().sort(ModUrlBinding.reviewOrder());modListView.refresh();progressBar.setProgress((double)current/entries.size());logText("[Bind Url "+current+"/"+entries.size()+"] "+entry.getModId()+"："+(entry.getMcmodBinding()==null?"未绑定":entry.getMcmodBinding().statusLabel()));});
                }
                }finally{if(cache.environment()!=null&&!cache.save(cache.environment()))Platform.runLater(()->logText(cache.warning()));}
                Platform.runLater(()->{long remaining=entries.size()-JarScanCache.bindingCompleted(entries);if(remaining>0)logText("Bind Url 尚有 "+remaining+" 项失败或未完成，可再次点击重试。");});
                Platform.runLater(()->logText("候选绑定处理结束。可点击字段或双击条目校对；浏览量不表示身份已验证。已建库模组换绑后请通过来源管理刷新。"));
            }catch(Exception failure){Platform.runLater(()->showError(failure.getMessage()));}
            finally{Platform.runLater(()->{operationInFlight=false;folderField.setDisable(false);setStatus("就绪",false);});}
        });
    }

    private BuildRequest readBuildRequest() {
        var sources = new java.util.ArrayList<BuildRequest.Source>();
        if (!folderField.getText().isBlank()) sources.add(BuildRequest.Source.of(BuildRequest.Kind.JAR_DIRECTORY, folderField.getText()));
        buildUrlsField.getText().lines().filter(line -> !line.isBlank())
                .forEach(line -> sources.add(BuildRequest.Source.of(BuildRequest.Kind.URL, line)));
        buildDocumentsField.getText().lines().filter(line -> !line.isBlank())
                .forEach(line -> sources.add(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY, line)));
        return new BuildRequest(sources,buildModeChoice.getValue());
    }

    private void chooseSiblingExport(Path mods, javafx.stage.Stage owner) {
        try {
            var candidates=ExportDirectoryDiscovery.siblings(mods);
            if(candidates.isEmpty())return;
            var choice=new ChoiceDialog<Path>(candidates.getFirst(),candidates);
            if(owner!=null)choice.initOwner(owner);
            choice.setTitle("选择 MKB-Export 导出目录");
            choice.setHeaderText("发现同级导出目录；选择后替换旧导出目录，保留普通资料");
            choice.setContentText("导出目录：");
            choice.getDialogPane().setPrefWidth(900);
            choice.showAndWait().ifPresent(selected->{
                buildDocumentsField.setText(ExportDirectoryDiscovery.selectSnapshot(buildDocumentsField.getText(),selected));
                logText("已添加导出目录："+selected+"；Build 时校验内容。");
            });
        } catch(java.io.IOException failure) {logText("无法查找同级导出目录，可手动添加："+failure.getMessage());}
    }

    private void onBuild() {
        if (busy()) return;
        final BuildRequest request;
        final Path targetDirectory=Path.of(buildTargetField.getText());
        final List<ModEntry> scanned=scannedMods==null?List.of():List.copyOf(scannedMods);
        final var preparedScan=scannedPreparation;final String preparedFolder=folderField.getText();
        try {
            request = readBuildRequest();
        } catch (Exception ex) { showError(ex.getMessage()); return; }
        buildCount.setText("Build 0/"+request.sources().size());
        logText(request.mode()+"："+targetDirectory+"；来源数量：" + request.sources().size());
        setStatus("正在构建知识库…", true);
        operationInFlight = true;
        submitOperation(() -> {
            try(var target=BuildTarget.open(targetDirectory,config,db)) {
            new KnowledgeContentStore(target.database()).saveDraft(request);
            var builder=target.external()?new KnowledgeBuilder(target.config(),target.database(),embedder):knowledgeBuilder;
            builder.setScannedMods(scanned);builder.clearScanPreparations();
            if(preparedScan!=null)builder.setScanPreparation(Path.of(preparedFolder),preparedScan);
            KnowledgeBuilder.BuildResult r = builder.build(request,
                    (phase, cur, total, msg) -> Platform.runLater(() -> {
                        if(phase.equals("publish"))cancelButton.setDisable(true);
                        if (total > 0) progressBar.setProgress((double) cur / total);
                        if(phase.startsWith("progress:")){String step=phase.substring(9);buildCount.setText("Build "+cur+"/"+total+" · "+step);buildCount.setTooltip(new Tooltip("Build 按当前阶段统计已完成/总计："+step));return;}
                        if(phase.equals("done"))buildCount.setText("Build "+cur+"/"+total+(msg.contains("没有新增内容")?" · 无变化":" · 已发布"));
                        logText("[" + phase + "] " + msg);
                    }));
            if(target.external()) {
                Platform.runLater(() -> {
                    logBuildResult(r);
                    logText("已发布到："+targetDirectory+"；在设置中选择该数据目录并重启后可用于问答。");
                });
                return;
            }
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
                refreshKnowledgeScopes();
                refreshIconProfiles();
                logBuildResult(r);
                setStatus("就绪", false);
            });
            } catch (Exception ex) {
                Platform.runLater(() -> { buildCount.setText(buildCount.getText()+(ex instanceof java.util.concurrent.CancellationException?" · 已取消":" · 失败"));if(ex instanceof java.util.concurrent.CancellationException) logText("构建已取消，保留原基础知识库。"); else showError("构建失败，保留原基础知识库："+ex.getMessage()); });
            } finally { Platform.runLater(() -> { operationInFlight = false; setStatus("就绪", false); }); }
        });
    }

    private void runSourceMaintenance(Runnable task){
        if(operationInFlight){showError("已有任务执行中，请等待完成或取消后再操作来源。");return;}
        operationInFlight=true;setStatus("正在管理来源…",true);
        submitOperation(()->{try{task.run();}finally{Platform.runLater(()->{operationInFlight=false;setStatus("就绪",false);});}});
    }
    private void reloadAfterSourceMaintenance(){
        rendererService.clearCache();
        entityAgent=new EntityAgent(ragAgent,db,embedder,vectorStore);
        qaPipeline=new QaPipeline(classifyAgent,entityAgent,new UrlAgent(ragAgent,db,embedder),answerAgent,ragAgent,vectorStore,embedder,db,dbManager,config.getDataDir());
        metricsHistoryService=qaPipeline.getMetricsHistory();if(currentSession!=null)qaPipeline.restoreHistory(currentSession.messages);
        Platform.runLater(()->{refreshKnowledgeScopes();refreshIconProfiles();});
    }
    private void logBuildResult(KnowledgeBuilder.BuildResult result) {
        logText("构建完成；新增模组："+result.parsed+"，跳过JAR："+result.skipped+"，冲突："+result.conflicts);
        logText("新增文档："+result.documents+"，复用文档："+result.reusedDocuments+"，跳过网址："+result.reusedSources);
        logText("图标包："+result.nativeIconPacks+"，新增图标："+result.nativeIcons+"，失败记录："+result.failedNativeIcons+"，复用图标包："+result.reusedIconPacks);
        logText("新增向量："+result.addedEmbeddings+"，总向量："+result.embeddings+"，本次导入配方："+result.recipes);
        logText("教程："+result.tutorials+"，网页图片："+result.webImages+"，未缓存图片："+result.deferredWebImages);
        logText("本地图片："+result.localImages+"，OCR："+result.ocrImages+"，视觉描述："+result.visionImages);
        logText("网页图片正文关联："+result.enrichedWebImages+"；网页自动 OCR 已关闭，按标题、图注、相邻段落和步骤检索配图。");
        logText("图片流水线：获取尝试 "+result.webImageAttempts+"，复用 "+result.reusedWebImages+"，批量关联 "+result.webImageBindings+"；永久失败 "+result.permanentImageFailures+"，临时失败 "+result.temporaryImageFailures+"，站点冷却 "+result.cooldownImageFailures);
        logText("图片耗时：获取／限流 "+result.webImageFetchMs+"ms，本地处理 "+result.webImageProcessMs+"ms，关联入库 "+result.webImageStoreMs+"ms（获取与本地处理交错执行，耗时不可相加）；待处理响应队列峰值 "+result.webImageQueuePeak+"。");
        logText("实机图标："+result.nativeIcons+"，完全透明："+result.transparentNativeIcons+"，失败或不支持："+result.failedNativeIcons);
        logText("实体图片：可见 "+result.entityVisible+"，透明 "+result.entityTransparent+"，失败 "+result.entityFailed+"，跳过 "+result.entityExcluded+"，取景待复核 "+result.entityUncertain);
        logText("实体图片资产："+result.entityUniqueImages+" 张；复用状态："+result.entityImageAliases);
        logText("组成诊断：完整 "+result.entityCompositionComplete+"，部分 "+result.entityCompositionPartial+"，未验证 "+result.entityCompositionUnverified+"，异常空图 "+result.entityUnexpectedEmpty);
        result.warnings.forEach(this::logText);
        logText("耗时："+(result.durationMs/1000.0)+"s（含预检查和清理，不含 Scan/Bind Url）");
        if(result.timingReport!=null)logText("阶段计时报告："+result.timingReport);
    }

    // =================== QA Page ===================

    private VBox sessionListView; // for refreshSessionList
    private ComboBox<ItemIconStore.Profile> iconProfileChoice;
    private boolean refreshingIconProfiles;

    private VBox buildQAPage() {

        // --- Left: History sidebar ---
        VBox historySidebar = new VBox(8);
        historySidebar.setPrefWidth(240);
        historySidebar.setPadding(new Insets(12));
        historySidebar.getStyleClass().add("history-sidebar");

        qaDataDirLabel = new Label("DB: " + config.getDataDir().getFileName());
        qaDataDirLabel.setTooltip(new Tooltip(config.getDataDir().toString()));
        qaDataDirLabel.setFont(Font.font(10));
        qaDataDirLabel.getStyleClass().add("muted");

        Button newChatBtn = styledButton("+ 新对话", "#6C63FF");
        newChatBtn.setMaxWidth(Double.MAX_VALUE);
        newChatBtn.setOnAction(e -> {
            if (busy()) return;
            dbManager.setCurrentSessionId(null);
            currentSession = null;
            chatHistory.getChildren().clear();
            qaPipeline.clearHistory();
            if (knowledgeScopeChoice != null) knowledgeScopeChoice.getSelectionModel().selectFirst();
            refreshSessionList(historySidebar);
        });

        Button clearBtn = new Button("清空对话");
        clearBtn.getStyleClass().addAll("link-button", "danger-text");
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
        sessionScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sessionScroll.setPrefHeight(400);
        VBox.setVgrow(sessionScroll, Priority.ALWAYS);

        // DB management checkboxes
        dbCheckboxList = new VBox(4);
        dbCheckboxList.setPadding(new Insets(4, 0, 4, 0));
        ScrollPane dbScroll = new ScrollPane(dbCheckboxList);
        dbScroll.setFitToWidth(true);
        dbScroll.setPrefHeight(100);
        VBox.setVgrow(dbScroll,Priority.ALWAYS);
        Button refreshDbBtn = new Button("刷新知识库");
        refreshDbBtn.setFont(Font.font(10));
        refreshDbBtn.getStyleClass().add("link-button");
        refreshDbBtn.setOnAction(ev -> refreshDBCheckboxes(dbCheckboxList));

        var refreshHistory=new Button("刷新对话");refreshHistory.setOnAction(e -> {if(!busy())refreshSessionList(historySidebar);});
        var importHistory=new Button("导入评测会话…");importHistory.setMaxWidth(Double.MAX_VALUE);
        importHistory.setOnAction(e -> {
            if(busy())return;
            var chooser=new DirectoryChooser();chooser.setTitle("选择包含 sessions 的评测运行目录");
            Path runs=config.getDataDir().resolve("eval-runs");chooser.setInitialDirectory((Files.isDirectory(runs)?runs:config.getDataDir()).toFile());
            var selected=chooser.showDialog(importHistory.getScene().getWindow());if(selected==null)return;
            operationInFlight=true;setStatus("正在导入评测会话…",true);
            submitOperation(()->{
                try {
                    var imported=new EvaluationSessionImporter().importRun(selected.toPath(),config.getDataDir(),"评测回放");
                    Platform.runLater(()->{refreshSessionList(historySidebar);qaLog.appendText("已导入/复用 "+imported.sessions().size()+" 个会话，图片资产 "+imported.imageAssets()+"；请在左侧最近对话中选择“评测回放”。\n");});
                } catch(Exception failure) {Platform.runLater(()->showError("评测会话导入失败："+failure.getMessage()));}
                finally {Platform.runLater(()->{operationInFlight=false;setStatus("就绪",false);});}
            });
        });
        var databases=new VBox(6,new Label("检索数据库："),dbScroll,refreshDbBtn);
        var recent=new VBox(6,new Label("最近对话："),sessionScroll);
        var historySections=workspaceLayout.vertical("qa-lists",databases,recent,.25,100,140);
        VBox.setVgrow(historySections,Priority.ALWAYS);
        historySidebar.getChildren().addAll(qaDataDirLabel,newChatBtn,importHistory,refreshHistory,clearBtn,new Separator(),historySections);

        // --- Right: Chat area ---
        VBox chatArea = new VBox(12);
        chatArea.setPadding(new Insets(20));

        chatHistory = new VBox(8);
        chatHistory.setPadding(new Insets(8));
        ScrollPane chatScroll = new ScrollPane(chatHistory);
        chatHistory.setMinWidth(0);chatScroll.setMinWidth(0);
        chatScroll.setFitToWidth(true);
        chatScroll.setPrefHeight(440);
        chatScroll.getStyleClass().add("chat-scroll");

        knowledgeScopeChoice = new ComboBox<>();
        knowledgeScopeChoice.setMaxWidth(Double.MAX_VALUE);
        knowledgeScopeChoice.setMinWidth(0);
        refreshKnowledgeScopes();
        fetchKnowledgeImages = new CheckBox("补全历史网页图片");
        fetchKnowledgeImages.setSelected(true);
        fetchKnowledgeImages.setTooltip(new Tooltip("新获取的网页自动完整缓存正文图片；此开关控制补全以前未完成的网页图片。"));
        HBox scopeRow = new HBox(8, new Label("资料范围："), knowledgeScopeChoice, fetchKnowledgeImages);
        HBox.setHgrow(knowledgeScopeChoice, Priority.ALWAYS);
        iconProfileChoice=new ComboBox<>();iconProfileChoice.setMaxWidth(Double.MAX_VALUE);iconProfileChoice.setMinWidth(0);
        iconProfileChoice.setOnAction(event -> {
            if(refreshingIconProfiles)return;
            if(busy()){refreshIconProfiles();return;}
            var selected=iconProfileChoice.getValue();if(selected==null)return;
            try {
                new ItemIconStore(db).selectProfile(selected.id());
                if(rendererService!=null)rendererService.clearCache();
                if(qaLog!=null)qaLog.appendText("游戏快照 / 配方图标："+selected+"；存在多个知识快照时，此选项同时决定问答所用的游戏环境；只有一个时自动使用其知识。\n");
                iconProfileChoice.setTooltip(new Tooltip(Objects.toString(selected.location(),"仅使用当前库的平面候选或缺失占位")));
            }catch(Exception failure){showError(failure.getMessage());refreshIconProfiles();}
        });
        var refreshIcons=new Button("刷新图标环境");refreshIcons.setOnAction(event -> {if(!busy())refreshIconProfiles();});
        HBox iconRow=new HBox(8,new Label("游戏快照 / 配方图标："),iconProfileChoice,refreshIcons);HBox.setHgrow(iconProfileChoice,Priority.ALWAYS);
        refreshIconProfiles();

        questionField = new TextArea();
        questionField.setWrapText(true); questionField.setPrefRowCount(2); questionField.setMaxHeight(Double.MAX_VALUE);questionField.setMinWidth(0);
        questionField.setPromptText("询问模组、生物、配方或机制 · Ctrl + Enter 发送");
        questionField.setPrefWidth(500);
        questionField.setOnKeyPressed(e -> { if(e.isControlDown() && e.getCode()==javafx.scene.input.KeyCode.ENTER) { onAsk(); e.consume(); } });
        askBtn = styledButton("发送", "#6C63FF");
        askBtn.setMinWidth(Region.USE_PREF_SIZE);
        refreshIcons.setMinWidth(Region.USE_PREF_SIZE);
        fetchKnowledgeImages.setMinWidth(Region.USE_PREF_SIZE);
        scopeRow.getChildren().stream().filter(Label.class::isInstance).map(Label.class::cast).forEach(label->label.setMinWidth(Region.USE_PREF_SIZE));
        iconRow.getChildren().stream().filter(Label.class::isInstance).map(Label.class::cast).forEach(label->label.setMinWidth(Region.USE_PREF_SIZE));
        askBtn.setOnAction(e -> onAsk());
        HBox inputRow = new HBox(8, questionField, askBtn);
        HBox.setHgrow(questionField,Priority.ALWAYS); inputRow.setAlignment(Pos.BOTTOM_CENTER);

        qaLog = new TextArea();
        qaLog.setEditable(false);
        qaLog.setPrefRowCount(3);
        qaLog.setStyle("-fx-font-family: 'Consolas'; -fx-font-size: 11px;");

        VBox composer=new VBox(8,scopeRow,iconRow,inputRow);VBox.setVgrow(inputRow,Priority.ALWAYS);inputRow.setMinHeight(64);
        var chatSection=workspaceLayout.vertical("qa-composer",chatScroll,composer,.76,180,170);
        var detailSection = new TitledPane("检索与性能详情",qaLog); detailSection.setExpanded(false);
        VBox qaLayout = new VBox(12,chatSection,detailSection); VBox.setVgrow(chatSection,Priority.ALWAYS);

        chatArea.getChildren().add(pageTitle("知识问答"));
        String migration=RuntimeBuildContract.migrationWarning(db);
        if(migration!=null){var warning=new Label(migration);warning.setWrapText(true);chatArea.getChildren().add(warning);}
        chatArea.getChildren().add(qaLayout);
        VBox.setVgrow(qaLayout, Priority.ALWAYS);
        var page=workspaceLayout.horizontal("qa-history",historySidebar,chatArea,.25,220,360);
        page.getStyleClass().add("workspace-page");
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
                row.getStyleClass().add("history-row");

                Label lbl = new Label(sess.title + " (" + sess.messages.size() + " msgs)");
                lbl.setMinWidth(0);lbl.setMaxWidth(Double.MAX_VALUE);HBox.setHgrow(lbl,Priority.ALWAYS);
                lbl.setTooltip(new Tooltip(sess.title+"\n"+sess.dataDir));
                lbl.setFont(Font.font(11));

                Button delBtn = new Button("X");
                delBtn.setFont(Font.font(9));
                delBtn.setMinWidth(Region.USE_PREF_SIZE);
                delBtn.getStyleClass().addAll("link-button", "danger-text");
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
        refreshKnowledgeScopes();
        if (knowledgeScopeChoice != null) {
            knowledgeScopeChoice.getSelectionModel().selectFirst();
            var last = sess.messages.stream().filter(m -> m.role().equals("maa")).reduce((a,b)->b);
            last.filter(message -> message.artifacts()!=null && message.artifacts().scopeId()!=null).ifPresent(message ->
                    knowledgeScopeChoice.getItems().stream().filter(item -> java.util.Objects.equals(item.id(), message.artifacts().scopeId())).findFirst().ifPresent(knowledgeScopeChoice::setValue));
        }
        refreshDBCheckboxes(dbCheckboxList);

        chatHistory.getChildren().clear();
        for (ChatHistoryService.ChatMessage msg : sess.messages) {
            String bg = msg.role().equals("user") ? "#e8f0fe" : "#f0fdf4";
            addChatBubble(msg.role().equals("user") ? "You" : "MAA", msg.content(), bg, msg.artifacts());
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
        submittedPipeline.setKnowledgeImageFetching(fetchKnowledgeImages == null || fetchKnowledgeImages.isSelected());
        final String submittedScope = knowledgeScopeChoice == null || knowledgeScopeChoice.getValue() == null ? null : knowledgeScopeChoice.getValue().id();

        submitOperation(() -> {
            try {
                QaPipeline.QaResult result = submittedPipeline.process(q, submittedScope);
                var recipeImages = result.recipeRenderBatch!=null?result.recipeRenderBatch:rendererService.renderDetailed(result.recipesToRender);
                if(result.recipeRenderBatch==null)AnswerMedia.attachRecipes(result, recipeImages, config.getDataDir());
                final var media=AnswerMedia.assess(result.answer,result.artifacts,config.getDataDir());
                final String answerWithImages = result.answer;

                Platform.runLater(() -> {
                    addChatBubble("MAA", answerWithImages, "#f0fdf4", result.artifacts);
                    appendEvidence(result,media);
                    for(String diagnostic:recipeImages.diagnostics())qaLog.appendText("配方图："+diagnostic+"\n");
                    if (result.metrics != null) {
                        log.info("QA Metrics: total={}ms answers={} imgs={}",
                                result.metrics.totalTimeMs, result.metrics.answerLength,
                                media.valid());
                    }
                    chatHistoryService.addMessage(submittedSession, "maa", answerWithImages, result.artifacts);
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

    private void refreshIconProfiles() {
        if(iconProfileChoice==null||db==null)return;
        refreshingIconProfiles=true;
        try {
            var store=new ItemIconStore(db);String active=store.activeProfileId();
            var choices=new java.util.ArrayList<ItemIconStore.Profile>();choices.add(ItemIconStore.Profile.none());choices.addAll(store.profiles());
            iconProfileChoice.getItems().setAll(choices);
            iconProfileChoice.setValue(choices.stream().filter(p -> java.util.Objects.equals(p.id(),active)).findFirst().orElse(choices.getFirst()));
            iconProfileChoice.setTooltip(new Tooltip(Objects.toString(iconProfileChoice.getValue().location(),"显式选择实机导出环境；未选择时不自动借用其他包图标。")));
        }finally{refreshingIconProfiles=false;}
    }
    private void refreshKnowledgeScopes() {
        if (knowledgeScopeChoice == null || qaPipeline == null) return;
        String selected = knowledgeScopeChoice.getValue() == null ? null : knowledgeScopeChoice.getValue().id();
        var choices = new java.util.ArrayList<KnowledgeRetrievalService.Scope>();
        choices.add(new KnowledgeRetrievalService.Scope(null, "自动识别资料范围", null, List.of(), null));
        choices.addAll(qaPipeline.knowledgeScopes());
        knowledgeScopeChoice.getItems().setAll(choices);
        knowledgeScopeChoice.setValue(choices.stream().filter(s -> java.util.Objects.equals(s.id(),selected)).findFirst().orElse(choices.getFirst()));
    }

    private void addChatBubble(String sender, String msg, String bg) { addChatBubble(sender,msg,bg,null); }

    private void addChatBubble(String sender, String msg, String bg, AnswerArtifacts artifacts) {
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
            copyBtn.getStyleClass().add("copy-button");
            String raw = msg;
            copyBtn.setOnAction(ev -> {
                javafx.scene.input.Clipboard.getSystemClipboard()
                        .setContent(Map.of(javafx.scene.input.DataFormat.PLAIN_TEXT, raw));
                copyBtn.setText("已复制");
                var delay = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(1.5));
                delay.setOnFinished(event -> copyBtn.setText("复制"));
                delay.play();
            });
            header.getChildren().add(copyBtn);

            b.getChildren().add(header);
            b.getChildren().add(yagen.waitmydawn.kb.ui.AnswerRenderer.render(msg, artifacts, config.getDataDir()));
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
        exportCsvBtn.getStyleClass().add("link-button");
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
        card.getStyleClass().add("metric-card");
        valueLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 22));
        valueLabel.getStyleClass().add("link-button");
        Label descLabel = new Label(label);
        descLabel.setFont(Font.font(11));
        descLabel.getStyleClass().add("muted");
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
            dc.setTitle("选择新的数据目录");
            File f = dc.showDialog(stage);
            if (f != null) {
                Path newDir = Path.of(f.getAbsolutePath());
                config.setDataDirForNextLaunch(newDir);
                showError("数据目录已保存，下次启动生效。请重启应用。");
            }
        });
        HBox dirRow = new HBox(8, dataDirField, changeDir);
        dirRow.setAlignment(Pos.CENTER_LEFT); HBox.setHgrow(dataDirField, Priority.ALWAYS);
        VBox dirCard = card("存储位置", dirRow);
        Label dirNote = new Label("数据库、纹理及生成图片保存在此目录，可将整个目录迁移到其他电脑。更换目录后需重启应用。");
        dirNote.setWrapText(true);
        dirNote.setFont(Font.font(12));
        dirNote.getStyleClass().add("muted");

        // Author
        Label authorTitle = pageTitle("个人信息");
        TextField authorField = new TextField(config.getUsername());
        authorField.setPrefWidth(200);
        authorField.textProperty().addListener((o, old, val) -> config.setUsername(val));
        HBox authorRow = new HBox(8, new Label("署名："), authorField);
        authorRow.setAlignment(Pos.CENTER_LEFT);
        ((Label) authorRow.getChildren().getFirst()).setMinWidth(108);
        VBox authorCard = card("种子作者", authorRow);

        // API Key
        Label apiTitle = pageTitle("API Key");
        apiKeyField = new PasswordField();
        apiKeyField.setPromptText("sk-...");
        apiKeyField.setPrefWidth(400);
        if (config.getApiKey() != null) apiKeyField.setText(config.getApiKey());
        apiKeyField.textProperty().addListener((o, old, val) -> config.setApiKey(val));
        HBox apiRow = new HBox(8, new Label("DeepSeek Key："), apiKeyField);
        apiRow.setAlignment(Pos.CENTER_LEFT);
        ((Label) apiRow.getChildren().getFirst()).setMinWidth(108);
        HBox.setHgrow(apiKeyField, Priority.ALWAYS);
        VBox apiCard = card("模型连接", apiRow);
        Label apiNote = new Label("Key 保存在本机设置文件中。问答会将选中的证据片段发送至模型服务；未配置时展示本地资料摘录。");
        apiNote.setWrapText(true);
        apiNote.setFont(Font.font(12));
        apiNote.getStyleClass().add("muted");

        CheckBox visionEnabled = new CheckBox("允许将导入的本地图片发送到下方视觉服务");
        visionEnabled.setSelected(config.isVisionEnabled());
        TextField visionEndpoint = new TextField(config.getVisionEndpoint());
        visionEndpoint.setPromptText("完整的 OpenAI 兼容接口地址，例如 https://服务地址/v1/chat/completions");
        TextField visionModel = new TextField(config.getVisionModel());
        visionModel.setPromptText("支持图像输入的模型名称");
        PasswordField visionKey = new PasswordField();
        visionKey.setText(config.getVisionApiKey()); visionKey.setPromptText("视觉服务 Key；本机服务可留空");
        Button applyVision = new Button("保存视觉设置");
        Label visionStatus = new Label("默认关闭。Windows OCR 在本机运行；启用后，构建会发送图片至指定服务，可能产生费用。");
        visionStatus.setWrapText(true);
        applyVision.setOnAction(e -> {
            if (busy()) return;
            config.setVisionSettings(visionEnabled.isSelected(), visionEndpoint.getText(), visionModel.getText(), visionKey.getText());
            visionStatus.setText(config.isVisionEnabled() ? "视觉描述已启用，下次构建生效；描述将标记为模型推断。" : "视觉描述已关闭，继续使用本地 OCR。");
        });
        TitledPane visionPane = new TitledPane("图片文字与视觉描述", new VBox(6, visionEnabled,
                visionEndpoint, visionModel, visionKey, applyVision, visionStatus));
        visionPane.setExpanded(false);

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

        Button applyModelBtn = styledButton("应用模型", "#6C63FF");
        applyModelBtn.setOnAction(e -> {
            if (busy()) return;
            String selected = modelCombo.getValue();
            if (selected == null) return;
            config.setEmbeddingModelName(selected);
            String loaded = embedder.initModel(selected);
            String status;
            if (selected.equals(loaded)) {
                status = "已应用：" + loaded + "（下次构建时使用）";
                modelStatus.getStyleClass().removeAll("muted", "warning-text"); modelStatus.getStyleClass().add("success-text");
            } else if ("hash-ngram-v2".equals(loaded)) {
                status = "正在使用稳定哈希向量，请先安装本地模型资源。";
                modelStatus.getStyleClass().removeAll("muted", "success-text"); modelStatus.getStyleClass().add("warning-text");
            } else {
                status = "当前模型：" + loaded;
                modelStatus.getStyleClass().removeAll("muted", "warning-text"); modelStatus.getStyleClass().add("success-text");
            }
            modelStatus.setText(status);
        });
        // Show current status on page load
        updateModelStatus(modelStatus, modelCombo);

        VBox embedCard = card("向量化设置",
                new VBox(6,
                        new Label("本地模型：all-MiniLM-L6-v2（384 维）"),
                        new HBox(8, modelCombo, applyModelBtn), modelStatus));
        var themeChoice = new ComboBox<yagen.waitmydawn.kb.ui.ThemeMode>();
        themeChoice.getItems().setAll(yagen.waitmydawn.kb.ui.ThemeMode.values());
        themeChoice.setValue(config.getThemeMode());
        themeChoice.valueProperty().addListener((o, previous, mode) -> { if (mode != null) themes.setMode(mode); });
        var themeRow = new HBox(8, new Label("色彩主题："), themeChoice);
        themeRow.setAlignment(Pos.CENTER_LEFT);
        var prewarm = new Spinner<Integer>(0, 30, config.getPrewarmPages());
        prewarm.setPrefWidth(108);
        prewarm.valueProperty().addListener((o, previous, value) -> config.setPrewarmPages(value));
        var prewarmRow = new HBox(8, new Label("每模组预取页数："), prewarm);
        prewarmRow.setAlignment(Pos.CENTER_LEFT);
        var prewarmNote = new Label("建库时预取不同生物分类的少量正文，并补充已配置的关系页面，减少首次问答的请求。0 为关闭；所有请求共享站点限速与冷却。设置从下次构建生效。");
        prewarmNote.setWrapText(true); prewarmNote.getStyleClass().add("muted");
        page.getChildren().addAll(pageTitle("外观"), card("主题设置", themeRow),
                card("模组条目字段配色",new yagen.waitmydawn.kb.ui.ModPalettePane(config,()->{themes.refreshPalette();if(modListView!=null)modListView.refresh();})),
                pageTitle("构建准备"), card("预取常用详情", new VBox(10, prewarmRow, prewarmNote)));
        page.getChildren().addAll(dirTitle, dirCard, dirNote, authorTitle, authorCard,
                apiTitle, apiCard, apiNote, visionPane, embedLabel, embedCard);
        page.getChildren().add(card("图片缓存维护",new yagen.waitmydawn.kb.ui.ImageCachePane(db,config.getDataDir(),this::busy,operation -> {
            operationInFlight=true;setStatus("正在维护图片缓存…",true);
            submitOperation(()->{
                try {operation.run();}
                finally {Platform.runLater(()->{operationInFlight=false;setStatus("就绪",false);});}
            });
        })));
        ScrollPane settingsScroll = new ScrollPane(page);
        settingsScroll.setFitToWidth(true);
        VBox container = new VBox(settingsScroll);
        VBox.setVgrow(settingsScroll, Priority.ALWAYS);
        return container;
    }

    // =================== Helpers ===================

    private Label pageTitle(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("page-title");

        l.setPadding(new Insets(0, 0, 4, 0));
        return l;
    }

    private VBox card(String title, javafx.scene.Node content) {
        VBox card = new VBox(8);
        card.getStyleClass().add("card");
        Label t = new Label(title);
        t.getStyleClass().add("card-title");
        t.setFont(Font.font("Segoe UI", FontWeight.BOLD, 13));

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

    private void appendEvidence(QaPipeline.QaResult result,AnswerMedia.Assessment media) {
        StringBuilder text=new StringBuilder();
        text.append("候选图片：").append(media.candidates()).append("；正文引用：").append(media.displayed())
                .append("；有效图片：").append(media.valid()).append("；不可用图片：").append(media.unavailable()).append("\n\n");
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
        if (themes != null) themes.prepare(a.getDialogPane().getScene());
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
        Stage dialog=new Stage();dialog.initOwner(owner);dialog.initModality(javafx.stage.Modality.WINDOW_MODAL);
        dialog.setTitle("模组绑定 · "+entry.getModId());
        var original=ModUrlBinding.vanilla(entry)?ModUrlBinding.vanilla():entry.getMcmodBinding();var pending=new java.util.concurrent.atomic.AtomicReference<ModUrlBinding>(original);
        var slug=new TextField(Objects.toString(entry.getSlug(),""));
        var url=new TextField(original==null?"":Objects.toString(original.url(),""));
        var chinese=new TextField(original==null?"":Objects.toString(original.chineseName(),""));
        var english=new TextField(original==null?"":Objects.toString(original.englishName(),""));
        var info=new Label(original==null?"尚未绑定":original.statusLabel()+" · 总浏览："+Objects.toString(original.views(),"未知"));info.setWrapText(true);
        var fetch=new Button("获取页面信息");var search=new Button("重新搜索");var save=new Button("保存");var cancel=new Button("取消");
        var open=new Button("打开百科");open.setOnAction(e->{try{getHostServices().showDocument(ModUrlBinding.normalize(url.getText()));}catch(Exception failure){info.setText(failure.getMessage());}});
        var modrinth=new Button("打开 Modrinth");modrinth.setOnAction(e->{String value=slug.getText().strip();if(value.matches("[A-Za-z0-9_-]+"))getHostServices().showDocument("https://modrinth.com/mod/"+value);});
        var form=new GridPane();form.setHgap(10);form.setVgap(10);
        form.addRow(0,new Label("modid"),new Label(entry.getModId()));form.addRow(1,new Label("Slug"),slug);
        form.addRow(2,new Label("百科 URL"),url);form.addRow(3,new Label("中文别名"),chinese);form.addRow(4,new Label("英文别名"),english);
        for(var field:List.of(slug,url,chinese,english)){field.setPrefWidth(490);GridPane.setHgrow(field,Priority.ALWAYS);}
        var root=new VBox(12,form,new HBox(8,search,fetch,open,modrinth),info,new Label("URL 留空并保存：仅使用本地资料。已建库模组换绑后需在来源管理刷新。"),new HBox(8,save,cancel));root.setPadding(new Insets(18));
        boolean vanilla=ModUrlBinding.vanilla(entry);form.setDisable(vanilla);search.setDisable(vanilla);fetch.setDisable(vanilla);open.setDisable(vanilla);modrinth.setDisable(vanilla);
        Path directory=Path.of(buildTargetField.getText());
        java.util.function.Consumer<Boolean> lookup=bySearch->{
            if(busy())return;final String requested;
            try{requested=bySearch?null:ModUrlBinding.normalize(url.getText());}catch(Exception failure){info.setText(failure.getMessage());return;}
            operationInFlight=true;root.setDisable(true);setStatus("正在获取百科候选…",true);
            submitOperation(()->{
                try(var target=BuildTarget.open(directory,config,db)){
                    var scraper=new WikiScraperService();scraper.setResourceDatabase(target.database());var binder=new ModUrlBinder(scraper);
                    var candidate=new ModEntry();candidate.setModId(entry.getModId());candidate.setDisplayName(entry.getDisplayName());candidate.setLoader(entry.getLoader());
                    var result=bySearch?binder.bind(candidate):binder.inspect(requested,"MANUAL");
                    Platform.runLater(()->{pending.set(result);url.setText(Objects.toString(result.url(),""));chinese.setText(Objects.toString(result.chineseName(),""));english.setText(Objects.toString(result.englishName(),""));info.setText(result.statusLabel()+" · 总浏览："+Objects.toString(result.views(),"未知")+" · "+Objects.toString(result.note(),"保存后生效"));});
                }catch(Exception failure){Platform.runLater(()->info.setText(failure.getMessage()));}
                finally{Platform.runLater(()->{operationInFlight=false;root.setDisable(false);form.setDisable(vanilla);setStatus("就绪",false);});}
            });
        };
        fetch.setOnAction(e->lookup.accept(false));search.setOnAction(e->lookup.accept(true));
        save.setOnAction(e->{
            if(busy())return;String value=slug.getText().strip();if(!value.isEmpty()&&!value.matches("[A-Za-z0-9_-]+")){info.setText("Slug 只能包含字母、数字、下划线和连字符。");return;}
            final ModUrlBinding binding;
            try{
                String selected=url.getText().isBlank()?null:ModUrlBinding.normalize(url.getText());var metadata=pending.get();
                boolean same=metadata!=null&&Objects.equals(selected,metadata.url());
                binding=vanilla?ModUrlBinding.vanilla():new ModUrlBinding(selected,chinese.getText().strip(),english.getText().strip(),same?metadata.views():null,selected==null?"DISABLED":"FOUND","MANUAL",java.time.Instant.now().toString(),same?metadata.note():"人工设置，浏览量待获取");
            }catch(Exception failure){info.setText(failure.getMessage());return;}
            operationInFlight=true;root.setDisable(true);
            submitOperation(()->{
                try(var target=BuildTarget.open(directory,config,db)){
                    var draft=new ModEntry();draft.setModId(entry.getModId());draft.setLoader(entry.getLoader());draft.setVersion(entry.getVersion());draft.setSlug(vanilla?null:value.isEmpty()?null:value);draft.setMcmodBinding(binding);
                    new ModBindingStore(target.database()).save(draft);
                    draft.setJarPath(entry.getJarPath());draft.setSource("manual");var cache=new JarScanCache(Path.of(folderField.getText()));cache.updateBinding(draft);if(cache.environment()!=null)cache.save(cache.environment());
                    Platform.runLater(()->{entry.setSlug(draft.getSlug());entry.setModrinthUrl(draft.getSlug()==null?null:"https://modrinth.com/mod/"+draft.getSlug());entry.setMcmodBinding(binding);modListView.getItems().sort(ModUrlBinding.reviewOrder());modListView.refresh();dialog.close();});
                }catch(Exception failure){Platform.runLater(()->info.setText(failure.getMessage()));}
                finally{Platform.runLater(()->{operationInFlight=false;root.setDisable(false);form.setDisable(vanilla);setStatus("就绪",false);});}
            });
        });
        cancel.setOnAction(e->{if(!busy())dialog.close();});dialog.setOnCloseRequest(e->{if(busy())e.consume();});
        var scene=new Scene(root,760,400);themes.prepare(scene);dialog.setScene(scene);dialog.showAndWait();
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
                delBtn.getStyleClass().addAll("link-button", "danger-text");
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
            status.setText("使用本地哈希向量 · 库中 " + vecCount + " 条向量");
            status.getStyleClass().removeAll("success-text", "warning-text"); status.getStyleClass().add("muted");
        } else {
            status.setText("当前：" + active + " · 库中 " + vecCount + " 条向量");
            status.getStyleClass().removeAll("muted", "warning-text"); status.getStyleClass().add("success-text");
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
