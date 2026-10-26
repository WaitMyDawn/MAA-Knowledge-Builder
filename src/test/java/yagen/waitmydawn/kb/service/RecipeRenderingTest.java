package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.config.*;
import yagen.waitmydawn.kb.model.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RecipeRenderingTest {
    @TempDir Path temp;
    @AfterEach void stopLogging(){LogSetup.shutdown();}
    private final String recipe="{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[{\"item\":\"demo:widget\"},{\"item\":\"demo:widget\"},{\"item\":\"demo:widget\"},{\"item\":\"demo:widget\"},{\"item\":\"demo:widget\"}],\"result\":{\"id\":\"demo:widget\",\"count\":4}}";
    @Test void nativeProfileIsExplicitAndCachedPicturesTrackAssetsSelectionAndMissingFiles() throws Exception {
        var config=AppConfig.forIsolatedRun(false);config.setDataDir(temp.resolve("data"));var db=new DatabaseBuilder(config);db.initDatabase();
        Path pack=temp.resolve("pack");new NativeIconPackTest().fixture(pack);
        try(var embedding=new EmbeddingService(null){@Override public float[] embed(String value){fail("Icons must not embed");return null;}}) {
            new KnowledgeBuilder(config,db,embedding).build(new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,pack.toString())),BuildRequest.Mode.APPEND),null);
            var service=new RendererService(config,db);var matches=List.of(new QaPipeline.RecipeMatch(recipe,"demo:widget","demo","JAR_PARSE"));
            var noProfile=service.renderDetailed(matches);assertEquals("MISSING",noProfile.cards().getFirst().output().source());
            var store=new ItemIconStore(db);var profile=store.profiles().getFirst();String generation=db.buildGeneration();store.selectProfile(profile.id());assertEquals(generation,db.buildGeneration());
            var nativeCard=service.renderDetailed(matches);assertEquals("NATIVE",nativeCard.cards().getFirst().output().source());assertEquals(5,nativeCard.cards().getFirst().slots().size());
            assertNotEquals(noProfile.images(),nativeCard.images());assertEquals(nativeCard.images(),service.renderDetailed(matches).images());
            Path other=temp.resolve("other-pack");var fixture=new NativeIconPackTest();var json=fixture.fixture(other);
            var image=javax.imageio.ImageIO.read(other.resolve("icons/demo/widget.png").toFile());image.setRGB(12,12,0xff00ee00);javax.imageio.ImageIO.write(image,"png",other.resolve("icons/demo/widget.png").toFile());
            ((com.fasterxml.jackson.databind.node.ObjectNode)json.path("icons").get(0)).put("sha256",ArtifactFingerprint.sha256(other.resolve("icons/demo/widget.png")));fixture.write(other,json);
            new KnowledgeBuilder(config,db,embedding).build(new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,other.toString())),BuildRequest.Mode.APPEND),null);
            store.selectProfile(store.profiles().stream().filter(p->!p.id().equals(profile.id())).findFirst().orElseThrow().id());
            var alternate=service.renderDetailed(matches);assertNotEquals(nativeCard.cards().getFirst().output().assetId(),alternate.cards().getFirst().output().assetId());assertNotEquals(nativeCard.images(),alternate.images());
            store.selectProfile(profile.id());assertEquals(nativeCard.images(),service.renderDetailed(matches).images());
            var result=new QaPipeline.QaResult();result.answer="离线配方验收";result.artifacts=AnswerArtifacts.empty();AnswerMedia.attachRecipes(result,nativeCard,config.getDataDir());
            assertEquals(1,AnswerMedia.assess(result.answer,result.artifacts,config.getDataDir()).valid());assertEquals("JAR_PARSE",result.artifacts.sources().getFirst().method());
            var snapshotPicture=result.artifacts.pictures().getFirst();
            var icon=store.findDefault(profile.id(),"demo:widget").orElseThrow();Files.delete(new ImageAssetStore(config.getDataDir()).resolve(icon.assetId(),icon.relativePath()));
            var missing=service.renderDetailed(matches);assertEquals("MISSING",missing.cards().getFirst().output().source());assertNotEquals(nativeCard.images(),missing.images());
            assertNotNull(AnswerMedia.resolveImage(config.getDataDir(),snapshotPicture));
            assertEquals(0,new VectorStore(db,384).count());
        }finally{db.shutdown();}
    }
    @Test void incompatibleProfileCannotBeActivatedAndCustomRecipeGetsDiagnostic() throws Exception {
        var config=AppConfig.forIsolatedRun(false);config.setDataDir(temp.resolve("data"));var db=new DatabaseBuilder(config);db.initDatabase();Path pack=temp.resolve("pack");new NativeIconPackTest().fixture(pack);
        try(var embedding=new EmbeddingService(null)) {
            new KnowledgeBuilder(config,db,embedding).build(new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,pack.toString())),BuildRequest.Mode.APPEND),null);
            var mod=new ModEntry();mod.setModId("demo");mod.setDisplayName("Demo");mod.setVersion("2.0");mod.setMcVersion("1.20.1");mod.setLoader("forge");db.saveModEntry(mod);
            var store=new ItemIconStore(db);assertEquals("CONFLICT",store.profiles().getFirst().compatibility());assertThrows(IllegalArgumentException.class,()->store.selectProfile(store.profiles().getFirst().id()));assertNull(store.activeProfileId());
            var batch=new RendererService(config,db).renderDetailed(List.of(new QaPipeline.RecipeMatch(recipe.replace("minecraft:crafting_shapeless","custom:crafting_shapeless"),"demo:widget","demo")));
            assertTrue(batch.images().isEmpty());assertTrue(batch.diagnostics().getFirst().contains("未适配"));
        }finally{db.shutdown();}
    }
    @Test void statefulOutputAndUnresolvedTagsNeverBorrowDefaultOrTagNamedIcons() throws Exception {
        var config=AppConfig.forIsolatedRun(false);config.setDataDir(temp.resolve("data"));var db=new DatabaseBuilder(config);db.initDatabase();new VectorStore(db,384);
        try {
            String json="{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[{\"tag\":\"demo:widget\"}],\"result\":{\"id\":\"demo:widget\",\"components\":{\"minecraft:dyed_color\":123}}}";
            var card=new RendererService(config,db).renderDetailed(List.of(new QaPipeline.RecipeMatch(json,"demo:widget","demo","TEST_FIXTURE"))).cards().getFirst();
            assertEquals("MISSING",card.slots().getFirst().preview().source());assertTrue(card.output().detail().contains("状态尚未导出"));assertTrue(card.plan().output().stateful());
            assertTrue(new RendererService(config,db).renderDetailed(List.of(new QaPipeline.RecipeMatch(recipe,"demo:widget","demo","SEED"))).images().isEmpty());
        }finally{db.shutdown();}
    }
}
