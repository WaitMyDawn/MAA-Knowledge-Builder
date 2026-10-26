package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.Test;
import yagen.waitmydawn.kb.model.AnswerArtifacts;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ModpackSourcesTest {
    private AnswerArtifacts artifacts() {
        return new AnswerArtifacts("mcmod:modpack:737","龙之冒险",List.of(
                new AnswerArtifacts.Source("s1","d1","全流程发展路线","https://www.mcmod.cn/post/5454.html","武器",null,null,"寒冰剑"),
                new AnswerArtifacts.Source("s2","d1","全流程发展路线","https://WWW.MCMOD.CN:443/post/5454.html#armor","装备",null,null,"龙炎钢"),
                new AnswerArtifacts.Source("s3","d2","战斗教程","https://www.mcmod.cn/post/5355.html","战斗",null,null,"亚波伦")),List.of());
    }

    @Test void chunkCitationsBecomeOneFooterLinkPerPageAndKeepSnapshots() {
        var artifacts=artifacts();
        String answer=AnswerMedia.bind("寒冰剑。[[source:s1]]\n\n龙炎钢。[[source:s2]] [[source:s1]]\n\n战斗。[[source:s3]]",artifacts);
        assertEquals(1,occurrences(answer,"全流程发展路线"));
        assertEquals(2,occurrences(answer,"kb-source:"));
        assertTrue(answer.indexOf("资料来源：")>answer.indexOf("战斗。"));
        assertEquals(answer,ModpackSources.format(answer,artifacts));
        assertEquals(3,artifacts.sources().size());
        assertEquals(List.of("s1","s2"),AnswerMedia.snapshotCitations("文字[[source:s1]] [[source:s2]]",artifacts).citedSourceIds());
        assertEquals(Set.of("s1","s3"),AnswerMedia.citedSourceIds(answer,artifacts));
    }

    @Test void oldHistoryIsFormattedWithoutLosingImagesOrCode() {
        var artifacts=artifacts();
        String old="寒冰剑。[全流程发展路线](kb-source:s1)\n\n龙炎钢。[全流程发展路线](kb-source:s2)\n\n![图](kb-image:i1)\n\n`[示例](kb-source:s3)`";
        String result=ModpackSources.format(old,artifacts);
        assertTrue(result.contains("![图](kb-image:i1)"));
        assertTrue(result.contains("`[示例](kb-source:s3)`"));
        assertEquals(1,occurrences(result,"全流程发展路线"));
        assertFalse(result.contains("战斗教程"));
        assertEquals(result,ModpackSources.format(result,artifacts));
    }

    @Test void naturalInlineWebLinksRemainClickableAndCountAsCitations() {
        var artifacts=artifacts();
        String result=AnswerMedia.bind("阅读[寒冰剑的获取步骤](https://www.mcmod.cn/post/5454.html)。[[source:s1]]",artifacts);
        assertTrue(result.contains("[寒冰剑的获取步骤](https://www.mcmod.cn/post/5454.html)"));
        assertFalse(result.contains("资料来源："));
        assertEquals(Set.of("s1","s2"),AnswerMedia.citedSourceIds(result,artifacts));
        assertEquals(2,AnswerMedia.assess(result,artifacts,java.nio.file.Path.of("target")).citedSources());
    }

    @Test void fallbackListsAllDistinctRetrievedPagesAndOtherScopesKeepExistingPolicy() {
        var artifacts=artifacts();
        String result=AnswerMedia.bind("资料摘录。",artifacts);
        assertEquals(2,occurrences(result,"kb-source:"));
        var local=new AnswerArtifacts("document:1","本地资料",artifacts.sources(),List.of());
        assertEquals("原文",ModpackSources.format("原文",local));
        assertTrue(AnswerMedia.bind("原文[[source:s1]]",local).contains("kb-source:s1"));
        assertNull(ModpackSources.canonicalUrl("file:///private"));
        assertNotEquals(ModpackSources.canonicalUrl("https://example.com/?page=1"),ModpackSources.canonicalUrl("https://example.com/?page=2"));
    }

    @Test void footerCleanupPreservesLiteralExamplesInsideCode() {
        String code="```markdown\n资料来源：\n- \n[参考](kb-source:s1)\n```";
        String result=ModpackSources.format(code,artifacts());
        assertTrue(result.startsWith(code));
    }

    private static int occurrences(String text,String token) {return text.split(java.util.regex.Pattern.quote(token),-1).length-1;}
}
