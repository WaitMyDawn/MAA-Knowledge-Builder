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
                    // DB_CLOSE_DELAY=-1：进程内保持数据库打开。否则每次最后一个连接关闭都会
                    // close+flush 整个 MVStore，长任务里反复 close/reopen 会写坏空闲位图
                    // （2026-09-15 实测 "Double mark" 损坏，见 workflow 第 10 章）。
                    + ";DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        }
    }

    public static DatabaseBuilder atPath(java.nio.file.Path path) {
        DatabaseBuilder database = new DatabaseBuilder(null);
        database.jdbcUrl = "jdbc:h2:file:" + path.toAbsolutePath().toString().replace('\\', '/')
                + ";DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        return database;
    }

    /** 诊断入口禁止建表和内容写入；已有凭据仍由统一连接入口处理。 */
    public static DatabaseBuilder atReadOnlyPath(java.nio.file.Path path) {
        var database=atPath(path);
        database.jdbcUrl+=";ACCESS_MODE_DATA=r;IFEXISTS=TRUE";
        return database;
    }

    private static final List<String> BUILD_TABLES = List.of("rag_mod_info", "rag_recipe",
            "rag_texture_cache", "rag_entity_registry", "rag_web_cache", "rag_web_category", "rag_web_category_member", "rag_vectors", "rag_vector_profile", "rag_build_metadata",
            "rag_knowledge_source", "rag_document", "rag_content_chunk", "rag_image_asset", "rag_image_occurrence", "rag_chunk_image",
            "rag_document_source", "rag_web_image_reference", "rag_document_import", "rag_jar_import","rag_content_block","rag_chunk_block","rag_item_icon_profile","rag_item_icon",
            "rag_item_tag_file","rag_item_name","rag_item_static_icon","rag_recipe_resource_import","rag_image_analysis","rag_image_grounding","rag_source_state","rag_jar_source","rag_source_event","rag_document_parser","rag_jar_parser","rag_runtime_snapshot","rag_runtime_record","rag_runtime_name","rag_runtime_recipe");

    /** 网络与解析工作在临时库完成，发布事务只包含已校验的数据复制。 */
    public void replaceBuildDataFrom(DatabaseBuilder staging) {
        replaceBuildDataFrom(staging,List.of());
    }
    public void replaceBuildDataFrom(DatabaseBuilder staging,java.util.Collection<String> invalidateUrls) {
        try (var target = getConnection(); var source = staging.getConnection()) {
            target.setAutoCommit(false);
            try {
                for (String table : BUILD_TABLES) {
                    var additions = BuildTableDelta.removeChangedRows(target, source, table);
                    try (var query = source.createStatement(); var rows = query.executeQuery("SELECT * FROM " + table)) {
                        var meta = rows.getMetaData();
                        var columns = new ArrayList<Integer>();
                        var names = new ArrayList<String>();
                        for (int i = 1; i <= meta.getColumnCount(); i++) {
                            if (meta.isAutoIncrement(i)) continue;
                            columns.add(i); names.add(meta.getColumnName(i));
                        }
                        String sql = "INSERT INTO " + table + " (" + String.join(",", names) + ") VALUES ("
                                + String.join(",", java.util.Collections.nCopies(names.size(), "?")) + ")";
                        try (var insert = target.prepareStatement(sql)) {
                            int batch = 0;
                            while (rows.next()) {
                                yagen.waitmydawn.kb.service.BuildTaskJournal.checkCancellation();
                                if (!BuildTableDelta.take(additions, rows)) continue;
                                for (int j = 0; j < columns.size(); j++) {
                                    int column = columns.get(j);
                                    if (meta.getColumnType(column) == Types.BLOB) insert.setBytes(j + 1, rows.getBytes(column));
                                    else if (meta.getColumnType(column) == Types.CLOB) insert.setString(j + 1, rows.getString(column));
                                    else insert.setObject(j + 1, rows.getObject(column));
                                }
                                insert.addBatch();
                                if (++batch % 100 == 0) insert.executeBatch();
                            }
                            insert.executeBatch();
                        }
                    }
                }
                for(String table:List.of("rag_public_page","rag_public_embedding")) {
                    boolean exists;try(var tables=target.getMetaData().getTables(null,null,table,null)){exists=tables.next();}
                    if(exists&&!invalidateUrls.isEmpty())try(var p=target.prepareStatement("DELETE FROM "+table+" WHERE "+(table.equals("rag_public_page")?"url":"source_url")+"=?")) {
                        for(String url:invalidateUrls){p.setString(1,url);p.addBatch();}p.executeBatch();
                    }
                }
                target.commit();
            } catch (Exception e) { target.rollback(); throw e; }
        } catch (Exception e) { throw new IllegalStateException("Build publication failed; previous database retained", e); }
    }

    public String buildGeneration() {
        try (var c = getConnection(); var s = c.createStatement(); var r = s.executeQuery("SELECT meta_value FROM rag_build_metadata WHERE name='generation'")) {
            return r.next() ? r.getString(1) : "legacy";
        } catch (SQLException e) { throw new IllegalStateException("Build metadata read failed", e); }
    }

    public String contentFingerprint() {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var c = getConnection()) {
                c.setAutoCommit(false); c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                for (String table : BUILD_TABLES) {
                    digest.update(table.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    String order = table.equals("rag_build_metadata") ? "name"
                            : table.equals("rag_web_category") ? "category_id"
                            : table.equals("rag_web_category_member") ? "category_id,item_url"
                            : table.equals("rag_chunk_image") ? "chunk_id,occurrence_id" : table.equals("rag_chunk_block")?"chunk_id,block_id"
                            : table.equals("rag_runtime_snapshot")?"source_id":table.equals("rag_runtime_record")?"profile_id,category,record_id":table.equals("rag_runtime_name")?"profile_id,item_id,language":"id";
                    try (var s = c.createStatement(); var rows = s.executeQuery("SELECT * FROM " + table + " ORDER BY " + order)) {
                        var metadata = rows.getMetaData();
                        while (rows.next()) for (int i = 1; i <= metadata.getColumnCount(); i++) {
                            byte[] bytes;
                            if (metadata.getColumnType(i) == Types.BLOB) bytes = rows.getBytes(i);
                            else { String value = rows.getString(i); bytes = value == null ? null : value.getBytes(java.nio.charset.StandardCharsets.UTF_8); }
                            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes == null ? -1 : bytes.length).array());
                            if (bytes != null) digest.update(bytes);
                        }
                    }
                }
                c.commit();
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) { throw new IllegalStateException("Database fingerprint failed", e); }
    }

    public void setBuildGeneration(String generation) {
        try (var c = getConnection(); var p = c.prepareStatement("MERGE INTO rag_build_metadata (name,meta_value) KEY(name) VALUES ('generation',?)")) {
            p.setString(1, generation); p.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException("Build metadata write failed", e); }
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

    public void shutdown() {
        try (var c = getConnection(); var s = c.createStatement()) { s.execute("SHUTDOWN"); }
        catch (SQLException e) { throw new IllegalStateException("Database shutdown failed", e); }
    }

    /**
     * 轻量健康检查（2026-09-15 事故后新增）：读取关键表的行数并触碰 LOB 列，
     * 用于在长任务开始前/运行中发现 MVStore 损坏（`Double mark` 等）。
     *
     * @throws SQLException 数据库不可读/已损坏时抛出
     */
    public void healthCheck() throws SQLException {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            for (String sql : new String[]{
                    "SELECT COUNT(*) FROM rag_mod_info",
                    "SELECT COUNT(*) FROM rag_recipe",
                    "SELECT COUNT(*) FROM rag_vectors",
                    "SELECT COUNT(*) FROM rag_web_cache",
                    "SELECT COUNT(*) FROM rag_web_category",
                    "SELECT COUNT(*) FROM rag_web_category_member",
                    "SELECT recipe_data FROM rag_recipe",
                    "SELECT embedding FROM rag_vectors",
                    "SELECT sub_web_page_json FROM rag_web_cache",
                    "SELECT path_json FROM rag_web_category"}) {
                try {
                    try (ResultSet rs = stmt.executeQuery(sql)) {
                        while (rs.next()) {
                            Object value = rs.getObject(1);
                            if (value instanceof Blob blob) { blob.getBytes(1, (int) blob.length()); blob.free(); }
                            else if (value instanceof Clob clob) { clob.getSubString(1, (int) clob.length()); clob.free(); }
                        }
                    }
                } catch (SQLException e) {
                    // 表尚未创建（空库/首次使用，如 rag_vectors 由 VectorStore 首次使用时建表）不算损坏
                    if (e.getErrorCode() == 42102 || String.valueOf(e.getMessage()).contains("not found")) {
                        continue;
                    }
                    throw e;
                }
            }
        }
    }

    /** 初始化数据库：创建所有表 */
    public void initDatabase() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            yagen.waitmydawn.kb.service.WebCatalogStore.init(conn);
            yagen.waitmydawn.kb.service.KnowledgeContentStore.init(conn);

            stmt.execute("CREATE TABLE IF NOT EXISTS rag_build_metadata (name VARCHAR(100) PRIMARY KEY, meta_value VARCHAR(1000))");
            stmt.execute("CREATE TABLE IF NOT EXISTS rag_vector_profile (id INT PRIMARY KEY, profile VARCHAR(1000) NOT NULL)");

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

            stmt.execute("ALTER TABLE rag_mod_info ADD COLUMN IF NOT EXISTS mcmod_binding CLOB");
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
            stmt.execute("ALTER TABLE rag_recipe ADD COLUMN IF NOT EXISTS recipe_id VARCHAR(500)");
            stmt.execute("ALTER TABLE rag_recipe ADD COLUMN IF NOT EXISTS raw_type VARCHAR(255)");
            stmt.execute("ALTER TABLE rag_recipe ADD COLUMN IF NOT EXISTS parse_status VARCHAR(50) DEFAULT 'LEGACY'");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_recipe_output ON rag_recipe(output_item)");

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

