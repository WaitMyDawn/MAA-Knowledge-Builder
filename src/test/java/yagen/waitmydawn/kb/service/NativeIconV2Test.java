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

class NativeIconV2Test {
    @TempDir Path temp;
    private final ObjectMapper json=new ObjectMapper();
    @AfterEach void logging(){LogSetup.shutdown();}
    @Test void transparentStateIsDistinctFromVisibleDefault()throws Exception {
        Path pack=temp.resolve("transparent-state");var fixture=new NativeIconPackTest();var doc=fixture.fixture(pack);doc.put("schemaVersion",2);
        var state=json.readTree("{\"components\":{\"demo:invisible\":true}}");var variant=((ObjectNode)doc.path("icons").get(0)).deepCopy();
        Path png=pack.resolve("icons/demo/invisible.png");javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(32,32,2),"png",png.toFile());
        variant.set("state",state);variant.put("variant",NativeIconPack.stateVariant(state));variant.put("status","TRANSPARENT");variant.put("file","icons/demo/invisible.png");variant.put("sha256",ArtifactFingerprint.sha256(png));((ArrayNode)doc.path("icons")).add(variant);fixture.write(pack,doc);
        var config=AppConfig.forIsolatedRun(false);config.setDataDir(temp.resolve("data"));var db=new DatabaseBuilder(config);db.initDatabase();
        try(var embedder=new EmbeddingService(null)) {
            var result=new RuntimeBuildFixtures.IconArchiveImporter(config,db,embedder).build(new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,pack.toString())),BuildRequest.Mode.APPEND),null);
            assertEquals(1,result.nativeIcons);assertEquals(1,result.transparentNativeIcons);assertEquals(1,result.failedNativeIcons);
            var store=new ItemIconStore(db);store.selectProfile(store.profiles().getFirst().id());var icons=new ItemIconService(db,config.getDataDir()).snapshot();
            assertEquals("NATIVE",icons.item("demo:widget",false).source());assertEquals("NATIVE_TRANSPARENT",icons.item("demo:widget",true,state).source());
        }finally{db.shutdown();}
    }
    @Test void stateVariantsHaveSeparateMappingsAndNeverFallBackToDefault()throws Exception {
        Path pack=temp.resolve("pack");var fixture=new NativeIconPackTest();var doc=fixture.fixture(pack);doc.put("schemaVersion",2);
        var state=json.readTree("{\"components\":{\"minecraft:dyed_color\":{\"rgb\":16711680,\"show_in_tooltip\":true}}}");
        var colored=((ObjectNode)doc.path("icons").get(0)).deepCopy();colored.set("state",state);colored.put("variant",NativeIconPack.stateVariant(state));
        ((ArrayNode)doc.path("icons")).add(colored);fixture.write(pack,doc);
        assertEquals(3,NativeIconPack.read(pack).icons().size());
        var config=AppConfig.forIsolatedRun(false);config.setDataDir(temp.resolve("data"));var db=new DatabaseBuilder(config);db.initDatabase();
        try(var embedder=new EmbeddingService(null)) {
            new RuntimeBuildFixtures.IconArchiveImporter(config,db,embedder).build(new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,pack.toString())),BuildRequest.Mode.APPEND),null);
            var store=new ItemIconStore(db);store.selectProfile(store.profiles().getFirst().id());
            var icons=new ItemIconService(db,config.getDataDir()).snapshot();
            assertEquals("NATIVE",icons.item("demo:widget",false).source());assertEquals("NATIVE",icons.item("demo:widget",true,state).source());
            assertTrue(icons.item("demo:widget",true,state).detail().contains("精确状态"));
            assertEquals("MISSING",icons.item("demo:widget",true,json.readTree("{\"components\":{\"minecraft:dyed_color\":1}}" )).source());
            String recipe="{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[{\"item\":\"demo:widget\"}],\"result\":{\"id\":\"demo:widget\",\"components\":{\"minecraft:dyed_color\":{\"rgb\":16711680,\"show_in_tooltip\":true}}}}";
            assertTrue(RecipeSummary.describe(recipe,"demo:widget").contains("产物状态：{\"components\""));
            assertEquals("NATIVE",new RendererService(config,db).renderDetailed(List.of(new QaPipeline.RecipeMatch(recipe,"demo:widget","demo","TEST_FIXTURE"))).cards().getFirst().output().source());
            assertEquals(0,new VectorStore(db,384).count());
        }finally{db.shutdown();}
        colored.put("variant","state:"+"0".repeat(64));fixture.write(pack,doc);assertThrows(java.io.IOException.class,()->NativeIconPack.read(pack));
    }
    @Test void updatingSelectedSourceKeepsOldAssetsAndTextGeneration()throws Exception {
        Path pack=temp.resolve("pack");var fixture=new NativeIconPackTest();var doc=fixture.fixture(pack);
        var config=AppConfig.forIsolatedRun(false);config.setDataDir(temp.resolve("data"));var db=new DatabaseBuilder(config);db.initDatabase();
        try(var embedder=new EmbeddingService(null)) {
            var builder=new RuntimeBuildFixtures.IconArchiveImporter(config,db,embedder);var request=new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,pack.toString())),BuildRequest.Mode.APPEND);
            builder.build(request,null);db.setBuildGeneration("preserved-text");var store=new ItemIconStore(db);String previous=store.profiles().getFirst().id();store.selectProfile(previous);
            var old=store.findDefault(previous,"demo:widget").orElseThrow();
            var image=javax.imageio.ImageIO.read(pack.resolve("icons/demo/widget.png").toFile());image.setRGB(12,12,0xff11bb33);javax.imageio.ImageIO.write(image,"png",pack.resolve("icons/demo/widget.png").toFile());
            ((ObjectNode)doc.path("icons").get(0)).put("sha256",ArtifactFingerprint.sha256(pack.resolve("icons/demo/widget.png")));fixture.write(pack,doc);
            var result=builder.build(request,null);assertEquals(1,result.nativeIconPacks);assertEquals(0,result.addedEmbeddings);assertEquals(2,store.profiles().size());
            assertNotEquals(previous,store.activeProfileId());assertEquals("preserved-text",db.buildGeneration());assertTrue(new ImageAssetStore(config.getDataDir()).available(old.assetId(),old.relativePath()));
            String selected=store.activeProfileId();((ObjectNode)doc.path("environment")).put("minecraftVersion","1.20.1");fixture.write(pack,doc);builder.build(request,null);assertEquals(selected,store.activeProfileId());
        }finally{db.shutdown();}
    }
}
