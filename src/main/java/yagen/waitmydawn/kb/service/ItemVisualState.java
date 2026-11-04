package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import java.util.regex.Pattern;

/** 只识别原版固定模型装备的附魔外观回退；未知组件和模组物品仍要求精确图。 */
final class ItemVisualState {
    static final String ENCHANTMENT_NOTE = "附魔状态未单独导出";
    private static final Set<String> EQUIPMENT = Set.of(
            "bow", "crossbow", "trident", "shield", "elytra", "fishing_rod", "flint_and_steel",
            "shears", "carrot_on_a_stick", "warped_fungus_on_a_stick", "turtle_helmet", "mace");
    private static final Pattern TOOLS = Pattern.compile("(?:wooden|stone|iron|golden|diamond|netherite)_(?:sword|pickaxe|axe|shovel|hoe)");
    private static final Pattern ARMOR = Pattern.compile("(?:leather|chainmail|iron|golden|diamond|netherite)_(?:helmet|chestplate|leggings|boots)");
    private static final String ID = "(?:id|\"id\"|'id')\\s*:\\s*(?:\"[a-z0-9_.-]+:[a-z0-9_./-]+\"|'[a-z0-9_.-]+:[a-z0-9_./-]+')";
    private static final String LEVEL = "(?:lvl|\"lvl\"|'lvl')\\s*:\\s*[0-9]{1,3}[sS]?";
    private static final String ENTRY = "\\{\\s*(?:" + ID + "\\s*,\\s*" + LEVEL + "|" + LEVEL + "\\s*,\\s*" + ID + ")\\s*\\}";
    private static final Pattern ENCHANTMENT_NBT = Pattern.compile("\\{\\s*(?:Enchantments|\"Enchantments\"|'Enchantments')\\s*:\\s*\\[\\s*(?:" + ENTRY + "(?:\\s*,\\s*" + ENTRY + ")*)?\\s*\\]\\s*\\}");
    private ItemVisualState() {}

    static boolean enchantmentOnly(String id, JsonNode state) {
        if (!fixedVanillaEquipment(id) || state == null || !state.isObject() || state.size() != 1) return false;
        if (state.path("nbt").isTextual()) {
            String nbt = state.path("nbt").asText();
            return nbt.length() <= 16384 && ENCHANTMENT_NBT.matcher(nbt).matches();
        }
        var components = state.path("components");
        if (!components.isObject() || components.isEmpty() || !components.has("minecraft:enchantments")) return false;
        for (var fields = components.fields(); fields.hasNext();) {
            var entry = fields.next();
            if (entry.getKey().equals("minecraft:enchantment_glint_override")) {
                if (!entry.getValue().isBoolean()) return false;
            } else if (!entry.getKey().equals("minecraft:enchantments") || !validEnchantments(entry.getValue())) return false;
        }
        return true;
    }

    static boolean fixedVanillaEquipment(String id) {
        if (id == null || !id.startsWith("minecraft:")) return false;
        String path = id.substring("minecraft:".length());
        return EQUIPMENT.contains(path) || TOOLS.matcher(path).matches() || ARMOR.matcher(path).matches();
    }

    private static boolean validEnchantments(JsonNode value) {
        if (!value.isObject()) return false;
        for (var fields = value.fields(); fields.hasNext();) {
            var entry = fields.next();
            if (entry.getKey().equals("show_in_tooltip")) {
                if (!entry.getValue().isBoolean()) return false;
            } else if (!entry.getKey().equals("levels") || !entry.getValue().isObject()) return false;
        }
        var levels = value.path("levels");
        if (!levels.isObject()) return false;
        for (var fields = levels.fields(); fields.hasNext();) {
            var entry = fields.next();
            if (!entry.getKey().matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || !entry.getValue().isIntegralNumber()
                    || !entry.getValue().canConvertToInt() || entry.getValue().asInt() < 0 || entry.getValue().asInt() > 255) return false;
        }
        return true;
    }
}
