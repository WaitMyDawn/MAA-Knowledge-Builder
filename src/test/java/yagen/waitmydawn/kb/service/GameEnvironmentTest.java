package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.model.*;
import yagen.waitmydawn.kb.config.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class GameEnvironmentTest {
    @TempDir Path temp;
    Path client(Path file,String version)throws Exception{
        try(var zip=new JarOutputStream(Files.newOutputStream(file))){for(var entry:Map.of("version.json","{\"id\":\""+version+"\"}","net/minecraft/client/main/Main.class","fixture").entrySet()){zip.putNextEntry(new JarEntry(entry.getKey()));zip.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();}}return file;
    }
    @Test void folderClientWinsOverParentAndVersionComesFromClientManifest()throws Exception{
        var mods=Files.createDirectory(temp.resolve("mods"));client(temp.resolve("1.21.1-NeoForge.jar"),"1.21.1");var file=client(mods.resolve("1.20.1-Forge-MKB.jar"),"1.20.1");
        var found=new GameEnvironmentDetector().discover(mods);assertEquals(1,found.size());assertEquals(file,found.getFirst().jar());assertEquals("forge",found.getFirst().loader());assertEquals("1.20.1",found.getFirst().version());
    }
    @Test void onlyDirectParentIsSearchedAndMinecraftAssetsInModDoNotQualify()throws Exception{
        var instance=Files.createDirectory(temp.resolve("instance"));var mods=Files.createDirectory(instance.resolve("mods"));var sibling=Files.createDirectory(instance.resolve("other"));client(sibling.resolve("other-Forge.jar"),"1.19.2");client(temp.resolve("grandparent-Forge.jar"),"1.18.2");
        try(var zip=new JarOutputStream(Files.newOutputStream(mods.resolve("override.jar")))){zip.putNextEntry(new JarEntry("assets/minecraft/"));zip.closeEntry();}
        var detector=new GameEnvironmentDetector();assertTrue(detector.discover(mods).isEmpty());var file=client(instance.resolve("fabric-client.jar"),"1.20.1");assertEquals(file,detector.discover(mods).getFirst().jar());assertEquals("fabric",detector.inspect(file).orElseThrow().loader());
    }
    @Test void unknownLoaderRequiresSelectionAndNeoForgeIsNotMisreadAsForge()throws Exception{
        var detector=new GameEnvironmentDetector();assertEquals("neoforge",detector.inspect(client(temp.resolve("1.21.1-NeoForge.jar"),"1.21.1")).orElseThrow().loader());assertNull(detector.inspect(client(temp.resolve("client.jar"),"1.20.1")).orElseThrow().loader());
        assertThrows(IllegalArgumentException.class,()->new GameEnvironment("client.jar",null,"1.20.1"));assertThrows(IllegalArgumentException.class,()->new GameEnvironment("client.jar","forge",""));
    }
    @Test void confirmedSourceEnvironmentIsIndependentFromPublishedKnowledge()throws Exception{
        var config=AppConfig.forIsolatedRun(false);config.setDataDir(temp.resolve("data"));var db=new DatabaseBuilder(config);db.initDatabase();new VectorStore(db,384);
        try{String before=db.contentFingerprint();var store=new ScanEnvironmentStore(db);var root=Files.createDirectory(temp.resolve("mods"));var env=new GameEnvironment(temp.resolve("client.jar").toString(),"forge","1.20.1");store.save(root,env);assertEquals(env,store.forJar(root.resolve("nested/mod.jar")));assertNull(store.forJar(temp.resolve("unrelated.jar")));assertEquals(before,db.contentFingerprint());}finally{db.shutdown();LogSetup.shutdown();}
    }
    @Test void buildPublishesConfirmedVersionAndRetainsSourceContextAcrossStaging()throws Exception{
        var config=AppConfig.forIsolatedRun(false);config.setDataDir(temp.resolve("data"));var db=new DatabaseBuilder(config);db.initDatabase();var mods=Files.createDirectory(temp.resolve("mods"));
        try(var zip=new JarOutputStream(Files.newOutputStream(mods.resolve("mod.jar")))){zip.putNextEntry(new JarEntry("fabric.mod.json"));zip.write("{\"id\":\"sample\",\"version\":\"1\",\"depends\":{\"minecraft\":\">=1.17\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();}
        var environment=new GameEnvironment(temp.resolve("client.jar").toString(),"fabric","1.20.1");new ScanEnvironmentStore(db).save(mods,environment);
        var scraper=new WikiScraperService(){@Override public WebPage fetchMcWiki(String page){return new WebPage(page,"https://example.com/wiki/"+page,"基础合成机制说明","mcwiki",Map.of());}};
        try(var embedding=new EmbeddingService(null)){
            var entries=RuntimeBuildFixtures.prepareJars(mods,environment);Path snapshot=RuntimeBuildFixtures.create(config.getDataDir().resolve("MKB-Export"),entries,"fabric","1.20.1");
            var request=new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.JAR_DIRECTORY,mods.toString()),BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,snapshot.toString())));
            var steps=new ArrayList<String>();new KnowledgeBuilder(config,db,embedding,null,scraper).build(request,(phase,done,total,text)->{if(phase.startsWith("progress:")){assertTrue(done>=0&&done<=total);steps.add(phase+":"+done+"/"+total);}});assertTrue(steps.contains("progress:身份:2/2"));assertTrue(steps.contains("progress:来源:2/2"));assertEquals("1.20.1",db.findModEntry("sample").getMcVersion());assertEquals(environment,new ScanEnvironmentStore(db).forFolder(mods));
            var source=BuildRequest.Source.of(BuildRequest.Kind.JAR_DIRECTORY,mods.toString());var maintenance=new SourceLifecycleService(config,db,embedding,null,scraper);
            var oldPreview=maintenance.preview(source.id(),"mod:sample",SourceLifecycleService.Operation.REFRESH,null);
            new ScanEnvironmentStore(db).save(mods,new GameEnvironment(environment.jarPath(),"fabric","1.20.2"));
            assertThrows(IllegalStateException.class,()->maintenance.apply(oldPreview,null));
            assertThrows(IllegalStateException.class,()->maintenance.apply(maintenance.preview(source.id(),"mod:sample",SourceLifecycleService.Operation.REFRESH,null),null));assertEquals("1.20.1",db.findModEntry("sample").getMcVersion());
        }finally{db.shutdown();LogSetup.shutdown();}
    }
}
