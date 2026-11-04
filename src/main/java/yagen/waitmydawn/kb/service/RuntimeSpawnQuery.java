package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import yagen.waitmydawn.kb.model.DatabaseBuilder;

import java.sql.*;
import java.util.*;

/**
 * spawn_rules 的候选查询。只读导出快照，不执行生成谓词，也不把 placement 当成栖息地分类。
 */
public final class RuntimeSpawnQuery {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> WATER_CATEGORIES = Set.of(
            "water_creature", "water_ambient", "axolotls", "underground_water_creature");
    private static final String PART = "(?i).*_(head|part|parts|segment|tail|body|claw|limb|piece)$";
    private static final String NOTE = "spawn_rules 只覆盖可枚举配置；placement 是生成放置条件，不是栖息地分类。"
            + "IN_WATER 不等于一定是被动水生生物，ON_GROUND 不等于一定是陆生；空数组/无规则不代表不会生成。"
            + "动态事件、召唤、结构部件代码和刷怪笼 NBT 不在此枚举范围内。"
            + "mob_category 是实体分类，spawn_category 是 biomeSpawns 的生成池分类，两者不能混用。";

    private RuntimeSpawnQuery() {
    }

    public record Filter(String mod, String mobCategory, String spawnCategory,
                         String placementStatus, String placementType, String heightmap,
                         String biome, String biomeQuery, String structure, String structureBoundingBox,
                         String excludePlacementTypes,
                         Boolean hasBiomeSpawn, Boolean hasStructureSpawn,
                         String query, int offset, int limit,
                         boolean includeNonMob, boolean includeParts, boolean includeFacets) {
        public Filter {
            offset = Math.max(0, offset);
            limit = Math.max(1, Math.min(100, limit));
        }
    }

    private record Row(String id, String mobCategory, String placementStatus, String placementType,
                       String heightmap, List<JsonNode> biomes, List<JsonNode> structures,
                       Set<String> spawnCategories) {
    }

    /** 读取实体标签成员；用于没有 spawn_rules 但由标签确认归属的候选。 */
    public static ObjectNode tagMembers(DatabaseBuilder db, String mod, String tag) throws Exception {
        var out = JSON.createObjectNode();
        out.put("status", "OK").put("mod", mod == null ? "" : mod).put("tag", tag == null ? "" : tag);
        if (mod == null || mod.isBlank() || tag == null || tag.isBlank())
            throw new IllegalArgumentException("mod and entity_tag are required");
        String profile = RuntimeKnowledgeStore.active(db);
        if (profile == null) {
            out.put("status", "NO_RUNTIME_SNAPSHOT");
            out.putArray("objects");
            out.put("total", 0);
            return out;
        }
        String recordId = tag.contains("|") ? tag : "minecraft:entity_type|" + tag;
        JsonNode row = null;
        try (var c = db.getConnection();
             var p = c.prepareStatement("SELECT record_data FROM rag_runtime_record WHERE profile_id=? AND category='tags' AND record_id=?")) {
            p.setString(1, profile);
            p.setString(2, recordId);
            try (var rows = p.executeQuery()) {
                if (rows.next()) row = JSON.readTree(rows.getString(1));
            }
        }
        if (row == null || !row.path("members").isArray()) {
            out.put("status", "TAG_NOT_FOUND");
            out.putArray("objects");
            out.put("total", 0);
            return out;
        }
        var ids = new ArrayList<String>();
        for (var member : row.path("members")) {
            String id = member.asText("");
            if (id.startsWith(mod + ":")) ids.add(id);
        }
        var names = new RuntimeNameIndex(db, profile).namesOf(ids);
        boolean aquaticTag = tag.toLowerCase(Locale.ROOT).contains("aquatic");
        var objects = out.putArray("objects");
        try (var c = db.getConnection();
             var p = c.prepareStatement("SELECT record_data FROM rag_runtime_record WHERE profile_id=? AND category='spawn_rules' AND record_id=?")) {
            for (String id : ids) {
                String placement = "";
                JsonNode rule = null;
                p.setString(1, profile);
                p.setString(2, id);
                try (var rows = p.executeQuery()) {
                    if (rows.next()) rule = JSON.readTree(rows.getString(1));
                }
                if (rule != null) placement = rule.path("placement").path("type").asText("");
                boolean lava = "IN_LAVA".equals(placement);
                var object = objects.addObject();
                object.put("id", id);
                String name = names.get(id);
                if (name != null && !name.isBlank()) object.put("name", name);
                object.put("tag", tag);
                object.put("tagOnly", true);
                object.put("aquaticTag", aquaticTag);
                object.put("aquaticCandidate", aquaticTag && !lava);
                object.put("waterCategoryMember", false);
                object.put("waterPlacementOnly", false);
                object.put("onGroundCandidate", false);
                if (!placement.isBlank()) object.put("placementType", placement);
                object.put("lavaPlacement", lava);
                var basis = object.putArray("basis").add("ENTITY_TAG");
                if (lava) {
                    basis.add("LAVA_PLACEMENT");
                    basis.add("EXCLUDED_IN_LAVA");
                    object.putArray("excludedReasons").add("IN_LAVA");
                }
            }
        }
        out.put("total", ids.size());
        return out;
    }

