package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.config.*;
import yagen.waitmydawn.kb.model.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class JarLanguageExtractorTest {
    @TempDir Path temp;
    Path jar(String name,String zh,String en,boolean badTag)throws Exception{
        var path=temp.resolve(name);var files=new LinkedHashMap<String,String>();files.put("fabric.mod.json","{\"id\":\"demo\",\"version\":\"1\",\"description\":\"A demonstration mod with crafting utilities and reference documentation.\"}");
        if(zh!=null)files.put("assets/demo/lang/zh_cn.json",zh);if(en!=null)files.put("assets/demo/lang/en_us.json",en);if(badTag)files.put("data/demo/tags/items/bad.json","{\"values\":\"invalid\"}");
        try(var zip=new JarOutputStream(Files.newOutputStream(path))){for(var e:files.entrySet()){zip.putNextEntry(new JarEntry(e.getKey()));zip.write(e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();}}return path;
    }
    String english(){return "{\"item.demo.guide\":\"Assembly Guide: instructions for assembling machines and processing materials\"}";}
    @Test void invalidChineseFallsBackToEnglishAndOtherResourceIndexesContinue()throws Exception{
        var path=jar("bad-zh.jar","{\"item.demo.guide\":\"指南\" \"other\":\"missing comma\"}",english(),false);
        var language=new JarLanguageExtractor().extract(path,"demo","Demo");assertFalse(language.chunks().isEmpty());assertTrue(language.chunks().stream().allMatch(c->c.sourcePath().endsWith("en_us.json")));assertEquals(1,language.warnings().size());
        var db=DatabaseBuilder.atPath(temp.resolve("index"));db.initDatabase();try{var imported=new RecipeResourceStore(db).importJar(path,"demo",temp);assertEquals(1,imported.names());assertEquals(1,imported.warnings().size());assertTrue(imported.warnings().getFirst().contains("zh_cn.json"));assertTrue(new ItemIconService(db,temp).snapshot().name("demo:guide").startsWith("Assembly Guide"));}finally{db.shutdown();}
    }
    @Test void validChineseIsPreferredForVectorsWhileBothLanguagesAreIndexed()throws Exception{
        var path=jar("valid.jar","{\"item.demo.guide\":\"机械装配指南：记录组装机械与材料加工的完整使用说明\"}",english(),false);var language=new JarLanguageExtractor().extract(path,"demo","Demo");assertTrue(language.warnings().isEmpty());assertTrue(language.chunks().stream().allMatch(c->c.sourcePath().endsWith("zh_cn.json")));
        var db=DatabaseBuilder.atPath(temp.resolve("index"));db.initDatabase();try{assertEquals(2,new RecipeResourceStore(db).importJar(path,"demo",temp).names());assertTrue(new ItemIconService(db,temp).snapshot().name("demo:guide").startsWith("机械装配指南"));}finally{db.shutdown();}
    }
    @Test void invalidChineseDoesNotPreventBuildPublicationAndEnglishVectorsAreStored()throws Exception{
        var mods=Files.createDirectory(temp.resolve("mods"));var path=jar("source.jar","{invalid",english(),false);Files.move(path,mods.resolve("source.jar"));var cfg=AppConfig.forIsolatedRun(false);cfg.setDataDir(temp.resolve("base"));var db=new DatabaseBuilder(cfg);db.initDatabase();
        var scraper=new WikiScraperService(){@Override public WebPage fetchMcWiki(String page){return null;}};
        try(var embedding=new EmbeddingService(null)){
            var result=new KnowledgeBuilder(cfg,db,embedding,null,scraper).build(BuildRequest.jars(mods),null);assertEquals(1,result.parsed);assertTrue(result.warnings.stream().anyMatch(w->w.contains("zh_cn.json")));assertNotNull(db.findModEntry("demo"));
            assertFalse(new SourceLifecycleStore(db).strings("SELECT chunk_text FROM rag_vectors WHERE chunk_type='lang_items' AND source_path LIKE '%en_us.json'").isEmpty());
        }finally{db.shutdown();LogSetup.shutdown();}
    }
    @Test void invalidTagRemainsFatalAndOriginalPublishedKnowledgeIsRetained()throws Exception{
        var mods=Files.createDirectory(temp.resolve("mods"));Files.move(jar("source.jar",null,english(),true),mods.resolve("source.jar"));var cfg=AppConfig.forIsolatedRun(false);cfg.setDataDir(temp.resolve("base"));var db=new DatabaseBuilder(cfg);db.initDatabase();
        try(var embedding=new EmbeddingService(null)){
            new VectorStore(db,384).storeBatch(List.of(new TextChunker.TextChunk("old","已发布的历史知识数据","old","Old","description","test")),embedding);String before=db.contentFingerprint();
            var scraper=new WikiScraperService(){@Override public WebPage fetchMcWiki(String p){return null;}};var error=assertThrows(IllegalStateException.class,()->new KnowledgeBuilder(cfg,db,embedding,null,scraper).build(BuildRequest.jars(mods),null));assertTrue(error.getMessage().contains("data/demo/tags/items/bad.json"));assertEquals(before,db.contentFingerprint());
        }finally{db.shutdown();LogSetup.shutdown();}
    }
}
