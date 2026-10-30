package yagen.waitmydawn.kb.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import yagen.waitmydawn.kb.model.BuildRequest;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** 验证专项评测规格可加载；线上教程发现与答案事实另行验收。 */
class Modpack737AcceptanceSpecTest {
    @Test void referenceAnswersPassRulesButWrongScopeOrInventedCitationsFail() throws Exception {
        var spec=GoldenSet.load(Path.of("test-set/old/golden-set-modpack.json"));
        for(var conversation:spec.conversations()) {
            var turn=conversation.turns().getFirst();
            var source=new yagen.waitmydawn.kb.model.AnswerArtifacts.Source("s1","d1","流程教程","https://www.mcmod.cn/post/5454.html",null,null,"NATIVE","证据");
            var artifacts=new yagen.waitmydawn.kb.model.AnswerArtifacts("mcmod:modpack:737","龙之冒险：新征程",java.util.List.of(source),java.util.List.of());
            assertTrue(RuleJudge.judge(turn,observation(turn.referenceAnswer()+" [来源](kb-source:s1)",artifacts)).passed(),turn.id());
            var wrong=new yagen.waitmydawn.kb.model.AnswerArtifacts("other","其他整合包",artifacts.sources(),java.util.List.of());
            assertFalse(RuleJudge.judge(turn,observation(turn.referenceAnswer()+" [来源](kb-source:s1)",wrong)).passed());
            assertTrue(RuleJudge.judge(turn,observation(turn.referenceAnswer()+" [获取步骤](https://www.mcmod.cn/post/5454.html)",artifacts)).passed());
            assertFalse(RuleJudge.judge(turn,observation(turn.referenceAnswer()+" [来源](https://www.mcmod.cn/post/999999.html)",artifacts)).passed());
            assertFalse(RuleJudge.judge(turn,observation(turn.referenceAnswer()+" [来源](kb-source:invented)",artifacts)).passed());
            assertFalse(RuleJudge.judge(turn,observation(turn.referenceAnswer()+" `[来源](https://www.mcmod.cn/post/5454.html)`",artifacts)).passed());
        }
    }
    private RuleJudge.Observation observation(String answer,yagen.waitmydawn.kb.model.AnswerArtifacts artifacts) {
        return new RuleJudge.Observation(answer,false,null,java.util.List.of(),java.util.List.of(),0,java.util.List.of(),java.util.List.of(),
                java.util.List.of(),java.util.List.of(),"TIER C",10,0,0,true,java.util.Map.of(),artifacts);
    }
    @Test void loadsBuildContractAndFiveIndependentQuestions() throws Exception {
        var contract = new ObjectMapper().readTree(Path.of("src/test/resources/web/modpack-737-acceptance.json").toFile());
        assertEquals(6, contract.path("buildAssertions").path("expectedUniqueTutorialCount").asInt());
        assertEquals("verified_live", contract.path("verificationStatus").asText());
        var source = BuildRequest.Source.of(BuildRequest.Kind.URL, contract.path("url").asText());
        assertEquals("https://www.mcmod.cn/modpack/737.html", source.location());
        var spec = GoldenSet.load(Path.of(contract.path("questionSet").asText()));
        assertEquals(5, spec.conversations().size());
        var questions = spec.conversations().stream().flatMap(c -> c.turns().stream()).toList();
        assertEquals(5, questions.size());
        for (var turn : questions) {
            assertTrue(turn.question().startsWith("龙之冒险新征程"));
            assertTrue(turn.referenceSources().contains(source.location()));
            assertFalse(turn.judgeRubric().isEmpty());
            assertFalse(turn.referenceAnswer().isBlank());
            assertTrue(turn.assertions().stream().anyMatch(a -> a.kind().equals("knowledge_scope")));
            assertTrue(turn.assertions().stream().anyMatch(a -> a.kind().equals("source_url_any")));
            assertTrue(turn.assertions().stream().anyMatch(a -> a.kind().equals("answer_contains_any")));
        }
    }
}