    public static ObjectNode find(DatabaseBuilder db, Filter filter) throws Exception {
        if (filter == null || filter.mod() == null || filter.mod().isBlank())
            throw new IllegalArgumentException("mod is required (namespace such as minecraft or alexsmobs)");
        String mod = filter.mod().trim();
        var out = JSON.createObjectNode();
        out.put("status", "OK").put("mod", mod);
        String profile = RuntimeKnowledgeStore.active(db);
        if (profile == null) {
            out.put("status", "NO_RUNTIME_SNAPSHOT");
            out.putNull("coverageStatus");
            out.put("partial", true);
            out.put("total", 0).put("offset", filter.offset()).put("returned", 0).put("nextOffset", -1);
            out.putArray("objects");
            out.put("note", NOTE);
            return out;
        }

        boolean complete;
        String coverageStatus;
        try (var c = db.getConnection();
             var p = c.prepareStatement("SELECT descriptor FROM rag_runtime_snapshot WHERE profile_id=?")) {
            p.setString(1, profile);
            try (var rows = p.executeQuery()) {
                var descriptor = rows.next() ? JSON.readTree(rows.getString(1)) : JSON.createObjectNode();
                coverageStatus = descriptor.path("categories").path("spawn_rules").path("status").asText("UNKNOWN");
                complete = "COMPLETE".equals(coverageStatus);
            }
        }
        out.put("coverageStatus", coverageStatus);
        out.put("partial", !complete);

        var baseRows = new ArrayList<Row>();
        var errors = 0;
        try (var c = db.getConnection();
             var p = c.prepareStatement("SELECT record_id,record_data FROM rag_runtime_record WHERE profile_id=? AND category='spawn_rules' AND record_id LIKE ? ORDER BY record_id")) {
            p.setString(1, profile);
            p.setString(2, mod + ":%");
            try (var rows = p.executeQuery()) {
                while (rows.next()) {
                    String id = rows.getString(1);
                    JsonNode data;
                    try {
                        data = JSON.readTree(rows.getString(2));
                    } catch (Exception malformed) {
                        errors++;
                        continue;
                    }
                    if (data == null || data.has("error")) {
                        errors++;
                        continue;
                    }
                    var biomes = new ArrayList<JsonNode>();
                    for (var entry : data.path("biomeSpawns")) biomes.add(entry);
                    var structures = new ArrayList<JsonNode>();
                    for (var entry : data.path("structureSpawns")) structures.add(entry);
                    var spawnCategories = new LinkedHashSet<String>();
                    for (var entry : biomes) {
                        String category = entry.path("category").asText("");
                        if (!category.isBlank()) spawnCategories.add(category);
                    }
                    var placement = data.path("placement");
                    baseRows.add(new Row(id,
                            data.path("mobCategory").asText(""),
                            placement.path("status").asText(""),
                            placement.path("type").asText(""),
                            placement.path("heightmap").asText(""),
                            List.copyOf(biomes), List.copyOf(structures), Set.copyOf(spawnCategories)));
                }
            }
        }
        out.put("errors", errors);
        out.put("excludedByPlacement", 0);

        var names = new RuntimeNameIndex(db, profile).namesOf(baseRows.stream().map(Row::id).toList());
        var candidates = new ArrayList<Row>();
        int excludedByPlacement = 0;
        for (var row : baseRows) {
            if (!filter.includeNonMob() && "misc".equals(row.mobCategory())) continue;
            if (!filter.includeParts() && row.id().matches(PART)) continue;
            if (!matches(row.mobCategory(), filter.mobCategory())) continue;
            if (!blank(filter.spawnCategory()) && row.spawnCategories().stream().noneMatch(value -> matches(value, filter.spawnCategory())))
                continue;
            if (!matches(row.placementStatus(), filter.placementStatus())) continue;
            if (!matchesPlacement(row.placementType(), filter.placementType())) continue;
            if (!blank(filter.excludePlacementTypes()) && matchesPlacement(row.placementType(), filter.excludePlacementTypes())) {
                excludedByPlacement++;
                continue;
            }
            if (!matches(row.heightmap(), filter.heightmap())) continue;

            var matchedBiomes = row.biomes().stream().filter(entry -> biomeMatches(entry, filter)).toList();
            if ((!blank(filter.biome()) || !blank(filter.biomeQuery())) && matchedBiomes.isEmpty()) continue;
            var matchedStructures = row.structures().stream().filter(entry -> structureMatches(entry, filter)).toList();
            if ((!blank(filter.structure()) || !blank(filter.structureBoundingBox())) && matchedStructures.isEmpty()) continue;

            if (filter.hasBiomeSpawn() != null && filter.hasBiomeSpawn() != !row.biomes().isEmpty()) continue;
            if (filter.hasStructureSpawn() != null && filter.hasStructureSpawn() != !row.structures().isEmpty()) continue;

            if (!blank(filter.query())) {
                String needle = RuntimeNameIndex.normalize(filter.query());
                String name = RuntimeNameIndex.normalize(names.getOrDefault(row.id(), ""));
                if (!row.id().toLowerCase(Locale.ROOT).contains(needle) && !name.contains(needle)) continue;
            }
            candidates.add(new Row(row.id(), row.mobCategory(), row.placementStatus(), row.placementType(),
                    row.heightmap(), row.biomes(), row.structures(), row.spawnCategories()));
            // 保存匹配项供输出使用；用同一 row 的额外 map 会破坏 record 简洁性，下面重新计算。
        }

        out.put("excludedByPlacement", excludedByPlacement);
        var aquaticGroups = new TreeMap<String, Integer>();
        for (var row : candidates) {
            String group = aquaticGroup(row);
            if (!group.isBlank()) aquaticGroups.merge(group, 1, Integer::sum);
        }
        out.set("aquaticGroupCounts", counts(aquaticGroups));
        out.put("total", candidates.size());
        out.put("offset", filter.offset());
        int from = Math.min(filter.offset(), candidates.size());
        int to = Math.min(candidates.size(), from + filter.limit());
        var page = candidates.subList(from, to);
        out.put("returned", page.size());
        out.put("nextOffset", to < candidates.size() ? to : -1);
        out.putArray("objects");
        for (var row : page) {
            var object = out.withArray("objects").addObject();
            object.put("id", row.id());
            String name = names.get(row.id());
            if (name != null && !name.isBlank()) object.put("name", name);
            object.put("mobCategory", row.mobCategory());
            var placement = object.putObject("placement");
            placement.put("status", row.placementStatus());
            if (!row.placementType().isBlank()) placement.put("type", row.placementType());
            if (!row.heightmap().isBlank()) placement.put("heightmap", row.heightmap());

            var matchedBy = object.putArray("matchedBy");
            if (!blank(filter.biome()) || !blank(filter.biomeQuery())) matchedBy.add("BIOME_SPAWN");
            if (!blank(filter.structure()) || !blank(filter.structureBoundingBox())) matchedBy.add("STRUCTURE_SPAWN");
            if (!blank(filter.placementStatus()) || !blank(filter.placementType()) || !blank(filter.heightmap()))
                matchedBy.add("PLACEMENT");
            if (matchedBy.isEmpty()) matchedBy.add("SPAWN_RULE");

            boolean waterCategory = row.spawnCategories().stream().anyMatch(WATER_CATEGORIES::contains);
            boolean waterPlacement = "IN_WATER".equals(row.placementType());
            boolean lava = "IN_LAVA".equals(row.placementType());
            boolean waterMob = row.mobCategory().startsWith("water_")
                    || Set.of("axolotls", "underground_water_creature").contains(row.mobCategory());
            object.put("aquaticGroup", aquaticGroup(row));
            object.put("waterCategoryMember", waterCategory && !lava);
            object.put("waterPlacementOnly", waterPlacement && !waterCategory && !lava);
            object.put("aquaticCandidate", !lava && (waterMob || waterCategory || waterPlacement));
            object.put("lavaPlacement", lava);
            object.put("onGroundCandidate", "ON_GROUND".equals(row.placementType()));
            var basis = object.putArray("basis");
            if (waterCategory) basis.add("WATER_CATEGORY");
            if (waterPlacement) basis.add("WATER_PLACEMENT");
            if (lava) {
                basis.add("LAVA_PLACEMENT");
                basis.add("EXCLUDED_IN_LAVA");
                object.putArray("excludedReasons").add("IN_LAVA");
            }
            if (!row.structures().isEmpty()) basis.add("STRUCTURE_SPAWN");
            if ("ON_GROUND".equals(row.placementType())) basis.add("ON_GROUND_PLACEMENT");
            if ("NO_RESTRICTIONS".equals(row.placementType())) basis.add("NO_RESTRICTIONS");
            if ("UNAVAILABLE".equals(row.placementStatus())) basis.add("PLACEMENT_UNAVAILABLE");
            if (row.biomes().isEmpty() && row.structures().isEmpty() && "NOT_REGISTERED".equals(row.placementStatus()))
                basis.add("NO_SPAWN_RULE");

            object.put("biomeSpawnCount", row.biomes().size());
            object.put("structureSpawnCount", row.structures().size());
            var spawnCategories = object.putArray("spawnCategories");
            row.spawnCategories().stream().sorted().forEach(spawnCategories::add);
            if (!blank(filter.biome()) || !blank(filter.biomeQuery())) {
                var matched = object.putArray("matchedBiomeSpawns");
                row.biomes().stream().filter(entry -> biomeMatches(entry, filter)).limit(10)
                        .forEach(entry -> matched.add(compactBiome(entry)));
            }
            if (!blank(filter.structure()) || !blank(filter.structureBoundingBox())) {
                var matched = object.putArray("matchedStructureSpawns");
                row.structures().stream().filter(entry -> structureMatches(entry, filter)).limit(10)
                        .forEach(entry -> matched.add(compactStructure(entry)));
            }
        }

        if (filter.includeFacets()) out.set("facets", facets(baseRows));
        out.put("note", NOTE);
        return out;
    }

