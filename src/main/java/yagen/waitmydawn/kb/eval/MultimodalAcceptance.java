package yagen.waitmydawn.kb.eval;

import java.util.*;

/** 固定图文题集的验收规则与人工事实复核边界；问题文件不携带实现细节。 */
public final class MultimodalAcceptance {
    private MultimodalAcceptance() {}
    public static final Map<String,List<String>> IDENTITIES=Map.ofEntries(
            Map.entry("MM-01",List.of("ITEM|minecraft:arrow")),
            Map.entry("MM-02",List.of("ITEM|minecraft:diamond_sword")),
            Map.entry("MM-03",List.of("RECIPE|minecraft:diamond_sword")),
            Map.entry("MM-04",List.of("ENTITY|minecraft:cow|age=adult","ENTITY|minecraft:cow|age=baby")),
            Map.entry("MM-05",List.of("ENTITY|iceandfire:fire_dragon|growth_stage=1","ENTITY|iceandfire:fire_dragon|growth_stage=5")),
            Map.entry("MM-06",List.of("ENTITY|iceandfire:fire_dragon|variant=red","ENTITY|iceandfire:ice_dragon|variant=blue")),
            Map.entry("MM-07",List.of("ENTITY|iceandfire:fire_dragon|growth_stage=5|sex=male|variant=red")),
            Map.entry("MM-11",List.of("ENTITY|minecraft:sheep|color=red","ENTITY|minecraft:sheep|color=blue")),
            Map.entry("MM-12",List.of("ENTITY|iceandfire:fire_dragon|variant=red|sex=female","ENTITY|iceandfire:fire_dragon|variant=green|sex=male")),
            Map.entry("MM-13",List.of("ENTITY|iceandfire:fire_dragon|growth_stage=1","ENTITY|iceandfire:fire_dragon|growth_stage=5","ENTITY|iceandfire:ice_dragon|growth_stage=1","ENTITY|iceandfire:ice_dragon|growth_stage=5")),
            Map.entry("MM-14",List.of("ITEM|minecraft:arrow")),
            Map.entry("MM-15",List.of("ENTITY|minecraft:cat|variant=minecraft:tabby")));
    public static final Set<String> ABSENT=Set.of("MM-08","MM-09","MM-10","MM-16");

    static GoldenSet.Turn apply(GoldenSet.Turn turn) {
        if(!turn.id().matches("MM-(?:0[1-9]|1[0-6]|MP-0[1-5]|DOC-0[1-3])"))return turn;
        var rules=new ArrayList<>(turn.assertions());
        rules.add(rule("no_error",List.of(),null,null));
        rules.add(rule("answer_media_refs_valid",List.of(),null,null));
        rules.add(rule("answer_media_sources_bound",List.of(),null,null));
        if(turn.id().matches("MM-(?:0[1-9]|1[0-6])")) {
            rules.add(rule("resource_http_zero",List.of(),null,null));
            rules.add(rule("runtime_snapshot_selected",List.of(),null,null));
        }
        if(IDENTITIES.containsKey(turn.id())) {
            rules.add(rule("answer_media_identity_all",IDENTITIES.get(turn.id()),null,null));
            rules.add(rule("answer_media_identity_only",IDENTITIES.get(turn.id()),null,null));
        }
        if(ABSENT.contains(turn.id())) {
            rules.add(rule("answer_images_absent",List.of(),null,null));
            rules.add(rule("answer_matches",List.of(),"(?s).*(未|没有|缺少|不可用|不支持|无法).*",null));
        }
        if(turn.id().startsWith("MM-MP-")) {
            rules.add(rule("knowledge_scope",List.of("mcmod:modpack:737"),null,null));
            rules.add(rule("answer_image_grounding_valid",List.of(),null,1));
            rules.add(rule("length_bounds",List.of(),null,240));
            if(turn.id().equals("MM-MP-02"))rules.add(rule("answer_image_facets_all",List.of("傀儡组装","傀儡升级"),null,1));
        }
        if(turn.id().startsWith("MM-DOC-")) {
            rules.add(rule("answer_image_grounding_valid",List.of(),null,1));
            if(turn.id().equals("MM-DOC-01"))rules.add(rule("knowledge_scope",List.of("mcmod:class:5214"),null,null));
            if(turn.id().equals("MM-DOC-02"))rules.add(rule("answer_image_page_any",List.of("2"),null,null));
        }
        var rubric=new ArrayList<>(turn.judgeRubric());
        rubric.add("核对实际讨论的对象、状态、配方或教程步骤；文字充分性与图片像素语义需人工复核，元数据或长度通过不代表事实正确。");
        rubric.add("网页知识与当前游戏快照分开表述，不能把未安装模组说成已安装；缺失状态明确说明，不能借用默认图片。");
        return new GoldenSet.Turn(turn.id(),turn.question(),turn.referenceAnswer(),turn.referenceSources(),turn.type(),turn.category(),turn.stabilityRuns(),turn.answerPoints(),List.copyOf(rubric),List.copyOf(rules));
    }

    private static GoldenSet.Assertion rule(String kind,List<String> values,String value,Integer min) {
        return new GoldenSet.Assertion(kind,values,value,null,min,null,1,true);
    }
}
