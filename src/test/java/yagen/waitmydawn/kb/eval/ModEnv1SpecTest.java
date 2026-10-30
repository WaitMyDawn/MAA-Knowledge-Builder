package yagen.waitmydawn.kb.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ModEnv1SpecTest {
    private static final Path FILE=Path.of("test-set/golden-set-modEnv1.json");
    @Test void questionOrderAndConversationBoundariesRemainStable() throws Exception {
        var spec=GoldenSet.load(FILE);
        assertEquals(11,spec.conversations().size());
        var sizes=new LinkedHashMap<String,Integer>();
        sizes.put("Crafting",9);sizes.put("Entity",8);sizes.put("Gameplay",15);sizes.put("Webpage",10);sizes.put("Error",7);sizes.put("MultiStep",12);
        var seen=new HashSet<String>();int total=0;
        for(var group:sizes.entrySet()) {
            var turns=spec.conversations().stream().flatMap(c->c.turns().stream()).filter(t->t.id().startsWith(group.getKey()+"-")).toList();
            assertEquals(group.getValue(),turns.size());
            for(int i=0;i<turns.size();i++) {
                var turn=turns.get(i);assertEquals(group.getKey()+"-"+String.format("%02d",i+1),turn.id());
                assertTrue(seen.add(turn.id()));assertEquals(1,turn.stabilityRuns());
                assertFalse(turn.question().isBlank());assertFalse(turn.answerPoints().isEmpty());assertFalse(turn.assertions().isEmpty());total++;
            }
        }
        assertEquals(61,total);
        for(int i=0;i<5;i++)assertEquals(new ArrayList<>(sizes.keySet()).get(i),spec.conversations().get(i).id());
        for(int i=5;i<11;i++)assertEquals(2,spec.conversations().get(i).turns().size());
        assertTrue(spec.conversations().get(1).turns().get(7).question().startsWith("介绍它们"));
    }
    @Test void humanJudgingAndMultiEntityExpectationsAreExplicit() throws Exception {
        var root=new ObjectMapper().readTree(FILE.toFile());assertFalse(root.path("evaluation").path("modelJudge").asBoolean(true));
        var turns=GoldenSet.load(FILE).conversations().stream().flatMap(c->c.turns().stream()).toList();
        var mages=turns.stream().filter(t->t.id().equals("Entity-05")).findFirst().orElseThrow();
        var identities=mages.assertions().stream().filter(a->a.kind().equals("answer_media_identity_all")).findFirst().orElseThrow();
        assertEquals(6,new HashSet<>(identities.values()).size());
        var boss=turns.stream().filter(t->t.id().equals("Entity-08")).findFirst().orElseThrow();
        assertEquals(2,boss.assertions().stream().filter(a->a.kind().equals("answer_media_identity_all")).findFirst().orElseThrow().values().size());
        for(var turn:turns)for(var rule:turn.assertions())if(rule.kind().equals("source_url_any"))
            assertTrue(rule.values().stream().allMatch(url->url.endsWith(".html")),"Source rules require exact evidence URLs, not host prefixes");
    }
    @Test void genericDiagramRequirementAcceptsRecipeCardsWithoutTreatingThemAsTutorialIllustrations() {
        var rule=new GoldenSet.Assertion("answer_media_displayed_min",List.of(),null,null,1,null,1,true);
        var turn=new GoldenSet.Turn("diagram","结构图或配方图","",List.of(),"scenario","Gameplay",1,List.of(),List.of(),List.of(rule));
        var withCard=new RuleJudge.Observation("回答",false,null,List.of(),List.of(),0,List.of(),List.of(),List.of(),List.of(),null,0,0,0,false,Map.of(),
                yagen.waitmydawn.kb.model.AnswerArtifacts.empty(),new yagen.waitmydawn.kb.service.AnswerMedia.Assessment(1,1,1,0,0,0,0));
        assertTrue(RuleJudge.judge(turn,withCard).passed());
        var missing=new RuleJudge.Observation("回答",false,null,List.of(),List.of(),0,List.of(),List.of(),List.of(),List.of(),null,0,0,0,false);
        assertFalse(RuleJudge.judge(turn,missing).passed());
    }
}
