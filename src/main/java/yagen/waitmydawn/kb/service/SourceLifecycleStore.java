package yagen.waitmydawn.kb.service;

import yagen.waitmydawn.kb.model.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** 来源版本与索引所有权随构建事务发布；物理图片保留供历史快照使用。 */
public final class SourceLifecycleStore {
    private final DatabaseBuilder db;
    public SourceLifecycleStore(DatabaseBuilder db){this.db=db;}
    public static void init(Connection c)throws SQLException {
        try(var s=c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS rag_source_state(id VARCHAR(64) PRIMARY KEY,logical_id VARCHAR(64) NOT NULL,parser_version VARCHAR(200) NOT NULL,content_hash VARCHAR(64) NOT NULL,indexed_at VARCHAR(80) NOT NULL,revision BIGINT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS rag_jar_source(id VARCHAR(64) PRIMARY KEY,source_id VARCHAR(64) NOT NULL,mod_id VARCHAR(255) NOT NULL,jar_path VARCHAR(4000) NOT NULL,content_hash VARCHAR(64) NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS rag_source_event(id VARCHAR(64) PRIMARY KEY,logical_id VARCHAR(64) NOT NULL,operation VARCHAR(40) NOT NULL,old_source VARCHAR(64),new_source VARCHAR(64),occurred_at VARCHAR(80) NOT NULL,details CLOB)");
            s.execute("CREATE TABLE IF NOT EXISTS rag_document_parser(id VARCHAR(200) PRIMARY KEY,parser_version VARCHAR(200) NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS rag_jar_parser(id VARCHAR(255) PRIMARY KEY,parser_version VARCHAR(200) NOT NULL)");
        }
    }
    public static String parserVersion(BuildRequest.Kind kind){return switch(kind){case URL->"web-r6.1/images-v1";case DOCUMENT_DIRECTORY->"documents-r6.1/native-v3.3";case JAR_DIRECTORY->"jar-r6.2/recipes-v3.3/lang-v2";};}
    public record State(String logicalId,String parserVersion,String hash,String indexedAt,long revision) {}
    public State state(String id){try(var c=db.getConnection();var p=c.prepareStatement("SELECT * FROM rag_source_state WHERE id=?")){p.setString(1,id);try(var r=p.executeQuery()){return r.next()?new State(r.getString("logical_id"),r.getString("parser_version"),r.getString("content_hash"),r.getString("indexed_at"),r.getLong("revision")):null;}}catch(SQLException e){throw new IllegalStateException(e);}}
    public List<BuildRequest.Source> sources(){return new KnowledgeContentStore(db).publishedRequest().map(BuildRequest::sources).orElse(List.of());}
    public List<String> documents(String source){return strings("SELECT d.id FROM rag_document d WHERE d.source_id=? OR EXISTS(SELECT 1 FROM rag_document_source l WHERE l.document_id=d.id AND l.source_id=?) ORDER BY d.id",source,source);}
    public List<String> strings(String sql,String... values){try(var c=db.getConnection();var p=c.prepareStatement(sql)){for(int i=0;i<values.length;i++)p.setString(i+1,values[i]);var out=new ArrayList<String>();try(var r=p.executeQuery()){while(r.next())out.add(r.getString(1));}return List.copyOf(out);}catch(SQLException e){throw new IllegalStateException(e);}}
    public void execute(String sql,String... values){try(var c=db.getConnection();var p=c.prepareStatement(sql)){for(int i=0;i<values.length;i++)p.setString(i+1,values[i]);p.executeUpdate();}catch(SQLException e){throw new IllegalStateException(e);}}
    public void captureJarOwners(BuildRequest.Source source){
        if(source.kind()!=BuildRequest.Kind.JAR_DIRECTORY)return;
        Path root=Path.of(source.location());
        for(var mod:db.findAllModEntries())if(mod.getJarPath()!=null) {
            Path path=Path.of(mod.getJarPath()).toAbsolutePath().normalize();if(!path.startsWith(root))continue;
            var hashes=strings("SELECT content_hash FROM rag_jar_import WHERE id=?",mod.getModId());if(hashes.isEmpty())continue;
            execute("MERGE INTO rag_jar_source KEY(id) VALUES(?,?,?,?,?)",BuildRequest.fingerprint(source.id()+"\n"+mod.getModId()),source.id(),mod.getModId(),path.toString(),hashes.getFirst());
        }
    }
    public List<String> mods(BuildRequest.Source source){
        var out=new TreeSet<>(strings("SELECT mod_id FROM rag_jar_source WHERE source_id=?",source.id()));
        // 旧库只允许从已发布的模组路径恢复所有权，不能按命名空间猜测目录归属。
        if(source.kind()==BuildRequest.Kind.JAR_DIRECTORY)for(var mod:db.findAllModEntries())if(mod.getJarPath()!=null&&Path.of(mod.getJarPath()).toAbsolutePath().normalize().startsWith(Path.of(source.location())))out.add(mod.getModId());
        return List.copyOf(out);
    }
    public String contentHash(BuildRequest.Source source){
        var parts=new ArrayList<String>();
        parts.addAll(strings("SELECT d.id||':'||d.content_hash FROM rag_document d WHERE d.source_id=? OR EXISTS(SELECT 1 FROM rag_document_source l WHERE l.document_id=d.id AND l.source_id=?) ORDER BY d.id",source.id(),source.id()));
        parts.addAll(strings("SELECT mod_id||':'||content_hash FROM rag_jar_source WHERE source_id=? ORDER BY mod_id",source.id()));
        parts.addAll(strings("SELECT manifest_hash FROM rag_item_icon_profile WHERE source_id=? ORDER BY id",source.id()));
        return BuildRequest.fingerprint(String.join("\n",parts));
    }
    public void indexed(BuildRequest.Source source,String logical,long minimumRevision){
        var old=state(source.id());String hash=contentHash(source),version=effectiveVersion(source);
        if(old!=null&&old.hash().equals(hash)&&old.parserVersion().equals(version)&&minimumRevision<=old.revision())return;
        execute("MERGE INTO rag_source_state KEY(id) VALUES(?,?,?,?,?,?)",source.id(),logical==null?(old==null?source.id():old.logicalId()):logical,version,hash,Instant.now().toString(),Long.toString(Math.max(minimumRevision,old==null?1:old.revision()+1)));
    }
    private String effectiveVersion(BuildRequest.Source source){
        String expected=parserVersion(source.kind());
        for(String doc:documents(source.id()))if(!strings("SELECT parser_version FROM rag_document_parser WHERE id=?",doc).equals(List.of(expected)))return "legacy/mixed";
        for(String mod:mods(source))if(!strings("SELECT parser_version FROM rag_jar_parser WHERE id=?",mod).equals(List.of(expected)))return "legacy/mixed";
        return expected;
    }
    public void parsedDocument(String id,BuildRequest.Kind kind){execute("MERGE INTO rag_document_parser KEY(id) VALUES(?,?)",id,parserVersion(kind));}
    public void clearDocumentContent(String id){
        execute("DELETE FROM rag_document_parser WHERE id=?",id);
        execute("DELETE FROM rag_vectors WHERE chunk_id IN (SELECT id FROM rag_content_chunk WHERE document_id=?)",id);
        execute("DELETE FROM rag_chunk_image WHERE chunk_id IN (SELECT id FROM rag_content_chunk WHERE document_id=?) OR occurrence_id IN (SELECT id FROM rag_image_occurrence WHERE document_id=?)",id,id);
        execute("DELETE FROM rag_chunk_block WHERE chunk_id IN (SELECT id FROM rag_content_chunk WHERE document_id=?) OR block_id IN (SELECT id FROM rag_content_block WHERE document_id=?)",id,id);
        execute("DELETE FROM rag_image_grounding WHERE id IN (SELECT id FROM rag_image_occurrence WHERE document_id=?)",id);
        for(String table:List.of("rag_content_chunk","rag_content_block","rag_web_image_reference","rag_image_occurrence"))execute("DELETE FROM "+table+" WHERE document_id=?",id);
    }
    public void unlinkDocument(String source,String doc){
        execute("DELETE FROM rag_document_source WHERE source_id=? AND document_id=?",source,doc);
        var remaining=strings("SELECT source_id FROM rag_document_source WHERE document_id=? AND source_id<>? ORDER BY source_id",doc,source);
        if(!remaining.isEmpty()){execute("UPDATE rag_document SET source_id=? WHERE id=? AND source_id=?",remaining.getFirst(),doc,source);return;}
        var owners=strings("SELECT source_id FROM rag_document WHERE id=?",doc);
        if(!owners.isEmpty()&&!owners.getFirst().equals(source))return;
        clearDocumentContent(doc);execute("DELETE FROM rag_document_import WHERE document_id=?",doc);execute("DELETE FROM rag_document_source WHERE document_id=?",doc);execute("DELETE FROM rag_document WHERE id=?",doc);
    }
    public void clearMod(String mod){
        execute("DELETE FROM rag_vectors WHERE mod_id=? AND chunk_id NOT IN (SELECT id FROM rag_content_chunk)",mod);
        for(String table:List.of("rag_recipe","rag_item_tag_file","rag_item_name","rag_item_static_icon"))execute("DELETE FROM "+table+" WHERE source_mod=?",mod);
        execute("DELETE FROM rag_texture_cache WHERE mod_source=?",mod);execute("DELETE FROM rag_entity_registry WHERE mod_id=?",mod);
        execute("DELETE FROM rag_mod_info WHERE mod_id=?",mod);
        for(String table:List.of("rag_jar_import","rag_recipe_resource_import","rag_jar_parser"))execute("DELETE FROM "+table+" WHERE id=?",mod);
        if(strings("SELECT id FROM rag_knowledge_source WHERE mod_id=?",mod).isEmpty()) {
            execute("DELETE FROM rag_web_cache WHERE mod_id=?",mod);
            execute("DELETE FROM rag_web_category_member WHERE category_id IN (SELECT category_id FROM rag_web_category WHERE mod_id=?)",mod);execute("DELETE FROM rag_web_category WHERE mod_id=?",mod);
        }
    }
    public void rehomeMod(String mod){
        var paths=strings("SELECT jar_path FROM rag_jar_source WHERE mod_id=? ORDER BY source_id",mod);
        if(!paths.isEmpty())execute("UPDATE rag_mod_info SET jar_path=?,jar_file_name=? WHERE mod_id=?",paths.getFirst(),Path.of(paths.getFirst()).getFileName().toString(),mod);
    }
    public void deleteSource(BuildRequest.Source source){
        for(String doc:documents(source.id()))unlinkDocument(source.id(),doc);
        var mods=mods(source);execute("DELETE FROM rag_jar_source WHERE source_id=?",source.id());
        for(String mod:mods)if(strings("SELECT id FROM rag_jar_source WHERE mod_id=?",mod).isEmpty())clearMod(mod);else rehomeMod(mod);
        execute("DELETE FROM rag_build_metadata WHERE name='icon_profile' AND meta_value IN (SELECT id FROM rag_item_icon_profile WHERE source_id=?)",source.id());
        execute("DELETE FROM rag_item_icon WHERE profile_id IN (SELECT id FROM rag_item_icon_profile WHERE source_id=?)",source.id());
        execute("DELETE FROM rag_item_icon_profile WHERE source_id=?",source.id());execute("DELETE FROM rag_document_import WHERE source_id=?",source.id());execute("DELETE FROM rag_source_state WHERE id=?",source.id());execute("DELETE FROM rag_knowledge_source WHERE id=?",source.id());
    }
    public void event(String logical,String operation,String oldId,String newId,String details){execute("INSERT INTO rag_source_event VALUES(?,?,?,?,?,?,?)",UUID.randomUUID().toString(),logical,operation,oldId,newId,Instant.now().toString(),details);}
}
