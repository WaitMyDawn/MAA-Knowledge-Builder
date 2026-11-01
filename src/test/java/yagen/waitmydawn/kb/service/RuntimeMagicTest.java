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
class RuntimeMagicTest {
 @TempDir Path temp;
 private final ObjectMapper json=new ObjectMapper();
 private JsonNode tree(String raw)throws Exception{return json.readTree(raw);}
    private void category(Path pack,String category,String raw)throws Exception {
        var rows=tree(raw);var payload=json.createObjectNode().put("format","mkb-data").put("schemaVersion",1).put("capabilityVersion",1).put("category",category).put("authoritative",true);payload.set("records",rows);Path file=pack.resolve("data/"+category+".json");json.writeValue(file.toFile(),payload);var descriptor=(ObjectNode)json.readTree(pack.resolve("snapshot.json").toFile());int failed=0;for(var row:rows)if(row.has("error"))failed++;((ObjectNode)descriptor.path("categories")).putObject(category).put("status",failed==0?"COMPLETE":"COMPLETE_WITH_ERRORS").put("total",rows.size()).put("succeeded",rows.size()-failed).put("transparent",0).put("unsupported",0).put("failed",failed).put("file","data/"+category+".json").put("sha256",ArtifactFingerprint.sha256(file));json.writeValue(pack.resolve("snapshot.json").toFile(),descriptor);
    }

 @Test void importsSearchesAndLinksStateMetadataWithoutGuessingRandomOutputs()throws Exception {
  Path pack=RuntimeBuildFixtures.create(temp.resolve("MKB-Export"),List.of(),"neoforge","1.21.1");
  category(pack,"potions","""
   [{"id":"minecraft:strong_strength","effects":[{"effect":"minecraft:strength","name":"力量","amplifier":1,"durationTicks":1800}]}]
   """);
  category(pack,"effects","""
   [{"id":"minecraft:strength","descriptionId":"effect.minecraft.strength","name":"力量","category":"BENEFICIAL","color":123,"instantaneous":false,"attributeModifiers":[{"amplifier":1,"attribute":"minecraft:generic.attack_damage","amount":6,"operation":"ADD_VALUE"}]}]
   """);
  category(pack,"enchantments","""
   [{"id":"minecraft:mending","descriptionId":"enchantment.minecraft.mending","minLevel":1,"maxLevel":1,"costs":[{"level":1,"minCost":25,"maxCost":75}],"supportedItems":["minecraft:diamond_sword"],"incompatible":["minecraft:infinity"],"treasureOnly":true,"coverage":"LEGACY_API_METADATA"}]
   """);
  category(pack,"brewing","""
   [{"id":"example:strong_strength","kind":"POTION_CONVERSION","input":{"item":"minecraft:potion","components":{"minecraft:potion_contents":{"potion":"minecraft:strength"}}},"ingredient":{"item":"minecraft:glowstone_dust"},"output":{"item":"minecraft:potion","components":{"minecraft:potion_contents":{"potion":"minecraft:strong_strength"}}},"outputPotionId":"minecraft:strong_strength"}]
   """);
  Files.createDirectories(temp.resolve("database"));var config=AppConfig.forIsolatedRun(false).forBuildTarget(temp.resolve("database"));var db=new DatabaseBuilder(config);db.initDatabase();
  try(var embedding=new EmbeddingService(null)){
   new KnowledgeBuilder(config,db,embedding).build(new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,pack.toString()))),null);
   String profile=RuntimeKnowledgeStore.active(db);var catalog=new RuntimeCatalog(db);
   assertEquals(1,catalog.page(profile,RuntimeCatalog.Kind.POTIONS,"strong_strength","minecraft",0,50).total());
   assertEquals(1,catalog.page(profile,RuntimeCatalog.Kind.EFFECTS,"力量","",0,50).total());
   assertEquals(1,catalog.page(profile,RuntimeCatalog.Kind.ENCHANTMENTS,"diamond_sword","",0,50).total());
   var potion=catalog.page(profile,RuntimeCatalog.Kind.POTIONS,"","",0,50).entries().getFirst();assertTrue(catalog.related(profile,potion).stream().anyMatch(e->e.category().equals("effects")&&e.key().equals("minecraft:strength")));
   var direct=RuntimeMagic.evidence(db,List.of("minecraft:mending"),"这个附魔有几级");assertEquals(1,direct.size());assertTrue(direct.getFirst().chunkText().contains("minecraft:mending"));assertFalse(direct.getFirst().chunkText().contains("\"mainhan"));
   assertTrue(RuntimeRules.capabilities(db).contains("potions=COMPLETE"));
   try(var c=db.getConnection();var p=c.prepareStatement("SELECT COUNT(*) FROM rag_runtime_record WHERE category='potions' AND LOCATE('1800',record_data)>0" );var rows=p.executeQuery()){assertTrue(rows.next());assertTrue(rows.getInt(1)>0);}
  }
 }
 @Test void rejectsInvalidMetadataAndKeepsOldCapabilitiesMissing()throws Exception {
  assertThrows(java.io.IOException.class,()->RuntimeMagic.validate("enchantments",tree("{\"minLevel\":3,\"maxLevel\":1}")));
  assertThrows(java.io.IOException.class,()->RuntimeMagic.validate("potions",tree("{\"effects\":[{\"effect\":\"BAD\"}]}")));
  assertThrows(java.io.IOException.class,()->RuntimeMagic.validate("effects",tree("{}")));
  assertTrue(RuntimeMagic.describe("potions",tree("{\"id\":\"x:y\",\"effects\":[]}")).contains("自定义效果"));
  assertTrue(RuntimeMagic.references(tree("{\"nbt\":\"{StoredEnchantments:[{id:'minecraft:mending',lvl:1s}]}\"}"),Map.of("enchantments",tree("{\"records\":[{\"id\":\"minecraft:mending\"}]}"))).contains("引用不证明固定产出"));
 }
}
