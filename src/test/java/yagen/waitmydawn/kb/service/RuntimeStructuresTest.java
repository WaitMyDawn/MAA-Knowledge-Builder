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
class RuntimeStructuresTest {
 @TempDir Path temp;
 private final ObjectMapper json=new ObjectMapper();
 private JsonNode tree(String raw)throws Exception{return json.readTree(raw);}
    private void category(Path pack,String category,String raw)throws Exception {
        var rows=tree(raw);var payload=json.createObjectNode().put("format","mkb-data").put("schemaVersion",1).put("capabilityVersion",1).put("category",category).put("authoritative",true);payload.set("records",rows);Path file=pack.resolve("data/"+category+".json");json.writeValue(file.toFile(),payload);var descriptor=(ObjectNode)json.readTree(pack.resolve("snapshot.json").toFile());int failed=0;for(var row:rows)if(row.has("error"))failed++;((ObjectNode)descriptor.path("categories")).putObject(category).put("status",failed==0?"COMPLETE":"COMPLETE_WITH_ERRORS").put("total",rows.size()).put("succeeded",rows.size()-failed).put("transparent",0).put("unsupported",0).put("failed",failed).put("file","data/"+category+".json").put("sha256",ArtifactFingerprint.sha256(file));json.writeValue(pack.resolve("snapshot.json").toFile(),descriptor);
    }


