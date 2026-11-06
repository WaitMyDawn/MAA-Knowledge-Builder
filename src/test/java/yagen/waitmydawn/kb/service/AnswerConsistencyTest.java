package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 结构化记录与答案缺失说明冲突的收尾回归。 */
class AnswerConsistencyTest {
    private static QaPipeline.QaResult result(String answer) {
        var result = new QaPipeline.QaResult();
        result.answer = answer;
        return result;
    }

    @Test void keepsContradictorySentenceInsteadOfDeletingFacts() {
        String original = "池 1 抽取 1 次，池 2 按权重选取；具体它位于哪个池、权重与数量，本轮证据未给出，无法确认确切概率。";
        var result = result(original);
        result.lootIds.add("irons_spellbooks:entities/evoker");
        String repaired = AnswerConsistency.repair(result, "背叛者徽记怎么获取？");
        assertTrue(repaired.contains("池 1 抽取 1 次"), repaired);
        assertTrue(repaired.contains("本轮证据未给出"), repaired);
    }

    @Test void appendsUnmentionedAcquisitionChannels() {
        var result = result("可通过流浪商人购买获得。");
        result.tradeIds.add("minecraft:wandering_trader");
        result.lootIds.add("iceandfire:entities/fire_dragon");
        String repaired = AnswerConsistency.repair(result, "传说墨水有哪些获取途径？");
        assertTrue(repaired.contains("战利品表"), repaired);
        assertTrue(repaired.contains("交易"), repaired);
    }

    @Test void leavesAnswersWithoutStructuredRecordsAlone() {
        var result = result("本轮没有取得足够证据。");
        assertEquals("本轮没有取得足够证据。", AnswerConsistency.repair(result, "某题"));
    }

    @Test void keepsExplicitChannelMentions() {
        var result = result("掉落自战利品箱；也可通过交易获得配方。");
        result.lootIds.add("a:b");
        result.tradeIds.add("a:c");
        String repaired = AnswerConsistency.repair(result, "某物品怎么获取？");
        assertEquals("掉落自战利品箱；也可通过交易获得配方。", repaired);
    }

    @Test void addsCheckedChannelLineWhenChannelsOnlyAppearInCaveats() {
        var result = result("炼金锅装瓶可获得；战利品表本轮未读取；流浪商人交易未确认。");
        result.lootIds.add("irons_spellbooks:magic_items/great_ink");
        String repaired = AnswerConsistency.repair(result, "传说墨水有哪些获取途径？");
        assertTrue(repaired.contains("已核对渠道"), repaired);
        assertTrue(TaskEvidence.positiveText(repaired).contains("战利品"), repaired);
        assertTrue(TaskEvidence.positiveText(repaired).contains("流浪商人"), repaired);
    }
}
