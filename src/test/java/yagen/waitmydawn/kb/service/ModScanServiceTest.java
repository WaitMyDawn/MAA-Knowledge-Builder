package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.model.*;
import yagen.waitmydawn.kb.config.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class ModScanServiceTest {
    @TempDir Path temp;
    DatabaseBuilder db;GameEnvironment environment;Path folder;
    @BeforeEach void init()throws Exception{
        var config=AppConfig.forIsolatedRun(false);config.setDataDir(temp.resolve("db"));db=new DatabaseBuilder(config);db.initDatabase();folder=Files.createDirectory(temp.resolve("mods"));environment=new GameEnvironment(temp.resolve("client.jar").toString(),"forge","1.20.1");
    }
    @AfterEach void close(){Thread.interrupted();db.shutdown();LogSetup.shutdown();}
    void jar(String name,String id,String version)throws Exception{try(var zip=new JarOutputStream(Files.newOutputStream(folder.resolve(name)))){zip.putNextEntry(new JarEntry("fabric.mod.json"));zip.write(("{\"id\":\""+id+"\",\"name\":\"Display "+id+"\",\"version\":\""+version+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();}}
    ModrinthBinder binder(){return new ModrinthBinder(uri->uri.getPath().contains("/version_file/")?new ModrinthBinder.Response(404,""):new ModrinthBinder.Response(200,"{\"hits\":[]}"));}
    ModScanService.Result scan(boolean restore,ModrinthBinder binder)throws Exception{return new ModScanService().scan(folder,environment,db,false,binder,restore,(done,total,mod,text)->{});}
    @Test void persistedCacheRestoresAcrossDatabaseInstancesAndDoesNotRepeatNetwork()throws Exception{
        jar("a.jar","a","1");jar("b.jar","b","1");var binder=binder();var first=scan(false,binder);assertEquals(6,first.metrics().httpRequests());assertEquals(2,first.completed());assertTrue(Files.isRegularFile(folder.resolve(JarScanCache.FILE_NAME)));
        var warm=scan(false,binder());assertEquals(2,warm.metrics().cacheHits());assertEquals(0,warm.metrics().httpRequests());
        var isolated=DatabaseBuilder.atPath(temp.resolve("other"));isolated.initDatabase();try{
            var restored=new ModScanService().scan(folder,environment,isolated,false,binder(),true,(d,t,m,s)->{});assertEquals(2,restored.mods().size());assertEquals(0,restored.metrics().httpRequests());assertEquals("1.20.1",restored.mods().getFirst().getMcVersion());
        }finally{isolated.shutdown();}
    }
    @Test void contentChangeWithSameSizeAndTimestampIsNotReusedAndDeletedFilesDisappear()throws Exception{
        jar("a.jar","a","1");jar("b.jar","b","1");scan(false,binder());Path file=folder.resolve("a.jar");var modified=Files.getLastModifiedTime(file);long size=Files.size(file);jar("a.jar","a","2");Files.setLastModifiedTime(file,modified);assertEquals(size,Files.size(file));
        var restored=scan(true,binder());assertEquals(1,restored.completed());assertEquals(2,restored.total());
        var fresh=scan(false,binder());assertEquals(1,fresh.metrics().cacheHits());assertEquals(3,fresh.metrics().httpRequests());assertEquals("2",fresh.mods().stream().filter(m->m.getModId().equals("a")).findFirst().orElseThrow().getVersion());
        Files.delete(folder.resolve("b.jar"));assertEquals(1,scan(false,binder()).mods().size());
    }
    @Test void environmentChangeInvalidatesAllCachedRowsAndBindingsCanBeUpdated()throws Exception{
        jar("a.jar","a","1");var result=scan(false,binder());var entry=result.mods().getFirst();entry.setSlug("manual");entry.setMcmodBinding(new ModUrlBinding("https://www.mcmod.cn/class/123.html","测试","Test",5L,"FOUND","MANUAL",null,null));var cache=new JarScanCache(folder);cache.updateBinding(entry);assertTrue(cache.save(environment));
        assertEquals("manual",scan(true,binder()).mods().getFirst().getSlug());
        environment=new GameEnvironment(environment.jarPath(),"forge","1.20.2");assertThrows(IllegalStateException.class,()->scan(true,binder()));
        var fresh=scan(false,binder());assertEquals(0,fresh.metrics().cacheHits());assertEquals("1.20.2",fresh.mods().getFirst().getMcVersion());
    }
    @Test void cancellationKeepsCompletedCheckpointAndIncompleteRestoreRequiresRescan()throws Exception{
        jar("a.jar","a","1");jar("b.jar","b","1");
        try{assertThrows(java.util.concurrent.CancellationException.class,()->new ModScanService().scan(folder,environment,db,false,binder(),false,(done,total,mod,text)->{if(done==1)Thread.currentThread().interrupt();}));}finally{Thread.interrupted();}
        var restored=scan(true,binder());assertEquals(1,restored.completed());assertEquals(2,restored.total());assertEquals(1,restored.mods().size());assertEquals(2,new JarScanCache(folder).progress().scan().total());
        assertEquals(1,scan(false,binder()).metrics().cacheHits());
    }
    @Test void skippedLibraryIsCountedAndCorruptRecordFallsBackToScan()throws Exception{
        jar("a.jar","a","1");try(var zip=new JarOutputStream(Files.newOutputStream(folder.resolve("library.jar")))){}
        var result=scan(false,binder());assertEquals(2,result.completed());assertEquals(1,result.metrics().skipped());assertEquals(1,scan(true,binder()).mods().size());
        Files.writeString(folder.resolve(JarScanCache.FILE_NAME),"broken");assertEquals(1,scan(false,binder()).mods().size());
    }
    @Test void legacySharedRecordMigratesAndRestoresBindingFieldsAndSeparateProgress()throws Exception{
        jar("a.jar","a","1");jar("b.jar","b","1");var entries=scan(false,binder()).mods();var cache=new JarScanCache(folder);ModEntry a=entries.get(0),b=entries.get(1);
        a.setMcmodBinding(new ModUrlBinding("https://www.mcmod.cn/class/123.html","中文名","English",269467L,"FOUND","AUTO",null,null));
        b.setMcmodBinding(new ModUrlBinding("https://www.mcmod.cn/class/456.html",null,null,null,"FAILED","AUTO",null,null));cache.updateBinding(a);cache.updateBinding(b);assertTrue(cache.save(environment));
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var root=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(folder.resolve(JarScanCache.FILE_NAME).toFile());root.put("schemaVersion",1);root.remove("progress");json.writeValue(folder.resolve(JarScanCache.LEGACY_FILE_NAME).toFile(),root);Files.delete(folder.resolve(JarScanCache.FILE_NAME));assertTrue(JarScanCache.exists(folder));
        var isolated=DatabaseBuilder.atPath(temp.resolve("legacy-restore"));isolated.initDatabase();try{
            var restored=new ModScanService().scan(folder,environment,isolated,false,binder(),true,(d,t,m,s)->{});assertEquals(2,restored.scanPrepared());assertEquals(1,JarScanCache.bindingCompleted(restored.mods()));assertEquals(0,restored.metrics().httpRequests());
            var binding=restored.mods().getFirst().getMcmodBinding();assertEquals("中文名（English）",binding.alias());assertEquals(269467L,binding.views());
        }finally{isolated.shutdown();}
        assertEquals(1,JarScanCache.bindingCompleted(scan(true,binder()).mods()));
        assertTrue(Files.isRegularFile(folder.resolve(JarScanCache.FILE_NAME)));assertFalse(Files.exists(folder.resolve(JarScanCache.LEGACY_FILE_NAME)));
        var progress=json.readTree(folder.resolve(JarScanCache.FILE_NAME).toFile()).path("progress");assertEquals(2,progress.path("scan").path("completed").asInt());assertEquals(2,progress.path("scan").path("total").asInt());assertEquals(1,progress.path("bindUrl").path("completed").asInt());assertEquals(2,progress.path("bindUrl").path("total").asInt());
        b.setMcmodBinding(new ModUrlBinding(null,null,null,null,"NOT_FOUND","AUTO",null,null));cache=new JarScanCache(folder);cache.updateBinding(b);assertEquals(2,cache.progress().bindUrl().completed());
    }
    @Test void failedNetworkRequestsRemainUnfinishedAfterOfflineRestore()throws Exception{
        jar("a.jar","a","1");var unavailable=new ModrinthBinder(uri->new ModrinthBinder.Response(503,""));var first=scan(false,unavailable);assertEquals(1,first.completed());assertEquals(0,first.scanPrepared());
        var restored=scan(true,binder());assertEquals(0,restored.scanPrepared());assertEquals(0,restored.metrics().httpRequests());assertEquals(0,new JarScanCache(folder).progress().scan().completed());
        assertEquals(1,scan(false,binder()).scanPrepared());
    }
    @Test void automaticNoMatchExpiryDoesNotSlideOnEveryCacheRead()throws Exception{
        jar("a.jar","a","1");scan(false,binder());var path=folder.resolve(JarScanCache.FILE_NAME);var json=new com.fasterxml.jackson.databind.ObjectMapper();var before=json.readTree(path.toFile()).path("items").get(0).path("checkedAt").asText();scan(false,binder());assertEquals(before,json.readTree(path.toFile()).path("items").get(0).path("checkedAt").asText());
        var root=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(path.toFile());((com.fasterxml.jackson.databind.node.ObjectNode)root.path("items").get(0)).put("checkedAt",java.time.Instant.now().minusSeconds(90000).toString());json.writeValue(path.toFile(),root);assertEquals(3,scan(false,binder()).metrics().httpRequests());
    }
    @Test void cacheWriteFailureDoesNotDiscardSuccessfulScan()throws Exception{
        jar("a.jar","a","1");var blocked=Files.createDirectory(temp.resolve("blocked"));var result=new ModScanService().scan(folder,environment,db,false,binder(),false,(d,t,m,s)->{},new JarScanCache(folder,blocked));assertEquals(1,result.mods().size());assertNotNull(result.warning());
    }
}
