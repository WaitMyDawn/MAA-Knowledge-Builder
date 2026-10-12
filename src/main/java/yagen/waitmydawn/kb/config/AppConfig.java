package yagen.waitmydawn.kb.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 全局配置管理。所有数据（DB、纹理、生成图）存放在用户选定的数据目录下。
 * 首次启动需选择数据目录，配置持久化到 %USERPROFILE%/.maa_kb/settings.properties。
 */
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    private static final Path SETTINGS_DIR = Path.of(System.getProperty("user.home"), ".maa_kb");
    private static final Path SETTINGS_FILE = SETTINGS_DIR.resolve("settings.properties");

    private Path dataDir;
    private Path nextLaunchDataDir;
    private String apiKey;
    /** 仅当前进程生效的临时 API Key（--api-key 提供），不写入 settings.properties。 */
    private volatile String runtimeApiKey;
    private String embeddingModelName = "all-MiniLM-L6-v2";
    private String username = "";
    private String defaultMcVersion = "1.21.1";
    private String defaultLoader = "neoforge";
    private yagen.waitmydawn.kb.ui.ThemeMode themeMode = yagen.waitmydawn.kb.ui.ThemeMode.SYSTEM;
    private int prewarmPages = 6;

    private boolean initialized = false;
    private boolean persistenceEnabled = true;

    private static AppConfig instance;

    public static synchronized AppConfig getInstance() {
        if (instance == null) instance = new AppConfig();
        return instance;
    }

    private AppConfig() { load(); }
    private AppConfig(boolean isolated) { persistenceEnabled = !isolated; }

    /** 独立运行只借用已授权的凭据，不加载或改写 GUI 的数据目录配置。 */
    public static AppConfig forIsolatedRun(boolean useSavedKey) throws IOException {
        var config = new AppConfig(true);
        if (useSavedKey) {
            var saved = new Properties();
            try (Reader reader = Files.newBufferedReader(SETTINGS_FILE)) { saved.load(reader); }
            config.runtimeApiKey = saved.getProperty("apiKey", "");
            config.embeddingModelName = saved.getProperty("embeddingModelName", "all-MiniLM-L6-v2");
        }
        return config;
    }

    // --- properties ---

    public Path getDataDir() { return dataDir; }

    public boolean isInitialized() { return initialized && dataDir != null; }

    public void setDataDirForNextLaunch(Path dir) {
        nextLaunchDataDir = dir;
        save(dir);
    }

    /**
     * 设置数据目录并持久化。首次调用时创建子目录。
     */
    public void setDataDir(Path dir) {
        this.dataDir = dir.toAbsolutePath().normalize();
        nextLaunchDataDir = null;
        this.initialized = true;
        try {
            Files.createDirectories(dataDir);
            Files.createDirectories(getTexturesDir());
            Files.createDirectories(getGeneratedDir());
            Files.createDirectories(getTemplatesDir());
            // Initialize file logging (creates logs/ dir under dataDir)
            LogSetup.init(dataDir);
        } catch (IOException e) {
            log.error("Failed to create data directories: {}", e.getMessage());
        }
        save();
        log.info("Data dir set: {}", dataDir);
    }

    /** 返回实际使用的 Key：临时 Key（--api-key）优先，缺省回退到持久化的 apiKey。 */
    public String getApiKey() {
        String runKey = runtimeApiKey;
        return (runKey != null && !runKey.isBlank()) ? runKey : apiKey;
    }

    /** 判断是否存在可用 Key（临时或持久化任一非空）。 */
    public boolean hasUsableApiKey() {
        String k = getApiKey();
        return k != null && !k.isBlank();
    }

    /** 设置仅本次进程生效的临时 Key，不触发持久化（不修改 settings.properties 中记录的 Key）。 */
    public void setApiKeyForRun(String v) {
        this.runtimeApiKey = v;
    }

    public void setApiKey(String v) { this.apiKey = v; save(); }

    public String getEmbeddingModelName() { return embeddingModelName; }
    public void setEmbeddingModelName(String v) { this.embeddingModelName = v; save(); }

    public String getUsername() { return username; }
    public void setUsername(String v) { this.username = v; }

    public String getDefaultMcVersion() { return defaultMcVersion; }
    public String getDefaultLoader() { return defaultLoader; }

    public yagen.waitmydawn.kb.ui.ThemeMode getThemeMode() { return themeMode; }
    public void setThemeMode(yagen.waitmydawn.kb.ui.ThemeMode mode) { themeMode = java.util.Objects.requireNonNull(mode); save(); }
    public int getPrewarmPages() { return prewarmPages; }
    public void setPrewarmPages(int pages) { prewarmPages = Math.max(0, Math.min(30, pages)); save(); }

    // --- derived paths ---

    public Path getTemplatesDir() { return resolve("templates"); }
    public Path getTexturesDir() { return resolve("textures"); }
    /**
     * 历史遗留目录：2026-09-07 起 setDataDir 不再自动创建（全项目无消费者）。
     * getter 保留以防外部引用编译破坏；如需恢复 jar 仓库语义需重新引入消费方。
     */
    public Path getJarsDir() { return resolve("jars"); }
    public Path getGeneratedDir() { return resolve("generated"); }
    public Path getWikiDir() { return resolve("wiki"); }
    public Path getDbPath() { return resolve("rag_data"); }

    private Path resolve(String sub) {
        return dataDir != null ? dataDir.resolve(sub) : Path.of("data", sub);
    }

    // --- persistence ---

    private void load() {
        try {
            Files.createDirectories(SETTINGS_DIR);
        } catch (IOException e) { return; }

        if (!Files.exists(SETTINGS_FILE)) {
            // No settings yet - don't set dataDir (triggers first-launch dialog)
            return;
        }

        Properties props = new Properties();
        try (Reader r = Files.newBufferedReader(SETTINGS_FILE)) {
            props.load(r);
            String dir = props.getProperty("dataDir");
            if (dir != null && !dir.isBlank()) {
                this.dataDir = Path.of(dir);
                this.initialized = true;
                Files.createDirectories(dataDir);
                // Initialize file logging on load (not just on setDataDir)
                LogSetup.init(dataDir);
            }
            themeMode = yagen.waitmydawn.kb.ui.ThemeMode.parse(props.getProperty("themeMode"));
            try { prewarmPages = Math.max(0, Math.min(30, Integer.parseInt(props.getProperty("prewarmPages", "6")))); }
            catch (NumberFormatException ignored) { prewarmPages = 6; }
            this.apiKey = props.getProperty("apiKey", "");
            this.embeddingModelName = props.getProperty("embeddingModelName", "all-MiniLM-L6-v2");
            String savedUser = props.getProperty("username", "");
            this.username = (savedUser != null && !savedUser.isBlank())
                    ? savedUser : ("MAA_" + java.util.UUID.randomUUID().toString().substring(0, 6));
            if (savedUser == null || savedUser.isBlank()) save(); // persist new UUID
            this.defaultMcVersion = props.getProperty("defaultMcVersion", "1.21.1");
            this.defaultLoader = props.getProperty("defaultLoader", "neoforge");
            log.info("Config loaded. dataDir={}", dataDir);
        } catch (IOException e) {
            log.warn("Failed to load settings: {}", e.getMessage());
        }
    }

    void save() {
        save(nextLaunchDataDir != null ? nextLaunchDataDir : dataDir);
    }

    private void save(Path savedDataDir) {
        if (!persistenceEnabled) return;
        try {
            Files.createDirectories(SETTINGS_DIR);
        } catch (IOException e) { return; }

        Properties props = new Properties();
        props.setProperty("dataDir", savedDataDir != null ? savedDataDir.toString() : "");
        props.setProperty("apiKey", apiKey != null ? apiKey : "");
        props.setProperty("embeddingModelName", embeddingModelName);
        props.setProperty("username", username);
        props.setProperty("defaultMcVersion", defaultMcVersion);
        props.setProperty("defaultLoader", defaultLoader);
        props.setProperty("themeMode", themeMode.name());
        props.setProperty("prewarmPages", Integer.toString(prewarmPages));

        try (Writer w = Files.newBufferedWriter(SETTINGS_FILE)) {
            props.store(w, "MAA Knowledge Builder Settings");
        } catch (IOException e) {
            log.warn("Failed to save settings: {}", e.getMessage());
        }
    }
}
