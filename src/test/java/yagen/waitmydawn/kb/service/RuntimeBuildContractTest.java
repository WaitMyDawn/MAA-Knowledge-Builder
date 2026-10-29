package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.config.*;
import yagen.waitmydawn.kb.model.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeBuildContractTest {
    @TempDir Path temp;
    AppConfig config;DatabaseBuilder db;EmbeddingService embedding;KnowledgeBuilder builder;Path snapshot;
    @BeforeEach void init()throws Exception {
        config=AppConfig.forIsolatedRun(false);config.setDataDir(temp.resolve("database"));db=new DatabaseBuilder(config);db.initDatabase();embedding=new EmbeddingService(null);builder=new KnowledgeBuilder(config,db,embedding);
        snapshot=RuntimeBuildFixtures.create(temp.resolve("MKB-Export"),List.of(),"neoforge","1.21.1");
    }
    @AfterEach void close(){embedding.close();db.shutdown();LogSetup.shutdown();}
    BuildRequest.Source source(Path path){return BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,path.toString());}
    BuildRequest request(BuildRequest.Mode mode){return new BuildRequest(List.of(source(snapshot)),mode);}
    @Test void everyBuildRequiresExplicitFullSnapshotEvenWhenDatabaseAlreadyHasOne()throws Exception {
        builder.build(request(BuildRequest.Mode.REBUILD),null);String before=db.contentFingerprint();
        Path docs=Files.createDirectories(temp.resolve("docs"));Files.writeString(docs.resolve("one.txt"),"测试中的额外知识资料与完整步骤。");
        var missing=new BuildRequest(List.of(source(docs)),BuildRequest.Mode.APPEND);
        assertThrows(IllegalArgumentException.class,()->builder.build(missing,null));assertEquals(before,db.contentFingerprint());
        builder.build(new BuildRequest(List.of(source(docs),source(snapshot)),BuildRequest.Mode.APPEND),null);
        assertEquals(1,new SourceLifecycleStore(db).documents(source(docs).id()).size());
        assertTrue(RuntimeBuildContract.migrated(db));
    }
    @Test void legacyAppendRequiresRebuildAndMigrationPreservesHistoryAndPhysicalAssets()throws Exception {
        new VectorStore(db,384).storeBatch(List.of(new TextChunker.TextChunk("legacy","旧的 JAR 语言资料","minecraft","Minecraft","lang_items","old.jar")),embedding);
        var history=new ChatHistoryService(config.getDataDir());var session=history.newSession("旧会话",config.getDataDir());history.addMessage(session,"maa","历史回答");history.save(session);
        var image=new java.awt.image.BufferedImage(4,4,2);image.setRGB(1,1,0xff112233);var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",bytes);var images=new ImageAssetStore(config.getDataDir());var asset=images.put(bytes.toByteArray());
        String before=db.contentFingerprint();assertNotNull(RuntimeBuildContract.migrationWarning(db));assertThrows(IllegalArgumentException.class,()->builder.build(request(BuildRequest.Mode.APPEND),null));assertEquals(before,db.contentFingerprint());
        builder.build(request(BuildRequest.Mode.REBUILD),null);assertNull(RuntimeBuildContract.migrationWarning(db));assertEquals("历史回答",history.load(session.sessionId).messages.getFirst().content());assertTrue(images.available(asset.id(),asset.relativePath()));
        assertTrue(new SourceLifecycleStore(db).strings("SELECT chunk_id FROM rag_vectors WHERE chunk_type='lang_items'").isEmpty());
    }
    @Test void oldIconOnlyPartialAndCorruptedExportsAreRejectedWithoutPublishing()throws Exception {
        builder.build(request(BuildRequest.Mode.REBUILD),null);String before=db.contentFingerprint();Path descriptor=snapshot.resolve("snapshot.json");String original=Files.readString(descriptor);
        Files.delete(descriptor);assertThrows(IllegalArgumentException.class,()->builder.build(request(BuildRequest.Mode.APPEND),null));
        var tree=(ObjectNode)RuntimeBuildFixtures.JSON.readTree(original);tree.put("coverage","SELECTION");RuntimeBuildFixtures.JSON.writeValue(descriptor.toFile(),tree);assertThrows(IllegalArgumentException.class,()->builder.build(request(BuildRequest.Mode.APPEND),null));
        Files.writeString(descriptor,original);Files.writeString(snapshot.resolve("data/tags.json"),"{}");assertThrows(IllegalArgumentException.class,()->builder.build(request(BuildRequest.Mode.APPEND),null));assertEquals(before,db.contentFingerprint());
    }
    @Test void authoritativeInventoryMustCoverMinecraftAndAllDefaultIcons()throws Exception {
        var registry=(ObjectNode)RuntimeBuildFixtures.JSON.readTree(snapshot.resolve("data/registries.json").toFile());
        registry.withArray("records").addObject().put("registry","item").put("id","minecraft:dirt").put("descriptionId","block.minecraft.dirt").put("displayName","Dirt");
        writeCategory("registries",registry);
        var failure=assertThrows(java.io.IOException.class,()->RuntimeBuildContract.validateComplete(NativeIconPack.read(snapshot)));assertTrue(failure.getMessage().contains("默认图标"));
    }
    void writeCategory(String name,ObjectNode payload)throws Exception {
        Path file=snapshot.resolve("data/"+name+".json");RuntimeBuildFixtures.JSON.writeValue(file.toFile(),payload);
        var descriptor=(ObjectNode)RuntimeBuildFixtures.JSON.readTree(snapshot.resolve("snapshot.json").toFile());
        descriptor.withObject("categories").withObject(name).put("total",payload.path("records").size()).put("succeeded",payload.path("records").size()).put("sha256",ArtifactFingerprint.sha256(file));
        RuntimeBuildFixtures.JSON.writeValue(snapshot.resolve("snapshot.json").toFile(),descriptor);
    }
    @Test void removingOrChangingExistingModsRequiresRebuild()throws Exception {
        Path jar=temp.resolve("identity.jar");Files.writeString(jar,"version1");var mod=new ModEntry();mod.setModId("demo");mod.setVersion("1");mod.setJarPath(jar.toString());
        RuntimeBuildFixtures.create(snapshot,List.of(mod),"neoforge","1.21.1");builder.build(request(BuildRequest.Mode.REBUILD),null);String before=db.contentFingerprint();
        mod.setVersion("2");RuntimeBuildFixtures.create(snapshot,List.of(mod),"neoforge","1.21.1");assertThrows(IllegalArgumentException.class,()->builder.build(request(BuildRequest.Mode.APPEND),null));
        mod.setVersion("1");Files.writeString(jar,"version1 changed");RuntimeBuildFixtures.create(snapshot,List.of(mod),"neoforge","1.21.1");assertThrows(IllegalArgumentException.class,()->builder.build(request(BuildRequest.Mode.APPEND),null));
        RuntimeBuildFixtures.create(snapshot,List.of(),"neoforge","1.21.1");assertThrows(IllegalArgumentException.class,()->builder.build(request(BuildRequest.Mode.APPEND),null));assertEquals(before,db.contentFingerprint());
    }
    @Test void runtimeDocumentsAreMaintainedOnlyThroughTheirWholeSnapshot()throws Exception {
        var metadata=(ObjectNode)RuntimeBuildFixtures.JSON.readTree(snapshot.resolve("data/mod_metadata.json").toFile());((ObjectNode)metadata.path("records").get(0)).put("description","运行时元数据包含的说明文本，描述基础玩法、对象身份和可追溯来源，用于检查快照派生文档整体维护约束。");writeCategory("mod_metadata",metadata);
        builder.build(request(BuildRequest.Mode.REBUILD),null);var service=new SourceLifecycleService(config,db,embedding);var items=service.items(source(snapshot).id());assertFalse(items.isEmpty());
        assertThrows(IllegalArgumentException.class,()->service.preview(source(snapshot).id(),items.getFirst().id(),SourceLifecycleService.Operation.REFRESH,null));
    }
    @Test void jarContentIsNeverOpenedAsAnArchiveDuringBuildAndStaleScanIsRejected()throws Exception {
        Path folder=Files.createDirectories(temp.resolve("mods")),jar=folder.resolve("sample.jar");Files.writeString(jar,"Deliberately not a ZIP: Build only validates Scan identity hashes.");
        var mod=new ModEntry();mod.setModId("sample");mod.setVersion("1");mod.setDisplayName("Sample");mod.setLoader("neoforge");mod.setJarPath(jar.toString());mod.setJarFileName("sample.jar");
        var env=new GameEnvironment(temp.resolve("client.jar").toString(),"neoforge","1.21.1");var cache=new JarScanCache(folder);cache.begin(env);cache.put(jar,JarScanCache.hashes(jar).sha512(),mod);cache.save(env);
        RuntimeBuildFixtures.create(snapshot,List.of(mod),env.loader(),env.minecraftVersion());
        var scraper=new WikiScraperService(){@Override public WebPage fetchMcWiki(String page){return null;}};
        var local=new KnowledgeBuilder(config,db,embedding,null,scraper);var request=new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.JAR_DIRECTORY,folder.toString()),source(snapshot)));
        local.build(request,null);assertEquals("1",db.findModEntry("sample").getVersion());assertTrue(new SourceLifecycleStore(db).strings("SELECT id FROM rag_recipe").isEmpty());
        String before=db.contentFingerprint();Files.writeString(jar,"changed");assertThrows(IllegalArgumentException.class,()->local.build(new BuildRequest(request.sources(),BuildRequest.Mode.APPEND),null));assertEquals(before,db.contentFingerprint());
    }
    @Test void scanCacheIsMandatoryForJarInputs()throws Exception {
        Path folder=Files.createDirectories(temp.resolve("mods"));Files.writeString(folder.resolve("missing-scan.jar"),"bytes");String before=db.contentFingerprint();
        assertThrows(IllegalArgumentException.class,()->builder.build(new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.JAR_DIRECTORY,folder.toString()),source(snapshot))),null));assertEquals(before,db.contentFingerprint());
    }
    @Test void environmentChangesRequireRebuildButNewDirectoryWithSameEnvironmentReplacesCurrentSource()throws Exception {
        builder.build(request(BuildRequest.Mode.REBUILD),null);Path replacement=RuntimeBuildFixtures.create(temp.resolve("MKB-Export-new"),List.of(),"neoforge","1.21.1");
        builder.build(new BuildRequest(List.of(source(replacement)),BuildRequest.Mode.APPEND),null);assertEquals(source(replacement).id(),RuntimeKnowledgeStore.activeSource(db));assertEquals(1,new SourceLifecycleStore(db).sources().size());
        String before=db.contentFingerprint();RuntimeBuildFixtures.create(snapshot,List.of(),"forge","1.20.1");assertThrows(IllegalArgumentException.class,()->builder.build(request(BuildRequest.Mode.APPEND),null));assertEquals(before,db.contentFingerprint());
        builder.build(request(BuildRequest.Mode.REBUILD),null);assertEquals("1.20.1",db.findModEntry("minecraft").getMcVersion());
    }
    @Test void previewPinsSnapshotAndDerivedDocumentsCannotBeMaintainedIndividually()throws Exception {
        builder.build(request(BuildRequest.Mode.REBUILD),null);var service=new SourceLifecycleService(config,db,embedding);var plan=service.preview(source(snapshot).id(),null,SourceLifecycleService.Operation.REFRESH,null);
        var descriptor=(ObjectNode)RuntimeBuildFixtures.JSON.readTree(snapshot.resolve("snapshot.json").toFile());descriptor.put("serverGeneration",2);RuntimeBuildFixtures.JSON.writeValue(snapshot.resolve("snapshot.json").toFile(),descriptor);
        String before=db.contentFingerprint();assertThrows(IllegalStateException.class,()->service.apply(plan,null));assertEquals(before,db.contentFingerprint());
        service.apply(service.preview(source(snapshot).id(),null,SourceLifecycleService.Operation.REFRESH,null),null);assertEquals(1,new SourceLifecycleStore(db).sources().size());
    }
    @Test void changesDuringBuildCannotBePublishedAndIdenticalAppendDoesNotChangeFingerprint()throws Exception {
        builder.build(request(BuildRequest.Mode.REBUILD),null);String before=db.contentFingerprint();builder.build(request(BuildRequest.Mode.APPEND),null);assertEquals(before,db.contentFingerprint());
        assertThrows(IllegalStateException.class,()->builder.build(request(BuildRequest.Mode.APPEND),(phase,cur,total,message)->{if(phase.equals("progress:来源")&&cur==total)try{Files.writeString(snapshot.resolve("data/recipes.json"),"{}");}catch(Exception e){throw new RuntimeException(e);}}));assertEquals(before,db.contentFingerprint());
    }
}