 private ObjectNode multiblock(){var row=json.createObjectNode().put("id","example:forge/fire").put("name","示例锻炉").put("controller","minecraft:furnace").put("status","DECLARED").put("coverage","DECLARED_OPERATIONAL_EXAMPLE").put("recipeType","example:forge").put("recipeVariantField","element").put("dragonType","fire");row.putArray("size").add(3).add(3).add(3);row.putArray("origin").add(1).add(1).add(1);row.putArray("conditions").add("中层至少三个炉砖，输入口用于工作示例供能");row.putArray("steps").add("从底向上搭建");var cells=row.putArray("cells");for(int y=-1;y<=1;y++)for(int z=-1;z<=1;z++)for(int x=-1;x<=1;x++){String id=y!=0&&Math.abs(x)==1&&Math.abs(z)==1?"minecraft:bone_block":y==0&&x==0&&z==0?"minecraft:furnace":y==0&&x==0&&z==-1?"minecraft:dropper":"minecraft:stone";var cell=cells.addObject().put("block",id);cell.putArray("pos").add(x).add(y).add(z);}return row;}
 private JsonNode template()throws Exception{return tree("""
 {"id":"example:room","runtimeLoaded":true,"geometryStatus":"COMPLETE","size":[2,1,2],"blockCount":4,"palette":[{"Name":"minecraft:stone"},{"Name":"minecraft:air"}],"palettes":[],"blocks":[{"pos":[0,0,0],"state":0},{"pos":[1,0,0],"state":1},{"pos":[0,0,1],"state":0},{"pos":[1,0,1],"state":0}]}
 """);}
 @Test void importsDefinitionsLinksRecipesAndCachesLayerCards()throws Exception {
  Path pack=RuntimeBuildFixtures.create(temp.resolve("MKB-Export"),List.of(),"neoforge","1.21.1");category(pack,"multiblocks",json.createArrayNode().add(multiblock()).toString());category(pack,"structure_templates",json.createArrayNode().add(template()).toString());
  category(pack,"structures","[{\"id\":\"example:fort\",\"runtimeLoaded\":true,\"definition\":{\"type\":\"minecraft:jigsaw\",\"start_pool\":\"example:start\"}}]");
  category(pack,"structure_sets","[{\"id\":\"example:set\",\"runtimeLoaded\":true,\"definition\":{\"structures\":[{\"structure\":\"example:fort\",\"weight\":1}]}}]");
  category(pack,"template_pools","[{\"id\":\"example:start\",\"runtimeLoaded\":true,\"definition\":{\"elements\":[{\"element\":{\"location\":\"example:room\",\"processors\":\"example:clean\"},\"weight\":1}],\"fallback\":\"minecraft:empty\"}}]");
  category(pack,"structure_processors","[{\"id\":\"example:clean\",\"runtimeLoaded\":true,\"definition\":{\"processors\":[]}}]");
  category(pack,"recipes","""
  [{"id":"example:ingot","serializer":"example:forge","recipeType":"example:forge","runtimeLoaded":true,"normalizationStatus":"DYNAMIC","serialization":"CODEC_JSON","special":true,"definition":{"type":"example:forge","element":"fire","input":{"item":"minecraft:iron_ingot"},"result":{"id":"example:ingot"}},"outputPreview":{"id":"example:ingot","count":1}}]
  """);
  Files.createDirectories(temp.resolve("database"));var config=AppConfig.forIsolatedRun(false).forBuildTarget(temp.resolve("database"));var db=new DatabaseBuilder(config);db.initDatabase();
  try(var embedding=new EmbeddingService(null)){
   new KnowledgeBuilder(config,db,embedding).build(new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,pack.toString()))),null);String profile=RuntimeKnowledgeStore.active(db);var catalog=new RuntimeCatalog(db);
   assertEquals(4,catalog.page(profile,RuntimeCatalog.Kind.STRUCTURES,"","",0,50).total());assertEquals(1,catalog.page(profile,RuntimeCatalog.Kind.MULTIBLOCKS,"锻炉","",0,50).total());assertEquals(1,catalog.page(profile,RuntimeCatalog.Kind.TEMPLATES,"stone","",0,50).total());
   var pool=catalog.page(profile,RuntimeCatalog.Kind.STRUCTURES,"example:start","",0,50).entries().stream().filter(e->e.category().equals("template_pools")).findFirst().orElseThrow();assertTrue(catalog.related(profile,pool).stream().anyMatch(e->e.category().equals("structure_templates")));assertTrue(catalog.related(profile,pool).stream().anyMatch(e->e.category().equals("structure_processors")));
   var forge=catalog.page(profile,RuntimeCatalog.Kind.MULTIBLOCKS,"","",0,50).entries().getFirst();assertTrue(catalog.related(profile,forge).stream().anyMatch(e->e.category().equals("recipes")));
   var evidence=RuntimeStructures.evidence(db,List.of("example:ingot"),"如何制作铸锭");assertEquals(1,evidence.size());assertTrue(evidence.getFirst().chunkText().contains("minecraft:iron_ingot"));assertTrue(evidence.getFirst().chunkText().contains("不是掉落清单"));
   var service=new LayoutCardService(db,config.getDataDir());assertNull(service.render(profile,"multiblocks",forge.key(),true,null));var file=service.render(profile,"multiblocks",forge.key(),false,null);var stamp=Files.getLastModifiedTime(file);assertEquals(file,service.render(profile,"multiblocks",forge.key(),false,null));assertEquals(stamp,Files.getLastModifiedTime(file));assertNotEquals(file,service.render(profile,"multiblocks",forge.key(),false,0));
   var answer=new QaPipeline.QaResult();answer.vectorResults=evidence;service.attach(answer,"如何搭建锻炉");assertEquals(1,answer.artifacts.pictures().size());assertEquals("RUNTIME_RULE",answer.artifacts.pictures().getFirst().role());var templateAnswer=new QaPipeline.QaResult();templateAnswer.vectorResults=RuntimeStructures.evidence(db,List.of("example:room"),"example:room模板布局");service.attach(templateAnswer,"example:room模板布局");assertEquals(1,templateAnswer.artifacts.pictures().size());assertTrue(templateAnswer.artifacts.pictures().getFirst().caption().contains("资源模板"));
   try(var c=db.getConnection();var p=c.prepareStatement("SELECT chunk_text FROM rag_vectors WHERE chunk_type='runtime_structure_templates'");var r=p.executeQuery()){assertFalse(r.next());assertTrue(new RuntimeStructuredQuery(db,profile).get("structure_templates","example:room").rows().getFirst().data().has("blocks"));}
   Path preview=Path.of("target/e7-preview");Files.createDirectories(preview);Files.copy(file,preview.resolve("fixture-multiblock.png"),StandardCopyOption.REPLACE_EXISTING);
  }finally{db.shutdown();}
 }
 @Test void layoutBoundsMaterialsAndPaletteSelectionAreExplicit()throws Exception {
  var forge=multiblock();RuntimeStructures.validate("multiblocks",forge);assertEquals(Map.of("minecraft:bone_block",8,"minecraft:stone",17,"minecraft:furnace",1,"minecraft:dropper",1),RuntimeStructures.materials("multiblocks",forge));
  var duplicate=forge.deepCopy();((ArrayNode)duplicate.path("cells")).add(duplicate.path("cells").get(0));assertThrows(java.io.IOException.class,()->RuntimeStructures.validate("multiblocks",duplicate));
  var invalid=(ObjectNode)template();((ObjectNode)invalid.path("blocks").get(0)).put("state",99);assertThrows(java.io.IOException.class,()->RuntimeStructures.validate("structure_templates",invalid));
  assertEquals(3,RuntimeStructures.materials("structure_templates",template()).get("minecraft:stone"));assertTrue(RuntimeStructures.describe("multiblocks",forge).contains("不自动等同于必须留空气"));
  assertFalse(RuntimeStructures.recipeMatches(forge,tree("{\"type\":\"example:forge\",\"element\":\"ice\"}"),"x:y"));assertFalse(RuntimeStructures.renderable("structure_templates",tree("{\"geometryStatus\":\"METADATA_ONLY\"}")));
 }
}
