package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.*;

/** 公共网页快照与模型向量跨会话复用；不包含聊天记录，且不参与基础库发布事务。 */
public final class PublicKnowledgeCache {
    private static final ObjectMapper JSON = new ObjectMapper();
    // 正文提取规则改变时旧快照必须失效，避免延续评论污染或旧分块语义。
    private static final int EXTRACTION_VERSION = 4;
    private static final long TTL = Duration.ofDays(7).toMillis();
    private final DatabaseBuilder db;
    private final Clock clock;
    public PublicKnowledgeCache(DatabaseBuilder db) { this(db, Clock.systemUTC()); }
    PublicKnowledgeCache(DatabaseBuilder db, Clock clock) {
        this.db = db; this.clock = clock;
        try (var c = db.getConnection(); var s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS rag_public_page (url VARCHAR(1000) PRIMARY KEY, payload CLOB NOT NULL, fetched_at BIGINT NOT NULL, extraction_version INT NOT NULL)");
            s.execute("ALTER TABLE rag_public_page ADD COLUMN IF NOT EXISTS etag VARCHAR(2000)");
            s.execute("ALTER TABLE rag_public_page ADD COLUMN IF NOT EXISTS last_modified VARCHAR(2000)");
            s.execute("CREATE TABLE IF NOT EXISTS rag_public_embedding (chunk_id VARCHAR(500) NOT NULL, profile VARCHAR(1000) NOT NULL, embedding BLOB NOT NULL, source_url VARCHAR(1000), PRIMARY KEY(chunk_id, profile))");
            s.execute("ALTER TABLE rag_public_embedding ADD COLUMN IF NOT EXISTS source_url VARCHAR(1000)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_public_embedding_source ON rag_public_embedding(source_url)");
        } catch (SQLException e) { throw new IllegalStateException("Public knowledge cache init failed", e); }
    }
    public WikiScraperService.FetchedPage find(String url) {
        var snapshot=snapshot(url);
        if(snapshot==null || clock.millis()-snapshot.fetchedAt()>=TTL) return null;
        RetrievalTelemetry.pageHit(true);
        return snapshot.page();
    }
    public record Snapshot(WikiScraperService.FetchedPage page,long fetchedAt,String etag,String lastModified) {}
    /** 过期快照仅用于条件请求，不能无提示地当成当前机制事实。 */
    public Snapshot snapshot(String url) {
        try (var c = db.getConnection(); var p = c.prepareStatement("SELECT payload,fetched_at,extraction_version,etag,last_modified FROM rag_public_page WHERE url=?")) {
            p.setString(1,url);
            try (var r = p.executeQuery()) {
                if (!r.next() || r.getInt(3) != EXTRACTION_VERSION) return null;
                var page = JSON.readValue(r.getString(1), WikiScraperService.FetchedPage.class);
                return new Snapshot(page,r.getLong(2),r.getString(4),r.getString(5));
            }
        } catch (Exception e) { throw new IllegalStateException("Public page cache read failed", e); }
    }
    public void put(String requestedUrl, WikiScraperService.FetchedPage page) {
        put(requestedUrl,page,null,null);
    }
    public void put(String requestedUrl, WikiScraperService.FetchedPage page,String etag,String lastModified) {
        if (page == null || page.page() == null || page.page().content().isBlank()) return;
        try (var c = db.getConnection(); var p = c.prepareStatement("MERGE INTO rag_public_page (url,payload,fetched_at,extraction_version,etag,last_modified) KEY(url) VALUES (?,?,?,?,?,?)")) {
            p.setString(1,requestedUrl); p.setString(2,JSON.writeValueAsString(page)); p.setLong(3,clock.millis()); p.setInt(4,EXTRACTION_VERSION); p.setString(5,etag); p.setString(6,lastModified); p.executeUpdate();
            // 淘汰最旧快照以及对应的详情向量，防止长期问答让公共缓存无限增长。
            try(var s=c.createStatement(); var r=s.executeQuery("SELECT url FROM rag_public_page ORDER BY fetched_at DESC OFFSET 512 ROWS")) {
                var expired=new ArrayList<String>(); while(r.next()) expired.add(r.getString(1));
                try(var pages=c.prepareStatement("DELETE FROM rag_public_page WHERE url=?"); var vectors=c.prepareStatement("DELETE FROM rag_public_embedding WHERE source_url=?")) {
                    for(String url:expired) { pages.setString(1,url); pages.addBatch(); vectors.setString(1,url); vectors.addBatch(); }
                    pages.executeBatch(); vectors.executeBatch();
                }
            }
        } catch (Exception e) { throw new IllegalStateException("Public page cache write failed", e); }
    }
    /** 内容哈希属于 chunk_id；模型指纹属于联合键，防止模型切换后复用错误向量。 */
    public List<float[]> embeddings(List<TextChunker.TextChunk> chunks, EmbeddingService embedder) {
        var vectors = new ArrayList<float[]>();
        var missing = new ArrayList<Integer>();
        String profile = embedder.profile();
        try (var c = db.getConnection(); var p = c.prepareStatement("SELECT embedding FROM rag_public_embedding WHERE chunk_id=? AND profile=?")) {
            for (int i=0;i<chunks.size();i++) {
                p.setString(1,chunks.get(i).id()); p.setString(2,profile);
                try (var r = p.executeQuery()) {
                    if (r.next()) {
                        var b = ByteBuffer.wrap(r.getBytes(1)); var v = new float[b.remaining()/Float.BYTES];
                        for (int j=0;j<v.length;j++) v[j]=b.getFloat();
                        if (v.length != embedder.getDimension()) throw new IllegalStateException("Cached embedding dimension mismatch");
                        vectors.add(v);
                    } else { vectors.add(null); missing.add(i); }
                }
            }
            RetrievalTelemetry.vectorHit(chunks.size()-missing.size());
            var computed = embedder.embedBatch(missing.stream().map(i -> chunks.get(i).text()).toList());
            c.setAutoCommit(false);
            try (var insert = c.prepareStatement("MERGE INTO rag_public_embedding (chunk_id,profile,embedding,source_url) KEY(chunk_id,profile) VALUES (?,?,?,?)")) {
                for (int j=0;j<missing.size();j++) {
                    int i=missing.get(j); float[] v=computed.get(j); vectors.set(i,v);
                    var b=ByteBuffer.allocate(v.length*Float.BYTES); for(float value:v) b.putFloat(value);
                    insert.setString(1,chunks.get(i).id()); insert.setString(2,profile); insert.setBytes(3,b.array()); insert.setString(4,chunks.get(i).sourcePath()); insert.addBatch();
                }
                insert.executeBatch(); c.commit();
            }
            return vectors;
        } catch (SQLException e) { throw new IllegalStateException("Public embedding cache failed", e); }
    }
}
