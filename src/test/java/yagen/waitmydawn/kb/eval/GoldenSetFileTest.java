package yagen.waitmydawn.kb.eval;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 守护真实黄金集文件的结构：50 题、5:3:2 分层、模组分配、id 唯一、每题都有断言。
 * 防止后续编辑 golden-set.json 时破坏统计口径。
 */
class GoldenSetFileTest {

    @Test
    void goldenSetFileMatchesDesign() throws Exception {
        Path file = Path.of("test-set/old/golden-set.json");
        assumeTrue(Files.exists(file), "golden-set.json not found (run from project root)");

        GoldenSet.Spec spec = GoldenSet.load(file);

        assertEquals("8", spec.version());
        List<GoldenSet.Turn> turns = spec.conversations().stream()
                .flatMap(c -> c.turns().stream()).toList();

        assertEquals(50, turns.size(), "总题数应为 50（原版 20 + 模组 30）");

        Map<String, Long> byType = turns.stream()
                .collect(Collectors.groupingBy(GoldenSet.Turn::type, Collectors.counting()));
        assertEquals(25L, byType.getOrDefault("basic", 0L), "basic 应为 25（5:3:2 的 5）");
        assertEquals(15L, byType.getOrDefault("multi_step", 0L), "multi_step 应为 15（5:3:2 的 3）");
        assertEquals(10L, byType.getOrDefault("adversarial", 0L), "adversarial 应为 10（5:3:2 的 2）");

        Map<String, Long> byMod = spec.conversations().stream()
                .flatMap(c -> c.turns().stream().map(t -> Map.entry(c.modId(), t)))
                .collect(Collectors.groupingBy(e -> String.valueOf(e.getKey()), Collectors.counting()));
        assertEquals(20L, byMod.getOrDefault("minecraft", 0L));
        assertEquals(8L, byMod.getOrDefault("iceandfire", 0L));
        assertEquals(5L, byMod.getOrDefault("alexsmobs", 0L));
        assertEquals(5L, byMod.getOrDefault("alexscaves", 0L));
        assertEquals(5L, byMod.getOrDefault("create", 0L));
        assertEquals(6L, byMod.getOrDefault("irons_spellbooks", 0L));
        assertEquals(1L, byMod.getOrDefault("explorerscompass", 0L));

        Set<String> ids = new HashSet<>();
        for (GoldenSet.Conversation conv : spec.conversations()) {
            assertNotNull(conv.modId(), "conversation 缺少 modId: " + conv.id());
            for (GoldenSet.Turn turn : conv.turns()) {
                assertTrue(ids.add(turn.id()), "重复的 turn id: " + turn.id());
                assertFalse(turn.assertions().isEmpty(), "turn 缺少断言: " + turn.id());
                assertFalse(turn.question().isBlank(), "turn 缺少问题: " + turn.id());
                assertFalse(turn.referenceAnswer().isBlank(), "turn 缺少参考答案: " + turn.id());
            }
        }
    }
}
