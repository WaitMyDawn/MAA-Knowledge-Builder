package yagen.waitmydawn.kb.eval;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PromptCostReportTest {
    @Test void estimatesGroupByMethodAndHistoricalAbsenceIsNotZeroUsage(){
        var rows=List.<Map<String,Object>>of(Map.of("metrics",Map.of("promptCost",Map.of("method","v1","requests",2,"estimatedTokens",Map.of("evidence",100,"history",30)))),
                Map.of("metrics",Map.of("promptCost",Map.of("method","v1","requests",1,"estimatedTokens",Map.of("evidence",20)))),
                Map.of("metrics",Map.of("promptCost",Map.of("method","v2","requests",1,"estimatedTokens",Map.of("evidence",50)))),Map.of("metrics",Map.of("tokenIn",900)));
        var summary=EvalReport.promptCostSummary(rows);assertEquals(3,summary.get("observedTurns"));assertEquals(1,summary.get("unobservedTurns"));
        var groups=(Map<?,?>)summary.get("methodGroups");assertEquals(2,groups.size());var v1=(Map<?,?>)groups.get("v1");assertEquals(3L,v1.get("requests"));assertEquals(120L,((Map<?,?>)v1.get("estimatedTokens")).get("evidence"));
    }
}
