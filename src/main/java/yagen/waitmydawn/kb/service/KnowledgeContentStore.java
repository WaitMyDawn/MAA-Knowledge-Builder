package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import yagen.waitmydawn.kb.model.BuildRequest;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import yagen.waitmydawn.kb.model.KnowledgeContent.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** 基础库内容与图文关联；构建输入草稿独立于已发布的来源清单。 */
public final class KnowledgeContentStore {
    public static final List<String> BUILD_TABLES = List.of("rag_knowledge_source", "rag_document",
            "rag_content_chunk", "rag_image_asset", "rag_image_occurrence", "rag_chunk_image",
            "rag_document_source", "rag_web_image_reference", "rag_document_import", "rag_jar_import","rag_content_block","rag_chunk_block","rag_item_icon_profile","rag_item_icon",
            "rag_item_tag_file","rag_item_name","rag_item_static_icon","rag_recipe_resource_import","rag_image_analysis","rag_image_grounding","rag_source_state","rag_jar_source","rag_source_event","rag_document_parser","rag_jar_parser");
    private final DatabaseBuilder db;
    private static final ObjectMapper JSON = new ObjectMapper();

    public KnowledgeContentStore(DatabaseBuilder db) { this.db = db; }

    public static void init(Connection connection) throws SQLException {
        ItemIconStore.init(connection);
        RecipeResourceStore.init(connection);
        GuideImageIndex.init(connection);
        SourceLifecycleStore.init(connection);
        ModBindingStore.init(connection);
        ScanEnvironmentStore.init(connection);
        try (var s = connection.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS rag_jar_import (id VARCHAR(255) PRIMARY KEY, content_hash VARCHAR(64) NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS rag_content_block(id VARCHAR(500) PRIMARY KEY,document_id VARCHAR(200) NOT NULL,heading_path VARCHAR(2000),lineage VARCHAR(2000),ordinal INT NOT NULL,page_number INT,kind VARCHAR(40),table_id VARCHAR(200),content CLOB NOT NULL,extraction_method VARCHAR(40))");
            s.execute("CREATE TABLE IF NOT EXISTS rag_chunk_block(chunk_id VARCHAR(500) NOT NULL,block_id VARCHAR(500) NOT NULL,PRIMARY KEY(chunk_id,block_id))");
            s.execute("CREATE INDEX IF NOT EXISTS idx_block_document ON rag_content_block(document_id,ordinal)");
            s.execute("CREATE TABLE IF NOT EXISTS rag_build_draft (id INT PRIMARY KEY, request_json CLOB NOT NULL)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS rag_knowledge_source (
                      id VARCHAR(64) PRIMARY KEY, kind VARCHAR(40) NOT NULL, location VARCHAR(4000) NOT NULL)
                    """);
            s.execute("""
                    CREATE TABLE IF NOT EXISTS rag_document (
                      id VARCHAR(200) PRIMARY KEY, source_id VARCHAR(64) NOT NULL,
                      title VARCHAR(2000) NOT NULL, location VARCHAR(4000) NOT NULL,
                      content_hash VARCHAR(64) NOT NULL, media_type VARCHAR(200) NOT NULL)
                    """);
            s.execute("""
                    CREATE TABLE IF NOT EXISTS rag_content_chunk (
                      id VARCHAR(500) PRIMARY KEY, document_id VARCHAR(200) NOT NULL, content CLOB NOT NULL,
                      heading VARCHAR(2000), page_number INT, ordinal INT NOT NULL)
                    """);
            s.execute("""
                    CREATE TABLE IF NOT EXISTS rag_image_asset (
                      id VARCHAR(64) PRIMARY KEY, relative_path VARCHAR(1000) NOT NULL,
                      media_type VARCHAR(200) NOT NULL, width INT NOT NULL CHECK(width > 0),
                      height INT NOT NULL CHECK(height > 0), ocr_text CLOB, description CLOB)
                    """);
            s.execute("""
                    CREATE TABLE IF NOT EXISTS rag_image_occurrence (
                      id VARCHAR(200) PRIMARY KEY, document_id VARCHAR(200) NOT NULL,
                      image_id VARCHAR(64) NOT NULL, caption CLOB, original_location VARCHAR(4000),
                      page_number INT, ordinal INT NOT NULL)
                    """);
            s.execute("""
                    CREATE TABLE IF NOT EXISTS rag_chunk_image (
                      chunk_id VARCHAR(500) NOT NULL, occurrence_id VARCHAR(200) NOT NULL,
                      relation VARCHAR(200) NOT NULL, PRIMARY KEY(chunk_id, occurrence_id))
                    """);
            s.execute("CREATE INDEX IF NOT EXISTS idx_document_source ON rag_document(source_id)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_chunk_document ON rag_content_chunk(document_id)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_occurrence_document ON rag_image_occurrence(document_id)");
            s.execute("ALTER TABLE rag_knowledge_source ADD COLUMN IF NOT EXISTS scope_id VARCHAR(200)");
            s.execute("ALTER TABLE rag_knowledge_source ADD COLUMN IF NOT EXISTS scope_kind VARCHAR(40)");
            s.execute("ALTER TABLE rag_knowledge_source ADD COLUMN IF NOT EXISTS display_name VARCHAR(2000)");
            s.execute("ALTER TABLE rag_knowledge_source ADD COLUMN IF NOT EXISTS mod_id VARCHAR(255)");
            s.execute("ALTER TABLE rag_document ADD COLUMN IF NOT EXISTS author_text VARCHAR(2000)");
            s.execute("ALTER TABLE rag_document ADD COLUMN IF NOT EXISTS published_at_text VARCHAR(2000)");
            s.execute("ALTER TABLE rag_content_chunk ADD COLUMN IF NOT EXISTS extraction_method VARCHAR(40)");
            s.execute("ALTER TABLE rag_image_occurrence ADD COLUMN IF NOT EXISTS role VARCHAR(40)");
            s.execute("ALTER TABLE rag_image_occurrence ADD COLUMN IF NOT EXISTS position_text VARCHAR(1000)");
            s.execute("ALTER TABLE rag_image_occurrence ADD COLUMN IF NOT EXISTS bounds_json VARCHAR(1000)");
            s.execute("ALTER TABLE rag_image_occurrence ADD COLUMN IF NOT EXISTS analysis_status VARCHAR(200)");
            s.execute("CREATE TABLE IF NOT EXISTS rag_document_import(id VARCHAR(64) PRIMARY KEY, source_id VARCHAR(64) NOT NULL, location VARCHAR(4000) NOT NULL, format VARCHAR(40), status VARCHAR(40) NOT NULL, message VARCHAR(2000), document_id VARCHAR(200))");
            s.execute("CREATE TABLE IF NOT EXISTS rag_document_source(id VARCHAR(64) PRIMARY KEY, document_id VARCHAR(200) NOT NULL, source_id VARCHAR(64) NOT NULL)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS rag_web_image_reference(
                      id VARCHAR(200) PRIMARY KEY, document_id VARCHAR(200) NOT NULL, url VARCHAR(4000) NOT NULL,
                      caption CLOB, heading VARCHAR(2000), block_ordinal INT NOT NULL, status VARCHAR(40) NOT NULL, error_text VARCHAR(2000))
                    """);
            s.execute("ALTER TABLE rag_web_image_reference ADD COLUMN IF NOT EXISTS step_text CLOB");
        }
    }