    private static ObjectNode facets(List<Row> rows) {
        var facets = JSON.createObjectNode();
        var mob = new TreeMap<String, Integer>();
        var statuses = new TreeMap<String, Integer>();
        var placements = new TreeMap<String, Integer>();
        var heightmaps = new TreeMap<String, Integer>();
        var categories = new TreeMap<String, Integer>();
        var structureRelations = new TreeMap<String, Integer>();
        var structureBoxes = new TreeMap<String, Integer>();
        for (var row : rows) {
            merge(mob, row.mobCategory());
            merge(statuses, row.placementStatus());
            merge(placements, row.placementType());
            merge(heightmaps, row.heightmap());
            for (String category : row.spawnCategories()) merge(categories, category);
            for (var structure : row.structures()) {
                merge(structureRelations, structure.path("relation").asText(""));
                merge(structureBoxes, structure.path("boundingBox").asText(""));
            }
        }
        facets.set("mobCategories", counts(mob));
        facets.set("placementStatuses", counts(statuses));
        facets.set("placementTypes", counts(placements));
        facets.set("heightmaps", counts(heightmaps));
        facets.set("spawnCategories", counts(categories));
        facets.set("structureRelations", counts(structureRelations));
        facets.set("structureBoundingBoxes", counts(structureBoxes));
        facets.put("records", rows.size());
        return facets;
    }

