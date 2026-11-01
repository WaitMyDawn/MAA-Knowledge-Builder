package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.util.*;

/** 新增规则保持原始随机参数和上下文，文字说明不把候选权重解释为最终获得概率。 */
public final class RuntimeRules {
    public static final Set<String> CATEGORIES=Set.of("trades","brewing","workstation_rules");
    private RuntimeRules(){}
    static void validate(String category,JsonNode row)throws IOException {
        switch(category){
            case "trades"->{resource(row,"entity");resource(row,"profession");if(!row.path("entries").isArray()||row.path("entries").size()>10000||!row.path("draws").isIntegralNumber()||row.path("draws").asInt()<0||row.path("draws").asInt()>row.path("entries").size())throw new IOException("交易池结构无效");if(row.path("candidateCount").asInt(-1)!=row.path("entries").size())throw new IOException("交易候选计数无效");var seen=new HashSet<Integer>();for(var entry:row.path("entries")){if(!entry.path("index").isIntegralNumber()||entry.path("index").asInt()<0||entry.path("index").asInt()>=row.path("entries").size()||!seen.add(entry.path("index").asInt())||!Set.of("DECLARATIVE","RANDOM_RULE","UNSUPPORTED").contains(entry.path("status").asText()))throw new IOException("交易条目身份或状态无效");double probability=entry.path("selectionProbability").asDouble(Double.NaN);if(!Double.isFinite(probability)||probability<0||probability>1||!entry.path("weight").isNumber()||entry.path("weight").asDouble()<=0)throw new IOException("交易概率或权重无效");if(!entry.path("status").asText().equals("UNSUPPORTED")&&(!entry.path("inputs").isArray()||!entry.path("output").isObject()))throw new IOException("交易输入/输出缺失");}}
            case "brewing"->{if(!Set.of("POTION_CONVERSION","CONTAINER_CONVERSION","LOADER_DECLARED").contains(row.path("kind").asText())||!row.path("input").isContainerNode()||!row.path("ingredient").isContainerNode()||!row.path("output").isObject())throw new IOException("酿造规则无效");}
            case "workstation_rules"->{resource(row,"station");if(!row.path("operations").isArray())throw new IOException("工作站操作规则缺失");}
            default->throw new IOException("未知规则类别");
        }
    }
    private static void resource(JsonNode row,String field)throws IOException{if(!row.path(field).asText().matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IOException("资源注册名无效："+field);}
    public static String capabilities(yagen.waitmydawn.kb.model.DatabaseBuilder db){try{return readCapabilities(db);}catch(Exception failure){throw new IllegalStateException("快照能力声明读取失败",failure);}}
    private static String readCapabilities(yagen.waitmydawn.kb.model.DatabaseBuilder db)throws Exception {
        String profile=RuntimeKnowledgeStore.active(db);if(profile==null)return "没有活动实机快照；不能把未导出类别判为不存在。";
        try(var c=db.getConnection();var p=c.prepareStatement("SELECT descriptor FROM rag_runtime_snapshot WHERE profile_id=?")){p.setString(1,profile);try(var rows=p.executeQuery()){if(!rows.next())return "缺少快照能力声明";var categories=new ObjectMapper().readTree(rows.getString(1)).path("categories");var values=new ArrayList<String>();for(String category:List.of("trades","brewing","workstation_rules","loot_entrypoints","potions","effects","enchantments","spawn_rules","structures","structure_templates","multiblocks"))values.add(category+"="+categories.path(category).path("status").asText("未导出"));return "当前快照能力："+String.join("；",values)+"。未导出/NOT_IMPLEMENTED 表示能力缺口，不表示游戏中没有该内容；COMPLETE_WITH_ERRORS 与记录内 PARTIAL/UNSUPPORTED 需要披露限制。";}}
    }
    static String aliases(JsonNode row,Map<String,JsonNode> languages,Map<String,Set<String>> itemNames){
        var labels=new LinkedHashSet<String>();collectAliases(row,languages,itemNames,labels);
        String entity=row.path("entity").asText(),profession=row.path("profession").asText();
        for(var translations:languages.values())for(String side:List.of("inputEffects","outputEffects"))for(var effect:row.path(side)){String key="effect."+effect.path("effect").asText().replace(':','.');String translated=translations.path(key).asText();if(!translated.isBlank()){int level=effect.path("amplifier").asInt()+1;String numeral=level>=1&&level<=10?List.of("I","II","III","IV","V","VI","VII","VIII","IX","X").get(level-1):String.valueOf(level);labels.add(translated+" "+numeral);}}
        for(var translations:languages.values()){
            String station=row.path("station").asText();if(station.contains(":"))addLabel(labels,translations.path("block."+station.replace(':','.')).asText());
            if(entity.contains(":"))addLabel(labels,translations.path("entity."+entity.replace(':','.')).asText());
            if(profession.contains(":"))addLabel(labels,translations.path("entity.minecraft.villager."+profession.substring(profession.indexOf(':')+1)).asText());
        }return String.join("、",labels);
    }
    private static void addLabel(Set<String> labels,String value){if(value!=null&&!value.isBlank())labels.add(value);}
    private static void collectAliases(JsonNode node,Map<String,JsonNode> languages,Map<String,Set<String>> names,Set<String> labels){if(node.isContainerNode()){node.forEach(child->collectAliases(child,languages,names,labels));return;}if(node.isTextual()){String value=node.asText();labels.addAll(names.getOrDefault(value,Set.of()));for(var translations:languages.values())if(value.startsWith("effect."))addLabel(labels,translations.path(value).asText());}}
    static String effectNames(JsonNode effects){var names=new ArrayList<String>();for(var effect:effects){int level=effect.path("amplifier").asInt()+1;String numeral=level>=1&&level<=10?List.of("I","II","III","IV","V","VI","VII","VIII","IX","X").get(level-1):String.valueOf(level);names.add(effect.path("name").asText(effect.path("effect").asText())+" "+numeral+"（"+effect.path("durationTicks")+" tick）");}return String.join("、",names);}
    static Map<String,Set<String>> tradeOutputsByNamespace(JsonNode pool){var grouped=new TreeMap<String,Set<String>>();for(String collection:List.of("entries","verifiedGroupRules"))for(var entry:pool.path(collection)){addOutput(grouped,entry.path("output"));for(var output:entry.path("outputAlternatives"))addOutput(grouped,output);}return grouped;}
    private static void addOutput(Map<String,Set<String>> grouped,JsonNode stack){String id=stack.path("item").asText(stack.path("id").asText());if(id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))grouped.computeIfAbsent(id.substring(0,id.indexOf(':')),ignored->new TreeSet<>()).add(id);}
    static String namespace(String id){int split=id.indexOf(':');return split>0?id.substring(0,split):"mkb_export";}
    public static String focusedTrade(JsonNode pool,String item){return "按目标产物选择完整交易条目；候选数、抽取次数和覆盖情况仍指原池，未展示条目不代表不存在。\n"+describe("trades",focusedTradeRecord(pool,Set.of(item)));}
    static JsonNode focusedTradeRecord(JsonNode pool,Set<String> items){var view=((com.fasterxml.jackson.databind.node.ObjectNode)pool).deepCopy();var entries=new ObjectMapper().createArrayNode();for(var entry:pool.path("entries"))if(items.stream().anyMatch(item->outputMatches(entry,item)))entries.add(entry);var groups=new ObjectMapper().createArrayNode();for(var group:pool.path("verifiedGroupRules"))if(items.stream().anyMatch(item->outputMatches(group,item)))groups.add(group);view.set("entries",entries);view.set("verifiedGroupRules",groups);view.remove("identicalFixedOfferGroups");view.put("selectedView",true);return view;}
    private static boolean outputMatches(JsonNode entry,String item){if(item.equals(entry.path("output").path("item").asText(entry.path("output").path("id").asText())))return true;for(var output:entry.path("outputAlternatives"))if(item.equals(output.path("item").asText(output.path("id").asText())))return true;return false;}
    public static String describe(String category,JsonNode row){
        if(row.has("error"))return "导出失败："+row.path("error").asText();
        if(category.equals("trades")){
            var text=new StringBuilder("交易实体："+row.path("entity").asText()+"；职业："+row.path("profession").asText()+"；等级/池："+row.path("level")+"；候选数："+row.path("candidateCount")+"；选择次数："+row.path("draws")+"；覆盖："+row.path("coverage").asText()+"\n");
            for(var entry:row.path("entries")){text.append("条目 ").append(entry.path("index").asInt()+1).append("；工厂入选概率 ").append(entry.path("selectionProbability")).append("；权重 ").append(entry.path("weight")).append("；状态 ").append(entry.path("status").asText()).append("\n");if(entry.path("status").asText().equals("UNSUPPORTED"))text.append(entry.path("factory").asText()).append("：").append(entry.path("reason").asText()).append('\n');else text.append("输入：").append(entry.path("inputs")).append(" → 输出：").append(entry.path("output")).append("\n补充随机参数：").append(entry).append('\n');}
            if(row.has("verifiedGroupRules"))text.append("已验证工厂组规则（不映射到不透明lambda实例）：").append(row.path("verifiedGroupRules")).append('\n');
            return text.append("当前统一接口覆盖村民与流浪商人池；自定义商人系统可能需要额外适配。候选入选率不是具体随机附魔、等级或最终成功交易的概率。未解析条目不代表不存在交易。价格会受需求、折扣和上下文影响。\n").toString();
        }
        if(category.equals("brewing"))return effectNames(row.path("inputEffects"))+" → "+effectNames(row.path("outputEffects"))+"\n酿造台：输入 "+row.path("input")+" + 材料 "+row.path("ingredient")+" → 输出 "+row.path("output")+"；"+(row.path("preservePotion").asBoolean()?"转换容器且保留输入药水状态；":"")+"一次最多3瓶；基准400 tick；烈焰粉每份支持20次。仅声明规则，不保证模组钩子未覆盖。\n"+row;
        return "工作方块："+row.path("station").asText()+"；范围："+row.path("coverage").asText()+"\n"+row.path("operations")+"\n限制："+row.path("limitation").asText();
    }
}
