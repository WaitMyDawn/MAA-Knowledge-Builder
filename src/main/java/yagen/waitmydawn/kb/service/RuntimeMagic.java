package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.io.IOException;
import yagen.waitmydawn.kb.model.DatabaseBuilder;

/** 注册表定义与物品堆叠状态分开保存；基础药水定义不能覆盖自定义效果。 */
public final class RuntimeMagic {
    public static final Set<String> CATEGORIES=Set.of("potions","effects","enchantments");
    private static final ObjectMapper JSON=new ObjectMapper();
    private RuntimeMagic(){}
    static void validate(String category,JsonNode row)throws IOException {
        if(category.equals("potions")){if(!row.path("effects").isArray()||row.path("effects").size()>4096)throw new IOException("药水效果列表无效");for(var effect:row.path("effects")){if(!effect.path("effect").asText().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||!effect.path("amplifier").isIntegralNumber()||!effect.path("durationTicks").isIntegralNumber())throw new IOException("药水效果参数无效");}}
        if(category.equals("effects")&&(!row.path("instantaneous").isBoolean()||!row.path("category").isTextual()||!row.path("color").isIntegralNumber()))throw new IOException("状态效果定义无效");
        if(category.equals("enchantments")){int min=row.path("minLevel").asInt(-1),max=row.path("maxLevel").asInt(-1);if(min<0||max<min||!row.path("costs").isArray()||!row.path("supportedItems").isArray()||!row.path("incompatible").isArray())throw new IOException("附魔等级或适用关系无效");for(String field:List.of("supportedItems","incompatible")){if(row.path(field).size()>100000)throw new IOException("附魔关联数量超限");for(var id:row.path(field))if(!id.isTextual()||!id.asText().matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IOException("附魔关联注册名无效");}}
    }
    static String label(String category,JsonNode row,Map<String,JsonNode> languages){String id=row.path("id").asText();String key=row.path("descriptionId").asText((category.equals("enchantments")?"enchantment.":"effect.")+id.replace(':','.'));var names=new LinkedHashSet<String>();for(var lang:languages.values()){String value=lang.path(key).asText();if(!value.isBlank())names.add(value);}if(category.equals("potions")){for(var effect:row.path("effects")){String effectId=effect.path("effect").asText();for(var lang:languages.values()){String translated=lang.path("effect."+effectId.replace(':','.')).asText();if(!translated.isBlank())names.add(translated+" "+(effect.path("amplifier").asInt()+1));}if(effect.has("name"))names.add(effect.path("name").asText()+" "+(effect.path("amplifier").asInt()+1));}}if(row.has("name"))names.add(row.path("name").asText());return String.join("、",names);}
    public static String describe(String category,JsonNode row){
        if(row.has("error"))return "导出失败："+row.path("error").asText();
        String id=row.path("id").asText();
        if(category.equals("potions"))return "药水定义 "+id+"\n"+RuntimeRules.effectNames(row.path("effects"))+"\n等级=amplifier+1；持续时间单位为游戏tick，通常20tick/秒；-1表示无限。这是基础药水组成，不包括堆叠自定义效果和投掷/滞留等施加条件。\n"+row;
        if(category.equals("effects"))return "状态效果 "+id+"；分类 "+row.path("category").asText()+"；瞬时 "+row.path("instantaneous")+"\n等级=amplifier+1；持续时间来自药水或实际效果实例，不属于效果注册项固定属性。不能从颜色或有益分类推断具体伤害/治疗。\n"+row;
        return "附魔 "+id+"；等级 "+row.path("minLevel")+"–"+row.path("maxLevel")+"\n默认堆叠适用物品："+row.path("supportedItems")+"\n互斥附魔："+row.path("incompatible")+"\n附魔能量成本范围："+row.path("costs")+"；不是铁砧经验等级，也不是交易价格。定义/版本范围："+row.path("coverage").asText()+"\n数据驱动定义："+row.path("definition")+"\n标签："+row.path("tags")+"；宝藏："+row.path("treasureOnly")+"；诅咒："+row.path("curse")+"；可交易："+row.path("tradeable")+"；可发现："+row.path("discoverable")+"\n限制："+row.path("limitation").asText()+"\n随机附魔书不是指定附魔书；只有堆叠组件/NBT或明确规则指定附魔与等级时才可确认具体结果。";
    }
    static String references(JsonNode node,Map<String,JsonNode> categories){
        var ids=new LinkedHashSet<String>();var match=java.util.regex.Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+").matcher(node.toString());while(match.find())ids.add(match.group());var out=new StringBuilder();
        for(String category:CATEGORIES){var payload=categories.get(category);if(payload==null)continue;for(var row:payload.path("records"))if(!row.has("error")&&ids.contains(row.path("id").asText())){out.append("\n关联注册定义（引用不证明固定产出；随机候选、堆叠组件/NBT、附魔等级与条件仍以原规则为准）：\n");String text=category+" "+row.path("id").asText()+(category.equals("enchantments")?"；等级 "+row.path("minLevel")+"–"+row.path("maxLevel")+"；能量成本 "+row.path("costs")+"；互斥 "+row.path("incompatible"):category.equals("potions")?"；"+RuntimeRules.effectNames(row.path("effects")):"；分类 "+row.path("category").asText()+"；瞬时 "+row.path("instantaneous"));out.append(text,0,Math.min(1000,text.length()));}}
        return out.substring(0,Math.min(16000,out.length()));
    }
    static List<VectorStore.SearchResult> evidence(DatabaseBuilder db,Collection<String> ids,String question)throws Exception {
        if(!question.matches("(?s).*(附魔|药水|效果|酿造|交易|战利品|掉落).*"))return List.of();String profile=RuntimeKnowledgeStore.active(db);if(profile==null)return List.of();
        var targets=new LinkedHashSet<>(ids);var matcher=java.util.regex.Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+").matcher(question);while(matcher.find())targets.add(matcher.group());var out=new ArrayList<VectorStore.SearchResult>();
        try(var c=db.getConnection();var p=c.prepareStatement("SELECT record_data FROM rag_runtime_record WHERE profile_id=? AND category=? AND record_id=?")){
            for(String id:targets.stream().limit(20).toList())for(String category:CATEGORIES){p.setString(1,profile);p.setString(2,category);p.setString(3,id);try(var r=p.executeQuery()){if(r.next()){var row=JSON.readTree(r.getString(1));if(row.has("error"))continue;String path="mkb-runtime:"+profile+"/"+category+"/"+id;String text=describe(category,row);out.addAll(new RuntimeStructuredQuery(db,profile).evidencePages(new RuntimeStructuredQuery.Row(new RuntimeStructuredQuery.Key(category,id),row)));}}}
        }return List.copyOf(out);
    }
}