    private static void merge(Map<String, Integer> values, String value) {
        if (value != null && !value.isBlank()) values.merge(value, 1, Integer::sum);
    }

    private static ObjectNode counts(Map<String, Integer> values) {
        var result = JSON.createObjectNode();
        values.forEach(result::put);
        return result;
    }

    private static ObjectNode compactBiome(JsonNode entry) {
        var result = JSON.createObjectNode();
        result.put("biome", entry.path("biome").asText(""));
        result.put("category", entry.path("category").asText(""));
        result.put("weight", entry.path("weight").asLong());
        result.put("poolWeight", entry.path("poolWeight").asLong());
        result.put("minCount", entry.path("minCount").asLong());
        result.put("maxCount", entry.path("maxCount").asLong());
        return result;
    }

    private static ObjectNode compactStructure(JsonNode entry) {
        var result = JSON.createObjectNode();
        result.put("structure", entry.path("structure").asText(""));
        result.put("boundingBox", entry.path("boundingBox").asText(""));
        result.put("category", entry.path("category").asText(""));
        result.put("relation", entry.path("relation").asText(""));
        result.put("minCount", entry.path("minCount").asLong());
        result.put("maxCount", entry.path("maxCount").asLong());
        var allowed = result.putArray("allowedBiomes");
        entry.path("allowedBiomes").forEach(allowed::add);
        return result;
    }

