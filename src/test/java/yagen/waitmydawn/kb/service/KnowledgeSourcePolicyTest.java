package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeSourcePolicyTest {
    @Test void featureBoundsPreserveModInheritanceAndNewerVersions() {
        assertTrue(KnowledgeSourcePolicy.describe("minecraft", "1.21.1").contains("当前安装版没有"));
        assertFalse(KnowledgeSourcePolicy.describe("minecraft", "1.21.11").contains("当前安装版没有"));
        assertFalse(KnowledgeSourcePolicy.describe("minecraft", "26.1").contains("当前安装版没有"));
        assertFalse(KnowledgeSourcePolicy.describe("minecraft", null).contains("当前安装版没有"));
        assertFalse(KnowledgeSourcePolicy.describe("iceandfire", "1.21.1").contains("当前安装版没有"));
        assertTrue(KnowledgeSourcePolicy.describe("iceandfire", "1.21.1").contains("770.html"));
    }
    @Test void explicitBindingOverridesCanonicalSuggestionInAnswerContext(){
        var mod=ModUrlBindingTest.mod("iceandfire","neoforge");mod.setMcmodBinding(ModUrlBindingTest.binding("123",null,"MANUAL"));
        assertTrue(KnowledgeSourcePolicy.describe(mod).contains("123.html"));assertFalse(KnowledgeSourcePolicy.describe(mod).contains("770.html"));
    }
}
