package yagen.waitmydawn.kb.eval;

import java.util.List;

/** 真实教程图片经人工查看后的操作期望；不以检索器输出反向定义正确图片。 */
public final class GuideImage737Spec {
    private GuideImage737Spec() {}
    public record Case(String title,String question,String expectedAsset,String expectedPurpose,String visualEvidence) {}
    public static final List<Case> CASES=List.of(
            new Case("切石加工","龙之冒险新征程如何把傀儡胚料放入切石机，切成身体、双腿和手臂？",
                    "23e939237a14646d30aecff1b6dbb6288a28d42216446abbc369787b715305f4","UI","切石机界面中有胚料输入与部件选择。"),
            new Case("铁砧加工","龙之冒险新征程用铁砧加工傀儡部件时，需要几个材料？",
                    "c2058532745772cd1b42bc463b7f97652e68f2297d2905bd8d0f13212032d9a4","UI","修复和命名界面展示部件与材料合并，不能代替部件摆放图。"),
            new Case("部件摆放","龙之冒险新征程傀儡的各部件如何摆放组装？",
                    "bab6d3c62f003d0a80fcc0ceedaf9f8b770dd53d0eaeac425c5ac2e03659533d","RECIPE","合成网格展示身体、双臂和双腿的位置。"),
            new Case("替换部件","龙之冒险新征程如何替换傀儡的部件？",
                    "0146c82b5dafedeeeed17d8bc2d6a1a348f256120712434c05a745c2424ce65a","RECIPE","已有傀儡与替换手臂进入合成，输出换装后的傀儡。"),
            new Case("升级不借用组装图","龙之冒险新征程如何给傀儡升级和装备？",null,null,"当前缓存的组装图片不证明升级或装备操作。"),
            new Case("无依据不配图","龙之冒险新征程量子跃迁维护规程每一步需要多少材料？",null,null,"无对应步骤依据时不借用同来源的其他图片。")
    );
}
