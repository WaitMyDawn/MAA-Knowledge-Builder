package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import yagen.waitmydawn.kb.model.RecipePlan;
import java.util.*;

/** 回答文本与配方卡片共用槽位、数量和状态语义；静态结构核对不证明运行时生效。 */
public final class RecipeSummary {
    private static final ObjectMapper JSON=new ObjectMapper();
    private RecipeSummary() {}
    public static String describe(String json,String output) {
        try {
            var plan=RecipePlan.parse(json);var root=JSON.readTree(json);var counts=new LinkedHashMap<String,Integer>();
            for(var slot:plan.slots())counts.merge(ingredient(slot.ingredient()),slot.ingredient().count(),Integer::sum);
            boolean needsExisting=plan.slots().stream().flatMap(s->s.ingredient().alternatives().stream()).anyMatch(a->!a.tag()&&a.id().equals(output));
            var result=new StringBuilder(needsExisting?"用途：修复或状态转换，必须已有目标物品，不是首次制作。\n":"用途：该配方的加工／转换关系；不能单凭配方推断材料的初次获取途径。\n");
            result.append("工作站：").append(plan.kind().station).append("；").append(plan.kind().label).append('\n');
            if(plan.output().dynamic())result.append("产物：由基底及状态决定，静态 JSON 无法确定精确物品状态。\n");
            else result.append("产物：").append(plan.output().itemId()).append(" × ").append(plan.output().count()).append('\n');
            if(plan.output().stateful())result.append("产物状态：").append(stateSummary(plan.output().state())).append('\n');
            counts.forEach((id,count)->result.append("- ").append(id).append(" × ").append(count).append('\n'));
            if(root.path("pattern").isArray()) {
                result.append("原始槽位图案（空格为空槽）：\n```\n");root.path("pattern").forEach(row->result.append(row.asText()).append('\n'));result.append("```\n符号对应：\n");
                root.path("key").fields().forEachRemaining(entry->result.append(entry.getKey()).append(" = ").append(ingredient(RecipePlan.ingredient(entry.getValue()))).append('\n'));
            }
            if(plan.cookingTicks()!=null)result.append("加工时间：").append(plan.cookingTicks()).append(" tick\n");
            plan.notes().forEach(note->result.append(note).append('\n'));
            return result.toString();
        }catch(Exception failure){return "保留原始 JSON：当前类型或结构未适配，不生成材料数量、图案或动态产物推断。";}
    }
    private static String ingredient(RecipePlan.Ingredient ingredient) {
        if(ingredient.unsupported()!=null)return ingredient.unsupported();
        var choices=ingredient.alternatives().stream().map(a->(a.tag()?"标签 #":"")+a.id()).toList();
        String label=choices.size()==1?choices.getFirst():"任选其一("+String.join(" / ",choices)+")";
        return ingredient.stateful()?label+"（指定状态："+stateSummary(ingredient.state())+"）":label;
    }
    private static String stateSummary(com.fasterxml.jackson.databind.JsonNode state) {
        if(state==null)return "未完整解析，须核对原始 JSON";
        String text=state.toString();return text.length()>600?text.substring(0,600)+"…（完整状态见原始 JSON）":text;
    }
}
