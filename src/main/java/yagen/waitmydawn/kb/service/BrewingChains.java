package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** 用物品及完整药水状态连接酿造步骤；容器转换只替换物品类型，不清空状态。 */
final class BrewingChains {
    private final Map<String,List<JsonNode>> outputs=new HashMap<>();
    private final List<JsonNode> containers=new ArrayList<>();
    BrewingChains(JsonNode payload){if(payload==null)return;for(var row:payload.path("records")){if(row.has("error"))continue;if(row.path("preservePotion").asBoolean())containers.add(row);else outputs.computeIfAbsent(key(row.path("output")),ignored->new ArrayList<>()).add(row);}}
    String prerequisites(JsonNode rule){
        var text=new StringBuilder("酿造前置转换（同一物品和药水状态；可能有替代路线）：\n");var queue=new ArrayDeque<JsonNode>();queue.add(rule.path("input"));var states=new HashSet<String>();var rules=new HashSet<String>();int steps=0;
        while(!queue.isEmpty()&&steps<64){var state=queue.remove();if(!state.isObject()||!states.add(key(state)))continue;
            for(var parent:outputs.getOrDefault(key(state),List.of())){if(!rules.add(parent.path("id").asText()))continue;text.append(parent.path("id").asText()).append("：").append(parent.path("input")).append(" + ").append(parent.path("ingredient")).append(" → ").append(parent.path("output")).append('\n');queue.add(parent.path("input"));steps++;}
            for(var conversion:containers){if(!item(conversion.path("output")).equals(item(state)))continue;var before=((ObjectNode)state).deepCopy();before.remove("id");before.put("item",item(conversion.path("input")));String signature=conversion.path("id").asText()+key(before);if(!rules.add(signature))continue;text.append(conversion.path("id").asText()).append("：").append(before).append(" + ").append(conversion.path("ingredient")).append(" → ").append(state).append("（保留药水状态）\n");queue.add(before);steps++;}
        }
        if(!queue.isEmpty())text.append("前置路线达到64步上限；不能视为穷尽所有路线。\n");return text.toString();
    }
    private static String canonical(JsonNode node){if(node.isObject()){var fields=new TreeMap<String,String>();node.fields().forEachRemaining(entry->fields.put(entry.getKey(),canonical(entry.getValue())));return fields.toString();}if(node.isArray()){var values=new ArrayList<String>();node.forEach(child->values.add(canonical(child)));return values.toString();}return node.toString();}
    private static String item(JsonNode node){return node.path("item").asText(node.path("id").asText());}
    private static String key(JsonNode node){if(!node.isObject())return node.toString();var result=new TreeMap<String,String>();node.fields().forEachRemaining(entry->{if(!entry.getKey().equals("count")&&!entry.getKey().equals("id")&&!entry.getKey().equals("item"))result.put(entry.getKey(),canonical(entry.getValue()));});return item(node)+result;}
}
