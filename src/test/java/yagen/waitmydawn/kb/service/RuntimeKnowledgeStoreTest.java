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

class RuntimeKnowledgeStoreTest {
    @TempDir Path temp;
    final ObjectMapper json=new ObjectMapper();
    @AfterEach void logging(){LogSetup.shutdown();}
    Path fixture(String name,int count)throws Exception {
        Path root=Files.createDirectory(temp.resolve(name));
        try(var files=Files.walk(Path.of("examples/MKB-Export"))) {for(Path file:files.toList()){Path to=root.resolve(Path.of("examples/MKB-Export").relativize(file));if(Files.isDirectory(file))Files.createDirectories(to);else Files.copy(file,to);}}
        var manifest=(ObjectNode)json.readTree(root.resolve("manifest.json").toFile());manifest.put("origin","GAME_GUI");((ArrayNode)manifest.path("environment").path("mods")).addObject().put("id","minecraft").put("version","1.21.1");json.writeValue(root.resolve("manifest.json").toFile(),manifest);
        String client=NativeIconPack.read(root).environmentHash();var snapshot=json.createObjectNode().put("format","mkb-snapshot").put("schemaVersion",1).put("captureMode","SINGLEPLAYER").put("coverage","ALL")
                .put("clientFingerprint",client).put("serverFingerprint",String.valueOf(count%10).repeat(64)).put("serverSession","11111111-1111-1111-1111-111111111111").put("serverGeneration",1);
        var declarations=snapshot.putObject("categories");
        for(String category:ExportSnapshotPreflight.REQUIRED) {
            var declaration=declarations.putObject(category).put("total",0).put("succeeded",0).put("transparent",0).put("unsupported",0).put("failed",0);
            if(category.startsWith("loot_")){declaration.put("status","NOT_IMPLEMENTED");continue;}
            if(category.equals("item_icons")){declaration.put("status","COMPLETE_WITH_ERRORS").put("total",3).put("succeeded",2).put("unsupported",1).put("file","manifest.json").put("sha256",ArtifactFingerprint.sha256(root.resolve("manifest.json")));continue;}
            var payload=json.createObjectNode().put("format","mkb-data").put("schemaVersion",1).put("category",category).put("authoritative",true);var rows=payload.putArray("records");
            switch(category) {
                case "mod_metadata" -> {rows.addObject().put("id","minecraft").put("version","1.21.1").put("displayName","Minecraft");rows.addObject().put("id","mkb_fixture").put("version","1.0").put("displayName","示例模组").put("description","这是用于验证运行时模组说明的完整文本。");}
                case "registries" -> {
                    for(String id:List.of("minecraft:air","mkb_fixture:sample_b","mkb_fixture:requires_game_context"))rows.addObject().put("registry","item").put("id",id).put("descriptionId",id).put("displayName",id);
                    var row=rows.addObject().put("registry","item").put("id","mkb_fixture:sample_a").put("descriptionId","custom.unusual.translation").put("displayName","实例中的名称");
                    row.putArray("tooltip").add("实例中的名称").add("使用此物品可以打开示例机器并处理最终生效的材料标签。");
                }
                case "language" -> {for(String lang:List.of("current","zh_cn","en_us")){var row=rows.addObject().put("id",lang);row.putObject("translations").put("custom.unusual.translation",lang.equals("en_us")?"Example A":lang.equals("zh_cn")?"资源包译名":"实例中的名称").put("item.mkb_fixture.sample_a.description","用于验证说明性语言按实际命名空间检索，不把 item 误作模组 ID。");}}
                case "tags" -> rows.addObject().put("registry","minecraft:item").put("id","mkb_fixture:materials").putArray("members").add("mkb_fixture:sample_b");
                case "recipes" -> {
                    var definition=(ObjectNode)json.readTree("{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[{\"tag\":\"mkb_fixture:materials\"}],\"result\":{\"item\":\"mkb_fixture:sample_a\",\"count\":"+count+"}}");
                    var row=rows.addObject().put("id","mkb_fixture:changed").put("serializer","minecraft:crafting_shapeless").put("recipeType","minecraft:crafting").put("runtimeLoaded",true).put("serialization","RUNTIME_ADAPTER").put("normalizationStatus","STATIC");row.set("definition",definition);row.set("renderJson",definition.deepCopy());
                    rows.addObject().put("id","mkb_fixture:custom").put("serializer","mkb_fixture:machine").put("recipeType","mkb_fixture:machine").put("runtimeLoaded",true).put("serialization","NETWORK_ONLY").put("normalizationStatus","UNSUPPORTED").put("networkBase64","AA==");
                }
            }
            String path="data/"+category+".json";Files.createDirectories(root.resolve("data"));json.writeValue(root.resolve(path).toFile(),payload);
            declaration.put("status","COMPLETE").put("total",rows.size()).put("succeeded",rows.size()).put("file",path).put("sha256",ArtifactFingerprint.sha256(root.resolve(path)));
        }
        json.writeValue(root.resolve("snapshot.json").toFile(),snapshot);return root;
    }
    BuildRequest request(Path root,BuildRequest.Mode mode){return new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,root.toString())),mode);}
    @Test void buildUsesExactNamesFinalTagsAndRuntimeRecipesWithoutResurrectingDeletedJarFacts()throws Exception {
        Path root=fixture("MKB-Export",2);var config=AppConfig.forIsolatedRun(false);config.setDataDir(temp.resolve("database"));var db=new DatabaseBuilder(config);db.initDatabase();
        try(var embedder=new EmbeddingService(null)) {
            new VectorStore(db,384).storeBatch(List.of(new TextChunker.TextChunk("old-recipe","旧配方 minecraft:diamond_block 使用九个钻石合成钻石块。","minecraft","Minecraft","recipe","minecraft:diamond_block")),embedder);
            var builder=new KnowledgeBuilder(config,db,embedder);var imported=builder.build(request(root,BuildRequest.Mode.REBUILD),null);assertEquals(2,imported.recipes);
            assertTrue(ExportSnapshotPreflight.inspect(NativeIconPack.read(root),null).ready());
            assertEquals(List.of("mkb_fixture:sample_a"),new RecipeResourceStore(db).findItemIds("如何制作资源包译名？"));
            var icons=new ItemIconService(db,config.getDataDir()).snapshot();assertEquals("实例中的名称",icons.name("mkb_fixture:sample_a"));
            try(var c=db.getConnection()) {assertEquals(List.of("mkb_fixture:sample_b"),RecipeResourceStore.resolveTag("mkb_fixture:materials",RecipeResourceStore.tags(c)).items());
                try(var s=c.createStatement();var r=s.executeQuery("SELECT output_item,recipe_data FROM "+RuntimeKnowledgeStore.recipeRelation(db)+" WHERE recipe_id='mkb_fixture:changed'")){assertTrue(r.next());assertEquals("mkb_fixture:sample_a",r.getString(1));assertEquals(2,RecipePlan.parse(r.getString(2)).output().count());}
            }
            var hits=new VectorStore(db,384).search(embedder.embed("钻石块合成配方"),100);
            assertTrue(hits.stream().filter(hit->hit.type().equals("runtime_language")).allMatch(hit->"mkb_fixture".equals(hit.modId())));
            assertTrue(hits.stream().noneMatch(hit->hit.chunkId().equals("old-recipe")));
            assertTrue(hits.stream().anyMatch(hit->hit.type().equals("runtime_recipe")));
            var selected=RuntimeKnowledgeStore.active(db);new ItemIconStore(db).selectProfile(selected);
            try(var manager=new MultiDBManager(temp.resolve("qa"),db)) {
                var llm=new RagAgentService(null){@Override public dev.langchain4j.model.chat.ChatModel getChatModel(){return null;}};var vectors=new VectorStore(db,384);
                var answer=new yagen.waitmydawn.kb.agent.AnswerAgent(llm){@Override public String compose(AnswerContext context){assertEquals("RUNTIME_SNAPSHOT",context.recipeMatches().getFirst().sourceType());return "结构化配方验收";}};
                var pipeline=new QaPipeline(new yagen.waitmydawn.kb.agent.ClassifyAgent(llm),new yagen.waitmydawn.kb.agent.EntityAgent(llm,db,embedder,vectors),new yagen.waitmydawn.kb.agent.UrlAgent(llm,db,embedder),answer,llm,vectors,embedder,db,manager,temp);
                var result=pipeline.process("实例中的名称怎么合成",pipeline.knowledgeScopes().getFirst().id());
                assertEquals(1,result.recipeResults.size());assertEquals(1,result.recipesToRender.size());
                var cards=new RendererService(config,db).renderDetailed(result.recipesToRender);assertEquals(1,cards.cards().size());assertEquals(2,cards.cards().getFirst().plan().output().count());
            }
            var resolution=new KnowledgeRetrievalService(db,embedder).resolve("示例机器怎么使用",null,null);
            assertFalse(new KnowledgeRetrievalService(db,embedder).retrieve("示例机器怎么使用",resolution).hits().isEmpty());
            new KnowledgeContentStore(db).validate();db.healthCheck();
            var fingerprint=db.contentFingerprint();builder.build(request(root,BuildRequest.Mode.APPEND),null);assertEquals(fingerprint,db.contentFingerprint());
        }finally{db.shutdown();}
    }
    @Test void refreshReplacesOldVectorsAndMalformedPayloadRetainsPublishedDatabase()throws Exception {
        Path root=fixture("MKB-Export-refresh",2);var config=AppConfig.forIsolatedRun(false);config.setDataDir(temp.resolve("database"));var db=new DatabaseBuilder(config);db.initDatabase();
        try(var embedder=new EmbeddingService(null)) {
            var builder=new KnowledgeBuilder(config,db,embedder);builder.build(request(root,BuildRequest.Mode.REBUILD),null);String old=RuntimeKnowledgeStore.active(db);
            Path replacement=fixture("replacement",4);for(String name:List.of("recipes","registries","language","mod_metadata","tags"))Files.copy(replacement.resolve("data/"+name+".json"),root.resolve("data/"+name+".json"),StandardCopyOption.REPLACE_EXISTING);
            Files.copy(replacement.resolve("snapshot.json"),root.resolve("snapshot.json"),StandardCopyOption.REPLACE_EXISTING);
            builder.build(request(root,BuildRequest.Mode.APPEND),null);assertNotEquals(old,RuntimeKnowledgeStore.active(db));
            try(var c=db.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM rag_vectors WHERE source_path LIKE 'mkb-runtime:"+old+"/%'")){r.next();assertEquals(0,r.getInt(1));}
            String fingerprint=db.contentFingerprint();Files.writeString(root.resolve("data/recipes.json"),"{}");assertThrows(IllegalArgumentException.class,()->builder.build(request(root,BuildRequest.Mode.APPEND),null));assertEquals(fingerprint,db.contentFingerprint());
        }finally{db.shutdown();}
    }
    @Test void multipleSnapshotInputsAreRejectedBeforePublication()throws Exception {
        Path first=fixture("first",2),second=fixture("second",4);var config=AppConfig.forIsolatedRun(false);config.setDataDir(temp.resolve("database"));var db=new DatabaseBuilder(config);db.initDatabase();
        try(var embedder=new EmbeddingService(null)) {
            var sources=List.of(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,first.toString()),BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,second.toString()));
            new VectorStore(db,384);String before=db.contentFingerprint();assertThrows(IllegalArgumentException.class,()->new KnowledgeBuilder(config,db,embedder).build(new BuildRequest(sources),null));assertEquals(before,db.contentFingerprint());
        }finally{db.shutdown();}
    }
}
