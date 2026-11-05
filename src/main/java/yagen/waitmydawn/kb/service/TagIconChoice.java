package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** 标签图标候选：优先原版命名空间，否则退回标签展开后的第一个成员。 */
public final class TagIconChoice {
    private TagIconChoice() {}

    public static String preferred(List<String> items) {
        if (items == null || items.isEmpty()) return null;
        for (String item : items) if (item != null && item.startsWith("minecraft:")) return item;
        return items.getFirst();
    }

    public static String preferred(JsonNode members) {
        if (members == null || !members.isArray()) return null;
        for (JsonNode member : members) {
            String id = member.asText("");
            if (id.startsWith("minecraft:")) return id;
        }
        return members.isEmpty() ? null : members.get(0).asText("");
    }
}
