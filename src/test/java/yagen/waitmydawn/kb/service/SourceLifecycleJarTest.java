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
        scraper=new WikiScraperService(){@Override public ModScrapeResult scrapeModCatalog(String id,ScrapeProgress progress){return new ModScrapeResult(List.of(),new WebCatalog(List.of(),List.of()));}};
        folder=Files.createDirectory(temp.resolve("mods"));jar(folder.resolve("one.jar"),"one","1");jar(folder.resolve("two.jar"),"two","1");source=BuildRequest.Source.of(BuildRequest.Kind.JAR_DIRECTORY,folder.toString());
        new KnowledgeBuilder(config,db,embedding,null,scraper).build(new BuildRequest(List.of(source),BuildRequest.Mode.APPEND),null);
    }
    @AfterEach void close(){embedding.close();db.shutdown();LogSetup.shutdown();}
    @Test void selectedModRefreshChangesRecipesAndMetadataButPreservesOtherMod()throws Exception {
        var bound=db.findModEntry("one");bound.setSlug("manual-binding");bound.setModrinthUrl("https://modrinth.com/mod/manual-binding");bound.setSource("manual");db.saveModEntry(bound);
        var store=new SourceLifecycleStore(db);String before=String.join("\n",store.strings("SELECT chunk_id||':'||RAWTOHEX(embedding) FROM rag_vectors WHERE mod_id='two' ORDER BY chunk_id"));jar(folder.resolve("one.jar"),"one","2");
        var service=new SourceLifecycleService(config,db,embedding,null,scraper);service.apply(service.preview(source.id(),"mod:one",SourceLifecycleService.Operation.REFRESH,null),null);
        assertEquals("2",db.findModEntry("one").getVersion());assertEquals("1",db.findModEntry("two").getVersion());assertEquals(before,String.join("\n",store.strings("SELECT chunk_id||':'||RAWTOHEX(embedding) FROM rag_vectors WHERE mod_id='two' ORDER BY chunk_id")));
        assertEquals(List.of("2"),store.strings("SELECT DISTINCT mod_version FROM rag_recipe WHERE source_mod='one'"));assertEquals(2,store.mods(source).size());new KnowledgeContentStore(db).validate();
        assertEquals("manual-binding",db.findModEntry("one").getSlug());assertEquals("manual",db.findModEntry("one").getSource());
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
        var pending=new KnowledgeBuilder(config,db,embedding,null,boundScraper).build(new BuildRequest(List.of(source),BuildRequest.Mode.APPEND),null);
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
    @Test void unavailableReboundWikiRetainsPublishedKnowledgeAndDraft(){
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
        assertTrue(assertThrows(IllegalStateException.class,()->service.apply(preview,null)).getMessage().contains("绑定草稿或游戏环境已变化"));
    }
    @Test void duplicateDirectoryOwnershipPreventsDeletingSharedModAndConflictingRefresh()throws Exception {
        Path copy=Files.createDirectory(temp.resolve("copy"));Files.copy(folder.resolve("one.jar"),copy.resolve("one.jar"));var other=BuildRequest.Source.of(source.kind(),copy.toString());
        new KnowledgeBuilder(config,db,embedding,null,scraper).build(new BuildRequest(List.of(other),BuildRequest.Mode.APPEND),null);
        var service=new SourceLifecycleService(config,db,embedding,null,scraper);jar(folder.resolve("one.jar"),"one","2");String before=db.contentFingerprint();assertThrows(IllegalStateException.class,()->service.apply(service.preview(source.id(),"mod:one",SourceLifecycleService.Operation.REFRESH,null),null));assertEquals(before,db.contentFingerprint());
        service.apply(service.preview(source.id(),null,SourceLifecycleService.Operation.DELETE,null),null);assertNotNull(db.findModEntry("one"));assertNull(db.findModEntry("two"));assertEquals(List.of("one"),new SourceLifecycleStore(db).mods(other));new KnowledgeContentStore(db).validate();
    }
    @Test void interruptDuringWikiWaitCannotPublishJarMaintenance() {
        new SourceLifecycleStore(db).execute("DELETE FROM rag_vectors WHERE chunk_type='mcwiki'");String before=db.contentFingerprint();
        var cancelling=new WikiScraperService(){@Override public WebPage fetchMcWiki(String page){Thread.currentThread().interrupt();return null;}};
        var service=new SourceLifecycleService(config,db,embedding,null,cancelling);
        try{assertThrows(java.util.concurrent.CancellationException.class,()->service.apply(service.preview(source.id(),"mod:one",SourceLifecycleService.Operation.REPARSE,null),null));}finally{Thread.interrupted();}
        assertEquals(before,db.contentFingerprint());
    }
}
