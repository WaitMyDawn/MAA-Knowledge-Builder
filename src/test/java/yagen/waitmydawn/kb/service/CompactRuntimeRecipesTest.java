package yagen.waitmydawn.kb.service;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import yagen.waitmydawn.kb.config.*;
import yagen.waitmydawn.kb.model.*;
import static org.junit.jupiter.api.Assertions.*;
class CompactRuntimeRecipesTest {
 @org.junit.jupiter.api.io.TempDir Path temp;final com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();
 @org.junit.jupiter.api.AfterEach void shutdownLog(){LogSetup.shutdown();}
 Path fixture(String name,int count)throws Exception{var helper=new RuntimeKnowledgeStoreTest();helper.temp=temp;return helper.fixture(name,count);}
 BuildRequest request(Path root,BuildRequest.Mode mode){return new BuildRequest(java.util.List.of(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,root.toString())),mode);}

 @Test void definitionOnlyRecipesBuildAndRetainParsedOutput()throws Exception {
  Path root=fixture("compact-recipes",2),file=root.resolve("data/recipes.json");var payload=(ObjectNode)json.readTree(file.toFile());for(var row:payload.path("records"))if(row.path("normalizationStatus").asText().equals("STATIC")){var node=(ObjectNode)row;node.remove("renderJson");node.remove("networkBase64");node.remove("outputPreview");}json.writerWithDefaultPrettyPrinter().writeValue(file.toFile(),payload);var descriptor=(ObjectNode)json.readTree(root.resolve("snapshot.json").toFile());((ObjectNode)descriptor.path("categories").path("recipes")).put("sha256",ArtifactFingerprint.sha256(file));json.writeValue(root.resolve("snapshot.json").toFile(),descriptor);
  var config=AppConfig.forIsolatedRun(false);config.setDataDir(temp.resolve("compact-db"));var db=new DatabaseBuilder(config);db.initDatabase();try(var embedding=new EmbeddingService(null)){new KnowledgeBuilder(config,db,embedding).build(request(root,BuildRequest.Mode.REBUILD),null);try(var c=db.getConnection();var s=c.createStatement();var rows=s.executeQuery("SELECT recipe_data FROM rag_runtime_recipe WHERE recipe_id='mkb_fixture:changed'")){assertTrue(rows.next());var plan=RecipePlan.parse(rows.getString(1));assertEquals("mkb_fixture:sample_a",plan.output().itemId());assertEquals(2,plan.output().count());}}finally{db.shutdown();}
 }
 @Test void conflictingLegacyRenderCopyIsStillRejected()throws Exception {var row=json.createObjectNode().put("id","demo:recipe").put("serializer","minecraft:crafting_shaped").put("recipeType","minecraft:crafting").put("runtimeLoaded",true).put("serialization","RUNTIME_ADAPTER").put("normalizationStatus","STATIC");row.putObject("definition").put("type","minecraft:crafting_shaped");row.putObject("renderJson").put("type","minecraft:smelting");var payload=json.createObjectNode().put("format","mkb-data").put("schemaVersion",1).put("category","recipes").put("authoritative",true);payload.putArray("records").add(row);var declaration=json.createObjectNode().put("total",1).put("succeeded",1).put("failed",0);assertThrows(java.io.IOException.class,()->RuntimeSnapshotData.validate("recipes",payload,declaration));}
}
