package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import java.util.*;

/**
 * 方块采掘要求由导出标签决定：可采掘工具类型 + 最低等级。
 *
 * <p>1.21 没有“需要木镐”这个独立标签：属于 `minecraft:mineable/pickaxe` 且不在
 * `needs_stone_tool`/`needs_iron_tool`/`needs_diamond_tool` 中的方块，最低工具就是木镐。
 * 这里只陈述标签能证明的结论，不补齐标签没有声明的内容。
 */
final class BlockMiningRules {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PICKAXE = "minecraft:block|minecraft:mineable/pickaxe";
    private static final List<String[]> TIERS = List.of(
            new String[]{"minecraft:block|minecraft:needs_diamond_tool", "钻石镐"},
            new String[]{"minecraft:block|minecraft:needs_iron_tool", "铁镐"},
            new String[]{"minecraft:block|minecraft:needs_stone_tool", "石镐"});

    private BlockMiningRules(){}

    /** 返回可读的采掘要求；该物品不是由标签约束的方块时返回 null。 */
    static String describe(DatabaseBuilder db, String profile, String itemId) {
        if (itemId == null || !itemId.contains(":")) return null;
        if (!members(db, profile, PICKAXE).contains(itemId)) return null;
        String tier = "木镐";
        for (var row : TIERS) if (members(db, profile, row[0]).contains(itemId)) { tier = row[1]; break; }
        return "采掘要求：需要镐类工具（最低" + tier + "）；用更低等级工具挖掘不会掉落本体。";
    }

    private static Set<String> members(DatabaseBuilder db, String profile, String recordId) {
        try (var c = db.getConnection();
             var p = c.prepareStatement("SELECT record_data FROM rag_runtime_record WHERE profile_id=? AND category='tags' AND record_id=?")) {
            p.setString(1, profile);
            p.setString(2, recordId);
            try (var rows = p.executeQuery()) {
                if (!rows.next()) return Set.of();
                JsonNode data = JSON.readTree(rows.getString(1));
                var ids = new HashSet<String>();
                for (JsonNode member : data.path("members")) if (member.isTextual()) ids.add(member.asText());
                return ids;
            }
        } catch (java.sql.SQLException | java.io.IOException failure) {
            throw new IllegalStateException("方块采掘标签读取失败", failure);
        }
    }
}
