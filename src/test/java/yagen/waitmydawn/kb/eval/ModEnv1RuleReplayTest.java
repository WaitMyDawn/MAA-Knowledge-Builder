package yagen.waitmydawn.kb.eval;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 冻结旧回答的规则诊断；不同题集版本的结果不得冒充新问答成绩。 */
class ModEnv1RuleReplayTest {
    @Test void recordsVersionedRechecksWithoutChangingBaseline()throws Exception {
        Path original=Path.of("test-set/golden-set-modEnv1/runs/run-20261101-114122-1a3f8be9-modEnv1/turns.jsonl");
        Assumptions.assumeTrue(Files.isRegularFile(original),"Frozen baseline is local review data");
        var json=new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        var spec=GoldenSet.load(Path.of("test-set/golden-set-modEnv1.json"));
        var turns=new HashMap<String,GoldenSet.Turn>();spec.conversations().forEach(c->c.turns().forEach(t->turns.put(t.id(),t)));
        var output=json.createObjectNode().put("method","OLD_ANSWERS_NEW_CHECKS_DIAGNOSTIC_ONLY").put("modelCalls",0).put("ruleSetVersion",2);
        var results=output.putArray("turns");
        for(String line:Files.readAllLines(original)){
            var row=json.readTree(line);var obs=(ObjectNode)row.path("observation").deepCopy();
            obs.put("answer",row.path("answer").asText());obs.put("error",row.path("error").asBoolean());obs.put("totalTimeMs",row.path("elapsedMs").asLong());
            var verdict=RuleJudge.judge(turns.get(row.path("turnId").asText()),json.treeToValue(obs,RuleJudge.Observation.class));
            var value=results.addObject().put("turnId",row.path("turnId").asText()).put("originalPassed",row.path("rule").path("passed").asBoolean()).put("recheckedPassed",verdict.passed());value.set("failedChecks",json.valueToTree(verdict.failedOutcomes()));
        }
        assertEquals(61,results.size());Files.createDirectories(Path.of("target/rule-rechecks"));
        json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/rule-rechecks/modEnv1-v1-answers-v2-checks.json").toFile(),output);
        Path review=Path.of("test-set/golden-set-modEnv1/history/run-20261101-114122-1a3f8be9-modEnv1/human-review.json");
        if(Files.isRegularFile(review)){var grades=json.readTree(review.toFile());for(var result:results)if(grades.path(result.path("turnId").asText()).path("grade").asText().equals("D"))assertFalse(result.path("recheckedPassed").asBoolean(),result.toPrettyString());}
    }
}
