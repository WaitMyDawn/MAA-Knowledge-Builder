package yagen.waitmydawn.kb.service;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.io.IOException;
/** 属性快照保留基础值、有效值及条件，不把装备修饰器当作最终战斗伤害。 */
public final class RuntimeAttributes {
    public static final Set<String> CATEGORIES=Set.of("item_attributes","entity_attributes","entity_state_attributes");
    private RuntimeAttributes(){}
    public static void validate(String category,JsonNode row)throws IOException {
        if(row.path("schemaVersion").asInt()!=1||!row.path("context").isTextual())throw new IOException("属性协议或上下文无效");
        String field=category.equals("item_attributes")?"modifiers":"attributes";if(!row.path(field).isArray()||row.path(field).size()>4096)throw new IOException("属性列表无效");
        for(var value:row.path(field)){if(!value.path("attribute").asText().matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IOException("属性注册名无效");if(category.equals("item_attributes")){finite(value,"amount");if(!value.path("slot").isTextual()||!value.path("operation").isTextual())throw new IOException("属性修饰器缺少槽位或运算");}else{finite(value,"base");finite(value,"effective");for(var modifier:value.path("modifiers"))finite(modifier,"amount");}}
    }
    private static void finite(JsonNode value,String key)throws IOException{if(!value.path(key).isNumber()||!Double.isFinite(value.path(key).asDouble()))throw new IOException("属性数值无效："+key);}
    static String label(String id){String key=id.substring(id.indexOf(':')+1).replaceFirst("^generic\\.","");return switch(key){case "max_health"->"最大生命值";case "attack_damage"->"攻击伤害属性";case "attack_speed"->"攻击速度";case "armor"->"护甲值";case "armor_toughness"->"护甲韧性";case "movement_speed"->"移动速度属性";case "knockback_resistance"->"击退抗性";case "follow_range"->"追踪距离";default->id;};}
    public static String describe(String category,JsonNode row){var out=new StringBuilder("实机属性 "+row.path("id").asText()+"\n采样上下文："+row.path("context").asText()+"\n");
        if(row.has("baby"))out.append("年龄：").append(row.path("baby").asBoolean()?"幼年":"成年").append('\n');
        if(row.has("factoryEntityType")&&!row.path("factoryEntityType").asText().equals(row.path("id").asText()))out.append("工厂实际返回注册类型：").append(row.path("factoryEntityType").asText()).append("；属性属于该工厂返回的构造样本。\n");
        if(row.has("lootTable"))out.append("采样掉落入口：").append(row.path("lootTable").asText()).append("；仍受掉落条件、函数、全局修饰器及事件影响。\n");
        if(row.has("state"))out.append("状态：").append(row.path("state")).append('\n');
        if(category.equals("item_attributes")){out.append("默认物品堆叠上限：").append(row.path("maxStackSize")).append("；最大耐久：").append(row.path("maxDamage")).append('\n');for(var m:row.path("modifiers"))out.append("槽位 ").append(m.path("slot").asText()).append("：").append(label(m.path("attribute").asText())).append(" ").append(m.path("amount")).append("，运算 ").append(m.path("operation").asText()).append('\n');out.append("这些是默认物品按槽位的修饰器，不是玩家最终攻击伤害；附魔、组件、效果及事件可能改变结果。");}
        else{for(var a:row.path("attributes"))out.append(label(a.path("attribute").asText())).append("（").append(a.path("attribute").asText()).append("）：基础值 ").append(a.path("base")).append("；此采样有效值 ").append(a.path("effective")).append('\n');out.append("难度：").append(row.path("difficulty").asText("未知")).append("；幼年：").append(row.path("baby")).append("。生命值2点对应1颗心。默认构造实例未执行真实生成钩子；离世界样本未强制推进装备同步tick，属性映射值不保证含未同步装备加成；实际捕获值只适用于当时状态。");}return out.toString();
    }
    public static boolean requested(String question){return question.matches("(?s).*(属性|生命|血量|护甲|韧性|攻击力|攻击速度|移动速度|耐久|堆叠).*" );}
    public record Evidence(List<VectorStore.SearchResult> hits,Set<String> coveredIds) {
        public Evidence { hits=List.copyOf(hits);coveredIds=Set.copyOf(coveredIds); }
    }
    public static List<VectorStore.SearchResult> evidence(yagen.waitmydawn.kb.model.DatabaseBuilder db,Collection<String> ids,String question)throws Exception {
        return queryEvidence(db,ids,question).hits();
    }
    public static Evidence queryEvidence(yagen.waitmydawn.kb.model.DatabaseBuilder db,Collection<String> ids,String question)throws Exception {
        if(!requested(question))return new Evidence(List.of(),Set.of());
        String profile=RuntimeKnowledgeStore.active(db);if(profile==null)return new Evidence(List.of(),Set.of());
        var query=new RuntimeStructuredQuery(db,profile);var hits=new LinkedHashMap<String,VectorStore.SearchResult>();var covered=new LinkedHashSet<String>();
        boolean baby=question.contains("幼年"),adult=question.contains("成年"),observed=question.matches("(?s).*(实际捕获|当前实体|捕获实例).*"),equipment=question.contains("装备");
        boolean specific=baby||adult||observed||equipment;
        for(String id:new LinkedHashSet<>(ids).stream().limit(20).toList()) {
            boolean found=false,complete=true;
            if(specific) {
                var ages=new ArrayList<Boolean>();if(baby)ages.add(true);if(adult)ages.add(false);if(ages.isEmpty())ages.add(null);
                for(Boolean age:ages) {
                    Boolean equipped=equipment?!(question.contains("无装备")||question.contains("未装备")||question.contains("没有装备")):null;
                    var page=query.page("entity_state_attributes",id,new RuntimeStructuredQuery.State(null,age,equipped,observed?"OBSERVED_INSTANCE":null),0,6);
                    boolean matched=false;
                    for(var row:page.rows())if(!row.data().has("error")&&!Boolean.FALSE.equals(EntityVariantQuery.matches(row.data(),question))) {
                        var hit=query.evidence(row,"查询状态："+page.status()+"；类别覆盖："+page.coverage()+"\n"+String.join("\n",page.warnings())+"\n"+describe(row.key().category(),row.data()));
                        hits.put(hit.chunkId(),hit);matched=true;
                    }
                    complete&=matched&&page.status()==RuntimeStructuredQuery.Status.FOUND;found|=matched;
                }
            } else {
                for(String category:List.of("item_attributes","entity_attributes")) {
                    var page=query.get(category,id);
                    for(var row:page.rows())if(!row.data().has("error")) {
                        var hit=query.evidence(row,"查询状态："+page.status()+"；类别覆盖："+page.coverage()+"\n"+String.join("\n",page.warnings())+"\n"+describe(category,row.data()));
                        hits.put(hit.chunkId(),hit);found=true;complete&=page.status()==RuntimeStructuredQuery.Status.FOUND;
                    }
                }
            }
            if(found&&complete)covered.add(id);
        }
        return new Evidence(new ArrayList<>(hits.values()),covered);
    }
    public static String details(yagen.waitmydawn.kb.model.DatabaseBuilder db,String profile,String id,String state)throws Exception {
        var text=new StringBuilder();try(var c=db.getConnection();var p=c.prepareStatement("SELECT category,record_data FROM rag_runtime_record WHERE profile_id=? AND category IN ('item_attributes','entity_attributes','entity_state_attributes') AND (record_id=? OR record_id=?) ORDER BY category")){p.setString(1,profile);p.setString(2,id);p.setString(3,id+"|"+Objects.toString(state,"default"));try(var r=p.executeQuery()){while(r.next()){var row=new com.fasterxml.jackson.databind.ObjectMapper().readTree(r.getString(2));text.append(row.has("error")?"属性导出失败："+row.path("error").asText():describe(r.getString(1),row)).append("\n属性原始JSON：\n").append(row.toPrettyString()).append("\n\n");}}}return text.length()==0?"该对象没有对应的实机属性记录；可能是旧包、非生物实体或尚未覆盖的类别。":text.toString();
    }
}
