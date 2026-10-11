package yagen.waitmydawn.kb.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleJudgeTest {
    @Test void linksAndEmphasisDoNotBreakRelationAssertions() {
        var check = new GoldenSet.Assertion("answer_matches", List.of(), "摧毁.{0,10}水晶", null, null, null, 1, true);
        assertTrue(RuleJudge.judge(turn(check), obs("摧毁**[末地水晶](https://example.com/long-source)**", List.of(), List.of(), 0, "TIER B")).passed());
    }

    @Test void manyCoverageAssertionsAllowEightyPercentButHardFailuresRemainBlocking() {
        var checks = new java.util.ArrayList<GoldenSet.Assertion>();
        for (int i = 0; i < 6; i++) checks.add(assertion("answer_contains_any", List.of("点" + i), true));
        checks.add(assertion("no_error", List.of(), true));
        assertTrue(RuleJudge.judge(turn(checks.toArray(GoldenSet.Assertion[]::new)),
                obs("点0 点1 点2 点3 点4", List.of(), List.of(), 0, "TIER B")).passed());
        checks.add(assertion("answer_not_contains", List.of("编造"), true));
        assertFalse(RuleJudge.judge(turn(checks.toArray(GoldenSet.Assertion[]::new)),
                obs("点0 点1 点2 点3 点4 编造", List.of(), List.of(), 0, "TIER B")).passed());
    }

    @Test void refusalIgnoresMarkdownEmphasisAndStillRejectsFabricatedClaims() {
        var a=assertion("refusal",List.of("霸王龙生成在"),true);
        assertTrue(RuleJudge.judge(turn(a),obs("目录中**没有**名为 T-Rex 的正式条目。",List.of(),List.of(),0,"TIER C")).passed());
        assertFalse(RuleJudge.judge(turn(a),obs("目录中**没有**名为 T-Rex 的正式条目，但霸王龙**生成在**洞穴。",List.of(),List.of(),0,"TIER C")).passed());
    }
    @Test void openEnumerationCountsOnlyDistinctCatalogUrls() {
        var a=new GoldenSet.Assertion("answer_catalog_lists_at_least",List.of(),null,null,3,null,1,true);
        var observation=new RuleJudge.Observation("### 1. **雨蛙**：例子\n- 大猩猩：例子\n- 蜜袋鼯：例子",false,null,
                List.of(),List.of(),0,List.of(),List.of(),List.of(),List.of(),"TIER C",1,0,0,true,
                java.util.Map.of("雨蛙","a","大猩猩","b","蜜袋鼯","c"));
        assertTrue(RuleJudge.judge(turn(a),observation).passed());
        var duplicate=new RuleJudge.Observation(observation.answer(),false,null,List.of(),List.of(),0,List.of(),List.of(),List.of(),List.of(),"TIER C",1,0,0,true,
                java.util.Map.of("雨蛙","a","大猩猩","a","蜜袋鼯","c"));
        assertFalse(RuleJudge.judge(turn(a),duplicate).passed());
    }
    @Test void listedMembersExcludeIngredientSubstrings() {
        var a=new GoldenSet.Assertion("answer_lists_at_least",List.of("鲶鱼","大猩猩","蜜袋鼯"),null,null,3,null,1,true);
        assertFalse(RuleJudge.judge(turn(a),obs("- 大猩猩：香蕉\n- 蜜袋鼯：甜浆果\n- 生鲶鱼：食物",List.of(),List.of(),0,"TIER B")).passed());
        assertTrue(RuleJudge.judge(turn(a),obs("1. **大猩猩**：香蕉\n2. 蜜袋鼯（Sugar Glider）\n3. 鲶鱼：水生生物",List.of(),List.of(),0,"TIER B")).passed());
    }

    @Test void supplementalRecyclingDoesNotReplaceCoreAcquisition() {
        var a=new GoldenSet.Assertion("answer_matches",List.of(),"(?:搜刮[^。\\n]{0,20}龙尸|龙尸[^。\\n]{0,20}搜刮)",null,null,null,1,true);
        assertFalse(RuleJudge.judge(turn(a),obs("分解龙鳞块获得龙鳞。龙尸是龙的尸体。",List.of(),List.of(),0,"TIER A")).passed());
        assertTrue(RuleJudge.judge(turn(a),obs("空手右键搜刮龙尸获得龙鳞。也可分解龙鳞块。",List.of(),List.of(),0,"TIER A")).passed());
    }

    @Test void judgePromptIncludesEvidenceAndAllowsSupportedSupplements() {
        var outcome=LlmJudge.judge(null,turn(),"分解龙鳞块","JAR: 龙鳞块 -> 九个龙鳞");
        assertTrue(outcome.prompt().contains("JAR: 龙鳞块 -> 九个龙鳞"));
        assertTrue(outcome.prompt().contains("补充不能替代核心"));
    }

    private static GoldenSet.Assertion assertion(String kind, List<String> values, boolean required) {
        return new GoldenSet.Assertion(kind, values, null, null, null, null, 1.0, required);
    }

    private static GoldenSet.Turn turn(GoldenSet.Assertion... assertions) {
        return new GoldenSet.Turn("T1", "q", "", List.of(), "basic", "recipe", 1,
                List.of(), List.of(), List.of(assertions));
    }

    private static RuleJudge.Observation obs(String answer, List<String> recipes,
                                             List<String> registries, int render, String tier) {
        return new RuleJudge.Observation(answer, false, null, recipes, registries, render,
                List.of(), List.of(), List.of(), List.of(), tier, 1000, 10, 20, true);
    }

    @Test
    void passesWhenRequiredAssertionsSatisfied() {
        RuleJudge.Verdict v = RuleJudge.judge(
                turn(assertion("answer_contains_all", List.of("钻石剑", "木棍"), true)),
                obs("钻石剑需要 2 个钻石和 1 个木棍", List.of(), List.of(), 0, "TIER A"));

        assertTrue(v.passed());
        assertTrue(v.allRequiredPassed());
        assertTrue(v.score() >= 0.8);
    }

    @Test
    void failsWhenRequiredAssertionMissing() {
        RuleJudge.Verdict v = RuleJudge.judge(
                turn(assertion("answer_contains_all", List.of("钻石剑", "木棍"), true)),
                obs("钻石剑需要 2 个钻石", List.of(), List.of(), 0, "TIER A"));

        assertFalse(v.passed());
        assertFalse(v.allRequiredPassed());
        assertFalse(v.failedOutcomes().isEmpty());
    }

    @Test
    void optionalAssertionFailureStillPassesWhenScoreAboveThreshold() {
        // required 权重 5、optional 权重 1：optional 失败后 5/6=0.83 >= 0.8，仍应通过
        GoldenSet.Assertion required = new GoldenSet.Assertion("answer_contains_all",
                List.of("钻石剑"), null, null, null, null, 5.0, true);
        GoldenSet.Assertion optional = new GoldenSet.Assertion("answer_contains_any",
                List.of("不存在的关键词"), null, null, null, null, 1.0, false);
        RuleJudge.Verdict v = RuleJudge.judge(
                turn(required, optional),
                obs("钻石剑的做法", List.of(), List.of(), 0, "TIER A"));

        assertTrue(v.passed());
        assertTrue(v.score() < 1.0);
    }

    @Test
    void refusalAssertionDetectsRefusalAndFabrication() {
        GoldenSet.Assertion a = assertion("refusal", List.of("twilightforest:hydra"), true);

        RuleJudge.Verdict refused = RuleJudge.judge(turn(a),
                obs("相关知识不在当前知识库中，建议先安装暮色森林模组", List.of(), List.of(), 0, "TIER D"));
        assertTrue(refused.passed());

        RuleJudge.Verdict fabricated = RuleJudge.judge(turn(a),
                obs("九头蛇的弱点是火焰，你可以用 twilightforest:hydra 掉落物", List.of(), List.of(), 0, "TIER C"));
        assertFalse(fabricated.passed());
    }

    @Test
    void tierAndRenderAssertions() {
        GoldenSet.Assertion tier = new GoldenSet.Assertion("tier_at_least", List.of(), "TIER B",
                null, null, null, 1.0, true);
        assertTrue(RuleJudge.judge(turn(tier), obs("x", List.of(), List.of(), 0, "TIER A")).passed());
        assertFalse(RuleJudge.judge(turn(tier), obs("x", List.of(), List.of(), 0, "TIER C")).passed());

        GoldenSet.Assertion render = new GoldenSet.Assertion("render_expected", List.of(), null,
                true, null, null, 1.0, true);
        assertTrue(RuleJudge.judge(turn(render), obs("x", List.of(), List.of(), 2, "TIER A")).passed());
        assertFalse(RuleJudge.judge(turn(render), obs("x", List.of(), List.of(), 0, "TIER A")).passed());
    }

    @Test
    void recipeOutputAndEntityAssertions() {
        RuleJudge.Verdict v = RuleJudge.judge(
                turn(assertion("recipe_output_any", List.of("minecraft:diamond_sword"), true),
                        assertion("entity_resolved_any", List.of("minecraft:diamond_sword"), true)),
                obs("答案", List.of("minecraft:diamond_sword"), List.of("minecraft:diamond_sword"), 1, "TIER A"));

        assertTrue(v.passed());
    }

    @Test
    void errorObservationFailsNoErrorAssertion() {
        RuleJudge.Verdict v = RuleJudge.judge(
                turn(new GoldenSet.Assertion("no_error", List.of(), null, null, null, null, 1.0, true)),
                RuleJudge.Observation.error("boom"));

        assertFalse(v.passed());
    }

    @Test
    void renderImageAssertionChecksTypeAndValidity() {
        GoldenSet.Assertion img = new GoldenSet.Assertion("render_image_valid", List.of(),
                "smithing", null, null, null, 1.0, true);

        RuleJudge.Observation ok = new RuleJudge.Observation("x", false, null, List.of(), List.of(),
                1, List.of(new RuleJudge.RenderedImage("i", "smithing", "p.png", 2048, true)),
                List.of(), List.of(), List.of(), "TIER A", 1000, 10, 20, true);
        assertTrue(RuleJudge.judge(turn(img), ok).passed());

        RuleJudge.Observation wrongType = new RuleJudge.Observation("x", false, null, List.of(), List.of(),
                1, List.of(new RuleJudge.RenderedImage("i", "crafting", "p.png", 2048, true)),
                List.of(), List.of(), List.of(), "TIER A", 1000, 10, 20, true);
        assertFalse(RuleJudge.judge(turn(img), wrongType).passed());

        RuleJudge.Observation invalid = new RuleJudge.Observation("x", false, null, List.of(), List.of(),
                1, List.of(new RuleJudge.RenderedImage("i", "smithing", "p.png", 0, false)),
                List.of(), List.of(), List.of(), "TIER A", 1000, 10, 20, true);
        assertFalse(RuleJudge.judge(turn(img), invalid).passed());
    }

    @Test
    void webMappingNodeAssertions() {
        GoldenSet.Assertion cache = assertion("web_cache_has_label", List.of("弹跳机兵"), true);
        GoldenSet.Assertion subpage = assertion("subpage_url_any", List.of("mcmod.cn"), true);

        RuleJudge.Observation ok = new RuleJudge.Observation("x", false, null, List.of(), List.of(),
                0, List.of(), List.of("弹跳机兵(Boundroid)"), List.of("https://www.mcmod.cn/item/750361.html"),
                List.of("弹跳机兵(Boundroid) -> https://www.mcmod.cn/item/750361.html"),
                "TIER A", 1000, 10, 20, true);
        assertTrue(RuleJudge.judge(turn(cache, subpage), ok).passed());

        RuleJudge.Observation miss = new RuleJudge.Observation("x", false, null, List.of(), List.of(),
                0, List.of(), List.of(), List.of(), List.of(), "TIER A", 1000, 10, 20, true);
        assertFalse(RuleJudge.judge(turn(cache, subpage), miss).passed());
    }

    @Test
    void containsAtLeastCountsHits() {
        GoldenSet.Assertion atLeast = new GoldenSet.Assertion("answer_contains_at_least",
                List.of("森蚺", "大鳄龟", "大猩猩", "蜜袋鼯", "冠蓝鸦", "袋鼠"),
                null, null, 3, null, 1.0, true);

        assertTrue(RuleJudge.judge(turn(atLeast),
                obs("例如森蚺、大猩猩和袋鼠", List.of(), List.of(), 0, "TIER A")).passed());
        assertFalse(RuleJudge.judge(turn(atLeast),
                obs("例如森蚺和大猩猩", List.of(), List.of(), 0, "TIER A")).passed());
    }

    @Test
    void refusalAcceptsPremiseCorrection() {
        GoldenSet.Assertion refusal = assertion("refusal", List.of("鞘翅的合成配方是"), true);
        // 没有"抱歉/知识库"等标记，但明确纠正了前提
        assertTrue(RuleJudge.judge(turn(refusal),
                obs("鞘翅不能合成，唯一获取途径是末地船", List.of(), List.of(), 0, "TIER B")).passed());
    }
}
