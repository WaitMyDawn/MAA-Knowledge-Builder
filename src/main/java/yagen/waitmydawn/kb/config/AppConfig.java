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
    private String apiKey;
    private String embeddingModelName = "all-MiniLM-L6-v2";
    private String username = "";
    private String defaultMcVersion = "1.21.1";
    private String defaultLoader = "neoforge";

    private boolean initialized = false;

    private static AppConfig instance;

    public static synchronized AppConfig getInstance() {
        if (instance == null) instance = new AppConfig();
        return instance;
    }

    private AppConfig() {
        load();
    }

    // --- properties ---

    public Path getDataDir() { return dataDir; }

    public boolean isInitialized() { return initialized && dataDir != null; }

    /**
     * 设置数据目录并持久化。首次调用时创建子目录。
     */
    public void setDataDir(Path dir) {
        this.dataDir = dir;
        this.initialized = true;
        try {
            Files.createDirectories(dataDir);
            Files.createDirectories(getTexturesDir());
            Files.createDirectories(getGeneratedDir());
            Files.createDirectories(getTemplatesDir());
            Files.createDirectories(getJarsDir());
            // Initialize file logging (creates logs/ dir under dataDir)
            LogSetup.init(dataDir);
        } catch (IOException e) {
            log.error("Failed to create data directories: {}", e.getMessage());
        }
        save();
        log.info("Data dir set: {}", dataDir);
    }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String v) { this.apiKey = v; save(); }

    public String getEmbeddingModelName() { return embeddingModelName; }
    public void setEmbeddingModelName(String v) { this.embeddingModelName = v; save(); }

    public String getUsername() { return username; }
    public void setUsername(String v) { this.username = v; }

    public String getDefaultMcVersion() { return defaultMcVersion; }
    public String getDefaultLoader() { return defaultLoader; }

    // --- derived paths ---

    public Path getTemplatesDir() { return resolve("templates"); }
    public Path getTexturesDir() { return resolve("textures"); }
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
        try {
            Files.createDirectories(SETTINGS_DIR);
        } catch (IOException e) { return; }

        Properties props = new Properties();
        props.setProperty("dataDir", dataDir != null ? dataDir.toString() : "");
        props.setProperty("apiKey", apiKey != null ? apiKey : "");
        props.setProperty("embeddingModelName", embeddingModelName);
        props.setProperty("username", username);
        props.setProperty("defaultMcVersion", defaultMcVersion);
        props.setProperty("defaultLoader", defaultLoader);

        try (Writer w = Files.newBufferedWriter(SETTINGS_FILE)) {
            props.store(w, "MAA Knowledge Builder Settings");
        } catch (IOException e) {
            log.warn("Failed to save settings: {}", e.getMessage());
        }
    }
}
