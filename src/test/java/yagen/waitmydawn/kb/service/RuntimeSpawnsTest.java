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
class RuntimeSpawnsTest {
 @TempDir Path temp;
 private final ObjectMapper json=new ObjectMapper();
 private JsonNode tree(String raw)throws Exception{return json.readTree(raw);}
 private final String rows="""
 [{"id":"minecraft:zombie","mobCategory":"monster","coverage":"ENUMERABLE_CONFIGURATION_ONLY","placement":{"status":"REGISTERED","type":"ON_GROUND","heightmap":"MOTION_BLOCKING_NO_LEAVES","conditionCoverage":"OPAQUE_CODE","predicate":"example.Predicate"},
 "biomeSpawns":[{"biome":"minecraft:plains","category":"monster","entryIndex":0,"weight":95,"poolWeight":515,"minCount":4,"maxCount":4,"creatureGenerationProbability":0.1,"referencedByLoadedDimensions":["minecraft:overworld"],"spawnCost":{"energyBudget":0.7,"charge":0.15}}],
 "structureSpawns":[{"structure":"minecraft:swamp_hut","category":"monster","entryIndex":0,"weight":1,"poolWeight":1,"minCount":1,"maxCount":1,"boundingBox":"PIECE","relation":"STRUCTURE_POOL_OVERRIDE","allowedBiomes":["minecraft:swamp"]}]},
 {"id":"example:ritual","mobCategory":"monster","coverage":"ENUMERABLE_CONFIGURATION_ONLY","placement":{"status":"NOT_REGISTERED"},"biomeSpawns":[],"structureSpawns":[]},
 {"id":"mkb_export:spawn/biome/example/broken","error":"Custom settings unavailable"}]
 """;
    private void category(Path pack,String category,String raw)throws Exception {
        var rows=tree(raw);var payload=json.createObjectNode().put("format","mkb-data").put("schemaVersion",1).put("capabilityVersion",1).put("category",category).put("authoritative",true);payload.set("records",rows);Path file=pack.resolve("data/"+category+".json");json.writeValue(file.toFile(),payload);var descriptor=(ObjectNode)json.readTree(pack.resolve("snapshot.json").toFile());int failed=0;for(var row:rows)if(row.has("error"))failed++;((ObjectNode)descriptor.path("categories")).putObject(category).put("status",failed==0?"COMPLETE":"COMPLETE_WITH_ERRORS").put("total",rows.size()).put("succeeded",rows.size()-failed).put("transparent",0).put("unsupported",0).put("failed",failed).put("file","data/"+category+".json").put("sha256",ArtifactFingerprint.sha256(file));json.writeValue(pack.resolve("snapshot.json").toFile(),descriptor);
    }


 @Test void importsSpawnRulesAndKeepsUnenumeratedSourcesUnknown()throws Exception {
  Path pack=RuntimeBuildFixtures.create(temp.resolve("MKB-Export"),List.of(),"neoforge","1.21.1");category(pack,"spawn_rules",rows);
  var languages=json.readTree(pack.resolve("data/language.json").toFile()).path("records");for(var row:languages)if(row.path("id").asText().equals("zh_cn")){((ObjectNode)row.path("translations")).put("entity.minecraft.zombie","僵尸").put("biome.minecraft.plains","平原");}category(pack,"language",languages.toString());
  Files.createDirectories(temp.resolve("database"));var config=AppConfig.forIsolatedRun(false).forBuildTarget(temp.resolve("database"));var db=new DatabaseBuilder(config);db.initDatabase();
  try(var embedding=new EmbeddingService(null)){
   new KnowledgeBuilder(config,db,embedding).build(new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,pack.toString()))),null);
   String profile=RuntimeKnowledgeStore.active(db);var catalog=new RuntimeCatalog(db);
   assertEquals(3,catalog.page(profile,RuntimeCatalog.Kind.SPAWNS,"","",0,50).total());assertEquals(1,catalog.page(profile,RuntimeCatalog.Kind.SPAWNS,"僵尸","minecraft",0,50).total());assertEquals(1,catalog.page(profile,RuntimeCatalog.Kind.SPAWNS,"平原","",0,50).total());
   var evidence=RuntimeSpawns.evidence(db,List.of("minecraft:zombie"),"僵尸在哪些群系生成");assertEquals(1,evidence.size());assertTrue(evidence.getFirst().chunkText().contains("95"));assertTrue(evidence.getFirst().chunkText().contains("权重不是玩家实际遭遇概率"));assertTrue(evidence.getFirst().chunkText().contains("[生成配置部分覆盖]"));assertFalse(RuntimeSpawns.covered(List.of("minecraft:zombie"),evidence));assertFalse(RuntimeSpawns.covered(List.of("minecraft:zombie","example:ritual"),evidence));
   assertFalse(RuntimeSpawns.covered(List.of("example:ritual"),RuntimeSpawns.evidence(db,List.of("example:ritual"),"在哪生成")));
   assertTrue(RuntimeRules.capabilities(db).contains("spawn_rules=COMPLETE_WITH_ERRORS"));
   try(var c=db.getConnection();var p=c.prepareStatement("SELECT COUNT(*) FROM rag_vectors WHERE source_path LIKE '%/spawn_rules/example:ritual'" );var result=p.executeQuery()){assertTrue(result.next());assertEquals(0,result.getInt(1));}
   var fake=new RuntimeCatalog.Entry("entity_type|minecraft:zombie","僵尸","registries","{\"id\":\"minecraft:zombie\"}");assertTrue(catalog.related(profile,fake).stream().anyMatch(e->e.category().equals("spawn_rules")));assertTrue(catalog.defaultEntityImage(profile,"minecraft:zombie").isEmpty());
  }finally{db.shutdown();}
 }
 @Test void longEvidenceCannotPretendToBeComplete(){String text=RuntimeSpawns.preview("x".repeat(25000));assertTrue(text.contains("[生成规则证据预览已截断]"));assertTrue(text.length()<21000);}
 @Test void rejectsBrokenPoolNumbersAndPreservesOpaqueConditions()throws Exception {
  var row=((ObjectNode)tree(rows).get(0)).deepCopy();RuntimeSpawns.validate(row);((ObjectNode)row.path("biomeSpawns").get(0)).put("poolWeight",2);assertThrows(java.io.IOException.class,()->RuntimeSpawns.validate(row));
  var missing=((ObjectNode)tree(rows).get(0)).deepCopy();((ObjectNode)missing.path("placement")).remove("conditionCoverage");assertThrows(java.io.IOException.class,()->RuntimeSpawns.validate(missing));
  assertTrue(RuntimeSpawns.describe(tree(rows).get(1)).contains("未列出不表示不存在"));assertTrue(RuntimeSpawns.configurationOnly("僵尸在哪些群系自然生成"));assertFalse(RuntimeSpawns.configurationOnly("怎么召唤僵尸"));assertFalse(RuntimeSpawns.configurationOnly("僵尸的生成光照条件"));assertFalse(RuntimeSpawns.configurationOnly("怎么生成僵尸并获取掉落物"));
 }
}
