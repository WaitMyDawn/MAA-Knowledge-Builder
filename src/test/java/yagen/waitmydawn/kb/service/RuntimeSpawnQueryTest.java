package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.agent.*;
import yagen.waitmydawn.kb.model.*;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeSpawnQueryTest {
    @TempDir Path temp;
    private final ObjectMapper json = new ObjectMapper();
    private final String profile = "a".repeat(64);
    private DatabaseBuilder db;

    @BeforeEach void setUp() throws Exception {
        db = DatabaseBuilder.atPath(temp.resolve("db"));
        db.initDatabase();
        try (var c = db.getConnection()) {
            RuntimeKnowledgeStore.init(c);
            var descriptor = json.createObjectNode();
            descriptor.put("captureMode", "SINGLEPLAYER");
            descriptor.putObject("categories").putObject("spawn_rules").put("status", "COMPLETE");
            try (var p = c.prepareStatement("INSERT INTO rag_runtime_snapshot VALUES('source',?,?)")) {
                p.setString(1, profile);
                p.setString(2, descriptor.toString());
                p.executeUpdate();
            }
        }
        record("minecraft:guardian", """
                {"id":"minecraft:guardian","mobCategory":"monster","coverage":"ENUMERABLE_CONFIGURATION_ONLY",
                 "placement":{"status":"REGISTERED","type":"IN_WATER","heightmap":"MOTION_BLOCKING_NO_LEAVES"},
                 "biomeSpawns":[],
                 "structureSpawns":[{"structure":"minecraft:monument","category":"monster","boundingBox":"STRUCTURE",
                   "relation":"STRUCTURE_POOL_OVERRIDE","minCount":2,"maxCount":4,"allowedBiomes":["minecraft:deep_ocean"]}]}
                """);
        record("minecraft:elder_guardian", """
                {"id":"minecraft:elder_guardian","mobCategory":"monster","coverage":"ENUMERABLE_CONFIGURATION_ONLY",
                 "placement":{"status":"REGISTERED","type":"IN_WATER","heightmap":"MOTION_BLOCKING_NO_LEAVES"},
                 "biomeSpawns":[],"structureSpawns":[]}
                """);
        record("minecraft:tadpole", """
                {"id":"minecraft:tadpole","mobCategory":"creature","coverage":"ENUMERABLE_CONFIGURATION_ONLY",
                 "placement":{"status":"NOT_REGISTERED"},"biomeSpawns":[],"structureSpawns":[]}
                """);
        record("minecraft:drowned", """
                {"id":"minecraft:drowned","mobCategory":"monster","coverage":"ENUMERABLE_CONFIGURATION_ONLY",
                 "placement":{"status":"REGISTERED","type":"IN_WATER","heightmap":"MOTION_BLOCKING_NO_LEAVES"},
                 "biomeSpawns":[{"biome":"minecraft:river","category":"monster","weight":1,"poolWeight":1,"minCount":1,"maxCount":1}],
                 "structureSpawns":[]}
                """);
        record("minecraft:turtle", """
                {"id":"minecraft:turtle","mobCategory":"creature","coverage":"ENUMERABLE_CONFIGURATION_ONLY",
                 "placement":{"status":"REGISTERED","type":"ON_GROUND","heightmap":"MOTION_BLOCKING_NO_LEAVES"},
                 "biomeSpawns":[{"biome":"minecraft:beach","category":"creature","weight":1,"poolWeight":1,"minCount":1,"maxCount":1}],
                 "structureSpawns":[]}
                """);
        record("minecraft:cow", """
                {"id":"minecraft:cow","mobCategory":"creature","coverage":"ENUMERABLE_CONFIGURATION_ONLY",
                 "placement":{"status":"REGISTERED","type":"ON_GROUND","heightmap":"MOTION_BLOCKING_NO_LEAVES"},
                 "biomeSpawns":[{"biome":"minecraft:plains","category":"creature","weight":8,"poolWeight":100,"minCount":4,"maxCount":4}],
                 "structureSpawns":[]}
                """);
        record("alexsmobs:orca", """
                {"id":"alexsmobs:orca","mobCategory":"water_creature","coverage":"ENUMERABLE_CONFIGURATION_ONLY",
                 "placement":{"status":"REGISTERED","type":"IN_WATER","heightmap":"MOTION_BLOCKING_NO_LEAVES"},
                 "biomeSpawns":[{"biome":"minecraft:ocean","category":"water_creature","weight":1,"poolWeight":1,"minCount":1,"maxCount":2}],
                 "structureSpawns":[]}
                """);
        record("alexsmobs:platypus", """
                {"id":"alexsmobs:platypus","mobCategory":"creature","coverage":"ENUMERABLE_CONFIGURATION_ONLY",
                 "placement":{"status":"REGISTERED","type":"ON_GROUND","heightmap":"MOTION_BLOCKING_NO_LEAVES"},
                 "biomeSpawns":[{"biome":"minecraft:river","category":"creature","weight":1,"poolWeight":1,"minCount":1,"maxCount":1}],
                 "structureSpawns":[]}
                """);
        record("alexsmobs:stradpole", """
                {"id":"alexsmobs:stradpole","mobCategory":"water_ambient","coverage":"ENUMERABLE_CONFIGURATION_ONLY",
                 "placement":{"status":"REGISTERED","type":"IN_LAVA","heightmap":"MOTION_BLOCKING_NO_LEAVES"},
                 "biomeSpawns":[{"biome":"minecraft:nether_wastes","category":"water_creature","weight":1,"poolWeight":1,"minCount":1,"maxCount":1}],
                 "structureSpawns":[]}
                """);
        record("alexsmobs:sea_bear", """
                {"id":"alexsmobs:sea_bear","mobCategory":"water_creature","coverage":"ENUMERABLE_CONFIGURATION_ONLY",
                 "placement":{"status":"NOT_REGISTERED"},"biomeSpawns":[],"structureSpawns":[]}
                """);
        record("alexsmobs:anaconda_part", """
                {"id":"alexsmobs:anaconda_part","mobCategory":"misc","coverage":"ENUMERABLE_CONFIGURATION_ONLY",
                 "placement":{"status":"NOT_REGISTERED"},"biomeSpawns":[],"structureSpawns":[]}
                """);
        record("alexsmobs:skreecher", """
                {"id":"alexsmobs:skreecher","mobCategory":"creature","coverage":"ENUMERABLE_CONFIGURATION_ONLY",
                 "placement":{"status":"REGISTERED","type":"ON_GROUND","heightmap":"MOTION_BLOCKING_NO_LEAVES"},
                 "biomeSpawns":[{"biome":"minecraft:dark_forest","category":"monster","weight":1,"poolWeight":1,"minCount":1,"maxCount":1}],
                 "structureSpawns":[]}
                """);
        names();
    }

    @AfterEach void tearDown() {
        if (db != null) db.shutdown();
    }

    private void record(String id, String raw) throws Exception {
        try (var c = db.getConnection(); var p = c.prepareStatement("INSERT INTO rag_runtime_record VALUES(?,?,?,?)")) {
            p.setString(1, profile);
            p.setString(2, "spawn_rules");
            p.setString(3, id);
            p.setString(4, json.readTree(raw).toString());
            p.executeUpdate();
        }
    }

    private void tagRecord(String id, String raw) throws Exception {
        try (var c = db.getConnection(); var p = c.prepareStatement("INSERT INTO rag_runtime_record VALUES(?,?,?,?)")) {
            p.setString(1, profile);
            p.setString(2, "tags");
            p.setString(3, id);
            p.setString(4, json.readTree(raw).toString());
            p.executeUpdate();
        }
    }

    private void names() throws Exception {
        var values = Map.ofEntries(
                Map.entry("minecraft:guardian", "守卫者"),
                Map.entry("minecraft:elder_guardian", "远古守卫者"),
                Map.entry("minecraft:tadpole", "蝌蚪"),
                Map.entry("minecraft:drowned", "溺尸"),
                Map.entry("minecraft:turtle", "海龟"),
                Map.entry("minecraft:cow", "牛"),
                Map.entry("alexsmobs:orca", "虎鲸"),
                Map.entry("alexsmobs:platypus", "鸭嘴兽"),
                Map.entry("alexsmobs:stradpole", "跨座蝌蚪"),
                Map.entry("alexsmobs:sea_bear", "海熊"),
                Map.entry("alexsmobs:seal", "海豹"),
                Map.entry("alexsmobs:mudskipper", "弹涂鱼"),
                Map.entry("alexsmobs:anaconda_part", "森蚺部件"),
                Map.entry("alexsmobs:skreecher", "贱叫怪"));
        try (var c = db.getConnection();
             var clear = c.prepareStatement("DELETE FROM rag_runtime_object_name WHERE profile_id=?")) {
            clear.setString(1, profile);
            clear.executeUpdate();
        }
        try (var c = db.getConnection();
             var p = c.prepareStatement("INSERT INTO rag_runtime_object_name(profile_id,category,record_id,registry_id,kind,language,name_norm,display_name,translation_key,origin) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            for (var entry : values.entrySet()) {
                p.setString(1, profile);
                p.setString(2, "registries");
                p.setString(3, entry.getKey());
                p.setString(4, entry.getKey());
                p.setString(5, "entity_type");
                p.setString(6, "zh_cn");
                p.setString(7, RuntimeNameIndex.normalize(entry.getValue()));
                p.setString(8, entry.getValue());
                p.setString(9, "entity." + entry.getKey().replace(':', '.'));
                p.setString(10, "EXPORTED_TRANSLATION_KEY");
                p.addBatch();
            }
            p.executeBatch();
        }
    }

    private JsonNode query(RuntimeSpawnQuery.Filter filter) throws Exception {
        return RuntimeSpawnQuery.find(db, filter);
    }

    private RuntimeSpawnQuery.Filter filter(String mod, String mobCategory, String spawnCategory,
                                            String placementStatus, String placementType, String heightmap,
                                            String biome, String biomeQuery, String structure, String boundingBox,
                                            Boolean hasBiome, Boolean hasStructure, String query,
                                            int offset, int limit, boolean includeNonMob, boolean includeParts,
                                            boolean includeFacets) {
        return new RuntimeSpawnQuery.Filter(mod, mobCategory, spawnCategory, placementStatus, placementType, heightmap,
                biome, biomeQuery, structure, boundingBox, "", hasBiome, hasStructure, query, offset, limit,
                includeNonMob, includeParts, includeFacets);
    }

    private Set<String> ids(JsonNode result) {
        var ids = new LinkedHashSet<String>();
        result.path("objects").forEach(row -> ids.add(row.path("id").asText()));
        return ids;
    }

    private JsonNode object(JsonNode result, String id) {
        for (var row : result.path("objects")) if (id.equals(row.path("id").asText())) return row;
        fail("missing " + id);
        return null;
    }

    @Test void placementAndStructureAreSeparateEvidenceFromHabitat() throws Exception {
        var water = query(filter("minecraft", "", "", "", "IN_WATER", "", "", "", "", "",
                null, null, "", 0, 100, false, false, true));
        assertEquals(3, water.path("total").asInt(), water.toString());
        assertEquals(Set.of("minecraft:guardian", "minecraft:elder_guardian", "minecraft:drowned"), ids(water));
        var guardian = object(water, "minecraft:guardian");
        assertTrue(guardian.path("basis").toString().contains("WATER_PLACEMENT"));
        assertTrue(guardian.path("basis").toString().contains("STRUCTURE_SPAWN"));
        assertFalse(guardian.path("basis").toString().contains("WATER_CATEGORY"));
        assertTrue(guardian.path("waterPlacementOnly").asBoolean());
        assertFalse(guardian.path("waterCategoryMember").asBoolean());

        var structure = query(filter("minecraft", "", "", "", "", "", "", "", "minecraft:monument", "",
                null, null, "", 0, 100, false, false, true));
        assertEquals(Set.of("minecraft:guardian"), ids(structure));
        assertTrue(structure.path("objects").get(0).path("matchedBy").toString().contains("STRUCTURE_SPAWN"));

        var noRules = query(filter("minecraft", "", "", "", "", "", "", "", "", "",
                false, false, "", 0, 100, true, false, true));
        assertEquals(Set.of("minecraft:tadpole", "minecraft:elder_guardian"), ids(noRules));
        assertTrue(object(noRules, "minecraft:tadpole").path("basis").toString().contains("NO_SPAWN_RULE"));
    }

    @Test void mobCategoryAndSpawnCategoryRemainDistinctAndPartsAreExcluded() throws Exception {
        var bySpawnPool = query(filter("alexsmobs", "", "water_creature", "", "", "", "", "", "", "",
                null, null, "", 0, 100, false, false, true));
        assertEquals(Set.of("alexsmobs:orca", "alexsmobs:stradpole"), ids(bySpawnPool));

        var byMob = query(filter("alexsmobs", "water_creature", "", "", "", "", "", "", "", "",
                null, null, "", 0, 100, false, false, true));
        assertEquals(Set.of("alexsmobs:orca", "alexsmobs:sea_bear"), ids(byMob));

        var all = query(filter("alexsmobs", "", "", "", "", "", "", "", "", "",
                null, null, "", 0, 100, false, false, false));
        assertFalse(ids(all).contains("alexsmobs:anaconda_part"));
        var withParts = query(filter("alexsmobs", "", "", "", "", "", "", "", "", "",
                null, null, "", 0, 100, true, true, false));
        assertTrue(ids(withParts).contains("alexsmobs:anaconda_part"));

        var skreecher = query(filter("alexsmobs", "creature", "monster", "", "", "", "", "", "", "",
                null, null, "", 0, 100, false, false, true));
        assertEquals(Set.of("alexsmobs:skreecher"), ids(skreecher));
        assertTrue(skreecher.toString().contains("\"mobCategory\":\"creature\""));
        assertTrue(skreecher.toString().contains("\"spawnCategories\":[\"monster\"]"));
    }

    @Test void lavaPlacementIsNotAquaticEvenWhenCategorySaysWater() throws Exception {
        var all = query(new RuntimeSpawnQuery.Filter("alexsmobs", "water_creature,water_ambient", "", "", "", "",
                "", "", "", "", "", null, null, "", 0, 100, false, false, true));
        assertEquals(Set.of("alexsmobs:orca", "alexsmobs:stradpole", "alexsmobs:sea_bear"), ids(all));
        var stradpole = object(all, "alexsmobs:stradpole");
        assertFalse(stradpole.path("aquaticCandidate").asBoolean());
        assertTrue(stradpole.path("lavaPlacement").asBoolean());
        assertTrue(stradpole.path("excludedReasons").toString().contains("IN_LAVA"));
        assertTrue(object(all, "alexsmobs:sea_bear").path("aquaticCandidate").asBoolean());

        var noLava = query(new RuntimeSpawnQuery.Filter("alexsmobs", "water_creature,water_ambient", "", "", "", "",
                "", "", "", "", "IN_LAVA", null, null, "", 0, 100, false, false, true));
        assertEquals(Set.of("alexsmobs:orca", "alexsmobs:sea_bear"), ids(noLava));
        assertEquals(1, noLava.path("excludedByPlacement").asInt());
        assertEquals(2, noLava.path("aquaticGroupCounts").path("water_creature").asInt());
        assertTrue(object(all, "alexsmobs:sea_bear").path("aquaticGroup").asText().equals("water_creature"));
        assertTrue(object(all, "alexsmobs:stradpole").path("aquaticGroup").asText().isBlank());
    }

    @Test void entityTagAddsNoSpawnCandidates() throws Exception {
        tagRecord("minecraft:entity_type|minecraft:aquatic", """
                {"id":"minecraft:aquatic","registry":"minecraft:entity_type",
                 "members":["minecraft:tadpole","minecraft:cow","alexsmobs:orca","alexsmobs:stradpole"]}
                """);
        try (var embedding = new EmbeddingService(null)) {
            var result = new QaPipeline.QaResult();
            var turn = new QaAgentTurn(db, new VectorStore(db, 384),
                    new KnowledgeRetrievalService(db, embedding), null, result,
                    "Minecraft 原版有哪些水生生物？", null, null, () -> {});
            var output = json.readTree(turn.execute("find_mobs_by_spawn",
                    json.readTree("{\"mod\":\"minecraft\",\"entity_tag\":\"minecraft:aquatic\",\"limit\":20}")));
            assertTrue(ids(output).contains("minecraft:tadpole"), output.toString());
            var tadpole = object(output, "minecraft:tadpole");
            assertTrue(tadpole.path("basis").toString().contains("ENTITY_TAG"), tadpole.toString());
            assertTrue(tadpole.path("aquaticCandidate").asBoolean());
            assertTrue(tadpole.path("tagOnly").asBoolean());
            var alexTag = RuntimeSpawnQuery.tagMembers(db, "alexsmobs", "minecraft:aquatic");
            var stradpole = object(alexTag, "alexsmobs:stradpole");
            assertFalse(stradpole.path("aquaticCandidate").asBoolean());
            assertTrue(stradpole.path("lavaPlacement").asBoolean());
            assertTrue(stradpole.path("basis").toString().contains("EXCLUDED_IN_LAVA"));
        }
    }

    @Test void deterministicMergeKeepsCatalogAndSpawnUnion() throws Exception {
        new WebCatalogStore(db).save(new WebCatalog(
                List.of(new WebCatalog.Node("aquatic", null, "alexsmobs",
                        "https://example.invalid/aquatic", 4, "水生动物", List.of("生物/实体", "水生动物"))),
                List.of(new WebCatalog.Member("aquatic", "https://example.invalid/seal", "海豹", "海豹", "Seal", "entry"),
                        new WebCatalog.Member("aquatic", "https://example.invalid/mudskipper", "弹涂鱼", "弹涂鱼", "Mudskipper", "entry"),
                        new WebCatalog.Member("aquatic", "https://example.invalid/orca", "虎鲸", "虎鲸", "Orca", "entry"))));
        try (var embedding = new EmbeddingService(null)) {
            var turn = new QaAgentTurn(db, new VectorStore(db, 384),
                    new KnowledgeRetrievalService(db, embedding), null, new QaPipeline.QaResult(),
                    "Alex 的生物有哪些水生生物？", null, null, () -> {});
            turn.execute("find_family", json.readTree("{\"mod\":\"alexsmobs\",\"kind\":\"ENTITY\",\"query\":\"水生动物\"}"));
            names();
            turn.execute("find_mobs_by_spawn", json.readTree("{\"mod\":\"alexsmobs\",\"mob_category\":\"water_creature,water_ambient\",\"limit\":20}"));
            String merged = turn.deterministicMerge();
            assertTrue(merged.contains("海豹"), merged);
            assertTrue(merged.contains("弹涂鱼"), merged);
            assertTrue(merged.contains("虎鲸"), merged);
            assertTrue(merged.contains("海熊"), merged);
            assertTrue(merged.contains("CATALOG"), merged);
            assertFalse(merged.contains("跨座蝌蚪"), merged);
        }
    }

    @Test void deterministicMergeAlsoWorksForLandCandidates() throws Exception {
        new WebCatalogStore(db).save(new WebCatalog(
                List.of(new WebCatalog.Node("land", null, "minecraft",
                        "https://example.invalid/land", 4, "陆地动物", List.of("生物/实体", "陆地动物"))),
                List.of(new WebCatalog.Member("land", "https://example.invalid/cow", "牛", "牛", "Cow", "entry"),
                        new WebCatalog.Member("land", "https://example.invalid/drowned", "溺尸", "溺尸", "Drowned", "entry"))));
        try (var embedding = new EmbeddingService(null)) {
            var turn = new QaAgentTurn(db, new VectorStore(db, 384),
                    new KnowledgeRetrievalService(db, embedding), null, new QaPipeline.QaResult(),
                    "Minecraft 原版有哪些陆地生物？", null, null, () -> {});
            turn.execute("find_family", json.readTree("{\"mod\":\"minecraft\",\"kind\":\"ENTITY\",\"query\":\"陆地动物\"}"));
            names();
            var unfiltered = json.readTree(turn.execute("find_mobs_by_spawn",
                    json.readTree("{\"mod\":\"minecraft\",\"limit\":100}")));
            assertTrue(ids(unfiltered).contains("minecraft:drowned"), unfiltered.toString());
            var landQuery = json.readTree(turn.execute("find_mobs_by_spawn",
                    json.readTree("{\"mod\":\"minecraft\",\"exclude_placement_types\":\"IN_WATER,IN_LAVA\",\"limit\":100}")));
            assertFalse(ids(landQuery).contains("minecraft:drowned"), landQuery.toString());
            assertTrue(ids(landQuery).contains("minecraft:cow"), landQuery.toString());
            String merged = turn.deterministicMerge();
            assertTrue(merged.contains("牛"), merged);
            assertTrue(merged.contains("CATALOG"), merged);
            assertTrue(merged.contains("ON_GROUND"), merged);
            assertTrue(merged.contains("地面放置候选"), merged);
            assertFalse(merged.contains("溺尸"), merged);
        }
    }

    @Test void namesPaginationAndFacetsAreAvailableWithoutHardcodedValues() throws Exception {
        var named = query(filter("minecraft", "", "", "", "", "", "", "", "", "",
                null, null, "虎鲸", 0, 1, false, false, true));
        assertEquals(0, named.path("total").asInt());
        var alex = query(filter("alexsmobs", "", "", "", "", "", "", "", "", "",
                null, null, "虎鲸", 0, 1, false, false, true));
        assertEquals(1, alex.path("total").asInt());
        assertEquals("虎鲸", alex.path("objects").get(0).path("name").asText());

        var page = query(filter("minecraft", "", "", "", "", "", "", "", "", "",
                null, null, "", 0, 2, false, false, true));
        assertEquals(2, page.path("returned").asInt());
        assertEquals(2, page.path("nextOffset").asInt());
        assertTrue(page.path("facets").path("placementTypes").has("IN_WATER"));
        assertTrue(page.path("facets").path("placementTypes").has("ON_GROUND"));
        assertTrue(query(filter("alexsmobs", "", "", "", "", "", "", "", "", "",
                null, null, "", 0, 2, false, false, true)).path("facets").path("spawnCategories").has("water_creature"));
        assertFalse(query(filter("minecraft", "", "", "", "", "", "", "", "", "",
                null, null, "", 0, 2, false, false, false)).has("facets"));
    }

    @Test void vanillaQuestionPrefersRuntimeOverSameNamedWebScopes() throws Exception {
        try (var c = db.getConnection()) {
            try (var p = c.prepareStatement("INSERT INTO rag_knowledge_source(id,kind,location,scope_id,scope_kind,display_name,mod_id) VALUES(?,?,?,?,?,?,?)")) {
                for (int i = 0; i < 3; i++) {
                    String id = "web-" + i;
                    p.setString(1, id);
                    p.setString(2, "URL");
                    p.setString(3, "https://example.invalid/minecraft/" + i);
                    p.setString(4, "https://example.invalid/minecraft/" + i);
                    p.setString(5, "WEB");
                    p.setString(6, "Minecraft");
                    p.setString(7, "minecraft");
                    p.addBatch();
                }
                p.setString(1, "source");
                p.setString(2, "DOCUMENT_DIRECTORY");
                p.setString(3, "runtime");
                p.setString(4, "runtime:source");
                p.setString(5, "LOCAL");
                p.setString(6, "游戏快照");
                p.setNull(7, java.sql.Types.VARCHAR);
                p.addBatch();
                p.executeBatch();
            }
            try (var p = c.prepareStatement("INSERT INTO rag_document(id,source_id,title,location,content_hash,media_type,author_text,published_at_text) VALUES(?,?,?,?,?,?,?,?)")) {
                for (int i = 0; i < 3; i++) {
                    p.setString(1, "doc-" + i);
                    p.setString(2, "web-" + i);
                    p.setString(3, "Minecraft " + i);
                    p.setString(4, "https://example.invalid/minecraft/" + i);
                    p.setString(5, "hash-" + i);
                    p.setString(6, "text/html");
                    p.setString(7, "");
                    p.setString(8, "");
                    p.addBatch();
                }
                p.executeBatch();
            }
        }
        try (var embedding = new EmbeddingService(null)) {
            var resolution = new KnowledgeRetrievalService(db, embedding)
                    .resolve("Minecraft 原版有哪些水生生物？请尽量列全。", null, null);
            assertNull(resolution.warning());
            assertNotNull(resolution.scope());
            assertTrue(resolution.scope().id().startsWith("runtime:"));
        }
    }

    @Test void toolIsDiscoverableAndDispatchAddsEvidence() throws Exception {
        assertTrue(AgentToolLoop.specifications().stream().anyMatch(tool -> tool.name().equals("find_mobs_by_spawn")));
        var spec = AgentToolLoop.specifications().stream()
                .filter(tool -> tool.name().equals("find_mobs_by_spawn")).findFirst().orElseThrow();
        assertTrue(spec.description().contains("placement is spawn placement"));
        var schema = spec.parameters().toString();
        assertTrue(schema.contains("has_biome_spawn"));
        assertTrue(schema.contains("placement_type"));
        assertTrue(schema.contains("required = [mod]"));

        try (var embedding = new EmbeddingService(null)) {
            var result = new QaPipeline.QaResult();
            var turn = new QaAgentTurn(db, new VectorStore(db, 384),
                    new KnowledgeRetrievalService(db, embedding), null, result, "test", null, null, () -> {});
            var output = json.readTree(turn.execute("find_mobs_by_spawn",
                    json.readTree("{\"mod\":\"minecraft\",\"placement_type\":\"IN_WATER\",\"limit\":10}")));
            assertEquals(3, output.path("total").asInt());
            assertFalse(result.vectorResults.isEmpty());

            var waterTurn = new QaAgentTurn(db, new VectorStore(db, 384),
                    new KnowledgeRetrievalService(db, embedding), null, new QaPipeline.QaResult(),
                    "Alex 的生物有哪些水生生物？", null, null, () -> {});
            assertTrue(waterTurn.readinessGap().contains("find_family"));
            var water = json.readTree(waterTurn.execute("find_mobs_by_spawn",
                    json.readTree("{\"mod\":\"alexsmobs\",\"mob_category\":\"water_creature,water_ambient\",\"exclude_placement_types\":\"IN_LAVA\",\"limit\":10}")));
            assertFalse(ids(water).contains("alexsmobs:stradpole"));
            assertEquals(1, water.path("excludedByPlacement").asInt());
            assertTrue(ids(water).contains("alexsmobs:sea_bear"));
            assertTrue(waterTurn.readinessGap().contains("find_family"));
        }
    }
}
