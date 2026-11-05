package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RuleCardServiceTest {
    @Test void randomPriceRuleIsSummarizedWithoutHardcodedItemNames() {
        String rule = "For uniformly chosen enchantment and uniformly chosen allowed level L: min(64,(2+uniformInt(0,4+10*L)+3*L)*(doublePrice?2:1)); empty candidate pool follows version-specific behavior";
        String summary = RuleCardService.summarizePriceRule(rule);
        assertTrue(summary.contains("随机魔咒"));
        assertTrue(summary.contains("64"));
        assertTrue(summary.contains("翻倍"));
        assertFalse(summary.contains("For uniformly"));
    }

    @Test void ordinaryRuleIsNotRewritten() {
        assertEquals("固定价格", RuleCardService.summarizePriceRule("固定价格"));
    }
}
