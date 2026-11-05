package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 取证下限的口径回归：动作/获取词表覆盖真实写法，代词题仍按原句法判断缺口。
 */
class TaskEvidenceTest {
    @Test void forgeAndCraftWordsCountAsAcquisitionChannels() {
        String pattern = TaskEvidence.requirements("冰火传说龙钢如何获得？").get("获取途径");
        for (String word : List.of("烧炼", "熔炼", "锻造", "加工", "合成", "掉落", "交易")) {
            assertTrue(Pattern.compile(pattern).matcher(word).find(), word);
        }
    }

    @Test void feedingCountsAsInteraction() {
        String pattern = TaskEvidence.requirements("森蚺能驯服吗？玩家可以如何与它交互？").get("交互操作");
        for (String word : List.of("喂食", "投喂", "右键", "点击")) {
            assertTrue(Pattern.compile(pattern).matcher(word).find(), word);
        }
    }

    @Test void equippingAloneIsNotYetAnOperation() {
        String pattern = TaskEvidence.requirements("铁魔法的法术书有什么用，如何获取和使用？").get("使用操作");
        assertFalse(Pattern.compile(pattern).matcher("装备到饰品栏，通过法术轮盘选择法术").find());
        assertTrue(Pattern.compile(pattern).matcher("右键施放").find());
    }

    @Test void pronounQuestionStillUsesSentenceLevelGap() {
        String answer = "本轮证据没有包含“如何驯服野生龙”的操作说明。";
        assertTrue(TaskEvidence.missing("怎么驯服它们？", answer).contains("交互或驯服方法"));
    }

    @Test void headingAloneDoesNotProveBuildLayout() {
        String answer = "## 三、搭建与使用\n\n本轮未提供具体搭建布局（方块摆放位置、尺寸）。";
        assertTrue(TaskEvidence.missing("龙钢锻炉怎么搭建？请说明多方块结构。", answer).contains("搭建布局与操作"));
        String body = "## 结构\n\n按 3×3×3 分三层搭建：底层四角龙骨块，中层中心放核心，顶层同底层。";
        assertTrue(TaskEvidence.missing("龙钢锻炉怎么搭建？请说明多方块结构。", body).isEmpty());
    }

    @Test void coveredAnswerWithoutGapSentencePasses() {
        String answer = "不能驯服。玩家可以用生鸡肉喂食使其繁殖，击杀后获得经验。";
        assertTrue(TaskEvidence.missing("森蚺能驯服吗？玩家可以如何与它交互？", answer).isEmpty());
    }
}
