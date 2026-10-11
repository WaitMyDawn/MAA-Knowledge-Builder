package yagen.waitmydawn.kb.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RunComparatorTest {

    @TempDir
    Path tempDir;

    @Test
    void comparesRunsAndDetectsRegression() throws Exception {
        Path runA = tempDir.resolve("run-A");
        Path runB = tempDir.resolve("run-B");
        writeRun(runA, "run-A", 1.0, 1000, true);
        writeRun(runB, "run-B", 0.0, 2400, false);

        String md = RunComparator.compare(runA, runB);

        assertTrue(md.contains("run-A"));
        assertTrue(md.contains("run-B"));
        assertTrue(md.contains("-100.0pp"), "rate delta should use percentage points: " + md);
        assertTrue(md.contains("+1400"), "numeric delta should keep its unit: " + md);
        assertTrue(md.contains("T1"), "regressed turn should be listed: " + md);
    }

    private static void writeRun(Path dir, String runId, double passRate, int avgMs,
                                 boolean passed) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("summary.json"), """
                {
                  "runId": "%s",
                  "tag": "t",
                  "passRate": %s,
                  "avgRuleScore": %s,
                  "hitRate": 1.0,
                  "avgTotalMs": %d,
                  "p95TotalMs": %d,
                  "avgTokenIn": 100,
                  "avgTokenOut": 50,
                  "judge": { "scored": 1, "passRate": %s, "hallucinationRate": 0.0 },
                  "byType": { "basic": { "total": 1, "passed": %s, "passRate": %s } }
                }
                """.formatted(runId, passRate, passRate, avgMs, avgMs, passRate,
                passed ? 1 : 0, passRate), StandardCharsets.UTF_8);

        Files.writeString(dir.resolve("turns.jsonl"), """
                {"turnId":"T1","rule":{"passed":%s}}
                """.formatted(passed), StandardCharsets.UTF_8);
    }
}
