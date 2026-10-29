package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** 保留规则结构与未知节点，不把权重、条件概率或嵌套引用误报为最终掉落率。 */
final class RuntimeLootSummary {
    static String capability(yagen.waitmydawn.kb.model.DatabaseBuilder db,String question) {
        if(!question.toLowerCase(Locale.ROOT).matches("(?s).*(掉落|战利品|loot|drop).*"))return null;
        String profile=RuntimeKnowledgeStore.active(db);if(profile==null)return "当前没有运行时掉落快照；网页掉落说明不能证明当前整合包的实际规则。";
        try(var c=db.getConnection();var p=c.prepareStatement("SELECT descriptor FROM rag_runtime_snapshot WHERE profile_id=?")) {
            p.setString(1,profile);try(var r=p.executeQuery()) {
                if(!r.next())return "当前没有运行时掉落快照。";
                var categories=new com.fasterxml.jackson.databind.ObjectMapper().readTree(r.getString(1)).path("categories");
                for(String category:List.of("loot_tables","loot_modifiers"))if(!"COMPLETE".equals(categories.path(category).path("status").asText()))return "当前快照的掉落表或全局掉落修饰器未完整导出；未命中记录不等于没有掉落，请重新导出并核对。";
            }
        }catch(Exception failure){throw new IllegalStateException("掉落快照能力读取失败",failure);}
        return "掉落证据来自当前运行时规则快照；条件、工具、附魔、全局修饰器及脚本事件可能影响结果。引用候选和权重不是最终掉落率，不得声称必掉或穷尽所有来源。";
    }
    static String describe(String category,JsonNode row) {
        var references=new TreeSet<String>();references(row.path("definition"),references);
        return (category.equals("loot_tables")?"掉落表":"按执行顺序 "+row.path("order").asInt()+" 的全局掉落修饰器")+"："+row.path("id").asText()
                +"\n这是生效运行时对象的序列化规则。下列物品、标签或表引用只是规则中的候选，不保证必掉。条件、函数、工具、玩家、附魔及其他修饰器会影响结果；不能据此计算最终概率。Java 或脚本事件仍可能改变结果。"
                +"\n规则引用："+String.join("、",references)+"\n完整条件与函数规则：\n"+row.path("definition");
    }
    private static void references(JsonNode node,Set<String> values) {
        if(node.isTextual()&&node.asText().matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))values.add(node.asText());
        else if(node.isContainerNode())for(var child:node)references(child,values);
    }
}
