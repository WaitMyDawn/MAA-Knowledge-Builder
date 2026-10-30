package yagen.waitmydawn.kb.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.model.AnswerArtifacts;
import yagen.waitmydawn.kb.service.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MultimodalAcceptanceTest {
    @TempDir Path temp;
    @Test void missingNetworkTelemetryCannotBeMistakenForZeroRequests() {
        var turn=new GoldenSet.Turn("network","本地查询","",List.of(),"runtime","runtime",1,List.of(),List.of(),List.of(new GoldenSet.Assertion("resource_http_zero",List.of(),null,null,null,null,1,true)));
        var old=new RuleJudge.Observation("回答",false,null,List.of(),List.of(),0,List.of(),List.of(),List.of(),List.of(),null,0,0,0,false);
        assertFalse(RuleJudge.judge(turn,old).passed());
        var zero=observation("回答",AnswerArtifacts.empty());assertTrue(RuleJudge.judge(turn,zero).passed());
        var nonzero=new RuleJudge.Observation(zero.answer(),false,null,List.of(),List.of(),0,List.of(),List.of(),List.of(),List.of(),null,0,0,0,false,Map.of(),AnswerArtifacts.empty(),AnswerMedia.Assessment.empty(),new RetrievalTelemetry.ResourceAudit(1,0,1,0,0,0,0,0,0,List.of(),false),zero.runtimeEvidence());
        assertFalse(RuleJudge.judge(turn,nonzero).passed());
    }
    @Test void fixedQuestionsHaveCodeDefinedAcceptanceAndAllRequiredCategories()throws Exception {
        var set=GoldenSet.load(Path.of("golden-set-media.json"));assertEquals(24,set.conversations().size());
        var categories=new HashSet<String>();
        for(var conversation:set.conversations())for(var turn:conversation.turns()) {
            categories.add(turn.category());assertFalse(turn.judgeRubric().isEmpty());assertTrue(turn.assertions().stream().anyMatch(a->a.kind().equals("answer_media_refs_valid")));
            assertTrue(turn.assertions().stream().allMatch(GoldenSet.Assertion::required));
        }
        assertTrue(categories.containsAll(Set.of("recipe","runtime","modpack","local-document","web-document","missing-state")));
    }
    @Test void sharedPixelsPreserveExactStatesAndWrongStateOrCaptionOnlyCannotPass()throws Exception {
        var source=new AnswerArtifacts.Source("s1","d1","实机快照","mkb-runtime:test","实体",null,"GAME_GUI","来源");
        var image=new java.awt.image.BufferedImage(16,16,2);image.setRGB(4,4,0xff2255aa);var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",bytes);
        var asset=new ImageAssetStore(temp).put(bytes.toByteArray());
        var adult=picture("a",asset.id(),asset.relativePath(),"adult");var baby=picture("b",asset.id(),asset.relativePath(),"baby");
        var turn=GoldenSet.load(Path.of("golden-set-media.json")).conversations().stream().filter(c->c.id().equals("MM-04")).findFirst().orElseThrow().turns().getFirst();
        var artifacts=new AnswerArtifacts(null,null,List.of(source),List.of(adult,baby));
        String answer="成年和幼年。![成年](kb-image:a)\n\n![幼年](kb-image:b)";
        assertTrue(RuleJudge.judge(turn,observation(answer,artifacts)).passed());
        var wrong=new AnswerArtifacts(null,null,List.of(source),List.of(adult,picture("b",asset.id(),asset.relativePath(),"adult")));
        assertFalse(RuleJudge.judge(turn,observation(answer,wrong)).passed());
        var old=new AnswerArtifacts.Picture("b",asset.id(),asset.relativePath(),"幼年牛","s1","ENTITY",List.of(),"age=baby");
        assertFalse(RuleJudge.judge(turn,observation(answer,new AnswerArtifacts(null,null,List.of(source),List.of(adult,old)))).passed());
        assertFalse(RuleJudge.judge(turn,observation("```\n"+answer+"\n```",artifacts)).passed());
        var history=new ChatHistoryService(temp);var session=history.newSession("状态身份",temp);history.addMessage(session,"maa",answer,artifacts);history.save(session);
        assertEquals(artifacts,history.load(session.sessionId).messages.getFirst().artifacts());
    }
    @Test void missingSourceAndWrongDocumentPageFailEvenWhenImageExists()throws Exception {
        var turn=new GoldenSet.Turn("test","第2页流程图","",List.of(),"image","document",1,List.of(),List.of(),List.of(
                new GoldenSet.Assertion("answer_image_page_any",List.of("2"),null,null,null,null,1,true),
                new GoldenSet.Assertion("answer_media_sources_bound",List.of(),null,null,null,null,1,true)));
        var picture=new AnswerArtifacts.Picture("i","unused","unused.png","图","s1","ILLUSTRATION",List.of(),"");
        var source=new AnswerArtifacts.Source("s1","d","指南.pdf","guide.pdf","流程",1,"NATIVE","内容");
        assertFalse(RuleJudge.judge(turn,observation("![图](kb-image:i)",new AnswerArtifacts(null,null,List.of(source),List.of(picture)))).passed());
        assertFalse(RuleJudge.judge(turn,observation("![图](kb-image:i)",new AnswerArtifacts(null,null,List.of(),List.of(picture)))).passed());
    }
    private AnswerArtifacts.Picture picture(String id,String asset,String relative,String age) {
        return new AnswerArtifacts.Picture(id,asset,relative,"牛 "+age,"s1","ENTITY",List.of(),"",null,null,null,
                new AnswerArtifacts.RuntimeIdentity("minecraft:cow",age,Map.of("age",age)));
    }
    private RuleJudge.Observation observation(String answer,AnswerArtifacts artifacts) {
        return new RuleJudge.Observation(answer,false,null,List.of(),List.of(),0,List.of(),List.of(),List.of(),List.of(),null,0,0,0,false,Map.of(),artifacts,AnswerMedia.assess(answer,artifacts,temp),new RetrievalTelemetry.ResourceAudit(0,0,0,0,0,0,0,0,0,List.of(),false),new RuntimeEvidencePolicy.Snapshot("fixture","1.21.1","neoforge",Map.of()));
    }
}
