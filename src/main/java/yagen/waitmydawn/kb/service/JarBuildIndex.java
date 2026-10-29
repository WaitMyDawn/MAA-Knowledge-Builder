package yagen.waitmydawn.kb.service;

import yagen.waitmydawn.kb.model.DatabaseBuilder;
import yagen.waitmydawn.kb.model.ModEntry;
import java.nio.file.*;
import java.util.*;

/** 已发布 JAR 的内容指纹用于区分重复导入和同 modId 的版本冲突。 */
public final class JarBuildIndex {
    public enum Status {
        NEW("新增"), DUPLICATE("已存在"), LEGACY("已存在（旧库无内容指纹）"), CONFLICT("版本或内容冲突，补充模式跳过");
        private final String label;
        Status(String label) { this.label=label; }
        @Override public String toString() { return label; }
    }
    private final DatabaseBuilder db;
    public JarBuildIndex(DatabaseBuilder db) { this.db=db; }

    public Status inspect(ModEntry entry, String hash) {
        var previous=db.findModEntry(entry.getModId());
        if(previous==null) return Status.NEW;
        if(RuntimeBuildContract.migrated(db)) {
            try {
                var rows=new SourceLifecycleStore(db).strings("SELECT p.manifest_json FROM rag_runtime_snapshot s JOIN rag_item_icon_profile p ON p.id=s.profile_id");
                if(rows.size()==1) {
                    var environment=new com.fasterxml.jackson.databind.ObjectMapper().readTree(rows.getFirst()).path("environment");
                    for(var mod:environment.path("mods"))if(mod.path("id").asText().equals(entry.getModId())) {
                        if(!Objects.equals(entry.getVersion(),mod.path("version").asText()))return Status.CONFLICT;
                        if(entry.getModId().equals("minecraft"))return Status.DUPLICATE;
                        if(mod.hasNonNull("sha256"))return hash.equals(mod.path("sha256").asText())?Status.DUPLICATE:Status.CONFLICT;
                        return Status.LEGACY;
                    }
                }
            }catch(java.io.IOException failure){throw new IllegalStateException("快照身份摘要读取失败",failure);}
        }
        try(var c=db.getConnection();var p=c.prepareStatement("SELECT content_hash FROM rag_jar_import WHERE id=?")) {
            p.setString(1,entry.getModId());
            try(var r=p.executeQuery()) {
                if(r.next()) return hash.equals(r.getString(1)) ? Status.DUPLICATE : Status.CONFLICT;
            }
        } catch(java.sql.SQLException e) {throw new IllegalStateException("JAR 指纹读取失败",e);}
        // 旧版本的扫描也会写入模组表，解析产物才能证明该模组已进入基础知识库。
        if(!hasJarContent(entry.getModId())) return Status.NEW;
        return Objects.equals(entry.getVersion(),previous.getVersion())
                && Objects.equals(entry.getLoader(),previous.getLoader())
                && Objects.equals(entry.getMcVersion(),previous.getMcVersion()) ? Status.LEGACY : Status.CONFLICT;
    }

    private boolean hasJarContent(String modId) {
        try(var c=db.getConnection()) {
            boolean vectorsExist;
            try(var tables=c.getMetaData().getTables(null,null,"rag_vectors",null)) {vectorsExist=tables.next();}
            String sql="SELECT EXISTS(SELECT 1 FROM rag_recipe WHERE source_mod=?)"
                    +" OR EXISTS(SELECT 1 FROM rag_texture_cache WHERE registry_name LIKE ? ESCAPE '!')"
                    +(vectorsExist?" OR EXISTS(SELECT 1 FROM rag_vectors WHERE mod_id=? AND chunk_type IN ('lang_items','description','recipe'))":"");
            try(var p=c.prepareStatement(sql)) {
                p.setString(1,modId);
                p.setString(2,modId.replace("!","!!").replace("_","!_").replace("%","!%")+":%");
                if(vectorsExist) p.setString(3,modId);
                try(var r=p.executeQuery()) {r.next();return r.getBoolean(1);}
            }
        } catch(java.sql.SQLException e) {throw new IllegalStateException("JAR 已发布内容读取失败",e);}
    }

    public void save(ModEntry entry,String hash) {
        try(var c=db.getConnection();var p=c.prepareStatement("MERGE INTO rag_jar_import(id,content_hash) KEY(id) VALUES(?,?)")) {
            p.setString(1,entry.getModId());p.setString(2,hash);p.executeUpdate();
            new SourceLifecycleStore(db).execute("MERGE INTO rag_jar_parser KEY(id) VALUES(?,?)",entry.getModId(),SourceLifecycleStore.parserVersion(yagen.waitmydawn.kb.model.BuildRequest.Kind.JAR_DIRECTORY));
        } catch(java.sql.SQLException e) {throw new IllegalStateException("JAR 指纹保存失败",e);}
    }

    public static String hash(Path file) throws java.io.IOException {
        try {
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            try(var input=Files.newInputStream(file)) {
                byte[] buffer=new byte[65536];int count;
                while((count=input.read(buffer))!=-1) {
                    if(Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("JAR scan cancelled");
                    digest.update(buffer,0,count);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch(java.security.NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }
}
