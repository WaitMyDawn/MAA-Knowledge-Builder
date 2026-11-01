package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.*;
import yagen.waitmydawn.kb.model.*;
import java.sql.*;
import java.util.*;

/** 运行时数据以来源最新快照隔离；完整快照中的缺失项具有删除语义，不能回落到 JAR。 */
public final class RuntimeKnowledgeStore {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final DatabaseBuilder db;
    public RuntimeKnowledgeStore(DatabaseBuilder db){this.db=db;}
    public static void init(Connection c)throws SQLException {
        RuntimeStructuredQuery.init(c);RuntimeNameIndex.init(c);
        try(var s=c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS rag_runtime_snapshot(source_id VARCHAR(64) PRIMARY KEY,profile_id VARCHAR(64) UNIQUE NOT NULL,descriptor CLOB NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS rag_runtime_record(profile_id VARCHAR(64) NOT NULL,category VARCHAR(40) NOT NULL,record_id VARCHAR(1100) NOT NULL,record_data CLOB NOT NULL,PRIMARY KEY(profile_id,category,record_id))");
            s.execute("CREATE TABLE IF NOT EXISTS rag_runtime_name(profile_id VARCHAR(64) NOT NULL,item_id VARCHAR(500) NOT NULL,language VARCHAR(16) NOT NULL,display_name VARCHAR(2000) NOT NULL,PRIMARY KEY(profile_id,item_id,language))");
            s.execute("CREATE TABLE IF NOT EXISTS rag_runtime_recipe(id VARCHAR(64) PRIMARY KEY,profile_id VARCHAR(64) NOT NULL,recipe_id VARCHAR(500) NOT NULL,recipe_data CLOB NOT NULL,output_item VARCHAR(500),source_mod VARCHAR(255) NOT NULL,source_type VARCHAR(32) NOT NULL,parse_status VARCHAR(32) NOT NULL)");
        }
    }
    public void importPack(BuildRequest.Source source,NativeIconPack.Pack pack,BuildSourceProcessor.Context context)throws Exception {
        try(var timingScope=BuildTrace.phase("snapshot.runtime",source.location())) {

        migrateRuntimeText(db);
        var data=RuntimeSnapshotData.read(pack);
        if(!data.categories().keySet().containsAll(List.of("mod_metadata","registries","language","recipes","tags"))) {
            try(var c=db.getConnection();var p=c.prepareStatement("SELECT 1 FROM rag_runtime_snapshot WHERE source_id=?")){p.setString(1,source.id());try(var r=p.executeQuery()){if(r.next())throw new IllegalStateException("已有运行时知识的来源不能降级为不完整快照");}}return;
        }
        if(!"SINGLEPLAYER".equals(data.descriptor().path("captureMode").asText()))return;
        for(String category:List.of("mod_metadata","registries","language","recipes","tags"))if(!"COMPLETE".equals(data.descriptor().path("categories").path(category).path("status").asText()))throw new IllegalStateException("运行时知识类别尚有失败："+category);
        var entities=data.categories().get("entity_icons");
        if(entities!=null){context.result().entityExcluded+=entities.path("exclusions").size();for(var row:entities.path("records")){
            if(row.has("error"))context.result().entityFailed++;else if(row.path("status").asText().equals("TRANSPARENT"))context.result().entityTransparent++;else context.result().entityVisible++;
            if(!row.has("error")){if(row.has("imageRef"))context.result().entityImageAliases++;else context.result().entityUniqueImages++;switch(row.path("compositionRender").path("status").asText().equals("PARTIAL")?"PARTIAL":row.path("composition").path("status").asText()){case "COMPLETE"->context.result().entityCompositionComplete++;case "PARTIAL"->context.result().entityCompositionPartial++;default->context.result().entityCompositionUnverified++;}}
            if(row.path("visibilityAssessment").asText().equals("UNEXPECTED_EMPTY"))context.result().entityUnexpectedEmpty++;
            if(Set.of("POSSIBLY_CLIPPED","UNSTABLE_FRAMING").contains(row.path("framing").asText()))context.result().entityUncertain++;
        }}
        String profile=BuildRequest.fingerprint(source.id()+"\n"+pack.manifestHash());
        try(var c=db.getConnection();var p=c.prepareStatement("SELECT profile_id FROM rag_runtime_snapshot WHERE source_id=?")) {
            p.setString(1,source.id());try(var r=p.executeQuery()){if(r.next()&&profile.equals(r.getString(1))&&new SourceLifecycleStore(db).strings("SELECT meta_value FROM rag_build_metadata WHERE name=?","runtime_text_policy:"+profile).equals(List.of("3"))){context.visitedDocuments().addAll(new SourceLifecycleStore(db).documents(source.id()));return;}}
        }
        deleteSource(source.id());
        try(var c=db.getConnection();var p=c.prepareStatement("INSERT INTO rag_runtime_snapshot VALUES(?,?,?)")) {p.setString(1,source.id());p.setString(2,profile);p.setString(3,data.descriptor().toString());p.executeUpdate();}
        var entityAssets=new HashMap<String,String>();
        try(var entityTiming=BuildTrace.step("snapshot.entity_assets","实体图片校验与归档")) {
        if(entities!=null)for(var row:entities.path("records"))if(!row.has("error")&&!row.has("imageRef")){
            byte[] bytes=entities.has("imageStorage")?RuntimeEntityMedia.externalBytes(pack.root(),row):RuntimeEntityMedia.bytes(row);RuntimeEntityMedia.validate(row,bytes);
            var asset=context.images().put(bytes);context.content().saveImage(asset);entityAssets.put(row.path("id").asText()+"|"+EntityAppearanceState.key(row),asset.relativePath());
        }
        }

        try(var c=db.getConnection();var p=c.prepareStatement("INSERT INTO rag_runtime_record VALUES(?,?,?,?)")) {
            for(var category:data.categories().entrySet())try(var categoryTiming=BuildTrace.step("snapshot.prepare."+category.getKey(),category.getValue().path("records").size()+" records")) {
            for(var row:category.getValue().path("records")) {
                context.checkCancelled();String id=row.path("id").asText();String identity=(category.getKey().equals("registries")||category.getKey().equals("tags")?row.path("registry").asText()+"|":"")+id;
                if(category.getKey().equals("entity_icons")||category.getKey().equals("entity_state_attributes"))identity+="|"+EntityAppearanceState.key(row);
                p.setString(1,profile);p.setString(2,category.getKey());p.setString(3,identity);JsonNode stored=row;if(category.getKey().equals("entity_icons")){var metadata=((com.fasterxml.jackson.databind.node.ObjectNode)row).deepCopy();metadata.remove("pngBase64");metadata.remove("assetPath");String asset=entityAssets.get(identity);if(asset!=null)metadata.put("assetPath",asset);stored=metadata;}
                p.setString(4,stored.toString());p.addBatch();
            }
            }
            if(entities!=null)for(var row:entities.path("exclusions")) {
                p.setString(1,profile);p.setString(2,"entity_exclusions");p.setString(3,row.path("id").asText());p.setString(4,row.toString());p.addBatch();
            }try(var writeTiming=BuildTrace.step("snapshot.records.write","运行时原始记录批量入库")){p.executeBatch();}
        }
        var language=new HashMap<String,JsonNode>();for(var row:data.categories().get("language").path("records"))language.put(row.path("id").asText(),row.path("translations"));
        try(var namesTiming=BuildTrace.step("snapshot.names","名称、语言和物品提示");var c=db.getConnection();var p=c.prepareStatement("INSERT INTO rag_runtime_name VALUES(?,?,?,?)")) {
            for(var row:data.categories().get("registries").path("records")) {
                String id=row.path("id").asText(),key=row.path("descriptionId").asText();
                if(row.path("registry").asText().equals("item"))for(String lang:List.of("current","zh_cn","en_us")) {
                    String name=lang.equals("current")?row.path("displayName").asText():language.getOrDefault(lang,JSON.createObjectNode()).path(key).asText("");
                    if(!name.isBlank()&&name.length()<=2000){p.setString(1,profile);p.setString(2,id);p.setString(3,lang);p.setString(4,name);p.addBatch();}
                }
            }p.executeBatch();
        }
        try(var recipesTiming=BuildTrace.step("snapshot.recipes","配方规范化与索引");var c=db.getConnection();var p=c.prepareStatement("INSERT INTO rag_runtime_recipe VALUES(?,?,?,?,?,?,?,?)")) {
            for(var row:data.categories().get("recipes").path("records")) {
                String id=row.path("id").asText();JsonNode definition=row.has("definition")?row.path("definition"):row.path("renderJson");
                String status=row.path("normalizationStatus").asText(),output=null;
                if(!definition.isObject()){definition=JSON.createObjectNode().put("type",row.path("serializer").asText());status="UNSUPPORTED";}
                try {var plan=RecipePlan.parse(definition.toString());output=plan.output().itemId();}
                catch(IllegalArgumentException unsupported){status="UNSUPPORTED";}
                p.setString(1,BuildRequest.fingerprint(profile+"\n"+id));p.setString(2,profile);p.setString(3,id);p.setString(4,definition.toString());p.setString(5,output);p.setString(6,id.substring(0,id.indexOf(':')));p.setString(7,"RUNTIME_SNAPSHOT");p.setString(8,status);p.addBatch();
            }p.executeBatch();
        }
        AcquisitionIndex.importRows(db,profile,data.categories(),new ArrayList<>());
        context.content().setSourceScope(source.id(),"runtime:"+source.id(),"LOCAL","游戏快照 "+pack.minecraftVersion()+" · "+pack.loader(),null);
        new SourceLifecycleStore(db).execute("MERGE INTO rag_build_metadata KEY(name) VALUES(?,?)","runtime_text_policy:"+profile,"3");
        context.result().runtimeSnapshots++;
        context.result().recipes+=data.categories().get("recipes").path("records").size();
        if(context.progress()!=null)context.progress().onProgress("runtime",data.categories().get("recipes").path("records").size(),0,"已导入运行时知识快照");
        }
    }
    public static void migrateRuntimeText(DatabaseBuilder db) {
        var store=new SourceLifecycleStore(db);
        for(String document:store.strings("SELECT id FROM rag_document WHERE media_type='application/x-mkb-runtime'"))
            for(String source:store.strings("SELECT source_id FROM rag_document WHERE id=?",document))store.unlinkDocument(source,document);
        store.execute("DELETE FROM rag_vectors WHERE LEFT(chunk_type,8)='runtime_' OR source_path LIKE 'mkb-runtime:%'");
    }
    public void deleteSource(String source)throws SQLException {
        var lifecycle=new SourceLifecycleStore(db);
        for(String document:lifecycle.strings("SELECT id FROM rag_document WHERE source_id=? AND media_type='application/x-mkb-runtime'",source))lifecycle.unlinkDocument(source,document);
        try(var c=db.getConnection();var p=c.prepareStatement("SELECT profile_id FROM rag_runtime_snapshot WHERE source_id=?")) {
            p.setString(1,source);try(var r=p.executeQuery()){while(r.next()) {
                String profile=r.getString(1);for(String table:List.of("rag_runtime_fact","rag_runtime_lookup","rag_runtime_link","rag_runtime_query_version","rag_runtime_object_name","rag_runtime_name_version","rag_runtime_record","rag_runtime_name","rag_runtime_recipe"))try(var delete=c.prepareStatement("DELETE FROM "+table+" WHERE profile_id=?")){delete.setString(1,profile);delete.executeUpdate();}
                try(var delete=c.prepareStatement("DELETE FROM rag_vectors WHERE source_path LIKE ?")){delete.setString(1,"mkb-runtime:"+profile+"/%");delete.executeUpdate();}
            }}
            try(var delete=c.prepareStatement("DELETE FROM rag_runtime_snapshot WHERE source_id=?")){delete.setString(1,source);delete.executeUpdate();}
        }
    }
    static String active(Connection c)throws SQLException {
        // 对话增量库仅存向量，不拥有基础库的快照表和环境选择元数据。
        try(var tables=c.getMetaData().getTables(null,null,"rag_runtime_snapshot",null)){if(!tables.next())return null;}
        String selected=null;try(var s=c.createStatement();var r=s.executeQuery("SELECT meta_value FROM rag_build_metadata WHERE name='icon_profile'")){if(r.next())selected=r.getString(1);}
        if(selected!=null)try(var p=c.prepareStatement("SELECT s.profile_id FROM rag_runtime_snapshot s JOIN rag_item_icon_profile i ON i.source_id=s.source_id WHERE i.id=?")){p.setString(1,selected);try(var r=p.executeQuery()){if(r.next())return r.getString(1);}}
        var profiles=new ArrayList<String>();try(var s=c.createStatement();var r=s.executeQuery("SELECT profile_id FROM rag_runtime_snapshot")){while(r.next())profiles.add(r.getString(1));}
        if(profiles.size()>1)throw new IllegalStateException("存在多个运行时知识环境，请在问答页选择对应的图标环境。");return profiles.isEmpty()?null:profiles.getFirst();
    }
    public static String active(DatabaseBuilder db){try(var c=db.getConnection()){return active(c);}catch(SQLException e){throw new IllegalStateException(e);}}
    static String activeSource(DatabaseBuilder db){try(var c=db.getConnection()){String profile=active(c);if(profile==null)return null;try(var p=c.prepareStatement("SELECT source_id FROM rag_runtime_snapshot WHERE profile_id=?")){p.setString(1,profile);try(var r=p.executeQuery()){return r.next()?r.getString(1):null;}}}catch(SQLException e){throw new IllegalStateException(e);}}
    public static String recipeRelation(DatabaseBuilder db){String profile=active(db);return profile==null?"rag_recipe":"(SELECT * FROM rag_runtime_recipe WHERE profile_id='"+profile+"')";}
    public static String nameRelation(DatabaseBuilder db){String profile=active(db);return profile==null?"rag_item_name":"(SELECT * FROM rag_runtime_name WHERE profile_id='"+profile+"')";}
    public static String vectorPredicate(DatabaseBuilder db) {
        String exclusion="LEFT(COALESCE(chunk_type,''),8)<>'runtime_' AND (source_path IS NULL OR source_path NOT LIKE 'mkb-runtime:%')";
        return active(db)==null?"("+exclusion+")":"("+exclusion+" AND chunk_type NOT IN ('recipe','lang_items','description'))";
    }
    static Map<String,List<RecipeResourceStore.TagFile>> tags(Connection c,String profile)throws Exception {
        var result=new TreeMap<String,List<RecipeResourceStore.TagFile>>();try(var p=c.prepareStatement("SELECT record_data FROM rag_runtime_record WHERE profile_id=? AND category='tags'")) {
            p.setString(1,profile);try(var r=p.executeQuery()){while(r.next()){var row=JSON.readTree(r.getString(1));if(!row.path("registry").asText().equals("minecraft:item"))continue;
                var file=JSON.createObjectNode().put("replace",true);file.set("values",row.path("members"));result.put(row.path("id").asText(),List.of(new RecipeResourceStore.TagFile("RUNTIME_SNAPSHOT",file)));}}
        }return result;
    }
}
