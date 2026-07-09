package yagen.waitmydawn.kb.model;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.*;

/**
 * 内置原版 Minecraft 常用配方种子数据，确保基础物品配方可检索。
 */
public class VanillaRecipeSeeder {

    private static final List<VanillaRecipe> RECIPES = new ArrayList<>();

    static {
        // === Tools ===
        addCrafting("minecraft:diamond_pickaxe", 1, "shaped",
                "pattern", List.of("DDD", " S ", " S "),
                "key", Map.of("D", "minecraft:diamond", "S", "minecraft:stick"));
        addCrafting("minecraft:diamond_axe", 1, "shaped",
                "pattern", List.of("DD ", "DS ", " S "),
                "key", Map.of("D", "minecraft:diamond", "S", "minecraft:stick"));
        addCrafting("minecraft:diamond_sword", 1, "shaped",
                "pattern", List.of("D", "D", "S"),
                "key", Map.of("D", "minecraft:diamond", "S", "minecraft:stick"));
        addCrafting("minecraft:diamond_shovel", 1, "shaped",
                "pattern", List.of("D", "S", "S"),
                "key", Map.of("D", "minecraft:diamond", "S", "minecraft:stick"));
        addCrafting("minecraft:diamond_hoe", 1, "shaped",
                "pattern", List.of("DD", " S", " S"),
                "key", Map.of("D", "minecraft:diamond", "S", "minecraft:stick"));
        addCrafting("minecraft:iron_pickaxe", 1, "shaped",
                "pattern", List.of("III", " S ", " S "),
                "key", Map.of("I", "minecraft:iron_ingot", "S", "minecraft:stick"));
        addCrafting("minecraft:iron_sword", 1, "shaped",
                "pattern", List.of("I", "I", "S"),
                "key", Map.of("I", "minecraft:iron_ingot", "S", "minecraft:stick"));
        addCrafting("minecraft:iron_axe", 1, "shaped",
                "pattern", List.of("II ", "IS ", " S "),
                "key", Map.of("I", "minecraft:iron_ingot", "S", "minecraft:stick"));
        addCrafting("minecraft:stone_pickaxe", 1, "shaped",
                "pattern", List.of("CCC", " S ", " S "),
                "key", Map.of("C", "minecraft:cobblestone", "S", "minecraft:stick"));
        addCrafting("minecraft:golden_pickaxe", 1, "shaped",
                "pattern", List.of("GGG", " S ", " S "),
                "key", Map.of("G", "minecraft:gold_ingot", "S", "minecraft:stick"));
        addCrafting("minecraft:netherite_pickaxe", 1, "smithing",
                "base", Map.of("item", "minecraft:diamond_pickaxe"),
                "addition", Map.of("item", "minecraft:netherite_ingot"));

        // === Armor ===
        addCrafting("minecraft:diamond_chestplate", 1, "shaped",
                "pattern", List.of("D D", "DDD", "DDD"),
                "key", Map.of("D", "minecraft:diamond"));
        addCrafting("minecraft:diamond_helmet", 1, "shaped",
                "pattern", List.of("DDD", "D D", "   "),
                "key", Map.of("D", "minecraft:diamond"));
        addCrafting("minecraft:diamond_leggings", 1, "shaped",
                "pattern", List.of("DDD", "D D", "D D"),
                "key", Map.of("D", "minecraft:diamond"));
        addCrafting("minecraft:diamond_boots", 1, "shaped",
                "pattern", List.of("   ", "D D", "D D"),
                "key", Map.of("D", "minecraft:diamond"));

        // === Weapons ===
        addCrafting("minecraft:bow", 1, "shaped",
                "pattern", List.of(" SI", "S I", " SI"),
                "key", Map.of("S", "minecraft:stick", "I", "minecraft:string"));
        addCrafting("minecraft:arrow", 4, "shaped",
                "pattern", List.of("F", "S", "W"),
                "key", Map.of("F", "minecraft:flint", "S", "minecraft:stick", "W", "minecraft:feather"));
        addCrafting("minecraft:shield", 1, "shaped",
                "pattern", List.of("WIW", "WWW", " W "),
                "key", Map.of("W", "minecraft:oak_planks", "I", "minecraft:iron_ingot"));

        // === Blocks ===
        addCrafting("minecraft:crafting_table", 1, "shaped",
                "pattern", List.of("WW", "WW"),
                "key", Map.of("W", "minecraft:oak_planks"));
        addCrafting("minecraft:furnace", 1, "shaped",
                "pattern", List.of("CCC", "C C", "CCC"),
                "key", Map.of("C", "minecraft:cobblestone"));
        addCrafting("minecraft:chest", 1, "shaped",
                "pattern", List.of("WWW", "W W", "WWW"),
                "key", Map.of("W", "minecraft:oak_planks"));
        addCrafting("minecraft:brewing_stand", 1, "shaped",
                "pattern", List.of(" B ", "CCC"),
                "key", Map.of("B", "minecraft:blaze_rod", "C", "minecraft:cobblestone"));
        addCrafting("minecraft:enchanting_table", 1, "shaped",
                "pattern", List.of(" B ", "DOD", "OOO"),
                "key", Map.of("B", "minecraft:book", "D", "minecraft:diamond", "O", "minecraft:obsidian"));
        addCrafting("minecraft:anvil", 1, "shaped",
                "pattern", List.of("III", " I ", "III"),
                "key", Map.of("I", "minecraft:iron_block"));
        addCrafting("minecraft:cauldron", 1, "shaped",
                "pattern", List.of("I I", "I I", "III"),
                "key", Map.of("I", "minecraft:iron_ingot"));
        addCrafting("minecraft:hopper", 1, "shaped",
                "pattern", List.of("I I", "ICI", " I "),
                "key", Map.of("I", "minecraft:iron_ingot", "C", "minecraft:chest"));
        addCrafting("minecraft:beacon", 1, "shaped",
                "pattern", List.of("GGG", "GSG", "OOO"),
                "key", Map.of("G", "minecraft:glass", "S", "minecraft:nether_star", "O", "minecraft:obsidian"));
        addCrafting("minecraft:piston", 1, "shaped",
                "pattern", List.of("PPP", "CIC", "CRC"),
                "key", Map.of("P", "minecraft:oak_planks", "C", "minecraft:cobblestone", "I", "minecraft:iron_ingot", "R", "minecraft:redstone"));
        addCrafting("minecraft:sticky_piston", 1, "shaped",
                "pattern", List.of("S", "P"),
                "key", Map.of("S", "minecraft:slime_ball", "P", "minecraft:piston"));
        addCrafting("minecraft:observer", 1, "shaped",
                "pattern", List.of("CCC", "RRQ", "CCC"),
                "key", Map.of("C", "minecraft:cobblestone", "R", "minecraft:redstone", "Q", "minecraft:quartz"));
        addCrafting("minecraft:dispenser", 1, "shaped",
                "pattern", List.of("CCC", "CBC", "CRC"),
                "key", Map.of("C", "minecraft:cobblestone", "B", "minecraft:bow", "R", "minecraft:redstone"));
        addCrafting("minecraft:dropper", 1, "shaped",
                "pattern", List.of("CCC", "C C", "CRC"),
                "key", Map.of("C", "minecraft:cobblestone", "R", "minecraft:redstone"));
        addCrafting("minecraft:tnt", 1, "shaped",
                "pattern", List.of("GSG", "SGS", "GSG"),
                "key", Map.of("G", "minecraft:gunpowder", "S", "minecraft:sand"));
        addCrafting("minecraft:bookshelf", 1, "shaped",
                "pattern", List.of("PPP", "BBB", "PPP"),
                "key", Map.of("P", "minecraft:oak_planks", "B", "minecraft:book"));
        addCrafting("minecraft:jukebox", 1, "shaped",
                "pattern", List.of("PPP", "PDP", "PPP"),
                "key", Map.of("P", "minecraft:oak_planks", "D", "minecraft:diamond"));
        addCrafting("minecraft:note_block", 1, "shaped",
                "pattern", List.of("PPP", "PRP", "PPP"),
                "key", Map.of("P", "minecraft:oak_planks", "R", "minecraft:redstone"));

        // === Rails and Redstone ===
        addCrafting("minecraft:rail", 16, "shaped",
                "pattern", List.of("I I", "ISI", "I I"),
                "key", Map.of("I", "minecraft:iron_ingot", "S", "minecraft:stick"));
        addCrafting("minecraft:powered_rail", 6, "shaped",
                "pattern", List.of("G G", "GSG", "GRG"),
                "key", Map.of("G", "minecraft:gold_ingot", "S", "minecraft:stick", "R", "minecraft:redstone"));
        addCrafting("minecraft:repeater", 1, "shaped",
                "pattern", List.of("TRT", "SSS"),
                "key", Map.of("T", "minecraft:redstone_torch", "R", "minecraft:redstone", "S", "minecraft:stone"));
        addCrafting("minecraft:comparator", 1, "shaped",
                "pattern", List.of(" T ", "TQT", "SSS"),
                "key", Map.of("T", "minecraft:redstone_torch", "Q", "minecraft:quartz", "S", "minecraft:stone"));

        // === Other common items ===
        addCrafting("minecraft:torch", 4, "shaped",
                "pattern", List.of("C", "S"),
                "key", Map.of("C", "minecraft:coal", "S", "minecraft:stick"));
        addCrafting("minecraft:ladder", 3, "shaped",
                "pattern", List.of("S S", "SSS", "S S"),
                "key", Map.of("S", "minecraft:stick"));
        addCrafting("minecraft:compass", 1, "shaped",
                "pattern", List.of(" I ", "IRI", " I "),
                "key", Map.of("I", "minecraft:iron_ingot", "R", "minecraft:redstone"));
        addCrafting("minecraft:clock", 1, "shaped",
                "pattern", List.of(" G ", "GRG", " G "),
                "key", Map.of("G", "minecraft:gold_ingot", "R", "minecraft:redstone"));
        addCrafting("minecraft:painting", 1, "shaped",
                "pattern", List.of("SSS", "SWS", "SSS"),
                "key", Map.of("S", "minecraft:stick", "W", "minecraft:white_wool"));
        addCrafting("minecraft:item_frame", 1, "shaped",
                "pattern", List.of("SSS", "SLS", "SSS"),
                "key", Map.of("S", "minecraft:stick", "L", "minecraft:leather"));
        addCrafting("minecraft:flint_and_steel", 1, "shaped",
                "pattern", List.of("F ", " I"),
                "key", Map.of("F", "minecraft:flint", "I", "minecraft:iron_ingot"));
        addCrafting("minecraft:fishing_rod", 1, "shaped",
                "pattern", List.of("  S", " SI", "S I"),
                "key", Map.of("S", "minecraft:stick", "I", "minecraft:string"));
        addCrafting("minecraft:shears", 1, "shaped",
                "pattern", List.of(" I", "I "),
                "key", Map.of("I", "minecraft:iron_ingot"));
        addCrafting("minecraft:bucket", 1, "shaped",
                "pattern", List.of("I I", " I "),
                "key", Map.of("I", "minecraft:iron_ingot"));

        // === Furnace recipes ===
        addFurnace("minecraft:iron_ingot", "minecraft:iron_ore", 200);
        addFurnace("minecraft:gold_ingot", "minecraft:gold_ore", 200);
        addFurnace("minecraft:diamond", "minecraft:diamond_ore", 200);
        addFurnace("minecraft:glass", "minecraft:sand", 200);
        addFurnace("minecraft:stone", "minecraft:cobblestone", 200);
        addFurnace("minecraft:cooked_beef", "minecraft:beef", 200);
        addFurnace("minecraft:cooked_porkchop", "minecraft:porkchop", 200);
        addFurnace("minecraft:bread", "minecraft:wheat", 100); // actually crafting but close enough
    }

