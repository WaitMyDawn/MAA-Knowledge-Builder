package yagen.waitmydawn.kb.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoldenSetTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsV2SchemaWithAssertions() throws Exception {
        Path file = tempDir.resolve("set.json");
        Files.writeString(file, """
                {
                  "version": 2,
                  "name": "unit",
                  "conversations": [
                    {
                      "id": "C1",
                      "name": "conv",
                      "modId": "minecraft",
                      "turns": [
                        {
                          "id": "Q1",
                          "question": "钻石剑怎么制作？",
                          "referenceAnswer": "2 个钻石 + 1 根木棍竖排合成",
                          "type": "basic",
                          "category": "recipe",
                          "stabilityRuns": 3,
                          "expected": { "answerPoints": ["钻石", "木棍"] },
                          "judgeRubric": ["材料是否正确"],
                          "assertions": [
                            {"kind": "answer_contains_all", "values": ["钻石"], "required": true, "weight": 2}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """, StandardCharsets.UTF_8);

        GoldenSet.Spec spec = GoldenSet.load(file);

        assertEquals("2", spec.version());
        assertEquals(1, spec.conversations().size());
        GoldenSet.Turn turn = spec.conversations().get(0).turns().get(0);
        assertEquals("Q1", turn.id());
        assertEquals("钻石剑怎么制作？", turn.question());
        assertEquals("2 个钻石 + 1 根木棍竖排合成", turn.referenceAnswer());
        assertEquals(3, turn.effectiveRepeat(1));
        assertEquals(1, turn.assertions().size());
        assertEquals(2.0, turn.assertions().get(0).weight(), 0.0001);
        assertTrue(turn.assertions().get(0).required());
        assertEquals(2, turn.answerPoints().size());
    }

    @Test
    void loadsLegacyV1StringTurns() throws Exception {
        Path file = tempDir.resolve("legacy.json");
        Files.writeString(file, """
                {
                  "conversations": [
                    { "name": "old", "turns": ["龙有哪几种", "它们怎么驯服"] }
                  ]
                }
                """, StandardCharsets.UTF_8);

        GoldenSet.Spec spec = GoldenSet.load(file);

        GoldenSet.Conversation conv = spec.conversations().get(0);
        assertEquals("D1", conv.id());
        assertEquals(2, conv.turns().size());
        assertEquals("D1-T01", conv.turns().get(0).id());
        assertEquals("龙有哪几种", conv.turns().get(0).question());
        assertTrue(conv.turns().get(0).assertions().isEmpty());
        assertFalse(conv.turns().get(1).effectiveRepeat(1) > 1);
        // 未显式设置 stabilityRuns 时，应使用 CLI --repeat 的全局默认
        assertEquals(5, conv.turns().get(1).effectiveRepeat(5));
    }

    @Test
    void autoGeneratesAssertionFromAnswerPoints() throws Exception {
        Path file = tempDir.resolve("auto.json");
        Files.writeString(file, """
                {
                  "version": 2,
                  "conversations": [
                    {
                      "id": "C1",
                      "name": "conv",
                      "turns": [
                        { "id": "Q1", "question": "q", "expected": { "answerPoints": ["冰龙", "火龙"] } }
                      ]
                    }
                  ]
                }
                """, StandardCharsets.UTF_8);

        GoldenSet.Turn turn = GoldenSet.load(file).conversations().get(0).turns().get(0);

        assertEquals(1, turn.assertions().size());
        assertEquals("answer_contains_all", turn.assertions().get(0).kind());
        assertEquals(2, turn.assertions().get(0).values().size());
        assertTrue(turn.assertions().get(0).required());
    }
}
