package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import yagen.waitmydawn.kb.model.*;
import yagen.waitmydawn.kb.config.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeCatalogTest {
    @TempDir Path temp;
    @Test void idOnlySearchIgnoresStateKeysAndPayloadAndTextIndexesReusePages()throws Exception {
        var db=DatabaseBuilder.atPath(temp.resolve("fast-index"));db.initDatabase();
        try{try(var c=db.getConnection();var st=c.createStatement()){st.execute("INSERT INTO rag_runtime_snapshot VALUES('s','profile','{}')");}
            var json=new com.fasterxml.jackson.databind.ObjectMapper();var language=json.createObjectNode().put("id","zh_cn");var translations=language.putObject("translations");for(int i=0;i<10000;i++)translations.put("demo.key"+i,"翻译 "+i);
            try(var c=db.getConnection();var p=c.prepareStatement("INSERT INTO rag_runtime_record VALUES('profile',?,?,?)")){p.setString(1,"language");p.setString(2,"zh_cn");p.setString(3,language.toString());p.executeUpdate();for(String id:List.of("demo:owl|dragon_state","demo:dragon|hash")){p.setString(1,"entity_icons");p.setString(2,id);p.setString(3,json.createObjectNode().put("id",id.split("\\|")[0]).put("payload","dragon inside JSON").toString());p.executeUpdate();}}
            var catalog=new RuntimeCatalog(db);assertEquals(1,catalog.page("profile",RuntimeCatalog.Kind.ENTITIES,"dragon","",0,50).total());assertEquals(0,catalog.page("profile",RuntimeCatalog.Kind.ENTITIES,"payload","",0,50).total());
            long start=System.nanoTime();var first=catalog.page("profile",RuntimeCatalog.Kind.LANGUAGE,"翻译","",0,50);long initial=System.nanoTime()-start;start=System.nanoTime();var second=catalog.page("profile",RuntimeCatalog.Kind.LANGUAGE,"翻译","",50,50);long warm=System.nanoTime()-start;assertEquals(10000,first.total());assertEquals(50,second.entries().size());assertNotEquals(first.entries().getFirst().key(),second.entries().getFirst().key());System.out.println("CATALOG benchmark language10000 initialMs="+initial/1000000+" nextPageMs="+warm/1000000);
        }finally{db.shutdown();}
    }
    @Test void directoriesPageFilterLanguagesAndKeepProfilesIsolated()throws Exception {
        var config=AppConfig.forIsolatedRun(false).forBuildTarget(temp);var db=new DatabaseBuilder(config);db.initDatabase();
        try(var embedding=new EmbeddingService(null)) {
            new KnowledgeBuilder(config,db,embedding).build(new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,RuntimeBuildFixtures.create(temp.resolve("MKB-Export"),List.of(),"neoforge","1.21.1").toString()))),null);
            String profile=RuntimeKnowledgeStore.active(db);var catalog=new RuntimeCatalog(db);
            assertEquals(1,catalog.profiles().size());assertTrue(catalog.profiles().getFirst().source().contains("MKB-Export"));
            var page=catalog.page(profile,RuntimeCatalog.Kind.ITEMS,"","minecraft",0,1);assertEquals(2,page.total());assertEquals(1,page.entries().size());assertEquals(0,catalog.page("other",RuntimeCatalog.Kind.ITEMS,"","",0,50).total());
            assertEquals(0,catalog.page(profile,RuntimeCatalog.Kind.ITEMS,"%","",0,50).total());assertThrows(IllegalArgumentException.class,()->catalog.page(profile,RuntimeCatalog.Kind.ITEMS,"","",0,1000));
            try(var c=db.getConnection();var p=c.prepareStatement("UPDATE rag_runtime_record SET record_data=? WHERE profile_id=? AND category='language' AND record_id='zh_cn'")) {p.setString(1,"{\"id\":\"zh_cn\",\"translations\":{\"item.minecraft.stone\":\"石头\",\"item.minecraft.air\":\"空气\"}}");p.setString(2,profile);p.executeUpdate();}
            var lang=catalog.page(profile,RuntimeCatalog.Kind.LANGUAGE,"石头","",0,50);assertEquals(1,lang.total());assertTrue(catalog.pretty(lang.entries().getFirst()).contains("石头"));
            try(var c=db.getConnection();var p=c.prepareStatement("MERGE INTO rag_runtime_name KEY(profile_id,item_id,language) VALUES(?,?,?,?)")){p.setString(1,profile);p.setString(2,"minecraft:stone");p.setString(3,"zh_cn");p.setString(4,"石头");p.executeUpdate();}
            assertEquals(0,catalog.page(profile,RuntimeCatalog.Kind.ITEMS,"石头","",0,50).total());assertEquals(1,catalog.page(profile,RuntimeCatalog.Kind.ITEMS,"stone","",0,50).total());
            String recipe="{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[{\"item\":\"minecraft:stone\"}],\"result\":{\"id\":\"minecraft:stone\",\"count\":1}}";
            try(var c=db.getConnection();var p=c.prepareStatement("INSERT INTO rag_runtime_recipe VALUES(?,?,?,?,?,?,?,?)")){p.setString(1,"test-recipe");p.setString(2,profile);p.setString(3,"minecraft:test");p.setString(4,recipe);p.setString(5,"minecraft:stone");p.setString(6,"minecraft");p.setString(7,"RUNTIME_SNAPSHOT");p.setString(8,"STATIC");p.executeUpdate();}
            assertEquals(1,catalog.page(profile,RuntimeCatalog.Kind.RECIPES,"石头","minecraft",0,50).total());
            var matches=List.of(catalog.recipe(profile,"minecraft:test"));var renderer=new RendererService(config,db);
            assertTrue(renderer.cachedDetailed(matches).cards().isEmpty());assertFalse(Files.exists(config.getGeneratedDir().resolve("crafting")));
            var batch=renderer.renderDetailed(matches);assertEquals(1,batch.cards().size(),batch.diagnostics().toString());var png=Path.of(batch.cards().getFirst().path());var stamp=Files.getLastModifiedTime(png);
            assertEquals(batch.cards().getFirst().pngHash(),renderer.cachedDetailed(matches).cards().getFirst().pngHash());assertEquals(stamp,Files.getLastModifiedTime(png));

        }finally{db.shutdown();}
    }
    @Test void lootCardsPreserveRulesAndQuestionReusesDirectoryCache()throws Exception {
        var config=AppConfig.forIsolatedRun(false).forBuildTarget(temp);var db=new DatabaseBuilder(config);db.initDatabase();
        try(var embedding=new EmbeddingService(null)) {
            new KnowledgeBuilder(config,db,embedding).build(new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,RuntimeBuildFixtures.create(temp.resolve("MKB-Export"),List.of(),"neoforge","1.21.1").toString()))),null);String profile=RuntimeKnowledgeStore.active(db);
            String raw="{\"id\":\"minecraft:chests/test\",\"definition\":{\"pools\":[{\"rolls\":1,\"entries\":[{\"type\":\"minecraft:item\",\"name\":\"minecraft:stone\",\"weight\":2}],\"conditions\":[{\"condition\":\"minecraft:random_chance\",\"chance\":0.5}]}]}}";
            try(var c=db.getConnection();var p=c.prepareStatement("INSERT INTO rag_runtime_record VALUES(?,?,?,?)")){p.setString(1,profile);p.setString(2,"loot_tables");p.setString(3,"minecraft:chests/test");p.setString(4,raw);p.executeUpdate();}
            var catalog=new RuntimeCatalog(db);var page=catalog.page(profile,RuntimeCatalog.Kind.LOOT,"stone","minecraft",0,50);assertEquals(1,page.total());assertTrue(catalog.pretty(page.entries().getFirst()).contains("random_chance"));assertTrue(catalog.itemRelations(profile,"minecraft:stone").contains("minecraft:chests/test"));
            var service=new LootCardService(db,temp);assertNull(service.cached(profile,"loot_tables","minecraft:chests/test"));var file=service.render(profile,"loot_tables","minecraft:chests/test");var modified=Files.getLastModifiedTime(file);assertNotNull(javax.imageio.ImageIO.read(file.toFile()));
            var result=new QaPipeline.QaResult();result.vectorResults=List.of(new VectorStore.SearchResult("chunk","石头掉落规则","minecraft","Minecraft","runtime_loot_tables",0.9,"mkb-runtime:"+profile+"/loot_tables/minecraft:chests/test"));service.attach(result,"石头的战利品掉落是什么？");assertEquals(1,result.artifacts.pictures().size());assertEquals(modified,Files.getLastModifiedTime(file));
            var vector=new TextChunker().fromWebPage("石头掉落规则", "minecraft","Minecraft","runtime_loot_tables","mkb-runtime:"+profile+"/loot_tables/minecraft:chests/test");new VectorStore(db,embedding.getDimension()).storeBatch(vector,embedding);
            var scoped=new QaPipeline.QaResult();scoped.vectorResults=List.of(new VectorStore.SearchResult(vector.getFirst().id(),"石头掉落规则","minecraft","Minecraft","runtime_loot_tables",0.9,"D:/archive/data/loot_tables.json"));service.attach(scoped,"石头掉落是什么？");assertEquals(1,scoped.artifacts.pictures().size());

            assertThrows(IllegalArgumentException.class,()->service.render("other","loot_tables","minecraft:chests/test"));
            var meta=file.resolveSibling(file.getFileName()+".json");Files.writeString(meta,"broken");assertNotNull(service.render(profile,"loot_tables","minecraft:chests/test"));
        }finally{db.shutdown();}
    }
}
