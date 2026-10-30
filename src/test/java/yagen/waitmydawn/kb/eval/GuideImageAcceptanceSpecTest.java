package yagen.waitmydawn.kb.eval;

import org.junit.jupiter.api.Test;
import yagen.waitmydawn.kb.model.AnswerArtifacts;
import yagen.waitmydawn.kb.service.ImageGroundingAssessment;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 人工规定操作与用途期望；可用性检查不能替代这些正反例。 */
class GuideImageAcceptanceSpecTest {
    private final AnswerArtifacts.Source source=new AnswerArtifacts.Source("s1","guide","傀儡教程","https://example.com/guide","傀儡",null,"NATIVE","第1步组装；第2步升级。");
    private AnswerArtifacts artifacts(String purpose,String step,String block,String sourceId) {
        var grounding=new AnswerArtifacts.ImageGrounding(purpose,"CAPTION",block,step,"EXACT_BLOCK","OCR_OK");
        var picture=new AnswerArtifacts.Picture("i1","asset","asset.png","操作图",sourceId,"ILLUSTRATION",List.of(),"",null,null,grounding);
        return new AnswerArtifacts("pack","整合包",List.of(source),List.of(picture));
    }
    private GoldenSet.Turn turn(String question) {
        return new GoldenSet.Turn("R5-UPGRADE",question,"",List.of(),"image","guide",1,List.of(),List.of(),List.of(
                new GoldenSet.Assertion("answer_image_grounding_valid",List.of(),null,null,1,null,1,true),
                new GoldenSet.Assertion("answer_image_purpose_any",List.of("UI"),null,null,1,null,1,true)));
    }
    private RuleJudge.Observation observation(String answer,AnswerArtifacts artifacts) {
        return new RuleJudge.Observation(answer,false,null,List.of(),List.of(),0,List.of(),List.of(),List.of(),List.of(),"TIER B",1,0,0,true,Map.of(),artifacts);
    }
    @Test void matchedUpgradeUiPassesButAssemblyMapAndDecorationDoNot() {
        String answer="将升级芯片放入菜单。![升级界面](kb-image:i1)";
        assertTrue(RuleJudge.judge(turn("傀儡如何升级"),observation(answer,artifacts("UI","傀儡\n第2步：升级傀儡，放入升级芯片。","b2","s1"))).passed());
        assertFalse(RuleJudge.judge(turn("傀儡如何升级"),observation(answer,artifacts("STRUCTURE","傀儡\n第1步：组装傀儡，把核心放到底座。","b1","s1"))).passed());
        assertFalse(RuleJudge.judge(turn("傀儡如何升级"),observation(answer,artifacts("MAP","第2步：升级傀儡。","b2","s1"))).passed());
        assertFalse(RuleJudge.judge(turn("傀儡如何升级"),observation(answer,artifacts("DECORATIVE","第2步：升级傀儡。","b2","s1"))).passed());
    }
    @Test void onlyDisplayedImagesWithRealStepAndProvidedSourceCount() {
        var artifacts=artifacts("UI","第2步：升级傀儡。","b2","s1");
        assertEquals(0,ImageGroundingAssessment.assess("傀儡升级","没有配图。",artifacts).displayed());
        assertFalse(RuleJudge.judge(turn("傀儡升级"),observation("```\n![界面](kb-image:i1)\n```",artifacts)).passed());
        assertFalse(RuleJudge.judge(turn("傀儡升级"),observation("![界面](kb-image:i1)",artifacts("UI","第2步：升级傀儡。",null,"s1"))).passed());
        assertFalse(RuleJudge.judge(turn("傀儡升级"),observation("![界面](kb-image:i1)",artifacts("UI","第2步：升级傀儡。","b2","other"))).passed());
    }
    @Test void wrongNumberedStepFailsEvenWhenImagePurposeMatches() {
        var grade=ImageGroundingAssessment.assess("傀儡第2步","![操作](kb-image:i1)",artifacts("UI","第1步：右键核心。","b1","s1"));
        assertEquals(1,grade.mismatched());assertEquals("STEP_NUMBER_MISMATCH",grade.checks().getFirst().reason());
    }
    @Test void compoundQuestionRequiresBothAssemblyAndUpgradeIllustrations() {
        var turn=new GoldenSet.Turn("facets","组装并升级傀儡","",List.of(),"image","guide",1,List.of(),List.of(),List.of(
                new GoldenSet.Assertion("answer_image_facets_all",List.of("傀儡组装","傀儡升级"),null,null,1,null,1,true)));
        var assembly=artifacts("STRUCTURE","第1步：组装傀儡，把核心放到底座。","b1","s1");
        assertFalse(RuleJudge.judge(turn,observation("![组装](kb-image:i1)",assembly)).passed());
        var upgrade=artifacts("UI","第2步：升级傀儡，打开升级菜单。","b2","s1").pictures().getFirst();
        var second=new AnswerArtifacts.Picture("i2",upgrade.assetId(),upgrade.relativePath(),upgrade.caption(),upgrade.sourceId(),upgrade.role(),upgrade.chunkIds(),upgrade.context(),null,null,upgrade.grounding());
        var complete=new AnswerArtifacts("pack","整合包",assembly.sources(),List.of(assembly.pictures().getFirst(),second));
        assertTrue(RuleJudge.judge(turn,observation("![组装](kb-image:i1)\n\n![升级](kb-image:i2)",complete)).passed());
    }
}
