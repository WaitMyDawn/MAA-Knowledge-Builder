package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.*;
import yagen.waitmydawn.kb.model.*;
import java.util.*;

/** 本地快照目录分页读取；浏览环境不改变问答的活动环境。 */
public final class RuntimeCatalog {
    private static final ObjectMapper JSON=new ObjectMapper();
    public enum Kind { RECIPES,ENTITIES,ITEMS,LANGUAGE,LOOT }
    public record Entry(String key,String label,String category,String json) { @Override public String toString(){return label;} }
    public record Page(List<Entry> entries,int total) {}
    public record Profile(String id,String source,String descriptor,String environment) { @Override public String toString(){return environment+" · "+id.substring(0,Math.min(12,id.length()));} }
    private final DatabaseBuilder db;
    public RuntimeCatalog(DatabaseBuilder db){this.db=db;}
    public List<Profile> profiles()throws Exception {
        var list=new ArrayList<Profile>();try(var c=db.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT s.*,COALESCE(k.location,s.source_id) AS source_location,p.manifest_json FROM rag_runtime_snapshot s LEFT JOIN rag_knowledge_source k ON k.id=s.source_id LEFT JOIN rag_item_icon_profile p ON p.id=s.profile_id ORDER BY s.profile_id")){while(r.next()){var manifest=r.getString("manifest_json");var env=manifest==null?JSON.createObjectNode():JSON.readTree(manifest).path("environment");list.add(new Profile(r.getString("profile_id"),r.getString("source_location"),r.getString("descriptor"),"Minecraft "+env.path("minecraftVersion").asText("未知版本")+" · "+env.path("loader").asText("未知加载器")));}}return list;
    }
    public Page page(String profile,Kind kind,String search,String namespace,int offset,int limit)throws Exception {
        if(offset<0||limit<1||limit>100)throw new IllegalArgumentException("分页范围无效");
        String term=search==null?"":search.strip().toLowerCase(Locale.ROOT),ns=namespace==null?"":namespace.strip().toLowerCase(Locale.ROOT);
        if(kind==Kind.LANGUAGE||kind==Kind.LOOT)return textPage(profile,kind,term,ns,offset,limit);
        boolean recipes=kind==Kind.RECIPES;String table=recipes?"rag_runtime_recipe":"rag_runtime_record",key=recipes?"recipe_id":"record_id",data=recipes?"recipe_data":"record_data";
        String category=switch(kind){case ENTITIES->"(category IN ('entity_icons','entity_exclusions') OR (category='registries' AND record_id LIKE 'entity_type|%'))";case ITEMS->"category='registries' AND record_id LIKE 'item|%'";case LOOT->"category IN ('loot_tables','loot_modifiers')";default->"1=1";};
        String nameMatch=recipes?"("+table+".output_item=n.item_id)":table+"."+key+"='item|'||n.item_id";
        String where=" WHERE profile_id=? AND "+category+" AND (LOWER("+key+") LIKE ? OR LOWER("+data+") LIKE ? OR EXISTS(SELECT 1 FROM rag_runtime_name n WHERE n.profile_id="+table+".profile_id AND "+nameMatch+" AND LOWER(n.display_name) LIKE ?)) AND LOWER(REPLACE("+key+",'entity_type|','')) LIKE ?";
        String pattern="%"+term.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        if(kind==Kind.ENTITIES||kind==Kind.ITEMS)where=" WHERE profile_id=? AND "+category+" AND LOWER(CASE WHEN record_id LIKE 'item|%' THEN SUBSTRING(record_id,6) WHEN record_id LIKE 'entity_type|%' THEN SUBSTRING(record_id,13) ELSE REGEXP_REPLACE(record_id,'\\|.*$','') END) LIKE ? AND ?=? AND LOWER(REPLACE("+key+",'entity_type|','')) LIKE ?";
        where=where.replace("LIKE ?","LIKE ? ESCAPE '!'");
        String prefix=kind==Kind.ITEMS?"item|":"";String mod=ns.isBlank()?"%":prefix+ns.replace("!","!!").replace("%","!%").replace("_","!_")+":%";
        try(var c=db.getConnection()) {
            int total;try(var p=c.prepareStatement("SELECT COUNT(*) FROM "+table+where)){bind(p,profile,pattern,mod);try(var r=p.executeQuery()){r.next();total=r.getInt(1);}}
            var list=new ArrayList<Entry>();try(var p=c.prepareStatement("SELECT * FROM "+table+where+" ORDER BY "+key+" OFFSET ? ROWS FETCH NEXT ? ROWS ONLY")){bind(p,profile,pattern,mod);p.setInt(6,offset);p.setInt(7,limit);try(var r=p.executeQuery()){while(r.next()) {
                String id=r.getString(key),raw=r.getString(data);var row=JSON.readTree(raw);String label=recipes?Objects.toString(r.getString("output_item"),"动态产物"):row.path("displayName").asText(row.path("id").asText(id));
                if(kind==Kind.ENTITIES)label+=" · "+(r.getString("category").equals("entity_icons")?EntityAppearanceState.label(row)+" · "+row.path("status").asText():r.getString("category").equals("registries")?"实体登记":"导出排除");
                String item=recipes?r.getString("output_item"):kind==Kind.ITEMS?row.path("id").asText():null;
                if(item!=null)try(var name=c.prepareStatement("SELECT display_name FROM rag_runtime_name WHERE profile_id=? AND item_id=? ORDER BY CASE language WHEN 'zh_cn' THEN 0 WHEN 'current' THEN 1 ELSE 2 END FETCH FIRST 1 ROW ONLY")){name.setString(1,profile);name.setString(2,item);try(var names=name.executeQuery()){if(names.next()&&!names.getString(1).equals(label))label=names.getString(1)+"（"+label+"）";}}
                if(recipes){var detail=JSON.createObjectNode().put("recipeId",id).put("parseStatus",r.getString("parse_status")).put("sourceType",r.getString("source_type"));detail.set("renderJson",row);try(var original=c.prepareStatement("SELECT record_data FROM rag_runtime_record WHERE profile_id=? AND category='recipes' AND record_id=?")){original.setString(1,profile);original.setString(2,id);try(var records=original.executeQuery()){if(records.next())detail.set("exportedRecord",JSON.readTree(records.getString(1)));}}raw=detail.toString();}
                list.add(new Entry(id,kind==Kind.ENTITIES||label.equals(id)?label:label+" · "+(id.startsWith("item|")?id.substring(5):id),recipes?"recipes":r.getString("category"),raw));
            }}}return new Page(list,total);
        }
    }
    private record Indexed(Entry entry,String text,String namespace){}
    private String indexedStamp="";private List<Indexed> indexed=List.of();private String lastQuery="";private List<Entry> matches=List.of();
    private volatile boolean resetRequested;
    public void invalidate(){resetRequested=true;}
    private synchronized Page textPage(String profile,Kind kind,String term,String ns,int offset,int limit)throws Exception {
        if(resetRequested){indexedStamp="";lastQuery="";indexed=List.of();matches=List.of();resetRequested=false;}
        String descriptor="";try(var c=db.getConnection();var p=c.prepareStatement("SELECT descriptor FROM rag_runtime_snapshot WHERE profile_id=?")){p.setString(1,profile);try(var r=p.executeQuery()){if(r.next())descriptor=r.getString(1);}}
        String stamp=profile+kind+BuildRequest.fingerprint(descriptor);
        if(!stamp.equals(indexedStamp)) {
            var rows=new ArrayList<Indexed>();String categories=kind==Kind.LANGUAGE?"category='language'":"category IN ('loot_tables','loot_modifiers')";
            try(var c=db.getConnection();var p=c.prepareStatement("SELECT category,record_id,record_data FROM rag_runtime_record WHERE profile_id=? AND "+categories+" ORDER BY category,record_id")){p.setString(1,profile);try(var r=p.executeQuery()){while(r.next()){
                String raw=r.getString(3);var row=JSON.readTree(raw);
                if(kind==Kind.LANGUAGE){var fields=row.path("translations").fields();while(fields.hasNext()){var field=fields.next();String lang=row.path("id").asText(),key=field.getKey(),value=field.getValue().asText();var entry=new Entry(lang+"|"+key,lang+" · "+key+" = "+value,"language",JSON.createObjectNode().put("language",lang).put("key",key).set("value",field.getValue()).toString());rows.add(new Indexed(entry,(lang+" "+key+" "+value).toLowerCase(Locale.ROOT),key.toLowerCase(Locale.ROOT)));}}
                else{String id=r.getString(2);rows.add(new Indexed(new Entry(id,id,r.getString(1),raw),(id+" "+raw).toLowerCase(Locale.ROOT),id.toLowerCase(Locale.ROOT)));}
            }}}
            indexed=List.copyOf(rows);indexedStamp=stamp;lastQuery="";
        }
        String query=stamp+"\n"+term+"\n"+ns;if(!query.equals(lastQuery)){matches=indexed.stream().filter(r->r.text().contains(term)&&(ns.isBlank()||r.namespace().startsWith(ns+(kind==Kind.LANGUAGE?".":":")))).map(Indexed::entry).toList();lastQuery=query;}
        return new Page(matches.subList(Math.min(offset,matches.size()),Math.min(offset+limit,matches.size())),matches.size());
    }
    private static void bind(java.sql.PreparedStatement p,String profile,String pattern,String ns)throws Exception{p.setString(1,profile);p.setString(2,pattern);p.setString(3,pattern);p.setString(4,pattern);p.setString(5,ns);}
    public String itemRelations(String profile,String item)throws Exception {
        var text=new StringBuilder("相关配方（产物）：\n");try(var c=db.getConnection()) {
            try(var p=c.prepareStatement("SELECT recipe_id FROM rag_runtime_recipe WHERE profile_id=? AND output_item=? ORDER BY recipe_id FETCH FIRST 100 ROWS ONLY")){p.setString(1,profile);p.setString(2,item);try(var r=p.executeQuery()){while(r.next())text.append(r.getString(1)).append('\n');}}
            text.append("规则引用候选（不保证掉落，最多100条）：\n");
            try(var p=c.prepareStatement("SELECT category,record_id FROM rag_runtime_record WHERE profile_id=? AND category IN ('loot_tables','loot_modifiers') AND LOCATE(?,record_data)>0 ORDER BY category,record_id FETCH FIRST 100 ROWS ONLY")){p.setString(1,profile);p.setString(2,"\""+item+"\"");try(var r=p.executeQuery()){while(r.next())text.append(r.getString(1)).append(" · ").append(r.getString(2)).append('\n');}}
        }return text.toString();
    }
    public String pretty(Entry entry)throws Exception{return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(JSON.readTree(entry.json()));}
    public QaPipeline.RecipeMatch recipe(String profile,String id)throws Exception {
        try(var c=db.getConnection();var p=c.prepareStatement("SELECT * FROM rag_runtime_recipe WHERE profile_id=? AND recipe_id=?")){p.setString(1,profile);p.setString(2,id);try(var r=p.executeQuery()){if(!r.next())throw new IllegalArgumentException("配方已不在该快照中");return new QaPipeline.RecipeMatch(r.getString("recipe_data"),r.getString("output_item"),r.getString("source_mod"),r.getString("source_type"));}}
    }
    public byte[] entityBytes(JsonNode row,java.nio.file.Path directory)throws Exception {return java.nio.file.Files.readAllBytes(new ImageAssetStore(directory).resolve(row.path("sha256").asText(),row.path("assetPath").asText()));}
    public JsonNode entityImage(String profile,Entry entry)throws Exception {
        var rows=JSON.createArrayNode();try(var c=db.getConnection();var p=c.prepareStatement("SELECT record_data FROM rag_runtime_record WHERE profile_id=? AND category='entity_icons' AND record_id LIKE ?")){p.setString(1,profile);p.setString(2,JSON.readTree(entry.json()).path("id").asText()+"|%");try(var r=p.executeQuery()){while(r.next())rows.add(JSON.readTree(r.getString(1)));}}
        return EntityImageReferences.resolve(JSON.readTree(entry.json()),EntityImageReferences.index(rows));
    }
}
