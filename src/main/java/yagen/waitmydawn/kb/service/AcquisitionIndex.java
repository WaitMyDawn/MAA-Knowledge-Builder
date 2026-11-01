package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import yagen.waitmydawn.kb.model.*;
import java.util.*;

/** 获取来源索引仅建立可追踪的输出和表引用，不将条件掉落或候选交易表述为保证获得。 */
public final class AcquisitionIndex {
    private static final ObjectMapper JSON=new ObjectMapper();
    private AcquisitionIndex(){}
    public static void importRows(DatabaseBuilder db,String profile,Map<String,JsonNode> categories,List<TextChunker.TextChunk> chunks)throws Exception {
        var outputs=new TreeMap<String,LinkedHashSet<String>>();var links=new TreeMap<String,ObjectNode>();var parents=new HashMap<String,Set<String>>();var entrypoints=new HashMap<String,List<JsonNode>>();
        var itemTags=new HashMap<String,List<String>>();var tags=categories.get("tags");if(tags!=null)for(var tag:tags.path("records"))if(tag.path("registry").asText().equals("minecraft:item")){var members=new ArrayList<String>();tag.path("members").forEach(value->members.add(value.asText()));itemTags.put(tag.path("id").asText(),members);}
        for(var category:categories.entrySet())for(var row:category.getValue().path("records")){
            if(row.has("error"))continue;String kind=category.getKey(),id=row.path("id").asText(),key=kind+"|"+id;
            var link=JSON.createObjectNode().put("category",kind).put("recordId",id);links.put(key,link);
            switch(kind){
                case "recipes"->{try{var plan=RecipePlan.parse(row.path("definition").toString());if(!plan.output().dynamic())add(outputs,plan.output().itemId(),key);}catch(IllegalArgumentException unsupported){String preview=item(row.path("outputPreview"));if(!preview.isBlank()){link.put("unverified",true);link.put("reason","Runtime outputPreview only; dynamic/unsupported recipe semantics must be checked in raw definition");add(outputs,preview,key);}}}
                case "brewing"->add(outputs,item(row.path("output")),key);
                case "trades"->{for(var group:row.path("verifiedGroupRules"))add(outputs,item(group.path("output")),key);for(var entry:row.path("entries"))if(!entry.path("status").asText().equals("UNSUPPORTED")){add(outputs,item(entry.path("output")),key);for(var variant:entry.path("outputAlternatives"))add(outputs,item(variant),key);}}
                case "loot_tables","loot_modifiers"->loot(row.path("definition"),key,outputs,parents,itemTags);
                case "entity_attributes","entity_state_attributes"->{if(row.path("lootTable").isTextual()){var sampled=JSON.createObjectNode().put("source",id).put("sourceKind","entity").put("relation","SAMPLED_INSTANCE_LOOT_TABLE").put("samplingContext",row.path("context").asText()).put("sourceCategory",kind).put("sourceRecordId",kind.equals("entity_state_attributes")?id+"|"+EntityAppearanceState.key(row):id);if(row.has("factoryEntityType"))sampled.set("factoryEntityType",row.path("factoryEntityType"));if(row.has("state")){sampled.put("stateKey",EntityAppearanceState.key(row));String label=EntityAppearanceState.label(row);sampled.put("stateLabel",label.length()>500?label.substring(0,500):label);}registerSample(entrypoints,row.path("lootTable").asText(),sampled);}}
                case "loot_entrypoints"->entrypoints.computeIfAbsent(row.path("lootTable").asText(),ignored->new ArrayList<>()).add(row);
                default->{}
            }
        }
        try(var c=db.getConnection();var p=c.prepareStatement("INSERT INTO rag_runtime_record VALUES(?,?,?,?)")){
            for(var value:outputs.entrySet()){
                var sources=JSON.createArrayNode();var visited=new LinkedHashSet<String>();var ordered=value.getValue().stream().sorted(Comparator.comparingInt((String key)->priority(key,links)).thenComparing(key->key)).toList();var queue=new ArrayDeque<>(ordered);
                while(!queue.isEmpty()&&visited.size()<1000){String key=queue.remove();if(!visited.add(key))continue;var link=links.get(key);if(link==null)continue;var copy=link.deepCopy();String table=copy.path("recordId").asText();if(copy.path("category").asText().equals("loot_tables")){var entrances=JSON.createArrayNode();entrypoints.getOrDefault(table,List.of()).forEach(entrances::add);copy.set("entrypoints",entrances);for(String parent:parents.getOrDefault(table,Set.of())){queue.add(parent);}}sources.add(copy);}
                var row=JSON.createObjectNode().put("id",value.getKey()).put("conditional",true).put("truncated",!queue.isEmpty());row.set("sources",sources);
                p.setString(1,profile);p.setString(2,"acquisition_index");p.setString(3,value.getKey());p.setString(4,row.toString());p.addBatch();
            }p.executeBatch();
        }
    }
    private static int priority(String key,Map<String,ObjectNode> links){int weight=switch(key.substring(0,key.indexOf('|'))){case "recipes"->0;case "brewing"->1;case "trades"->2;case "loot_tables"->3;default->4;};return weight+(links.get(key).path("unverified").asBoolean()?10:0);}
    private static String relationSummary(JsonNode row){var text=new StringBuilder("结构化获取关系 "+row.path("id").asText()+"（候选仍需核对原始条件）\n");int shown=0;for(var source:row.path("sources")){if(++shown>20){text.append("更多来源保留在目录索引中。\n");break;}text.append(source.path("category").asText()).append("：").append(source.path("recordId").asText());if(source.path("unverified").asBoolean())text.append("（仅输出预览候选）");int entrances=0;for(var entrance:source.path("entrypoints")){if(++entrances>8){text.append("；更多入口见原始关系");break;}text.append("；入口 ").append(entrance.path("source").asText()).append(" · ").append(entrance.path("samplingContext").asText(entrance.path("relation").asText()));}text.append('\n');if(text.length()>6000){text.append("关系摘要达到预算，完整内容见目录。\n");break;}}if(row.path("truncated").asBoolean())text.append("关系索引达到遍历上限，不能视为完整来源全集。\n");return text.toString();}
    private static void registerSample(Map<String,List<JsonNode>> entrypoints,String table,ObjectNode sampled){
        var list=entrypoints.computeIfAbsent(table,ignored->new ArrayList<>());ObjectNode grouped=null;
        for(var existing:list)if(existing.path("relation").asText().equals("SAMPLED_INSTANCE_LOOT_TABLE")&&existing.path("source").equals(sampled.path("source"))&&existing.path("samplingContext").equals(sampled.path("samplingContext"))){grouped=(ObjectNode)existing;break;}
        if(grouped==null){sampled.put("sampleCount",1);sampled.putArray("stateRecordIds").add(sampled.path("sourceRecordId").asText());list.add(sampled);}else{grouped.put("sampleCount",grouped.path("sampleCount").asInt()+1);var references=(ArrayNode)grouped.path("stateRecordIds");if(references.size()<32)references.add(sampled.path("sourceRecordId").asText());else grouped.put("stateRecordsTruncated",true);}
    }
    private static String item(JsonNode row){return row.path("item").asText(row.path("id").asText(""));}
    private static void add(Map<String,LinkedHashSet<String>> map,String item,String key){if(item!=null&&item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))map.computeIfAbsent(item,ignored->new LinkedHashSet<>()).add(key);}
    private static void loot(JsonNode node,String key,Map<String,LinkedHashSet<String>> outputs,Map<String,Set<String>> parents,Map<String,List<String>> itemTags){
        if(node.isArray()){node.forEach(child->loot(child,key,outputs,parents,itemTags));return;}if(!node.isObject())return;
        String type=node.path("type").asText();if(type.equals("minecraft:item")||type.equals("item"))add(outputs,node.path("name").asText(),key);
        if(type.equals("minecraft:tag")||type.equals("tag"))for(String item:itemTags.getOrDefault(node.path("name").asText(),List.of()))add(outputs,item,key);
        if(type.equals("minecraft:loot_table")||type.equals("loot_table")){String target=node.path("value").asText(node.path("name").asText(""));if(target.contains(":"))parents.computeIfAbsent(target,ignored->new LinkedHashSet<>()).add(key);else if(node.path("value").isObject())loot(node.path("value"),key,outputs,parents,itemTags);}
        for(String field:List.of("pools","entries","children","functions"))if(node.has(field))loot(node.path(field),key,outputs,parents,itemTags);
    }
    public static String relations(DatabaseBuilder db,String profile,String item)throws Exception {
        try(var c=db.getConnection();var p=c.prepareStatement("SELECT record_data FROM rag_runtime_record WHERE profile_id=? AND category='acquisition_index' AND record_id=?")){p.setString(1,profile);p.setString(2,item);try(var r=p.executeQuery()){return r.next()?JSON.readTree(r.getString(1)).toPrettyString():"尚无已解析获取关系；旧快照或未适配动态规则也可能造成缺口。";}}
    }
    public static List<VectorStore.SearchResult> evidence(DatabaseBuilder db,Collection<String> ids,String question)throws Exception {
        if(!question.matches("(?s).*(获取|获得|来源|掉落|交易|制作|合成|酿造).*"))return List.of();
        String profile=RuntimeKnowledgeStore.active(db);if(profile==null)return List.of();var result=new LinkedHashMap<String,VectorStore.SearchResult>();
        var query=new RuntimeStructuredQuery(db,profile);
        for(String id:new LinkedHashSet<>(ids).stream().limit(20).toList()) {
            // 带组件的通用物品须由具体药水或附魔条件选路，不能注入任意前几条转换。
            if(Set.of("minecraft:potion","minecraft:splash_potion","minecraft:lingering_potion","minecraft:tipped_arrow","minecraft:enchanted_book").contains(id))continue;
            var page=query.get("acquisition_index",id);
            for(var index:page.rows()) {
                var overview=query.evidence(index,relationSummary(index.data()));result.put(overview.chunkId(),overview);
                var perKind=new HashMap<String,Integer>();int selected=0;boolean truncated=false;
                for(var source:index.data().path("sources")) {
                    String category=source.path("category").asText();if(perKind.getOrDefault(category,0)>=5||selected>=20){truncated=true;continue;}
                    if(!Set.of("recipes","trades","brewing","loot_tables","loot_modifiers").contains(category))continue;
                    selected++;perKind.merge(category,1,Integer::sum);
                    var sourcePage=query.get(category,source.path("recordId").asText());
                    for(var record:sourcePage.rows()) {
                        if(record.data().has("error"))continue;
                        var selectedRecord=category.equals("trades")?new RuntimeStructuredQuery.Row(record.key(),RuntimeRules.focusedTradeRecord(record.data(),Set.of(id))):record;
                        for(var hit:query.evidencePages(selectedRecord))result.put(hit.chunkId(),hit);
                        if(category.equals("loot_tables")) {
                            var expansion=query.expand(record.key(),4,12,Set.of("NESTED_LOOT"));
                            for(var nested:expansion.rows())if(!nested.key().equals(record.key())&&!nested.data().has("error")) {
                                var nestedHit=query.evidence(nested,RuntimeLootSummary.describe(nested.key().category(),nested.data())+"\n"+String.join("\n",expansion.warnings()));result.put(nestedHit.chunkId(),nestedHit);
                            }
                            if(expansion.truncated())truncated=true;
                        }
                    }
                }
                if(truncated){var warning=query.evidence(index,"来源证据达到类别、条目或关联遍历预算，当前展示不是完整来源全集。\n"+relationSummary(index.data()));result.put(warning.chunkId(),warning);}
            }
            if(question.matches("(?s).*(掉落|战利品).*"))for(var row:lootEvidence(query,id))result.put(row.chunkId(),row);
        }
        var values=new ArrayList<>(result.values());
        if(values.size()>60){values=new ArrayList<>(values.subList(0,60));var last=values.removeLast();values.add(new VectorStore.SearchResult(last.chunkId(),last.chunkText()+"\n结构化证据达到60条上限，后续关系未展示。",last.modId(),last.modName(),last.type(),last.score(),last.sourcePath()));}
        return List.copyOf(values);
    }
    private static List<VectorStore.SearchResult> lootEvidence(RuntimeStructuredQuery query,String id)throws Exception {
        var results=new ArrayList<VectorStore.SearchResult>();
        for(String category:List.of("registries","loot_tables")) {
            var expansion=query.expand(new RuntimeStructuredQuery.Key(category,id),4,12,Set.of("DECLARED_LOOT","SAMPLED_LOOT","NESTED_LOOT"));
            for(var row:expansion.rows())if(Set.of("loot_tables","loot_modifiers").contains(row.key().category())&&!row.data().has("error"))
                results.add(query.evidence(row,RuntimeLootSummary.describe(row.key().category(),row.data())+"\n"+String.join("\n",expansion.warnings())));
        }
        return results;
    }
    private static String summary(String category,JsonNode row,String item) {
        if(category.equals("trades"))return RuntimeRules.focusedTrade(row,item);
        if(RuntimeRules.CATEGORIES.contains(category))return RuntimeRules.describe(category,row);
        if(Set.of("loot_tables","loot_modifiers").contains(category))return RuntimeLootSummary.describe(category,row);
        String definition=row.path("definition").toString();
        return "运行时配方 "+row.path("id").asText()+" · "+row.path("normalizationStatus").asText()+"（动态预览不是结果保证）：\n"+definition+"\n输出预览（仅候选）："+row.path("outputPreview");
    }
}