//            // 插入默认版本映射
//            initVersionMap(conn);

            log.info("Database initialized: {}", jdbcUrl);
        } catch (SQLException e) {
            log.error("Database init failed: {}", e.getMessage(), e);
            throw new RuntimeException("Database init failed", e);
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
        try (Connection conn = getConnection()) {
        Long existingId=null;
        // 未声明游戏版本的元数据也需要原位更新；MERGE 的复合 KEY 不匹配 NULL。
        try(var find=conn.prepareStatement("SELECT MIN(id) FROM rag_mod_info WHERE mod_id=? AND loader IS NOT DISTINCT FROM ? AND mc_version IS NOT DISTINCT FROM ?")){
            find.setString(1,entry.getModId());find.setString(2,entry.getLoader());find.setString(3,entry.getMcVersion());
            try(var row=find.executeQuery()){if(row.next()){long id=row.getLong(1);if(!row.wasNull())existingId=id;}}
        }
        String sql = """
            MERGE INTO rag_mod_info (mod_id, display_name, version, description, authors, license,
                logo_file, issue_tracker_url, loader, mc_version, slug, modrinth_url, source,
                jar_path, jar_file_name, mcmod_binding, updated_at%s)
            KEY (%s)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP%s)
            """.formatted(existingId==null?"":", id",existingId==null?"mod_id, loader, mc_version":"id",existingId==null?"":", ?");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
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
            ps.setString(16,yagen.waitmydawn.kb.service.ModBindingStore.encode(entry.getMcmodBinding()));
            if(existingId!=null)ps.setLong(17,existingId);
            ps.executeUpdate();
            log.debug("ModEntry saved: {}", entry.getModId());
        }
        } catch (SQLException e) {
            log.error("Save ModEntry failed: {}", e.getMessage(), e);
            throw new IllegalStateException("Save ModEntry failed", e);
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
            for (String table : yagen.waitmydawn.kb.service.KnowledgeContentStore.BUILD_TABLES)
                stmt.executeUpdate("DELETE FROM " + table);
            stmt.execute("DELETE FROM rag_vectors");
            stmt.execute("DELETE FROM rag_recipe");
            stmt.execute("DELETE FROM rag_texture_cache");
            stmt.execute("DELETE FROM rag_entity_registry");
            stmt.execute("DELETE FROM rag_web_cache");
            stmt.execute("DELETE FROM rag_web_category_member");
            stmt.execute("DELETE FROM rag_web_category");
            stmt.execute("DELETE FROM rag_vector_profile");
            stmt.execute("DELETE FROM rag_build_metadata WHERE name='icon_profile'");
            log.info("Build data cleared (overwrite mode)");
        } catch (SQLException e) {
            log.error("Clear build data failed", e);
            throw new IllegalStateException("Clear build data failed", e);
        }
    }

    /**
     * 保存爬取的网页列表 (含 subWebPage 映射) 到 rag_web_cache。
     * 用于后续 Q&A 阶段按 namespace 查找子网页 URL 进行增量抓取。
     */
    public void saveWebPages(String modId, List<? extends WebPageData> pages) {
        String sql = """
            INSERT INTO rag_web_cache (mod_id, title, url, content, source, sub_web_page_json)
            VALUES (?, ?, ?, ?, ?, ?)
            """;
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (WebPageData page : pages) {
                ps.setString(1, modId);
                ps.setString(2, page.title());
                ps.setString(3, page.url());
                ps.setString(4, page.content());
                ps.setString(5, page.source());
                // 序列化 subWebPage Map → JSON
                Object subMap = page.subWebPage();
                ps.setString(6, subMap != null ? jsonMapper.writeValueAsString(subMap) : "{}");
                ps.addBatch();
            }
            ps.executeBatch();
            log.info("Web cache saved: {} pages for modId={}", pages.size(), modId);
        } catch (Exception e) {
            log.error("Save WebPages failed: {}", e.getMessage(), e);
            throw new IllegalStateException("Save WebPages failed", e);
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
        for(int i=1;i<=rs.getMetaData().getColumnCount();i++)if(rs.getMetaData().getColumnName(i).equalsIgnoreCase("mcmod_binding"))e.setMcmodBinding(yagen.waitmydawn.kb.service.ModBindingStore.decode(rs.getString(i)));
        return e;
    }
}
