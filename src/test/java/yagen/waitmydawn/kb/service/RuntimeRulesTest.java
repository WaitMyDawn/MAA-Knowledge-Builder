package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;
import yagen.waitmydawn.kb.config.AppConfig;
import yagen.waitmydawn.kb.model.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeRulesTest {
    @TempDir Path temp;
    private final ObjectMapper json=new ObjectMapper();
    private JsonNode tree(String raw)throws Exception{return json.readTree(raw);}
    private void category(Path pack,String category,String raw)throws Exception {
        var rows=tree(raw);var payload=json.createObjectNode().put("format","mkb-data").put("schemaVersion",1).put("capabilityVersion",1).put("category",category).put("authoritative",true);payload.set("records",rows);Path file=pack.resolve("data/"+category+".json");json.writeValue(file.toFile(),payload);var descriptor=(ObjectNode)json.readTree(pack.resolve("snapshot.json").toFile());int failed=0;for(var row:rows)if(row.has("error"))failed++;((ObjectNode)descriptor.path("categories")).putObject(category).put("status",failed==0?"COMPLETE":"COMPLETE_WITH_ERRORS").put("total",rows.size()).put("succeeded",rows.size()-failed).put("transparent",0).put("unsupported",0).put("failed",failed).put("file","data/"+category+".json").put("sha256",ArtifactFingerprint.sha256(file));json.writeValue(pack.resolve("snapshot.json").toFile(),descriptor);
    }
    @Test void importsRulesIndexesNestedSourcesAndReusesRenderedCards()throws Exception {
        Path pack=RuntimeBuildFixtures.create(temp.resolve("MKB-Export"),List.of(),"neoforge","1.21.1");
        category(pack,"trades","""
          [{"id":"mkb_export:trade/wandering/rare","entity":"minecraft:wandering_trader","profession":"minecraft:none","level":2,"draws":1,"candidateCount":2,"coverage":"PARTIAL","entries":[
          {"index":0,"status":"DECLARATIVE","weight":1,"selectionProbability":0.5,"inputs":[{"item":"minecraft:emerald","count":64},{"item":"minecraft:emerald","minCount":48,"maxCount":64}],"output":{"item":"irons_spellbooks:hither_thither_wand","count":1},"maxUses":1},
          {"index":1,"status":"UNSUPPORTED","factory":"example.Dynamic","reason":"Context required","weight":1,"selectionProbability":0.5}]}]
          """);
        category(pack,"recipes","""
          [{"id":"example:dynamic_ink","serializer":"example:custom","recipeType":"example:machine","runtimeLoaded":true,"normalizationStatus":"DYNAMIC","serialization":"CODEC_JSON","special":true,"definition":{"type":"example:custom","fluid":{"id":"example:ink","amount":250},"input":{"item":"minecraft:glass_bottle"}},"outputPreview":{"id":"irons_spellbooks:legendary_ink","count":1}}]
          """);
        category(pack,"brewing","""
          [{"id":"mkb_export:brewing/strength","kind":"POTION_CONVERSION","input":{"item":"minecraft:potion","nbt":"{Potion:'minecraft:strength'}"},"ingredient":{"item":"minecraft:glowstone_dust"},"output":{"item":"minecraft:potion","nbt":"{Potion:'minecraft:strong_strength'}"},"outputPotionId":"minecraft:strong_strength","ticks":400},
          {"id":"mkb_export:brewing/splash","kind":"CONTAINER_CONVERSION","input":{"item":"minecraft:potion"},"ingredient":{"item":"minecraft:gunpowder"},"output":{"item":"minecraft:splash_potion"},"preservePotion":true}]
          """);
        category(pack,"workstation_rules","""
          [{"id":"minecraft:anvil","station":"minecraft:anvil","coverage":"VANILLA_BASE_RULES","operations":["同类物品合并耐久与附魔","费用依赖先前工作惩罚与玩家模式"],"limitation":"Dynamic hooks"}]
          """);
        category(pack,"loot_tables","""
          [{"id":"example:leaf","runtimeLoaded":true,"definition":{"pools":[{"rolls":1,"entries":[{"type":"minecraft:item","name":"irons_spellbooks:betrayer_signet"}]}]}},
          {"id":"example:parent","runtimeLoaded":true,"definition":{"pools":[{"rolls":1,"entries":[{"type":"minecraft:loot_table","value":"example:leaf"},{"type":"minecraft:loot_table","value":"example:parent"}]}]}}]
          """);
        category(pack,"loot_entrypoints","""
          [{"id":"mkb_export:entity/example/boss","source":"example:boss","sourceKind":"entity","lootTable":"example:parent","relation":"DEFAULT_DECLARATION"}]
          """);
        Files.createDirectories(temp.resolve("database"));var config=AppConfig.forIsolatedRun(false).forBuildTarget(temp.resolve("database"));var db=new DatabaseBuilder(config);db.initDatabase();
        try(var embedding=new EmbeddingService(null)){
            new KnowledgeBuilder(config,db,embedding).build(new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,pack.toString()))),null);
            String profile=RuntimeKnowledgeStore.active(db);try(var connection=db.getConnection();var statement=connection.createStatement();var counts=statement.executeQuery("SELECT (SELECT COUNT(*) FROM rag_vectors WHERE chunk_type='runtime_acquisition_index'),(SELECT COUNT(*) FROM rag_runtime_record WHERE category='acquisition_index')")){assertTrue(counts.next());assertEquals(0,counts.getInt(1));assertTrue(counts.getInt(2)>0,"获取关系保留在结构化索引");}try(var connection=db.getConnection();var statement=connection.createStatement();var records=statement.executeQuery("SELECT COUNT(*) FROM rag_vectors WHERE chunk_type='runtime_trades' AND mod_id='irons_spellbooks'")){records.next();assertEquals(0,records.getInt(1));assertFalse(AcquisitionIndex.evidence(db,List.of("irons_spellbooks:hither_thither_wand"),"获取来源").isEmpty());}var catalog=new RuntimeCatalog(db);
            assertEquals(1,catalog.page(profile,RuntimeCatalog.Kind.TRADES,"wandering","",0,50).total());assertEquals(2,catalog.page(profile,RuntimeCatalog.Kind.BREWING,"","",0,50).total());assertEquals(1,catalog.page(profile,RuntimeCatalog.Kind.WORKSTATIONS,"","",0,50).total());
            String relations=AcquisitionIndex.relations(db,profile,"irons_spellbooks:betrayer_signet");assertTrue(relations.contains("example:parent"));assertTrue(relations.contains("example:boss"));assertFalse(relations.contains("truncated\" : true"));
            assertTrue(AcquisitionIndex.relations(db,profile,"irons_spellbooks:hither_thither_wand").contains("trades"));
            assertTrue(AcquisitionIndex.relations(db,profile,"irons_spellbooks:legendary_ink").contains("unverified"));
            assertTrue(AcquisitionIndex.evidence(db,List.of("irons_spellbooks:legendary_ink"),"如何获取传说墨水").stream().anyMatch(hit->hit.type().equals("runtime_recipe")&&hit.chunkText().contains("250")));
            assertFalse(AcquisitionIndex.evidence(db,List.of("irons_spellbooks:hither_thither_wand"),"如何获取此呼彼应法杖").isEmpty());
            var source=catalog.page(profile,RuntimeCatalog.Kind.LOOT_SOURCES,"","",0,50).entries().getFirst();assertEquals("example:parent",catalog.related(profile,source).getFirst().key());
            var cards=new RuleCardService(db,config.getDataDir());assertNull(cards.render(profile,"trades","mkb_export:trade/wandering/rare",true));var file=cards.render(profile,"trades","mkb_export:trade/wandering/rare",false);var modified=Files.getLastModifiedTime(file);assertEquals(file,cards.render(profile,"trades","mkb_export:trade/wandering/rare",false));assertEquals(modified,Files.getLastModifiedTime(file));
            Path previews=Path.of("target/e24-preview");Files.createDirectories(previews);Files.copy(file,previews.resolve("trade.png"),StandardCopyOption.REPLACE_EXISTING);
            for(String category:List.of("brewing","workstation_rules")){String id=category.equals("brewing")?"mkb_export:brewing/strength":"minecraft:anvil";var png=cards.render(profile,category,id,false);assertNotNull(javax.imageio.ImageIO.read(png.toFile()));Files.copy(png,previews.resolve(category+".png"),StandardCopyOption.REPLACE_EXISTING);}
            var qa=new QaPipeline.QaResult();qa.vectorResults=AcquisitionIndex.evidence(db,List.of("irons_spellbooks:hither_thither_wand"),"交易获取");cards.attach(qa);assertEquals(1,qa.artifacts.pictures().size());assertTrue(qa.mediaWarnings.isEmpty());var media=new RuntimeEntityMedia(db,config.getDataDir());String imageId=qa.artifacts.pictures().getFirst().id();assertTrue(media.evidence(qa,"").contains("[[image:"+imageId+"]]"));qa.answer="此呼彼应法杖可以通过流浪商人交易获得。";media.finish(qa,"如何获取此呼彼应法杖");assertTrue(qa.answer.contains("kb-image:"+imageId));assertEquals(0,ImageGroundingAssessment.assess("交易获取",qa.answer,qa.artifacts).checks().size());var replay=json.readValue(json.writeValueAsString(qa.artifacts),AnswerArtifacts.class);assertEquals("RUNTIME_RULE",replay.pictures().getFirst().role());assertNotNull(AnswerMedia.resolveImage(config.getDataDir(),replay.pictures().getFirst()));
        }finally{db.shutdown();}
    }
    @Test void brewingPrerequisitesKeepStateAndTerminateOnCycles()throws Exception {
        var payload=tree("""
          {"records":[
          {"id":"example:water_to_awkward","input":{"item":"minecraft:potion","components":{"potion":"water"}},"ingredient":{"item":"minecraft:nether_wart"},"output":{"item":"minecraft:potion","components":{"potion":"awkward"}}},
          {"id":"example:awkward_to_strength","input":{"components":{"potion":"awkward"},"item":"minecraft:potion"},"ingredient":{"item":"minecraft:blaze_powder"},"output":{"item":"minecraft:potion","components":{"potion":"strength"}}},
          {"id":"example:strength_to_strong","input":{"item":"minecraft:potion","components":{"potion":"strength"}},"ingredient":{"item":"minecraft:glowstone_dust"},"output":{"item":"minecraft:potion","components":{"potion":"strong_strength"}}},
          {"id":"example:cycle","input":{"item":"minecraft:potion","components":{"potion":"water"}},"ingredient":{"item":"minecraft:stone"},"output":{"item":"minecraft:potion","components":{"potion":"water"}}}]}
          """);
        String chain=new BrewingChains(payload).prerequisites(payload.path("records").get(2));assertTrue(chain.contains("water_to_awkward"));assertTrue(chain.contains("awkward_to_strength"));assertFalse(chain.contains("达到64步"));
    }
    @Test void statefulCardsUseCanonicalIconStateInsteadOfWholeStack()throws Exception {
        var stack=(ObjectNode)tree("{\"id\":\"minecraft:potion\",\"count\":3,\"components\":{\"minecraft:potion_contents\":{\"potion\":\"minecraft:strong_strength\"}}}");var state=RuleCardService.exactState(stack);assertEquals(1,state.size());assertTrue(NativeIconPack.stateVariant(state).startsWith("state:"));stack.put("dynamicState",true);assertNull(RuleCardService.exactState(stack));
    }
    @Test void rejectsInvalidTradePoolsRatherThanInventingProbabilities()throws Exception {
        assertThrows(java.io.IOException.class,()->RuntimeRules.validate("trades",tree("{\"entity\":\"minecraft:villager\",\"profession\":\"minecraft:librarian\",\"draws\":2,\"entries\":[]}")));
    }
}
