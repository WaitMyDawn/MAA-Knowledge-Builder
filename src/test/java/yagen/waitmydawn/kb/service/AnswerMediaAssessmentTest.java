package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.model.AnswerArtifacts;
import yagen.waitmydawn.kb.eval.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AnswerMediaAssessmentTest {
    @TempDir Path temp;
    @Test void retrievedWebSourcesCanBindUrlMarkersButUnprovidedUrlsCannot() {
        var result=new QaPipeline.QaResult();String url="https://example.com/entity";
        AnswerMedia.attachVectorSources(result,List.of(new VectorStore.SearchResult("chunk","名称：测试生物\n有效摘录","test","测试模组","web",1,url)));
        String answer="说明 [[source:"+url+"]]";
        result.artifacts=AnswerMedia.snapshotCitations(answer,result.artifacts);
        String bound=AnswerMedia.bind(answer,result.artifacts);
        assertTrue(bound.contains("kb-source:sv"));assertFalse(bound.contains("[[source:"));
        assertEquals(1,AnswerMedia.assess(bound,result.artifacts,temp).citedSources());
        assertFalse(result.artifacts.citedSourceIds().isEmpty());
        assertEquals(1,AnswerMedia.assess(AnswerMedia.bind("[[source:https://unprovided.example/test]]",result.artifacts),result.artifacts,temp).invalidReferences());
        assertTrue(AnswerMedia.bind("`[[source:"+url+"]]`",result.artifacts).contains("`[[source:"+url+"]]`"));
    }
    @Test void unresolvedModelMarkersAreInvalidButQuotedExamplesAreIgnored() {
        var checked=AnswerMedia.assess("正文 [[source:https://example.com/page]] [[image:missing]]",AnswerArtifacts.empty(),temp);
        assertEquals(2,checked.invalidReferences());
        assertEquals(0,AnswerMedia.assess("`[[source:https://example.com/page]]`\n\n```\n[[image:missing]]\n```",AnswerArtifacts.empty(),temp).invalidReferences());
    }
    @Test void countsActualValidIllustrationsSeparatelyFromCandidatesAndRecipes() throws Exception {
        var asset=new ImageAssetStore(temp).put(Files.readAllBytes(Path.of("src/test/resources/web/tiny.webp")));
        var picture=new AnswerArtifacts.Picture("i1",asset.id(),asset.relativePath(),"插图","s1","ILLUSTRATION",List.of(),"");
        var recipe=new AnswerArtifacts.Picture("i2",asset.id(),asset.relativePath(),"配方",null,"RECIPE",List.of(),"");
        var unused=new AnswerArtifacts.Picture("i3",asset.id(),asset.relativePath(),"未引用",null,"ILLUSTRATION",List.of(),"");
        var source=new AnswerArtifacts.Source("s1","d1","教程","https://example.com/guide",null,null,"NATIVE","");
        var artifacts=new AnswerArtifacts(null,null,List.of(source),List.of(picture,recipe,unused));
        var result=AnswerMedia.assess("![图](kb-image:i1)\n\n![配方](kb-image:i2) [来源](kb-source:s1)",artifacts,temp);
        assertEquals(3,result.candidates());assertEquals(2,result.displayed());assertEquals(2,result.valid());
        assertEquals(1,result.validIllustrations());assertEquals(1,result.citedSources());assertEquals(0,result.invalidReferences());
        Files.delete(AnswerMedia.resolveImage(temp,picture));
        result=AnswerMedia.assess("![图](kb-image:i1) ![未知](kb-image:unknown)",artifacts,temp);
        assertEquals(0,result.valid());assertEquals(1,result.unavailable());assertEquals(1,result.invalidReferences());
    }

    @Test void codeExamplesDoNotCountAsPicturesOrCitations() {
        String text="```markdown\n![图](kb-image:unknown) [来源](kb-source:s1)\n```\n\n`![图](kb-image:another)`\n\n~~~\n[来源](kb-source:s2)\n~~~";
        var result=AnswerMedia.assess(text,AnswerArtifacts.empty(),temp);
        assertEquals(0,result.displayed());assertEquals(0,result.citedSources());assertEquals(0,result.invalidReferences());
        assertTrue(AnswerMedia.citedSourceIds(text).isEmpty());
    }

    @Test void illustrationRuleRequiresAValidDisplayedArticleImage() throws Exception {
        Path spec=temp.resolve("spec.json");
        Files.writeString(spec,"{\"conversations\":[{\"name\":\"图文验收\",\"turns\":[{\"question\":\"如何组装\",\"assertions\":[{\"kind\":\"answer_illustration_valid\",\"required\":true},{\"kind\":\"answer_media_refs_valid\",\"required\":true}]}]}]}");
        var turn=GoldenSet.load(spec).conversations().getFirst().turns().getFirst();
        assertTrue(RuleJudge.judge(turn,observation(new AnswerMedia.Assessment(3,1,1,1,0,1,0))).passed());
        assertFalse(RuleJudge.judge(turn,observation(new AnswerMedia.Assessment(3,0,0,0,0,1,0))).passed());
        assertFalse(RuleJudge.judge(turn,observation(new AnswerMedia.Assessment(1,1,1,0,0,1,0))).passed());
        assertFalse(RuleJudge.judge(turn,observation(new AnswerMedia.Assessment(1,1,0,0,1,1,0))).passed());
    }
    private RuleJudge.Observation observation(AnswerMedia.Assessment media) {
        return new RuleJudge.Observation("回答",false,null,List.of(),List.of(),0,List.of(),List.of(),List.of(),List.of(),
                "TIER C",10,0,0,true,Map.of(),AnswerArtifacts.empty(),media);
    }
}
