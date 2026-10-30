package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.config.*;
import yagen.waitmydawn.kb.model.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class BuildTaskJournalTest {
    @TempDir Path temp;
    @AfterEach void logging(){Thread.interrupted();LogSetup.shutdown();}

    @Test void activeTasksAreProtectedAndLegacyCleanupOnlyRecognizesOwnedNames()throws Exception {
        try(var task=BuildTaskJournal.begin(temp)) {
            Files.writeString(Path.of(task.staging()+".mv.db"),"active");Files.writeString(task.temporaryDirectory().resolve("asset.tmp"),"partial");
            assertEquals(0,BuildTaskJournal.recover(temp).files());assertTrue(Files.exists(Path.of(task.staging()+".mv.db")));
            String legacy="staging-"+UUID.randomUUID()+".mv.db";Files.writeString(temp.resolve(legacy),"stale");
            Files.writeString(temp.resolve("staging-user-notes.mv.db"),"unrelated");
            assertEquals(1,BuildTaskJournal.recover(temp).files());assertFalse(Files.exists(temp.resolve(legacy)));assertTrue(Files.exists(temp.resolve("staging-user-notes.mv.db")));
            task.finish("CANCELLED");assertFalse(Files.exists(Path.of(task.staging()+".mv.db")));
        }
    }
    @Test void cancellationCannotInterruptThePublicationBoundary()throws Exception {
        try(var task=BuildTaskJournal.begin(temp)) {
            assertTrue(BuildTaskJournal.requestCancellation(Thread.currentThread()));
            assertThrows(CancellationException.class,()->task.state("PUBLISHING"));assertFalse(Thread.currentThread().isInterrupted());task.finish("CANCELLED");
        }
        try(var task=BuildTaskJournal.begin(temp)) {
            task.state("PUBLISHING");assertFalse(BuildTaskJournal.requestCancellation(Thread.currentThread()));assertFalse(Thread.currentThread().isInterrupted());task.finish("PUBLISHED");
        }
    }

    @Test void cancelledBuildRemovesStagingAndReusesDownloadedImagesOnRetry()throws Exception {
        var config=AppConfig.forIsolatedRun(false).forBuildTarget(temp);var db=new DatabaseBuilder(config);db.initDatabase();
        var counts=new ConcurrentHashMap<String,AtomicInteger>();var once=new AtomicBoolean();var messages=new ArrayList<String>();
        String url="https://example.com/resume";var html=new StringBuilder("<article><h1>升级指南</h1><p>安装核心。");
        for(int i=0;i<40;i++)html.append("<img src='/").append(i).append(".png' alt='安装步骤'>");html.append("</p></article>");
        var web=new WikiScraperService(){@Override public Resource fetchResource(String location,String referer,boolean image,Map<String,String> headers){
            counts.computeIfAbsent(location,key->new AtomicInteger()).incrementAndGet();
            boolean picture=!location.equals(url);
            try{return new Resource(200,picture?FullWebMediaTest.image(location.hashCode(),8,8):html.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),picture?"image/png":"text/html",location,null,null);}catch(Exception failure){throw new IllegalStateException(failure);}
        }};
        try(var embedding=new EmbeddingService(null)) {
            new VectorStore(db,384).storeBatch(List.of(new TextChunker.TextChunk("sentinel","保留原库","minecraft","Minecraft","web","fixture")),embedding);
            String before=db.contentFingerprint();var builder=new KnowledgeBuilder(config,db,embedding,List.of(new UrlBuildProcessor(web)));
            var request=RuntimeBuildFixtures.withSnapshot(new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.URL,url)),BuildRequest.Mode.REBUILD),temp);
            assertThrows(CancellationException.class,()->builder.build(request,(phase,cur,total,message)->{
                if(phase.equals("cleanup"))messages.add(message);
                if(phase.equals("web-images")&&cur>=16&&once.compareAndSet(false,true))BuildTaskJournal.requestCancellation(Thread.currentThread());
            }));
            assertFalse(Thread.currentThread().isInterrupted());assertEquals(before,db.contentFingerprint());assertTrue(messages.stream().anyMatch(message->message.contains("已取消")&&message.contains("图片保留")));
            assertTrue(messages.stream().anyMatch(message->message.contains("原始资源缓存")&&message.contains("地址索引")));
            try(var files=Files.newDirectoryStream(temp,"staging-*.mv.db")){assertFalse(files.iterator().hasNext());}
            assertFalse(Thread.getAllStackTraces().keySet().stream().anyMatch(thread->thread.isAlive()&&thread.getName().startsWith("mkb-web-image-download-")));
            try(var c=db.getConnection();var s=c.createStatement()){s.execute("DELETE FROM rag_public_resource WHERE content_type LIKE 'image/%'");}
            var successfulBefore=new HashSet<String>();try(var c=db.getConnection();var s=c.createStatement();var rows=s.executeQuery("SELECT url FROM rag_web_image_download")){while(rows.next())successfulBefore.add(rows.getString(1));}
            assertTrue(successfulBefore.size()>=16);
            var result=builder.build(request,null);assertEquals(40,FullWebMediaTest.count(db,"SELECT COUNT(*) FROM rag_web_image_reference WHERE status='AVAILABLE'"));assertTrue(result.reusedWebImages>=16);
            for(String cached:successfulBefore)assertEquals(1,counts.get(cached).get(),cached);
            new KnowledgeContentStore(db).validate();
        }finally{db.shutdown();}
    }

    @Test void abruptExitIsRecoveredAfterDirectoryLockWithoutRemovingCachedAssets()throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin","java.exe").toString();
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        var child=new ProcessBuilder(java,"-Dfile.encoding=UTF-8","-cp",classpath,CrashChild.class.getName(),temp.toString()).redirectErrorStream(true).redirectOutput(temp.resolve("child.log").toFile()).start();
        try{assertTrue(child.waitFor(30,TimeUnit.SECONDS));assertEquals(23,child.exitValue());}
        finally{if(child.isAlive()){child.destroyForcibly();child.waitFor(10,TimeUnit.SECONDS);}}
        try(var lock=DataDirLock.acquire(temp)) {
            assertFalse(lock.recoverySummary().isBlank());
            try(var stages=Files.newDirectoryStream(temp,"staging-*.mv.db")){assertFalse(stages.iterator().hasNext());}
            try(var files=Files.walk(temp.resolve("build-tasks"))){assertFalse(files.anyMatch(file->file.getFileName().toString().equals("partial.tmp")));}
            var db=DatabaseBuilder.atPath(temp.resolve("rag_data"));db.initDatabase();
            try{var cache=new WebImageDownloadCache(db,new ImageAssetStore(temp));assertNotNull(cache.find("https://example.com/retained.png",false));}
            finally{db.shutdown();}
        }
    }
    public static class CrashChild {
        public static void main(String[] args)throws Exception {
            Path root=Path.of(args[0]);DataDirLock.acquire(root);var task=BuildTaskJournal.begin(root);
            var stage=DatabaseBuilder.atPath(task.staging());stage.initDatabase();Files.writeString(task.temporaryDirectory().resolve("partial.tmp"),"partial");
            var database=DatabaseBuilder.atPath(root.resolve("rag_data"));database.initDatabase();
            var assets=new ImageAssetStore(root);var asset=assets.put(FullWebMediaTest.image(19,8,8));new WebImageDownloadCache(database,assets).save("https://example.com/retained.png",asset);database.shutdown();
            Runtime.getRuntime().halt(23);
        }
    }
}
