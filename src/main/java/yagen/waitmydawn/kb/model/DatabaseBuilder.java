package yagen.waitmydawn.kb.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yagen.waitmydawn.kb.config.AppConfig;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * H2 嵌入式数据库管理 — 使用独立的 rag_data.db。
 * 负责: 连接管理、DDL 建表、基本 CRUD。
 */
public class DatabaseBuilder {

    private static final Logger log = LoggerFactory.getLogger(DatabaseBuilder.class);

    private final AppConfig config;
    private String jdbcUrl;

    public DatabaseBuilder(AppConfig config) {
        this.config = config;
        // H2 appends .mv.db automatically to the filename — don't add .db suffix
        // config may be null for IncrementalDB subclasses that override getJdbcUrl()
        if (config != null) {
            this.jdbcUrl = "jdbc:h2:file:" + config.getDbPath().toString().replace('\\', '/')
                    + ";DATABASE_TO_LOWER=TRUE";
        }
    }

    /** 获取 JDBC URL (可被子类覆写) */
    public String getJdbcUrl() { return jdbcUrl; }

    /** 初始化增量数据库 (仅向量表, 轻量) */
    public void initIncDatabase() {
        try (var conn = DriverManager.getConnection(getJdbcUrl(), "sa", "");
             var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS rag_vectors (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    chunk_id VARCHAR(500) NOT NULL,
                    chunk_text CLOB,
                    embedding BLOB,
                    mod_id VARCHAR(200),
                    mod_name VARCHAR(500),
                    chunk_type VARCHAR(50),
                    source_path VARCHAR(1000),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        } catch (Exception e) { log.error("Inc DB init failed", e); }
    }

    /** 建立连接 */
    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(getJdbcUrl(), "sa", "");
    }

