package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.Test;
import yagen.waitmydawn.kb.model.AnswerArtifacts;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GuideIllustrationCoverageTest {
    private AnswerArtifacts.Picture picture(String id,String caption,String step) {
        return new AnswerArtifacts.Picture(id,id,id+".png",caption,"s1","ILLUSTRATION",List.of(),"组装升级综合攻略",null,null,
                new AnswerArtifacts.ImageGrounding("UI","CONTEXT_INFERRED","b"+id,"组装升级教程\n"+step,"INLINE_STEP","CONTEXT_ONLY"));
    }
    private AnswerArtifacts artifacts(List<AnswerArtifacts.Picture> pictures) {
        return new AnswerArtifacts("pack","指南",List.of(new AnswerArtifacts.Source("s1","doc","指南","https://example.com/guide","组装升级",null,"NATIVE","先组装核心，再安装升级模块。")),pictures);
    }
    @Test void compoundStepsReserveSlotsBeforeFillingWithHighRankedAssemblyPictures() {
        var ranked=new ArrayList<AnswerArtifacts.Picture>();for(int i=0;i<4;i++)ranked.add(picture("a"+i,"组装示意","把核心组装到底座。"));
        ranked.add(picture("up","升级界面","在菜单中安装升级模块。"));ranked.add(picture("replace","更换界面","更换旧核心。"));
        var chosen=GuideIllustrationCoverage.select("如何组装、升级并更换核心",ranked,4);
        assertTrue(chosen.stream().anyMatch(p->p.id().equals("up")));assertTrue(chosen.stream().anyMatch(p->p.id().equals("replace")));
        String answer=chosen.stream().map(p->"![图](kb-image:"+p.id()+")").collect(java.util.stream.Collectors.joining("\n\n"));
        assertTrue(GuideIllustrationCoverage.audit("如何组装、升级并更换核心",answer,artifacts(chosen)).missing().isEmpty());
    }
    @Test void sharedHeadingCannotMakeAssemblyPicturesCountAsUpgradeEvidence() {
        var image=picture("a","组装和升级教程图","把核心组装到底座。");var result=new QaPipeline.QaResult();result.artifacts=artifacts(List.of(image));result.answer="先组装核心。![组装](kb-image:a)";
        var audit=GuideIllustrationCoverage.finish(result,"如何组装并升级傀儡");assertEquals(List.of("升级"),audit.missing());assertTrue(result.answer.contains("本轮未选到“升级”"));
    }
    @Test void wrongStepMarkerMovesToCompatibleParagraphAndDoesNotDuplicate() {
        var image=picture("up","升级界面","在菜单中安装升级模块。");
        String answer=AnswerMedia.bind("## 组装\n\n先把核心组装到底座。[[image:up]]\n\n## 升级\n\n在菜单中安装升级模块。",artifacts(List.of(image)));
        assertTrue(answer.indexOf("kb-image:up")>answer.indexOf("## 升级"),answer);
        assertEquals(1,java.util.regex.Pattern.compile("kb-image:up").matcher(answer).results().count());
    }
    @Test void missingStepNoticeKeepsModpackSourceLinksAtTheEnd() {
        var original=artifacts(List.of(picture("a","组装示意","把核心组装到底座。")));
        var result=new QaPipeline.QaResult();
        result.artifacts=new AnswerArtifacts("mcmod:modpack:737","指南",original.sources(),original.pictures());
        result.answer="先组装核心。[[source:s1]] ![组装](kb-image:a)";
        GuideIllustrationCoverage.finish(result,"如何组装并升级傀儡");
        assertTrue(result.answer.indexOf("配图说明")<result.answer.indexOf("资料来源："),result.answer);
        assertTrue(result.answer.endsWith("[指南](kb-source:s1)"),result.answer);
    }
}