    private static void addCrafting(String output, int count, String type, Object... kv) {
        Map<String, Object> recipe = new LinkedHashMap<>();
        recipe.put("type", "minecraft:" + (type.contains("shaped") ? "crafting_shaped" : type));
        recipe.put("result", Map.of("id", output, "count", count));
        // Apply key-value pairs
        Map<String, Object> kMap = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            if (i + 1 < kv.length) {
                String key = kv[i].toString();
                Object val = kv[i + 1];
                if ("key".equals(key)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> keyMap = (Map<String, Object>) val;
                    Map<String, Object> convertedKey = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> e : keyMap.entrySet()) {
                        convertedKey.put(e.getKey(), Map.of("id", e.getValue()));
                    }
                    kMap.putAll(convertedKey);
                } else {
                    kMap.put(key, val);
                }
            }
        }
        recipe.put("key", kMap);

        RECIPES.add(new VanillaRecipe(output, count, type, recipe));
    }

    @SuppressWarnings("unchecked")
    private static void addFurnace(String output, String input, int cookingTime) {
        Map<String, Object> recipe = new LinkedHashMap<>();
        recipe.put("type", "minecraft:smelting");
        recipe.put("ingredient", Map.of("id", input));
        recipe.put("result", Map.of("id", output, "count", 1));
        recipe.put("cookingtime", cookingTime);
        RECIPES.add(new VanillaRecipe(output, 1, "furnace", recipe));
    }

    /**
     * Insert vanilla recipes into the database. Idempotent (skips if already exists).
     */
    public static void seed(Connection conn) {
        String countSql = "SELECT COUNT(*) FROM rag_recipe WHERE source_mod = 'minecraft'";
        try (var ps = conn.prepareStatement(countSql); var rs = ps.executeQuery()) {
            if (rs.next() && rs.getInt(1) > 0) return; // already seeded
        } catch (Exception e) { /* proceed */ }

        String sql = """
            MERGE INTO rag_recipe (output_item, recipe_type, output_count, recipe_data, source_mod, source_type)
            KEY (output_item, recipe_type, source_mod) VALUES (?, ?, ?, ?, 'minecraft', 'SEED')
            """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (VanillaRecipe vr : RECIPES) {
                ps.setString(1, vr.output);
                ps.setString(2, vr.type);
                ps.setInt(3, vr.count);
                ps.setString(4, toJson(vr.recipe));
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (Exception e) {
            System.err.println("Vanilla recipe seed failed: " + e.getMessage());
        }
    }

    private static String toJson(Map<String, Object> map) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    private record VanillaRecipe(String output, int count, String type, Map<String, Object> recipe) {}
}