    private static boolean biomeMatches(JsonNode entry, Filter filter) {
        String id = entry.path("biome").asText("");
        if (!blank(filter.biome()) && !matches(id, filter.biome())) return false;
        if (!blank(filter.biomeQuery())) {
            String needle = RuntimeNameIndex.normalize(filter.biomeQuery());
            if (!RuntimeNameIndex.normalize(id).contains(needle)) return false;
        }
        return true;
    }

    private static boolean structureMatches(JsonNode entry, Filter filter) {
        if (!blank(filter.structure()) && !matches(entry.path("structure").asText(""), filter.structure())) return false;
        return blank(filter.structureBoundingBox()) || matches(entry.path("boundingBox").asText(""), filter.structureBoundingBox());
    }

    /** 实体分类口径；IN_LAVA 不属于水生组，结构/放置候选也不冒充实体分类。 */
    private static String aquaticGroup(Row row) {
        if ("IN_LAVA".equals(row.placementType())) return "";
        String category = row.mobCategory();
        return category.startsWith("water_") || Set.of("axolotls", "underground_water_creature").contains(category)
                ? category : "";
    }

    private static boolean matchesPlacement(String actual, String requested) {
        if (blank(requested)) return true;
        for (String value : requested.split(",")) {
            String wanted = value.trim();
            if (wanted.isBlank()) continue;
            if (wanted.equalsIgnoreCase("CUSTOM") && actual.toUpperCase(Locale.ROOT).startsWith("CUSTOM:")) return true;
            if (actual.equalsIgnoreCase(wanted)) return true;
        }
        return false;
    }

    private static boolean matches(String actual, String requested) {
        if (blank(requested)) return true;
        for (String value : requested.split(",")) if (actual.equalsIgnoreCase(value.trim())) return true;
        return false;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
