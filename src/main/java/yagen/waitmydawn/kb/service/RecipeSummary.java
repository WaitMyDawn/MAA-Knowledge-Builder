package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** 材料数量由配方槽位计算，标签和候选材料保留原语义，不猜测标签成员。 */
public final class RecipeSummary {
    private static final ObjectMapper JSON=new ObjectMapper();
    private RecipeSummary() {}
    public static String describe(String json,String output) {
        try {
            JsonNode root=JSON.readTree(json);
            var counts=new LinkedHashMap<String,Integer>();
            StringBuilder pattern=new StringBuilder();
            if(root.path("pattern").isArray()) {
                for(var row:root.path("pattern")) {
                    String text=row.asText(); pattern.append(text).append('\n');
                    for(char symbol:text.toCharArray()) if(symbol!=' ') counts.merge(ingredient(root.path("key").path(String.valueOf(symbol))),1,Integer::sum);
                }
            } else {
                JsonNode ingredients=root.has("ingredients")?root.path("ingredients"):root.path("ingredient");
                if(ingredients.isArray()) for(var value:ingredients) counts.merge(ingredient(value),1,Integer::sum);
                else if(!ingredients.isMissingNode()) counts.merge(ingredient(ingredients),1,Integer::sum);
            }
            if(counts.isEmpty()) return "配方类型 "+root.path("type").asText()+"：保留原始 JSON，当前类型不生成材料数量。";
            boolean needsExisting=counts.keySet().stream().anyMatch(i -> i.equals(output));
            StringBuilder result=new StringBuilder(needsExisting?"用途：修复或状态转换，必须已有目标物品，不是首次制作。\n":"用途：该配方的加工／转换关系；不能单凭配方推断材料的初次获取途径。\n");
            result.append("产物：").append(output).append(" × ").append(root.path("result").path("count").asInt(1)).append('\n');
            counts.forEach((id,count)->result.append("- ").append(id).append(" × ").append(count).append('\n'));
            if(!pattern.isEmpty()) {
                result.append("原始槽位图案（空格为空槽）：\n```\n").append(pattern).append("```\n符号对应：\n");
                root.path("key").fields().forEachRemaining(e -> result.append(e.getKey()).append(" = ").append(ingredient(e.getValue())).append('\n'));
            }
            return result.toString();
        } catch(Exception e) { return "配方无法生成结构化说明，不推断数量或图案。"; }
    }
    private static String ingredient(JsonNode node) {
        if(node.isTextual()) return node.asText();
        if(node.isArray()) { var choices=new ArrayList<String>(); node.forEach(n -> choices.add(ingredient(n))); return "任选其一("+String.join(" / ",choices)+")"; }
        if(node.has("tag")) return "标签 #"+node.path("tag").asText();
        return node.path("id").asText(node.path("item").asText("未知材料"));
    }
}