    /** 初始化数据库：创建所有表 */
    public void initDatabase() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {

            // rag_mod_info — 模组元数据
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS rag_mod_info (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    mod_id VARCHAR(255) NOT NULL,
                    display_name VARCHAR(500),
                    version VARCHAR(100),
                    description CLOB,
                    authors VARCHAR(500),
                    license VARCHAR(200),
                    logo_file VARCHAR(500),
                    issue_tracker_url VARCHAR(1000),
                    loader VARCHAR(50) DEFAULT 'neoforge',
                    mc_version VARCHAR(50),
                    slug VARCHAR(200),
                    modrinth_url VARCHAR(1000),
                    source VARCHAR(50) DEFAULT 'unknown',
                    jar_path VARCHAR(1000),
                    jar_file_name VARCHAR(500),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

            // rag_item — 物品
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS rag_item (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    registry_name VARCHAR(255) NOT NULL,
                    display_name VARCHAR(255) NOT NULL,
                    mod_source VARCHAR(100) DEFAULT 'minecraft',
                    item_type VARCHAR(50),
                    max_stack INTEGER DEFAULT 64,
                    durability INTEGER,
                    versions VARCHAR(1000),
                    texture_path VARCHAR(500),
                    icon_url VARCHAR(1000),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

            // rag_recipe — 配方
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS rag_recipe (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    recipe_type VARCHAR(127) NOT NULL,
                    output_item VARCHAR(255),
                    output_count INTEGER DEFAULT 1,
                    recipe_data CLOB NOT NULL,
                    source_mod VARCHAR(100) DEFAULT 'minecraft',
                    source_pack VARCHAR(255),
                    version_range VARCHAR(500),
                    version_exact VARCHAR(50),
                    is_modified BOOLEAN DEFAULT FALSE,
                    mod_version VARCHAR(100),
                    loader VARCHAR(50) DEFAULT 'neoforge',
                    source_url VARCHAR(1000),
                    confirm_count INTEGER DEFAULT 1,
                    source_type VARCHAR(50) DEFAULT 'JAR_PARSE',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

            // rag_multiblock — 多方块结构
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS rag_multiblock (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    structure_name VARCHAR(255) NOT NULL,
                    display_name VARCHAR(500),
                    mod_source VARCHAR(100) DEFAULT 'unknown',
                    layers_json CLOB NOT NULL,
                    description CLOB,
                    materials_json VARCHAR(2000),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

            // rag_wiki_entry — Wiki 知识条目
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS rag_wiki_entry (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    title VARCHAR(500) NOT NULL,
                    source_url VARCHAR(1000),
                    content CLOB NOT NULL,
                    embedding_json CLOB,
                    category VARCHAR(100),
                    mod_source VARCHAR(100),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

            // rag_texture_cache — 纹理缓存
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS rag_texture_cache (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    registry_name VARCHAR(255) NOT NULL,
                    local_path VARCHAR(1000) NOT NULL,
                    source VARCHAR(100) DEFAULT 'jar_extract',
                    mod_source VARCHAR(100),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

            // rag_version_map — MC 版本映射
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS rag_version_map (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    loader VARCHAR(50) NOT NULL,
                    loader_version_prefix VARCHAR(50) NOT NULL,
                    mc_version VARCHAR(50) NOT NULL
                )
                """);

            // rag_entity_registry — 从 textures/entity/ 提取的实体注册名
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS rag_entity_registry (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    registry_name VARCHAR(255) NOT NULL,
                    mod_id VARCHAR(200) NOT NULL,
                    source_path VARCHAR(1000),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

            // rag_web_cache — 缓存爬取的网页 (含 subWebPage 映射, 供 Q&A 增量抓取)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS rag_web_cache (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    mod_id VARCHAR(255) NOT NULL,
                    title VARCHAR(500),
                    url VARCHAR(1000),
                    content CLOB,
                    source VARCHAR(50),
                    sub_web_page_json CLOB,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

            // 插入默认版本映射
            initVersionMap(conn);

            log.info("Database initialized: {}", jdbcUrl);
        } catch (SQLException e) {
            log.error("Database init failed: {}", e.getMessage(), e);
            throw new RuntimeException("Database init failed", e);
        }
    }

    /** 插入 NeoForge/Forge → MC 版本映射 */
    private void initVersionMap(Connection conn) throws SQLException {
        // 先检查是否已有数据
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM rag_version_map");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next() && rs.getInt(1) > 0) return;
        }

        String sql = "INSERT INTO rag_version_map (loader, loader_version_prefix, mc_version) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            // NeoForge
            insertMapping(ps, "neoforge", "21.1", "1.21.1");
            insertMapping(ps, "neoforge", "21.0", "1.21");
            insertMapping(ps, "neoforge", "20.4", "1.20.4");
            insertMapping(ps, "neoforge", "20.2", "1.20.2");
            insertMapping(ps, "neoforge", "20.1", "1.20.1");
            // Forge
            insertMapping(ps, "forge", "49.0", "1.20.4");
            insertMapping(ps, "forge", "48.0", "1.20.1");
            insertMapping(ps, "forge", "47.0", "1.20");
            insertMapping(ps, "forge", "41.0", "1.19");
            insertMapping(ps, "forge", "40.0", "1.18");
            // Fabric
            insertMapping(ps, "fabric", "0.15", "1.20.4");
            insertMapping(ps, "fabric", "0.14", "1.20.1");
            insertMapping(ps, "fabric", "0.12", "1.19");

            ps.executeBatch();
            log.info("Version mappings inserted");
        }
    }

    private void insertMapping(PreparedStatement ps, String loader, String prefix, String mcVersion) throws SQLException {
        ps.setString(1, loader);
        ps.setString(2, prefix);
        ps.setString(3, mcVersion);
        ps.addBatch();
    }

    // --- ModEntry 写入 ---

    /** 保存模组元数据到 rag_mod_info */
    public void saveModEntry(ModEntry entry) {
        String sql = """
            MERGE INTO rag_mod_info (mod_id, display_name, version, description, authors, license,
                logo_file, issue_tracker_url, loader, mc_version, slug, modrinth_url, source,
                jar_path, jar_file_name, updated_at)
            KEY (mod_id, loader, mc_version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            """;
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, entry.getModId());
            ps.setString(2, entry.getDisplayName());
            ps.setString(3, entry.getVersion());
            ps.setString(4, entry.getDescription());
            ps.setString(5, entry.getAuthors());
            ps.setString(6, entry.getLicense());
            ps.setString(7, entry.getLogoFile());
            ps.setString(8, entry.getIssueTrackerUrl());
            ps.setString(9, entry.getLoader());
            ps.setString(10, entry.getMcVersion());
            ps.setString(11, entry.getSlug());
            ps.setString(12, entry.getModrinthUrl());
            ps.setString(13, entry.getSource());
            ps.setString(14, entry.getJarPath());
            ps.setString(15, entry.getJarFileName());
            ps.executeUpdate();
            log.debug("ModEntry saved: {}", entry.getModId());
        } catch (SQLException e) {
            log.error("Save ModEntry failed: {}", e.getMessage(), e);
        }
    }

    /** 查询单个 ModEntry */
    public ModEntry findModEntry(String modId) {
        String sql = "SELECT * FROM rag_mod_info WHERE mod_id = ? LIMIT 1";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, modId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapToModEntry(rs);
                else return null;
            }
        } catch (SQLException e) { return null; }
    }

    /** 查询所有已保存的 ModEntry */
    public List<ModEntry> findAllModEntries() {
        List<ModEntry> list = new ArrayList<>();
        String sql = "SELECT * FROM rag_mod_info ORDER BY mod_id";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(mapToModEntry(rs));
            }
        } catch (SQLException e) {
            log.error("Query ModEntry failed: {}", e.getMessage(), e);
        }
        return list;
    }

    // ======================== RAG Web 缓存 ========================

    private final ObjectMapper jsonMapper = new ObjectMapper();

    /**
     * 清空构建阶段产生的数据 (向量、配方、纹理)，实现覆盖式构建。
     * mod_info 使用 MERGE 语义，不需要清空。
     */
    public void clearBuildData() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM rag_vectors");
            stmt.execute("DELETE FROM rag_recipe");
            stmt.execute("DELETE FROM rag_texture_cache");
            stmt.execute("DELETE FROM rag_entity_registry");
            stmt.execute("DELETE FROM rag_web_cache");
            log.info("Build data cleared (overwrite mode)");
        } catch (SQLException e) {
            log.error("Clear build data failed", e);
        }
    }

    /**
     * 保存爬取的网页列表 (含 subWebPage 映射) 到 rag_web_cache。
     * 用于后续 Q&A 阶段按 namespace 查找子网页 URL 进行增量抓取。
     */
    public void saveWebPages(String modId, List<?> pages) {
        String sql = """
            INSERT INTO rag_web_cache (mod_id, title, url, content, source, sub_web_page_json)
            VALUES (?, ?, ?, ?, ?, ?)
            """;
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Object obj : pages) {
                if (!(obj instanceof Record r)) continue;
                ps.setString(1, modId);
                ps.setString(2, safeGet(r, "title"));
                ps.setString(3, safeGet(r, "url"));
                ps.setString(4, safeGet(r, "content"));
                ps.setString(5, safeGet(r, "source"));
                // 序列化 subWebPage Map → JSON
                Object subMap = safeGetObj(r, "subWebPage");
                ps.setString(6, subMap != null ? jsonMapper.writeValueAsString(subMap) : "{}");
                ps.addBatch();
            }
            ps.executeBatch();
            log.info("Web cache saved: {} pages for modId={}", pages.size(), modId);
        } catch (Exception e) {
            log.error("Save WebPages failed: {}", e.getMessage(), e);
        }
    }

    /** 加载指定 mod 的所有缓存网页 (含 subWebPage 映射) */
    public List<WebCacheEntry> loadWebPages(String modId) {
        List<WebCacheEntry> list = new ArrayList<>();
        String sql = "SELECT * FROM rag_web_cache WHERE mod_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, modId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String json = rs.getString("sub_web_page_json");
                    Map<String, String> subMap = new LinkedHashMap<>();
                    if (json != null && !json.isBlank() && !"{}".equals(json)) {
                        subMap = jsonMapper.readValue(json,
                                new TypeReference<LinkedHashMap<String, String>>() {});
                    }
                    list.add(new WebCacheEntry(
                            rs.getString("mod_id"),
                            rs.getString("title"),
                            rs.getString("url"),
                            rs.getString("content"),
                            rs.getString("source"),
                            subMap));
                }
            }
        } catch (Exception e) {
            log.error("Load WebPages failed: {}", e.getMessage(), e);
        }
        return list;
    }

    /** 加载所有已缓存的网页条目 */
    public List<WebCacheEntry> loadAllWebPages() {
        List<WebCacheEntry> list = new ArrayList<>();
        String sql = "SELECT * FROM rag_web_cache";
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String json = rs.getString("sub_web_page_json");
                Map<String, String> subMap = new LinkedHashMap<>();
                if (json != null && !json.isBlank() && !"{}".equals(json)) {
                    subMap = jsonMapper.readValue(json,
                            new TypeReference<LinkedHashMap<String, String>>() {});
                }
                list.add(new WebCacheEntry(
                        rs.getString("mod_id"),
                        rs.getString("title"),
                        rs.getString("url"),
                        rs.getString("content"),
                        rs.getString("source"),
                        subMap));
            }
        } catch (Exception e) {
            log.error("Load all WebPages failed: {}", e.getMessage(), e);
        }
        return list;
    }

    /** 根据 namespace (注册名) 查找子网页 URL */
    public String findSubPageUrl(String namespace) {
        String sql = "SELECT sub_web_page_json FROM rag_web_cache WHERE sub_web_page_json LIKE ? LIMIT 1";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "%" + namespace + "%");
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String json = rs.getString("sub_web_page_json");
                    if (json != null) {
                        Map<String, String> map = jsonMapper.readValue(json,
                                new TypeReference<LinkedHashMap<String, String>>() {});
                        return map.get(namespace);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Find subPageUrl failed: {}", e.getMessage());
        }
        return null;
    }

    /** 网页缓存条目 */
    public record WebCacheEntry(String modId, String title, String url,
                                String content, String source,
                                Map<String, String> subWebPage) {}

    // --- Record 反射辅助 (避免 model → service 依赖) ---

    private static String safeGet(Record r, String field) {
        try {
            var f = r.getClass().getDeclaredField(field);
            f.setAccessible(true);
            Object v = f.get(r);
            return v != null ? v.toString() : null;
        } catch (Exception e) { return null; }
    }

    private static Object safeGetObj(Record r, String field) {
        try {
            var f = r.getClass().getDeclaredField(field);
            f.setAccessible(true);
            return f.get(r);
        } catch (Exception e) { return null; }
    }

    // ======================== ModEntry CRUD ========================

    private ModEntry mapToModEntry(ResultSet rs) throws SQLException {
        ModEntry e = new ModEntry();
        e.setModId(rs.getString("mod_id"));
        e.setDisplayName(rs.getString("display_name"));
        e.setVersion(rs.getString("version"));
        e.setDescription(rs.getString("description"));
        e.setAuthors(rs.getString("authors"));
        e.setLicense(rs.getString("license"));
        e.setLogoFile(rs.getString("logo_file"));
        e.setIssueTrackerUrl(rs.getString("issue_tracker_url"));
        e.setLoader(rs.getString("loader"));
        e.setMcVersion(rs.getString("mc_version"));
        e.setSlug(rs.getString("slug"));
        e.setModrinthUrl(rs.getString("modrinth_url"));
        e.setSource(rs.getString("source"));
        e.setJarPath(rs.getString("jar_path"));
        e.setJarFileName(rs.getString("jar_file_name"));
        return e;
    }
}