    public void saveDraft(BuildRequest request) {
        try { execute("MERGE INTO rag_build_draft(id,request_json) KEY(id) VALUES(1,?)", JSON.writeValueAsString(request)); }
        catch (java.io.IOException e) { throw new IllegalStateException("构建来源序列化失败", e); }
    }

    public Optional<BuildRequest> draft() {
        try (var c = db.getConnection(); var s = c.createStatement();
             var r = s.executeQuery("SELECT request_json FROM rag_build_draft WHERE id=1")) {
            return r.next() ? Optional.of(JSON.readValue(r.getString(1), BuildRequest.class)) : Optional.empty();
        } catch (Exception e) { throw new IllegalStateException("构建来源草稿读取失败", e); }
    }

    public Optional<BuildRequest> publishedRequest() {
        try (var c = db.getConnection(); var s = c.createStatement();
             var r = s.executeQuery("SELECT id,kind,location FROM rag_knowledge_source ORDER BY id")) {
            var sources = new java.util.ArrayList<BuildRequest.Source>();
            while (r.next()) sources.add(new BuildRequest.Source(r.getString(1),
                    BuildRequest.Kind.valueOf(r.getString(2)), r.getString(3)));
            return sources.isEmpty() ? Optional.empty() : Optional.of(new BuildRequest(sources));
        } catch (SQLException e) { throw new IllegalStateException("已发布来源读取失败", e); }
    }

