package yagen.waitmydawn.kb.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yagen.waitmydawn.kb.model.DatabaseBuilder;

import yagen.waitmydawn.kb.model.DatabaseBuilder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.*;
import java.util.*;

/**
 * 基于 H2 的嵌入式向量数据库。
 * 向量存储为 BLOB (float[] 序列化), 检索时使用暴力余弦相似度。
 * 适用于 <100k 向量的桌面场景。
 */
public class VectorStore {

    private static final Logger log = LoggerFactory.getLogger(VectorStore.class);
    private final DatabaseBuilder db;
    private final int dimension;

    public VectorStore(DatabaseBuilder db, int dimension) {
        this.db = db;
        this.dimension = dimension;
        initTable();
    }

    private void initTable() {
        try (Connection conn = db.getConnection(); Statement stmt = conn.createStatement()) {
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
            // Index on mod_id for filtering
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_vec_mod ON rag_vectors(mod_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_vec_type ON rag_vectors(chunk_type)");
        } catch (SQLException e) {
            log.error("Vector table init failed", e);
        }
    }

    /**
     * Store a single vector chunk.
     */
    public void store(TextChunker.TextChunk chunk, float[] embedding) {
        String sql = "INSERT INTO rag_vectors (chunk_id, chunk_text, embedding, mod_id, mod_name, chunk_type, source_path) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, chunk.id());
            ps.setString(2, chunk.text());
            ps.setBytes(3, serialize(embedding));
            ps.setString(4, chunk.modId());
            ps.setString(5, chunk.modName());
            ps.setString(6, chunk.type());
            ps.setString(7, chunk.sourcePath());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Vector store failed", e);
        }
    }

    /**
     * Store multiple chunks with their embeddings.
     */
    public void storeBatch(List<TextChunker.TextChunk> chunks, EmbeddingService embedder) {
        if (chunks.isEmpty()) return;
        List<String> texts = chunks.stream().map(TextChunker.TextChunk::text).toList();
        List<float[]> vectors = embedder.embedBatch(texts);

        String sql = "INSERT INTO rag_vectors (chunk_id, chunk_text, embedding, mod_id, mod_name, chunk_type, source_path) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < chunks.size(); i++) {
                TextChunker.TextChunk chunk = chunks.get(i);
                ps.setString(1, chunk.id());
                ps.setString(2, chunk.text());
                ps.setBytes(3, serialize(vectors.get(i)));
                ps.setString(4, chunk.modId());
                ps.setString(5, chunk.modName());
                ps.setString(6, chunk.type());
                ps.setString(7, chunk.sourcePath());
                ps.addBatch();
            }
            ps.executeBatch();
            log.info("Stored {} vectors", chunks.size());
        } catch (SQLException e) {
            log.error("Vector batch store failed", e);
        }
    }

    /**
     * 余弦相似度搜索, 返回 top_k 个最相关块。
     */
    public List<SearchResult> search(float[] queryVec, int topK) {
        return search(queryVec, topK, null);
    }

    public List<SearchResult> search(float[] queryVec, int topK, String modIdFilter) {
        List<SearchResult> results = new ArrayList<>();
        String sql = "SELECT chunk_id, chunk_text, embedding, mod_id, mod_name, chunk_type FROM rag_vectors";
        if (modIdFilter != null) sql += " WHERE mod_id = ?";

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (modIdFilter != null) ps.setString(1, modIdFilter);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    byte[] blob = rs.getBytes("embedding");
                    if (blob == null) continue;
                    float[] vec = deserialize(blob);
                    double sim = cosine(queryVec, vec);
                    results.add(new SearchResult(
                            rs.getString("chunk_id"), rs.getString("chunk_text"),
                            rs.getString("mod_id"), rs.getString("mod_name"),
                            rs.getString("chunk_type"), sim));
                }
            }
        } catch (SQLException e) {
            log.error("Vector search failed", e);
        }

        results.sort((a, b) -> Double.compare(b.score, a.score));
        return results.subList(0, Math.min(topK, results.size()));
    }

    /** 获取向量总数 */
    public int count() {
        try (Connection conn = db.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM rag_vectors")) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException ignored) {}
        return 0;
    }

    /** 清空向量 (重建知识库时) */
    public void clear() {
        try (Connection conn = db.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM rag_vectors");
            log.info("Vectors cleared");
        } catch (SQLException ignored) {}
    }

    // --- serialization ---

    private byte[] serialize(float[] vec) {
        ByteBuffer buf = ByteBuffer.allocate(vec.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : vec) buf.putFloat(v);
        return buf.array();
    }

    private float[] deserialize(byte[] blob) {
        float[] vec = new float[blob.length / 4];
        ByteBuffer buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < vec.length; i++) vec[i] = buf.getFloat();
        return vec;
    }

    private double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        int minLen = Math.min(a.length, b.length);
        for (int i = 0; i < minLen; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    // ==================== Multi-DB search ====================

    /**
     * Search across multiple databases (base + incremental), merging and re-ranking results.
     * This ensures both base and incremental DBs are searched together for maximum recall.
     */
    public static List<SearchResult> searchAcross(List<DatabaseBuilder> dbs, float[] queryVec, int topK) {
        List<SearchResult> all = new ArrayList<>();
        for (DatabaseBuilder db : dbs) {
            if (db == null) continue;
            all.addAll(searchInDb(db, queryVec));
        }
        all.sort((a, b) -> Double.compare(b.score, a.score));
        return all.subList(0, Math.min(topK, all.size()));
    }

    /** Search a single DB and return raw results (no top-K cutoff) */
    private static List<SearchResult> searchInDb(DatabaseBuilder db, float[] queryVec) {
        List<SearchResult> results = new ArrayList<>();
        String sql = "SELECT chunk_id, chunk_text, embedding, mod_id, mod_name, chunk_type FROM rag_vectors";

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                byte[] blob = rs.getBytes("embedding");
                if (blob == null) continue;
                float[] vec = deserializeStatic(blob);
                double sim = cosineStatic(queryVec, vec);
                results.add(new SearchResult(
                        rs.getString("chunk_id"), rs.getString("chunk_text"),
                        rs.getString("mod_id"), rs.getString("mod_name"),
                        rs.getString("chunk_type"), sim));
            }
        } catch (SQLException e) {
            // DB might not have rag_vectors table yet — that's fine
        }
        return results;
    }

    private static byte[] serializeStatic(float[] vec) {
        ByteBuffer buf = ByteBuffer.allocate(vec.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : vec) buf.putFloat(v);
        return buf.array();
    }

    private static float[] deserializeStatic(byte[] blob) {
        float[] vec = new float[blob.length / 4];
        ByteBuffer buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < vec.length; i++) vec[i] = buf.getFloat();
        return vec;
    }

    private static double cosineStatic(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        int minLen = Math.min(a.length, b.length);
        for (int i = 0; i < minLen; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    public record SearchResult(String chunkId, String chunkText, String modId, String modName, String type, double score) {}
}
