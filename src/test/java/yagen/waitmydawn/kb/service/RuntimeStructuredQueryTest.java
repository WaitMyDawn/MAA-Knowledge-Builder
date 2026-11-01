package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.config.*;
import yagen.waitmydawn.kb.model.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeStructuredQueryTest {
    @TempDir Path temp;
    private final ObjectMapper json=new ObjectMapper();
    private DatabaseBuilder db;
    private final String profile="a".repeat(64);
    private ObjectNode descriptor;
    @BeforeEach void setup()throws Exception {
        var config=AppConfig.forIsolatedRun(false);config.setDataDir(temp);db=new DatabaseBuilder(config);db.initDatabase();
        descriptor=json.createObjectNode();var categories=descriptor.putObject("categories");
        for(String category:List.of("item_attributes","entity_attributes","entity_state_attributes","loot_tables","loot_modifiers","loot_entrypoints","recipes","trades","brewing","structures","structure_sets","template_pools","structure_processors","structure_templates","multiblocks"))categories.putObject(category).put("status","COMPLETE");
        snapshot(profile,"source");
    }
    @AfterEach void teardown(){RetrievalTelemetry.end();db.shutdown();LogSetup.shutdown();}
    private void snapshot(String id,String source)throws Exception {
        try(var c=db.getConnection();var p=c.prepareStatement("MERGE INTO rag_runtime_snapshot KEY(source_id) VALUES(?,?,?)")){p.setString(1,source);p.setString(2,id);p.setString(3,descriptor.toString());p.executeUpdate();}
    }
    private void row(String category,String id,JsonNode data)throws Exception {row(profile,category,id,data);}
    private void row(String profile,String category,String id,JsonNode data)throws Exception {
        try(var c=db.getConnection();var p=c.prepareStatement("MERGE INTO rag_runtime_record KEY(profile_id,category,record_id) VALUES(?,?,?,?)")){p.setString(1,profile);p.setString(2,category);p.setString(3,id);p.setString(4,data.toString());p.executeUpdate();}
    }
    private JsonNode tree(String raw)throws Exception{return json.readTree(raw);}
    private ObjectNode attribute(int health){var row=json.createObjectNode().put("id","example:mob").put("schemaVersion",1).put("context","SERVER_DEFAULT_INSTANCE");row.putArray("attributes").addObject().put("attribute","minecraft:generic.max_health").put("base",health).put("effective",health);return row;}
    private String state(boolean baby,boolean equipped)throws Exception {
        var row=attribute(baby?10:30);row.put("baby",baby);var traits=row.putObject("state").put("origin","SPAWN_INITIALIZED").putObject("traits").put("age",baby?"baby":"adult");var equipment=traits.putObject("equipment");if(equipped)equipment.putObject("head").put("item","minecraft:diamond_helmet");row.put("stateKey",BuildRequest.fingerprint(row.path("state").toString()));String key=EntityAppearanceState.key(row);row("entity_state_attributes","example:mob|"+key,row);return key;
    }
    @Test void attributesAndStateSelectionWorkWithoutVectorTable()throws Exception {
        row("entity_attributes","example:mob",attribute(20));String adult=state(false,true);state(true,false);
        var query=new RuntimeStructuredQuery(db,profile);
        var selected=query.page("entity_state_attributes","example:mob",new RuntimeStructuredQuery.State(adult,false,true,"SPAWN_INITIALIZED"),0,10);
        assertEquals(1,selected.total());assertEquals(30,selected.rows().getFirst().data().path("attributes").get(0).path("base").asInt());
        var hits=RuntimeAttributes.evidence(db,List.of("example:mob"),"生命值是多少");assertEquals(1,hits.size());assertTrue(hits.getFirst().chunkText().contains("基础值 20"));assertTrue(hits.getFirst().sourcePath().contains(profile+"/entity_attributes/example:mob"));
        var stateHits=RuntimeAttributes.queryEvidence(db,List.of("example:mob"),"成年装备后的生命值");assertEquals(1,stateHits.hits().size());assertTrue(stateHits.coveredIds().contains("example:mob"));assertTrue(stateHits.hits().getFirst().chunkText().contains("基础值 30"));
        assertEquals(2,RuntimeAttributes.evidence(db,List.of("example:mob"),"成年和幼年的生命值").size());
        assertTrue(RuntimeAttributes.queryEvidence(db,List.of("example:mob"),"幼年装备后的生命值").coveredIds().isEmpty());
        try(var c=db.getConnection();var p=c.prepareStatement("SELECT COUNT(*) FROM information_schema.tables WHERE table_name='rag_vectors'");var r=p.executeQuery()){r.next();assertEquals(0,r.getInt(1));}
    }
    @Test void indexedStateAxesComposeWithAgeAndEquipmentInsteadOfOverridingThem()throws Exception {
        String adult=state(false,true);state(true,false);
        var query=new RuntimeStructuredQuery(db,profile);var stored=query.page("entity_state_attributes","example:mob",new RuntimeStructuredQuery.State(adult,null,null,null),0,1).rows().getFirst();
        var data=(ObjectNode)stored.data().deepCopy();data.with("state").with("traits").putObject("axes").put("sex","female").put("growth_stage","5");
        String canonical=new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(json.convertValue(data.path("state"),Map.class));
        data.put("stateKey",BuildRequest.fingerprint(canonical));String key="example:mob|"+EntityAppearanceState.key(data);row("entity_state_attributes",key,data);
        try(var c=db.getConnection();var p=c.prepareStatement("DELETE FROM rag_runtime_query_version WHERE profile_id=?")){p.setString(1,profile);p.executeUpdate();}
        var exact=query.states("entity_state_attributes","example:mob",new RuntimeStructuredQuery.State(null,false,true,null),Map.of("state.sex","female","state.growth_stage","5"),0,10);
        assertEquals(1,exact.total());assertEquals(key,exact.rows().getFirst().key().id());
        assertEquals(0,query.states("entity_state_attributes","example:mob",new RuntimeStructuredQuery.State(null,true,true,null),Map.of("state.sex","female"),0,10).total());
    }
    @Test void lootEntrancesNestedCyclesAndItemReverseLookupAreStructured()throws Exception {
        var parent=tree("""
{"id":"example:parent","definition":{"pools":[{"rolls":2,"conditions":[{"condition":"minecraft:killed_by_player"}],"entries":[{"type":"minecraft:loot_table","name":"example:child"}]}]}}
""");
        var child=tree("""
{"id":"example:child","definition":{"pools":[{"entries":[{"type":"minecraft:item","name":"minecraft:diamond","functions":[{"function":"minecraft:set_count","count":3}]},{"type":"minecraft:loot_table","value":"example:parent"}]}]}}
""");
        row("loot_tables","example:parent",parent);row("loot_tables","example:child",child);
        row("loot_entrypoints","example:entry",tree("""
{"id":"example:entry","source":"example:mob","lootTable":"example:parent","relation":"DEFAULT_DECLARATION"}
"""));
        row("acquisition_index","minecraft:diamond",tree("""
{"id":"minecraft:diamond","sources":[{"category":"loot_tables","recordId":"example:child"},{"category":"loot_tables","recordId":"example:parent"}]}
"""));
        var query=new RuntimeStructuredQuery(db,profile);var graph=query.expand(new RuntimeStructuredQuery.Key("registries","example:mob"),4,10);
        assertEquals(2,graph.rows().size());assertFalse(graph.truncated());
        assertEquals(2,query.expand(new RuntimeStructuredQuery.Key("registries","example:mob"),4,10,Set.of("DECLARED_LOOT","NESTED_LOOT")).rows().size());
        assertTrue(query.expand(new RuntimeStructuredQuery.Key("registries","example:mob"),4,10,Set.of("CONTROLLER")).rows().isEmpty());assertEquals(1,query.related(new RuntimeStructuredQuery.Key("loot_tables","example:parent"),true,"LOOT_TABLE",0,10).total());
        assertTrue(query.expand(new RuntimeStructuredQuery.Key("loot_tables","example:parent"),0,10).truncated());
        var hits=AcquisitionIndex.evidence(db,List.of("minecraft:diamond"),"钻石的掉落来源");assertTrue(hits.stream().anyMatch(hit->hit.chunkText().contains("killed_by_player")));assertTrue(hits.stream().anyMatch(hit->hit.chunkText().contains("set_count")));assertTrue(hits.stream().allMatch(hit->hit.sourcePath().startsWith("mkb-runtime:"+profile)));
        assertTrue(AcquisitionIndex.evidence(db,List.of("example:mob"),"这个生物掉落什么").stream().anyMatch(hit->hit.sourcePath().endsWith("loot_tables/example:child")));
    }
    @Test void coverageFailurePaginationAndProfileIsolationRemainExplicit()throws Exception {
        row("entity_attributes","example:mob",attribute(20));row("entity_attributes","example:other",attribute(22));
        String other="b".repeat(64);snapshot(other,"other-source");row(other,"entity_attributes","example:mob",attribute(99));
        var query=new RuntimeStructuredQuery(db,profile);var first=query.page("entity_attributes",null,RuntimeStructuredQuery.State.any(),0,1);
        assertEquals(2,first.total());assertEquals(1,first.nextOffset());assertFalse(first.warnings().isEmpty());assertEquals(1,query.page("entity_attributes",null,RuntimeStructuredQuery.State.any(),1,1).rows().size());
        assertEquals(20,query.get("entity_attributes","example:mob").rows().getFirst().data().path("attributes").get(0).path("base").asInt());
        assertEquals(RuntimeStructuredQuery.Status.NOT_FOUND,query.get("entity_attributes","example:missing").status());
        ((ObjectNode)descriptor.path("categories").path("entity_attributes")).put("status","COMPLETE_WITH_ERRORS");snapshot(profile,"source");
        row("entity_attributes","example:error",tree("""
{"id":"example:error","error":"capture failed"}
"""));
        assertEquals(RuntimeStructuredQuery.Status.PARTIAL,new RuntimeStructuredQuery(db,profile).get("entity_attributes","example:missing").status());
        assertEquals(RuntimeStructuredQuery.Status.FAILED,new RuntimeStructuredQuery(db,profile).get("entity_attributes","example:error").status());
        ((ObjectNode)descriptor.path("categories")).remove("entity_state_attributes");snapshot(profile,"source");
        assertEquals(RuntimeStructuredQuery.Status.CAPABILITY_MISSING,new RuntimeStructuredQuery(db,profile).page("entity_state_attributes","example:mob",RuntimeStructuredQuery.State.any(),0,10).status());
        assertThrows(IllegalArgumentException.class,()->query.page("entity_attributes",null,RuntimeStructuredQuery.State.any(),0,101));
        assertThrows(IllegalArgumentException.class,()->query.get("rag_vectors","example:mob"));
    }
    @Test void poolPagesKeepGlobalAndLocalConditionsAndFunctions()throws Exception {
        row("loot_tables","example:chest",tree("""
{"id":"example:chest","definition":{"functions":[{"function":"example:global"}],"pools":[{"rolls":1},{"rolls":4,"conditions":[{"condition":"example:condition"}]}]}}
"""));
        var query=new RuntimeStructuredQuery(db,profile);var first=query.lootPools("example:chest",0,1);assertEquals(2,first.total());assertEquals(1,first.nextOffset());assertTrue(first.pools().getFirst().tableFunctions().toString().contains("global"));
        var second=query.lootPools("example:chest",1,1);assertEquals(2,second.pools().getFirst().ordinal());assertTrue(second.pools().getFirst().rule().toString().contains("example:condition"));assertEquals(-1,second.nextOffset());
    }
    @Test void indexIsReusedAndFailedRebuildDoesNotPublishPartialRows()throws Exception {
        String key=state(false,false);var query=new RuntimeStructuredQuery(db,profile);
        var telemetry=RetrievalTelemetry.begin();query.page("entity_state_attributes","example:mob",RuntimeStructuredQuery.State.any(),0,10);query.page("entity_state_attributes","example:mob",RuntimeStructuredQuery.State.any(),0,10);
        assertEquals(1,RetrievalTelemetry.work().get("structured.index.build").count());
        try(var c=db.getConnection();var p=c.prepareStatement("EXPLAIN SELECT record_id FROM rag_runtime_lookup WHERE profile_id=? AND category=? AND subject_id=?")){p.setString(1,profile);p.setString(2,"entity_state_attributes");p.setString(3,"example:mob");try(var r=p.executeQuery()){r.next();assertTrue(r.getString(1).toLowerCase(Locale.ROOT).matches("(?s).*(idx_runtime_lookup_subject|primary_key).*"));}}
        try(var c=db.getConnection();var s=c.createStatement()){s.execute("DELETE FROM rag_runtime_query_version");}
        var broken=attribute(20);broken.put("stateKey","broken");row("entity_state_attributes","example:mob|broken",broken);
        assertThrows(java.io.IOException.class,()->query.page("entity_state_attributes","example:mob",RuntimeStructuredQuery.State.any(),0,10));
        try(var c=db.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM rag_runtime_lookup")){r.next();assertEquals(1,r.getInt(1));}
        assertNotNull(key);
    }
    @Test void structureGraphUsesExplicitLinksAndRetainsMetadataOnlyBoundary()throws Exception {
        row("structures","example:fort",tree("""
{"id":"example:fort","definition":{"start_pool":"example:start"}}
"""));
        row("template_pools","example:start",tree("""
{"id":"example:start","definition":{"elements":[{"element":{"location":"example:room"}}]}}
"""));
        row("structure_templates","example:room",tree("""
{"id":"example:room","geometryStatus":"METADATA_ONLY","geometryReason":"budget","size":[10,10,10]}
"""));
        var query=new RuntimeStructuredQuery(db,profile);var graph=query.expand(new RuntimeStructuredQuery.Key("structures","example:fort"),4,10);assertEquals(3,graph.rows().size());assertTrue(graph.warnings().stream().anyMatch(warning->warning.contains("覆盖缺口")));
        var evidence=RuntimeStructures.evidence(db,List.of("example:fort"),"结构模板布局");assertEquals(3,evidence.size());assertTrue(evidence.stream().anyMatch(hit->hit.chunkText().contains("几何未导出")));
        assertEquals(0,query.related(new RuntimeStructuredQuery.Key("structures","example:room"),false,0,10).total());
    }
}