    public void saveSource(BuildRequest.Source s) {
        execute("MERGE INTO rag_knowledge_source(id,kind,location) KEY(id) VALUES(?,?,?)", s.id(), s.kind().name(), s.location());
    }
    public void setSourceScope(String sourceId, String scopeId, String kind, String name, String modId) {
        execute("UPDATE rag_knowledge_source SET scope_id=?,scope_kind=?,display_name=?,mod_id=? WHERE id=?", scopeId, kind, name, modId, sourceId);
    }
    public void linkDocumentSource(String documentId, String sourceId) {
        execute("MERGE INTO rag_document_source(id,document_id,source_id) KEY(id) VALUES(?,?,?)",
                BuildRequest.fingerprint(documentId + "\n" + sourceId), documentId, sourceId);
    }
    public void setDocumentMetadata(String documentId, String author, String publishedAt) {
        execute("UPDATE rag_document SET author_text=?,published_at_text=? WHERE id=?", author, publishedAt, documentId);
    }
    public void saveImageReference(String id, String documentId, String url, String caption, String heading,
                                   int ordinal, String status, String error) {
        execute("MERGE INTO rag_web_image_reference(id,document_id,url,caption,heading,block_ordinal,status,error_text) KEY(id) VALUES(?,?,?,?,?,?,?,?)",
                id, documentId, url, caption, heading, ordinal, status, error);
    }
    public boolean hasDocument(String documentId) {
        try (var c = db.getConnection(); var p = c.prepareStatement("SELECT 1 FROM rag_document WHERE id=?")) {
            p.setString(1, documentId); try (var r = p.executeQuery()) { return r.next(); }
        } catch (SQLException e) { throw new IllegalStateException("文档查询失败", e); }
    }
    public String documentHash(String documentId) {
        try (var c = db.getConnection(); var p = c.prepareStatement("SELECT content_hash FROM rag_document WHERE id=?")) {
            p.setString(1, documentId);
            try (var r = p.executeQuery()) { return r.next() ? r.getString(1) : null; }
        } catch (SQLException e) { throw new IllegalStateException("文档指纹读取失败", e); }
    }
    public void saveDocument(Document d) {
        execute("MERGE INTO rag_document(id,source_id,title,location,content_hash,media_type) KEY(id) VALUES(?,?,?,?,?,?)",
                d.id(), d.sourceId(), d.title(), d.location(), d.contentHash(), d.mediaType());
    }
    public void saveChunk(Chunk c) {
        execute("MERGE INTO rag_content_chunk(id,document_id,content,heading,page_number,ordinal) KEY(id) VALUES(?,?,?,?,?,?)",
                c.id(), c.documentId(), c.text(), c.heading(), c.pageNumber(), c.ordinal());
    }
    public void saveBlock(Block b) {
        execute("MERGE INTO rag_content_block KEY(id) VALUES(?,?,?,?,?,?,?,?,?,?)",b.id(),b.documentId(),b.headingPath(),b.lineage(),b.ordinal(),b.pageNumber(),b.kind(),b.tableId(),b.text(),b.method());
    }
    public void linkBlock(String chunkId,String blockId) {
        execute("MERGE INTO rag_chunk_block KEY(chunk_id,block_id) VALUES(?,?)",chunkId,blockId);
    }
    public void saveImage(ImageAsset i) {
        execute("""
                MERGE INTO rag_image_asset t USING (VALUES(?,?,?,?,?,?,?)) s(id,path,type,w,h,ocr,description) ON t.id=s.id
                WHEN MATCHED THEN UPDATE SET relative_path=s.path,media_type=s.type,width=s.w,height=s.h,
                  ocr_text=COALESCE(s.ocr,t.ocr_text),description=COALESCE(s.description,t.description)
                WHEN NOT MATCHED THEN INSERT(id,relative_path,media_type,width,height,ocr_text,description)
                  VALUES(s.id,s.path,s.type,s.w,s.h,s.ocr,s.description)
                """,
                i.id(), i.relativePath(), i.mediaType(), i.width(), i.height(), i.ocrText(), i.description());
    }
    public void setImageStep(String occurrence,String step){execute("UPDATE rag_web_image_reference SET step_text=? WHERE id=?",step,occurrence);}
    public void setChunkMethod(String id, String method) {
        execute("UPDATE rag_content_chunk SET extraction_method=? WHERE id=?", method, id);
    }
    public void setOccurrenceMetadata(String id, String role, String position, String bounds, String status) {
        execute("UPDATE rag_image_occurrence SET role=?,position_text=?,bounds_json=?,analysis_status=? WHERE id=?", role, position, bounds, status, id);
    }
    public void saveImportReport(String sourceId, String location, String format, String status, String message, String documentId) {
        execute("MERGE INTO rag_document_import(id,source_id,location,format,status,message,document_id) KEY(id) VALUES(?,?,?,?,?,?,?)",
                BuildRequest.fingerprint(sourceId + "\n" + location), sourceId, location, format, status, message, documentId);
    }
    public void saveOccurrence(ImageOccurrence i) {
        execute("MERGE INTO rag_image_occurrence(id,document_id,image_id,caption,original_location,page_number,ordinal) KEY(id) VALUES(?,?,?,?,?,?,?)",
                i.id(), i.documentId(), i.imageId(), i.caption(), i.originalLocation(), i.pageNumber(), i.ordinal());
    }
    public void saveLink(ChunkImageLink l) {
        execute("MERGE INTO rag_chunk_image(chunk_id,occurrence_id,relation) KEY(chunk_id,occurrence_id) VALUES(?,?,?)",
                l.chunkId(), l.occurrenceId(), l.relation());
    }

