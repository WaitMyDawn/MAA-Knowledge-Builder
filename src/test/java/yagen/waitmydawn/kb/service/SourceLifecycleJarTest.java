package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.config.*;
import yagen.waitmydawn.kb.model.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class SourceLifecycleJarTest {
    @TempDir Path temp;
    AppConfig config;DatabaseBuilder db;EmbeddingService embedding;WikiScraperService scraper;Path folder;BuildRequest.Source source;
    void jar(Path path,String mod,String version)throws Exception {
        try(var zip=new JarOutputStream(Files.newOutputStream(path))) {
            var values=Map.of("fabric.mod.json","{\"schemaVersion\":1,\"id\":\""+mod+"\",\"version\":\""+version+"\",\"name\":\""+mod+"\"}","assets/"+mod+"/lang/zh_cn.json","{\"item."+mod+".ingot\":\"材料版本"+version+"\"}","data/"+mod+"/recipes/widget.json","{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[{\"item\":\"minecraft:iron_ingot\"}],\"result\":{\"item\":\""+mod+":ingot\"}}");
            for(var entry:values.entrySet()){zip.putNextEntry(new JarEntry(entry.getKey()));zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
        }
    }
    @BeforeEach void init()throws Exception {
        config=AppConfig.forIsolatedRun(false);config.setDataDir(temp.resolve("data"));db=new DatabaseBuilder(config);db.initDatabase();embedding=new EmbeddingService(null);
        new VectorStore(db,384).storeBatch(List.of(new TextChunker.TextChunk("baseline","公共基线","baseline","baseline","mcwiki","fixture")),embedding);
        scraper=new WikiScraperService(){@Override public WebPage fetchMcWiki(String page){return new WebPage(page,"https://example.com/"+page,"公共基线知识说明，用于离线验证构建与维护流程。","mcwiki",Map.of());}@Override public ModScrapeResult scrapeModCatalog(String id,ScrapeProgress progress){return new ModScrapeResult(List.of(),new WebCatalog(List.of(),List.of()));}};
        folder=Files.createDirectory(temp.resolve("mods"));jar(folder.resolve("one.jar"),"one","1");jar(folder.resolve("two.jar"),"two","1");source=BuildRequest.Source.of(BuildRequest.Kind.JAR_DIRECTORY,folder.toString());
        new KnowledgeBuilder(config,db,embedding,null,scraper).build(request(source,BuildRequest.Mode.REBUILD),null);
    }
    BuildRequest request(BuildRequest.Source selected,BuildRequest.Mode mode)throws Exception {
        var environment=new GameEnvironment(temp.resolve("client.jar").toString(),"fabric","1.20.1");
        RuntimeBuildFixtures.prepareJars(Path.of(selected.location()),environment);
        Path snapshot=config.getDataDir().resolve("MKB-Export");
        if(!Files.exists(snapshot.resolve("snapshot.json")))RuntimeBuildFixtures.create(snapshot,RuntimeBuildFixtures.prepareJars(folder,environment),"fabric","1.20.1");
        return new BuildRequest(List.of(selected,BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,snapshot.toString())),mode);
    }
    @AfterEach void close(){embedding.close();db.shutdown();LogSetup.shutdown();}
    @Test void changedJarRequiresNewScanAndSnapshotWithoutPublishingPartialMetadata()throws Exception {
        jar(folder.resolve("one.jar"),"one","2");String before=db.contentFingerprint();
        var service=new SourceLifecycleService(config,db,embedding,null,scraper);
        assertThrows(IllegalStateException.class,()->service.apply(service.preview(source.id(),"mod:one",SourceLifecycleService.Operation.REFRESH,null),null));
        assertEquals(before,db.contentFingerprint());assertEquals("1",db.findModEntry("one").getVersion());
    }
    @Test void bindingDraftRequiresRefreshAndRefreshReplacesPreviousWikiEvidence()throws Exception {
        var entry=db.findModEntry("one");entry.setMcmodBinding(ModUrlBindingTest.binding("10",10L,"MANUAL"));new ModBindingStore(db).save(entry);
        var seen=new ArrayList<String>();
        var boundScraper=new WikiScraperService(){
            @Override public String findModCandidateUrl(String id){throw new AssertionError("Build must not search");}
            @Override public ModScrapeResult scrapeModCatalog(String id,ScrapeProgress p){throw new AssertionError("Build must supply a class id");}
            @Override public ModScrapeResult scrapeModCatalog(String id,String classId,ScrapeProgress p){
                seen.add(classId);return new ModScrapeResult(List.of(new WebPage("绑定教程", "https://www.mcmod.cn/class/"+classId+".html", "这是绑定来源"+classId+"的独有玩法说明，核心与材料需要放入装配台进行组装。", "mcmod",Map.of())),new WebCatalog(List.of(),List.of()));
            }
        };
        var pending=new KnowledgeBuilder(config,db,embedding,null,boundScraper).build(request(source,BuildRequest.Mode.APPEND),null);
        assertTrue(seen.isEmpty());assertNull(db.findModEntry("one").getMcmodBinding());assertTrue(pending.warnings.stream().anyMatch(w->w.contains("绑定已修改")));
        var service=new SourceLifecycleService(config,db,embedding,null,boundScraper);
        service.apply(service.preview(source.id(),"mod:one",SourceLifecycleService.Operation.REFRESH,null),null);
        assertEquals(List.of("10"),seen);assertEquals("10",db.findModEntry("one").getMcmodBinding().classId());
        entry=db.findModEntry("one");entry.setMcmodBinding(ModUrlBindingTest.binding("20",20L,"MANUAL"));new ModBindingStore(db).save(entry);
        service.apply(service.preview(source.id(),"mod:one",SourceLifecycleService.Operation.REFRESH,null),null);
        assertEquals(List.of("10","20"),seen);assertEquals("20",db.findModEntry("one").getMcmodBinding().classId());
        var chunks=new SourceLifecycleStore(db).strings("SELECT chunk_text FROM rag_vectors WHERE mod_id='one' AND chunk_type='mcmod'");assertFalse(chunks.isEmpty());assertTrue(chunks.stream().allMatch(t->!t.contains("绑定来源10")));assertTrue(chunks.stream().anyMatch(t->t.contains("绑定来源20")));
        var urls=new SourceLifecycleStore(db).strings("SELECT url FROM rag_web_cache WHERE mod_id='one'");assertTrue(urls.stream().noneMatch(u->u.contains("/10.html")));assertTrue(urls.stream().anyMatch(u->u.contains("/20.html")));
        var disabled=new ModUrlBinding(null,null,null,null,"DISABLED","MANUAL",null,null);entry.setMcmodBinding(disabled);new ModBindingStore(db).save(entry);
        service.apply(service.preview(source.id(),"mod:one",SourceLifecycleService.Operation.REFRESH,null),null);
        assertEquals(2,seen.size());assertTrue(new SourceLifecycleStore(db).strings("SELECT url FROM rag_web_cache WHERE mod_id='one'").isEmpty());assertEquals("DISABLED",db.findModEntry("one").getMcmodBinding().status());
    }
    @Test void unavailableReboundWikiRetainsPublishedKnowledgeAndDraft()throws Exception{
        var entry=db.findModEntry("one");entry.setMcmodBinding(ModUrlBindingTest.binding("10",1L,"MANUAL"));db.saveModEntry(entry);
        entry.setMcmodBinding(ModUrlBindingTest.binding("20",2L,"MANUAL"));new ModBindingStore(db).save(entry);String before=db.contentFingerprint();
        var unavailable=new WikiScraperService(){@Override public ModScrapeResult scrapeModCatalog(String id,String classId,ScrapeProgress p){return new ModScrapeResult(List.of(),new WebCatalog(List.of(),List.of()));}};
        var service=new SourceLifecycleService(config,db,embedding,null,unavailable);assertThrows(IllegalStateException.class,()->service.apply(service.preview(source.id(),"mod:one",SourceLifecycleService.Operation.REFRESH,null),null));
        assertEquals(before,db.contentFingerprint());assertEquals("10",db.findModEntry("one").getMcmodBinding().classId());var desired=ModUrlBindingTest.mod("one","fabric");assertTrue(new ModBindingStore(db).apply(desired,db.findModEntry("one")));assertEquals("20",desired.getMcmodBinding().classId());
    }
    @Test void changedBindingDraftInvalidatesMaintenancePreview(){
        var entry=db.findModEntry("one");entry.setMcmodBinding(ModUrlBindingTest.binding("10",1L,"MANUAL"));new ModBindingStore(db).save(entry);
        var service=new SourceLifecycleService(config,db,embedding,null,scraper);var preview=service.preview(source.id(),"mod:one",SourceLifecycleService.Operation.REFRESH,null);
        assertTrue(preview.summary().contains("10.html"));entry.setMcmodBinding(ModUrlBindingTest.binding("20",2L,"MANUAL"));new ModBindingStore(db).save(entry);
        assertTrue(assertThrows(IllegalStateException.class,()->service.apply(preview,null)).getMessage().contains("绑定草稿、游戏环境或快照已变化"));
    }
    @Test void duplicateDirectoryOwnershipPreventsDeletingSharedModAndConflictingRefresh()throws Exception {
        Path copy=Files.createDirectory(temp.resolve("copy"));Files.copy(folder.resolve("one.jar"),copy.resolve("one.jar"));var other=BuildRequest.Source.of(source.kind(),copy.toString());
        new KnowledgeBuilder(config,db,embedding,null,scraper).build(request(other,BuildRequest.Mode.APPEND),null);
        var service=new SourceLifecycleService(config,db,embedding,null,scraper);jar(folder.resolve("one.jar"),"one","2");String before=db.contentFingerprint();assertThrows(IllegalStateException.class,()->service.apply(service.preview(source.id(),"mod:one",SourceLifecycleService.Operation.REFRESH,null),null));assertEquals(before,db.contentFingerprint());
        service.apply(service.preview(source.id(),null,SourceLifecycleService.Operation.DELETE,null),null);assertNotNull(db.findModEntry("one"));assertNotNull(db.findModEntry("two"));assertNull(db.findModEntry("two").getJarPath());assertEquals(List.of("one"),new SourceLifecycleStore(db).mods(other));new KnowledgeContentStore(db).validate();
    }
    @Test void interruptDuringWikiWaitCannotPublishJarMaintenance() {
        var entry=db.findModEntry("one");entry.setMcmodBinding(ModUrlBindingTest.binding("10",1L,"MANUAL"));new ModBindingStore(db).save(entry);String before=db.contentFingerprint();
        var cancelling=new WikiScraperService(){@Override public ModScrapeResult scrapeModCatalog(String id,String classId,ScrapeProgress progress){Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException();}};
        var service=new SourceLifecycleService(config,db,embedding,null,cancelling);
        try{assertThrows(java.util.concurrent.CancellationException.class,()->service.apply(service.preview(source.id(),"mod:one",SourceLifecycleService.Operation.REPARSE,null),null));}finally{Thread.interrupted();}
        assertEquals(before,db.contentFingerprint());
    }
}
