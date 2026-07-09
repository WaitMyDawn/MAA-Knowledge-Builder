package yagen.waitmydawn.kb.service;

import yagen.waitmydawn.kb.model.DatabaseBuilder;

import java.nio.file.Path;

/**
 * 增量数据库 — 独立于基础 rag_data.db 的 H2 文件。
 * 命名: rag_data_{sessionId} (基础 DB 名称 + 对话记录 ID)
 * 用户可清理不需要的增量数据库，防止 DB 无限增大。
 */
public class IncrementalDB implements AutoCloseable {

    private final String id;
    private final Path dbFile;
    private final VectorStore vectorStore;
    private final DatabaseBuilder db;

    public IncrementalDB(Path storageDir, String dbName, int embeddingDim) {
        this.id = dbName;
        // Strip .mv.db if present in name
        String cleanName = dbName.replace(".mv.db", "");
        this.dbFile = storageDir.resolve(cleanName);

        this.db = new DatabaseBuilder(null) {
            @Override
            public String getJdbcUrl() {
                return "jdbc:h2:file:" + dbFile.toString().replace('\\', '/')
                        + ";DATABASE_TO_LOWER=TRUE";
            }
        };
        this.db.initIncDatabase();

        this.vectorStore = new VectorStore(this.db, embeddingDim);
    }

    public String getId() { return id; }
    public Path getDbFile() { return dbFile; }
    public VectorStore getVectorStore() { return vectorStore; }

    /** Expose underlying DatabaseBuilder for multi-DB search */
    public DatabaseBuilder getDb() { return db; }

    public void close() {
        try { vectorStore.clear(); } catch (Exception ignored) {}
    }

    @Override
    public String toString() { return id + " (" + dbFile.getFileName() + ")"; }
}