    /** 发布前检查引用，避免把暂存期间尚未补全的内容关系发布给问答。 */
    public void validate() {
        var queries = List.of(
                "SELECT COUNT(*) FROM rag_source_state x LEFT JOIN rag_knowledge_source s ON s.id=x.id WHERE s.id IS NULL",
                "SELECT COUNT(*) FROM rag_document_parser x LEFT JOIN rag_document d ON d.id=x.id WHERE d.id IS NULL",
                "SELECT COUNT(*) FROM rag_jar_parser x LEFT JOIN rag_jar_import j ON j.id=x.id WHERE j.id IS NULL",
                "SELECT COUNT(*) FROM rag_jar_source x LEFT JOIN rag_knowledge_source s ON s.id=x.source_id LEFT JOIN rag_jar_import j ON j.id=x.mod_id WHERE s.id IS NULL OR j.id IS NULL",
                "SELECT COUNT(*) FROM rag_image_analysis x LEFT JOIN rag_image_asset a ON a.id=x.id WHERE a.id IS NULL",
                "SELECT COUNT(*) FROM rag_image_grounding g LEFT JOIN rag_image_occurrence o ON o.id=g.id LEFT JOIN rag_content_block b ON b.id=g.block_id WHERE o.id IS NULL OR o.image_id<>g.image_id OR (g.block_id IS NOT NULL AND (b.id IS NULL OR b.document_id<>o.document_id))",
                "SELECT COUNT(*) FROM rag_item_static_icon i LEFT JOIN rag_image_asset a ON a.id=i.image_id WHERE i.status='FLAT_MODEL' AND a.id IS NULL",
                "SELECT COUNT(*) FROM rag_item_icon_profile p LEFT JOIN rag_knowledge_source s ON s.id=p.source_id WHERE s.id IS NULL",
                "SELECT COUNT(*) FROM rag_item_icon i LEFT JOIN rag_item_icon_profile p ON p.id=i.profile_id LEFT JOIN rag_image_asset a ON a.id=i.image_id WHERE p.id IS NULL OR (i.status IN ('OK','TRANSPARENT') AND a.id IS NULL) OR (i.status NOT IN ('OK','TRANSPARENT') AND i.image_id IS NOT NULL)",
                "SELECT COUNT(*) FROM rag_content_block b LEFT JOIN rag_document d ON d.id=b.document_id WHERE d.id IS NULL",
                "SELECT COUNT(*) FROM rag_content_block WHERE heading_path IS NULL OR lineage IS NULL OR kind IS NULL OR extraction_method IS NULL OR ordinal<0",
                "SELECT COUNT(*) FROM rag_chunk_block l LEFT JOIN rag_content_chunk c ON c.id=l.chunk_id LEFT JOIN rag_content_block b ON b.id=l.block_id WHERE c.id IS NULL OR b.id IS NULL OR c.document_id<>b.document_id",
                "SELECT COUNT(*) FROM rag_document d LEFT JOIN rag_knowledge_source s ON d.source_id=s.id WHERE s.id IS NULL",
                "SELECT COUNT(*) FROM rag_content_chunk c LEFT JOIN rag_document d ON c.document_id=d.id WHERE d.id IS NULL",
                "SELECT COUNT(*) FROM rag_image_occurrence o LEFT JOIN rag_document d ON o.document_id=d.id LEFT JOIN rag_image_asset i ON o.image_id=i.id WHERE d.id IS NULL OR i.id IS NULL",
                "SELECT COUNT(*) FROM rag_chunk_image l LEFT JOIN rag_content_chunk c ON l.chunk_id=c.id LEFT JOIN rag_image_occurrence o ON l.occurrence_id=o.id WHERE c.id IS NULL OR o.id IS NULL OR c.document_id<>o.document_id",
                "SELECT COUNT(*) FROM rag_document_source l LEFT JOIN rag_document d ON l.document_id=d.id LEFT JOIN rag_knowledge_source s ON l.source_id=s.id WHERE d.id IS NULL OR s.id IS NULL",
                "SELECT COUNT(*) FROM rag_web_image_reference i LEFT JOIN rag_document d ON i.document_id=d.id WHERE d.id IS NULL",
                "SELECT COUNT(*) FROM rag_document_import i LEFT JOIN rag_knowledge_source s ON i.source_id=s.id LEFT JOIN rag_document d ON i.document_id=d.id WHERE s.id IS NULL OR (i.document_id IS NOT NULL AND d.id IS NULL)");
        try (var c = db.getConnection(); var s = c.createStatement()) {
            for (String query : queries) try (var r = s.executeQuery(query)) {
                r.next(); if (r.getLong(1) != 0) throw new IllegalStateException("图文内容存在无效关联，拒绝发布");
            }
        } catch (SQLException e) { throw new IllegalStateException("图文内容校验失败", e); }
    }

    private void execute(String sql, Object... args) {
        try (var c = db.getConnection(); var p = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
            p.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException("图文内容写入失败", e); }
    }
}
