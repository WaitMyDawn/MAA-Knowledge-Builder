package yagen.waitmydawn.kb.eval;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import yagen.waitmydawn.kb.model.AnswerArtifacts;
import static org.junit.jupiter.api.Assertions.*;

class ModpackRegressionSpecTest {
    @Test void regressionAddsParaphrasesCounterexampleAndExplicitEvidenceGaps() throws Exception {
        var core=GoldenSet.load(Path.of("test-set/old/golden-set-modpack.json"));
        var regression=GoldenSet.load(Path.of("test-set/old/golden-set-modpack-regression.json"));
        assertEquals(5,core.conversations().size());assertEquals(8,regression.conversations().size());
        var ids=new HashSet<String>();
        for(var conversation:regression.conversations()) for(var turn:conversation.turns()) {
            assertTrue(ids.add(turn.id()));assertFalse(turn.referenceAnswer().isBlank());assertFalse(turn.judgeRubric().isEmpty());
            assertTrue(turn.assertions().stream().anyMatch(a -> a.kind().equals("knowledge_scope")));
            var source=new AnswerArtifacts.Source("s1","d","流程教程","https://www.mcmod.cn/post/5454.html",null,null,"NATIVE","证据");
            var artifacts=new AnswerArtifacts("mcmod:modpack:737","龙之冒险：新征程",List.of(source),List.of());
            var observation=new RuleJudge.Observation(turn.referenceAnswer()+" [来源](kb-source:s1)",false,null,List.of(),List.of(),0,List.of(),List.of(),List.of(),List.of(),"TIER C",10,0,0,true,Map.of(),artifacts);
            assertTrue(RuleJudge.judge(turn,observation).passed(),turn.id());
        }
    }
}
