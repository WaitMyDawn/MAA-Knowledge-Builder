package yagen.waitmydawn.kb.service;

import java.util.*;
import com.fasterxml.jackson.databind.*;
import yagen.waitmydawn.kb.model.DatabaseBuilder;

/** 从对象身份读取完整规则证据；检索结果容器仅适配既有回答与媒体接口，不要求持久化向量行。 */
public final class RuntimeRuleEvidence {
    private RuntimeRuleEvidence(){}
    public static List<VectorStore.SearchResult> read(DatabaseBuilder db,Collection<String> ids,String question)throws Exception {
        String profile=RuntimeKnowledgeStore.active(db);if(profile==null)return List.of();var query=new RuntimeStructuredQuery(db,profile);var out=new LinkedHashMap<String,VectorStore.SearchResult>();
        for(String id:new LinkedHashSet<>(ids).stream().limit(20).toList()) {
            for(String key:List.of("item|"+id,"entity_type|"+id,"entity|"+id,"block|"+id))for(var row:query.get("registries",key).rows()) {
                var view=row.data().deepCopy();if(view instanceof com.fasterxml.jackson.databind.node.ObjectNode object)object.remove("pngBase64");
                for(var hit:query.evidencePages(new RuntimeStructuredQuery.Row(row.key(),view)))out.put(hit.chunkId(),hit);
            }
            for(String category:List.of("mod_metadata","workstation_rules","potions","effects","enchantments"))for(var row:query.get(category,id).rows())if(!row.data().has("error")){for(var hit:query.evidencePages(row))out.put(hit.chunkId(),hit);}
            if(question.matches("(?s).*(交易|商人|出售|购买).*")) {
                var rows=new ArrayList<RuntimeStructuredQuery.Row>();rows.addAll(query.query("trades",Map.of("entity",id),0,100).rows());rows.addAll(query.query("trades",Map.of("profession",id),0,100).rows());
                for(var row:rows){for(var hit:query.evidencePages(row))out.put(hit.chunkId(),hit);}
            }
            if(question.matches("(?s).*(药水|酿造|效果).*")) {
                var all=new ObjectMapper().createObjectNode();var records=all.putArray("records");int offset=0;
                do{var page=query.page("brewing",null,RuntimeStructuredQuery.State.any(),offset,100);page.rows().forEach(row->records.add(row.data()));offset=page.nextOffset();}while(offset>=0);
                var chains=new BrewingChains(all);
                for(var row:query.query("brewing",Map.of("outputPotionId",id),0,100).rows()){var hit=query.evidence(row,row.data().toPrettyString()+"\n"+chains.prerequisites(row.data()));out.put(hit.chunkId(),hit);}
            }
        }
        return List.copyOf(out.values());
    }
}
