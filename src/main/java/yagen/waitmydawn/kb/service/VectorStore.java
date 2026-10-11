package yagen.waitmydawn.kb.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_vec_chunk ON rag_vectors(chunk_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_vec_source ON rag_vectors(source_path)");
            stmt.execute("CREATE TABLE IF NOT EXISTS rag_vector_profile (id INT PRIMARY KEY, profile VARCHAR(1000) NOT NULL)");
        } catch (SQLException e) {
            throw new IllegalStateException("Vector table init failed", e);
        }
    }

    /**
     * Store a single vector chunk.
     */
    public void store(TextChunker.TextChunk chunk, float[] embedding) {
        validate(embedding);
        String sql = "MERGE INTO rag_vectors (chunk_id, chunk_text, embedding, mod_id, mod_name, chunk_type, source_path) KEY(chunk_id) VALUES (?, ?, ?, ?, ?, ?, ?)";
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
            throw new IllegalStateException("Vector store failed", e);
        }
    }

    /**
     * Store multiple chunks with their embeddings.
     */
    public void storeBatch(List<TextChunker.TextChunk> chunks, EmbeddingService embedder) {
        if (chunks.isEmpty()) return;
        List<String> texts = chunks.stream().map(TextChunker.TextChunk::text).toList();
        List<float[]> vectors = embedder.embedBatch(texts);
        storeBatch(chunks, vectors, embedder.profile());
    }

    /** 已有公共向量可直接复制到会话库，保留会话检索范围而不重复推理。 */
    public void storeBatch(List<TextChunker.TextChunk> chunks, List<float[]> vectors, String profile) {
        if (chunks.size() != vectors.size()) throw new IllegalArgumentException("Chunk/vector count mismatch");
        if (chunks.isEmpty()) return;
        verifyProfile(profile, true);

        String sql = "MERGE INTO rag_vectors (chunk_id, chunk_text, embedding, mod_id, mod_name, chunk_type, source_path) KEY(chunk_id) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            conn.setAutoCommit(false);
            for (int i = 0; i < chunks.size(); i++) {
                validate(vectors.get(i));
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
            conn.commit();
            log.info("Stored {} vectors", chunks.size());
        } catch (SQLException e) {
            throw new IllegalStateException("Vector batch store failed", e);
        }
    }

    /** 按块身份加载已存向量；缺失数据是持久化故障，不能静默改为再次全文推理。 */
    public Map<String,float[]> loadEmbeddings(List<SearchResult> chunks, String profile) {
        verifyProfile(profile, false);
        var vectors = new HashMap<String,float[]>();
        var ids = chunks.stream().map(SearchResult::chunkId).distinct().toList();
        try (var c = db.getConnection()) {
            for (int offset=0;offset<ids.size();offset+=100) {
                var batch=ids.subList(offset,Math.min(ids.size(),offset+100));
                String sql="SELECT chunk_id,embedding FROM rag_vectors WHERE chunk_id IN ("+String.join(",",Collections.nCopies(batch.size(),"?"))+")";
                try(var p=c.prepareStatement(sql)) {
                    for(int i=0;i<batch.size();i++) p.setString(i+1,batch.get(i));
                    try(var r=p.executeQuery()) { while(r.next()) vectors.put(r.getString(1),deserialize(r.getBytes(2))); }
                }
            }
        } catch(SQLException e) { throw new IllegalStateException("Stored page embedding read failed",e); }
        if (vectors.size()!=ids.size()) throw new IllegalStateException("Stored page embeddings missing");
        RetrievalTelemetry.vectorHit(vectors.size());
        return vectors;
    }

    /**
     * 余弦相似度搜索, 返回 top_k 个最相关块。
     */
    public List<SearchResult> search(float[] queryVec, int topK) {
        return search(queryVec, topK, null);
    }

    public List<SearchResult> search(float[] queryVec, int topK, String modIdFilter) {
        if (topK <= 0) return List.of();
        var results = new java.util.PriorityQueue<SearchResult>(Comparator.comparingDouble(SearchResult::score));
        String sql = "SELECT chunk_id, chunk_text, embedding, mod_id, mod_name, chunk_type, source_path FROM rag_vectors";
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
                    if (!Double.isFinite(sim)) throw new IllegalStateException("Non-finite vector similarity");
                    if (results.size() == topK && sim <= results.peek().score()) continue;
                    results.add(new SearchResult(
                            rs.getString("chunk_id"), rs.getString("chunk_text"),
                            rs.getString("mod_id"), rs.getString("mod_name"),
                            rs.getString("chunk_type"), sim, rs.getString("source_path")));
                    if (results.size() > topK) results.poll();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Vector search failed", e);
        }

        var sorted = new ArrayList<>(results);
        sorted.sort(Comparator.comparingDouble(SearchResult::score).reversed());
        return sorted;
    }

    /**
     * 实体别名向量检索：在 chunk_type = 'entity_alias' 的中英对照块中查找匹配主体。
     * 别名块的 source_path 保存了对应的网页 URL，因此检索结果可直接驱动增量抓取。
     */
    public List<AliasResult> searchAliases(float[] queryVec, int topK, double minScore) {
        return searchAliases(queryVec, topK, minScore, null);
    }

    public List<AliasResult> searchAliases(float[] queryVec, int topK, double minScore,
                                           String modIdFilter) {
        List<AliasResult> results = new ArrayList<>();
        String sql = "SELECT chunk_text, mod_id, mod_name, source_path, embedding "
                + "FROM rag_vectors WHERE chunk_type = 'entity_alias'";
        if (modIdFilter != null && !modIdFilter.isBlank()) sql += " AND mod_id = ?";

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            // 必须在 executeQuery 之前设置参数（2026-09-07 修复：原实现先执行后设参，
            // 带 modId 过滤时抛出 Parameter "#1" is not set）。
            if (modIdFilter != null && !modIdFilter.isBlank()) ps.setString(1, modIdFilter);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    byte[] blob = rs.getBytes("embedding");
                    if (blob == null) continue;
                    float[] vec = deserialize(blob);
                    double sim = cosine(queryVec, vec);
                    if (sim < minScore) continue;
                    results.add(new AliasResult(
                            rs.getString("chunk_text"),
                            rs.getString("mod_id"),
                            rs.getString("mod_name"),
                            rs.getString("source_path"),
                            sim));
                }
            }
        } catch (SQLException e) {
            log.error("Alias vector search failed", e);
        }

        results.sort((a, b) -> Double.compare(b.score, a.score));
        return results.subList(0, Math.min(topK, results.size()));
    }

    /**
     * 向量阈值过高导致漏召回时的确定性兜底：直接按中文/英文字面包含关系查找别名。
     * 适合“贱叫怪”这种中文短词，即使本地 MiniLM 对中文相似度打分偏低也能命中。
     */
    public List<AliasResult> searchAliasesByText(String text, int topK) {
        return searchAliasesByText(text, topK, null);
    }

    public List<AliasResult> searchAliasesByText(String text, int topK, String modIdFilter) {
        if (text == null || text.isBlank()) return List.of();
        List<AliasResult> results = new ArrayList<>();
        String sql = "SELECT chunk_text, mod_id, mod_name, source_path FROM rag_vectors "
                + "WHERE chunk_type = 'entity_alias' "
                + "AND LOWER(CAST(chunk_text AS VARCHAR)) LIKE LOWER(?)";
        if (modIdFilter != null && !modIdFilter.isBlank()) sql += " AND mod_id = ?";
        sql += " ORDER BY chunk_text";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "%" + text + "%");
            if (modIdFilter != null && !modIdFilter.isBlank()) ps.setString(2, modIdFilter);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && results.size() < topK) {
                    results.add(new AliasResult(
                            rs.getString("chunk_text"),
                            rs.getString("mod_id"),
                            rs.getString("mod_name"),
                            rs.getString("source_path"),
                            1.0));
                }
            }
        } catch (SQLException e) {
            log.error("Alias text search failed", e);
        }
        return results;
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
            stmt.execute("DELETE FROM rag_vector_profile");
            log.info("Vectors cleared");
        } catch (SQLException ignored) {}
    }

    // --- serialization ---

    public void verifyProfile(String profile, boolean initialize) {
        verifyProfile(db, profile, initialize);
    }

    static void verifyProfile(DatabaseBuilder db, String profile, boolean initialize) {
        try (var c = db.getConnection(); var s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS rag_vector_profile (id INT PRIMARY KEY, profile VARCHAR(1000) NOT NULL)");
            String stored = null;
            try (var r = s.executeQuery("SELECT profile FROM rag_vector_profile WHERE id=1")) {
                if (r.next()) stored = r.getString(1);
            }
            if (stored != null && !stored.equals(profile))
                throw new IllegalStateException("向量模型不兼容，请使用原模型或重建知识库：" + db.getJdbcUrl());
            if (stored == null) {
                try (var r = s.executeQuery("SELECT COUNT(*) FROM rag_vectors")) {
                    r.next();
                    if (r.getLong(1) > 0)
                        throw new IllegalStateException("旧向量库没有模型指纹，请重建基础库或重新获取会话知识：" + db.getJdbcUrl());
                }
                if (initialize) try (var p = c.prepareStatement("INSERT INTO rag_vector_profile VALUES (1, ?)")) {
                    p.setString(1, profile); p.executeUpdate();
                }
            }
        } catch (SQLException e) { throw new IllegalStateException("Vector profile check failed", e); }
    }

    private void validate(float[] vector) {
        if (vector.length != dimension) throw new IllegalArgumentException("Unexpected embedding dimension: " + vector.length);
        for (float value : vector) if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite embedding");
    }

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
        if (a.length != b.length) throw new IllegalStateException("Embedding dimensions do not match");
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
        if (topK <= 0) return List.of();
        var best = new java.util.PriorityQueue<SearchResult>(Comparator.comparingDouble(SearchResult::score));
        for (DatabaseBuilder db : dbs) {
            if (db == null) continue;
            for (var hit : searchInDb(db, queryVec, topK)) {
                best.offer(hit);
                if (best.size() > topK) best.poll();
            }
        }
        var results = new ArrayList<>(best);
        results.sort(Comparator.comparingDouble(SearchResult::score).reversed());
        return results;
    }

    public static List<SearchResult> searchAcross(List<DatabaseBuilder> dbs, float[] queryVec, int topK, String profile) {
        for (var database : dbs) verifyProfile(database, profile, false);
        return searchAcross(dbs, queryVec, topK);
    }

    public static List<SearchResult> searchScoped(List<DatabaseBuilder> dbs,float[] query,int topK,String profile,String modId,Set<String> urls) {
        if (topK <= 0) return List.of();
        var all=new ArrayList<SearchResult>();
        for(var db:dbs) {
            verifyProfile(db,profile,false);
            all.addAll(searchInDb(db,query,topK,modId,urls));
        }
        return all.stream().sorted(Comparator.comparingDouble(SearchResult::score).reversed()).limit(topK).toList();
    }

    /** 保留每个数据库的最高分结果，限制正文对象的保留数量。 */
    private static List<SearchResult> searchInDb(DatabaseBuilder db, float[] queryVec, int topK) {
        return searchInDb(db,queryVec,topK,null,Set.of());
    }

    private static List<SearchResult> searchInDb(DatabaseBuilder db,float[] queryVec,int topK,String modId,Set<String> urls) {
        var results = new java.util.PriorityQueue<SearchResult>(Comparator.comparingDouble(SearchResult::score));
        String sql = "SELECT chunk_id, chunk_text, embedding, mod_id, mod_name, chunk_type, source_path FROM rag_vectors WHERE chunk_type <> 'entity_alias'";
        if(modId!=null) sql+=" AND mod_id=?";
        if(!urls.isEmpty()) sql+=" AND source_path IN ("+String.join(",",Collections.nCopies(urls.size(),"?"))+")";

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int parameter=1;
            if(modId!=null) ps.setString(parameter++,modId);
            for(String url:urls) ps.setString(parameter++,url);
            try(ResultSet rs=ps.executeQuery()) {
            while (rs.next()) {
                byte[] blob = rs.getBytes("embedding");
                if (blob == null) continue;
                float[] vec = deserializeStatic(blob);
                double sim = cosineStatic(queryVec, vec);
                if (!Double.isFinite(sim)) throw new IllegalStateException("Non-finite vector similarity");
                if (results.size() == topK && sim <= results.peek().score()) continue;
                results.add(new SearchResult(
                        rs.getString("chunk_id"), rs.getString("chunk_text"),
                        rs.getString("mod_id"), rs.getString("mod_name"),
                        rs.getString("chunk_type"), sim, rs.getString("source_path")));
                if (results.size() > topK) results.poll();
            }
            }
        } catch (SQLException e) { throw new IllegalStateException("Vector search failed", e); }
        return new ArrayList<>(results);
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
        if (a.length != b.length) throw new IllegalStateException("Embedding dimensions do not match");
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

    public record SearchResult(String chunkId, String chunkText, String modId, String modName, String type, double score, String sourcePath) {
        public SearchResult(String chunkId, String chunkText, String modId, String modName, String type, double score) {
            this(chunkId, chunkText, modId, modName, type, score, "");
        }
    }

    /** 中英对照别名块的检索结果：label 对应 source_path 保存的网页 URL。 */
    public record AliasResult(String label, String modId, String modName, String url, double score) {}
}
