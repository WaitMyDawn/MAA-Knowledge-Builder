package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.model.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class WebImagePipelineTest {
    @TempDir Path temp;
    @AfterEach void telemetry(){RetrievalTelemetry.end();}
    private BuildSourceProcessor.Context context(DatabaseBuilder db,EmbeddingService embedding,ImageAssetStore assets,KnowledgeBuilder.BuildResult result) {
        return new BuildSourceProcessor.Context(db,new KnowledgeContentStore(db),assets,new VectorStore(db,384),embedding,null,result);
    }
    private String html(int images,boolean duplicate) {
        var text=new StringBuilder("<article><h1>装配手册</h1>");
        for(int i=0;i<images;i++)text.append("<p>安装核心并连接部件。<img src='/").append(duplicate?i/2:i).append(".png' alt='核心安装示意'></p>");return text.append("</article>").toString();
    }

    @Test void fetchOverlapsProcessingWithBoundedQueueAndKeepsEveryOccurrence()throws Exception {
        var db=DatabaseBuilder.atPath(temp.resolve("pipeline"));db.initDatabase();
        var processing=new CountDownLatch(1);var release=new CountDownLatch(1);var fetched=new AtomicInteger();var once=new AtomicBoolean();
        byte[] png=FullWebMediaTest.image(11,192,192);String url="https://example.com/guide";
        var web=new WikiScraperService(){@Override public Resource fetchResource(String location,String referer,boolean image,Map<String,String> headers){
            RetrievalTelemetry.httpAttempt();if(location.equals(url))return new Resource(200,html(40,true).getBytes(java.nio.charset.StandardCharsets.UTF_8),"text/html",location,null,null);
            int n=fetched.incrementAndGet();if(n==2)try{assertTrue(processing.await(5,TimeUnit.SECONDS));}catch(InterruptedException interrupted){throw new CancellationException();}
            return new Resource(200,png,"image/png",location,null,null);
        }};web.setResourceDatabase(db);
        var images=new ImageAssetStore(temp);
        var counters=RetrievalTelemetry.begin();var executor=Executors.newSingleThreadExecutor();
        try(var embedding=new EmbeddingService(null)) {
            var source=BuildRequest.Source.of(BuildRequest.Kind.URL,url);new KnowledgeContentStore(db).saveSource(source);var result=new KnowledgeBuilder.BuildResult();
            var context=new BuildSourceProcessor.Context(db,new KnowledgeContentStore(db),images,new VectorStore(db,384),embedding,(phase,cur,total,message)->{
                if(phase.equals("web-images")&&cur>=2&&once.compareAndSet(false,true)){processing.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Processing barrier timed out");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new CancellationException();}}
            },result);
            var operation=executor.submit(RetrievalTelemetry.carry(()->{try{new UrlBuildProcessor(web).process(source,context);}catch(Exception failure){throw new CompletionException(failure);}}));
            try {
                assertTrue(processing.await(5,TimeUnit.SECONDS));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(fetched.get()<3&&System.nanoTime()<deadline)Thread.sleep(10);
                assertEquals(3,fetched.get(),"One consumer, one queued response and one downloading response bound prefetch");
                release.countDown();operation.get(20,TimeUnit.SECONDS);
            }finally{release.countDown();operation.cancel(true);}
            assertEquals(20,fetched.get());assertEquals(1,result.webImageDecodes,"Identical content across different URLs is decoded once");
            assertEquals(40,FullWebMediaTest.count(db,"SELECT COUNT(*) FROM rag_web_image_reference WHERE status='AVAILABLE'"));
            assertEquals(1,result.webImageQueuePeak);assertEquals(40,result.webImageBindings);assertEquals(21,counters.httpRequests,"Downloader telemetry belongs to the originating request");
            new KnowledgeContentStore(db).validate();
        }finally{release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(20,TimeUnit.SECONDS));db.shutdown();}
    }

    @Test void cooldownStopsFurtherSiteFetchesAndFailuresRemainClassified()throws Exception {
        var db=DatabaseBuilder.atPath(temp.resolve("cooldown"));db.initDatabase();var attempts=new AtomicInteger();String url="https://example.com/cooldown";
        var web=new WikiScraperService(){@Override public Resource fetchResource(String location,String referer,boolean image,Map<String,String> headers){
            if(location.equals(url))return new Resource(200,html(20,false).getBytes(java.nio.charset.StandardCharsets.UTF_8),"text/html",location,null,null);
            attempts.incrementAndGet();return new Resource(429,null,"",location,null,null);
        }};web.setResourceDatabase(db);
        try(var embedding=new EmbeddingService(null)) {
            var source=BuildRequest.Source.of(BuildRequest.Kind.URL,url);new KnowledgeContentStore(db).saveSource(source);var result=new KnowledgeBuilder.BuildResult();
            new UrlBuildProcessor(web).process(source,context(db,embedding,new ImageAssetStore(temp),result));
            assertEquals(1,attempts.get());assertEquals(20,result.cooldownImageFailures);assertEquals(20,result.deferredWebImages);assertEquals(0,result.permanentImageFailures);
        }finally{db.shutdown();}
    }

    @Test void aFailedBatchDoesNotPublishPartialImageBindings()throws Exception {
        var db=DatabaseBuilder.atPath(temp.resolve("batch"));db.initDatabase();byte[] png=FullWebMediaTest.image(2,192,192);String url="https://example.com/batch";
        try(var c=db.getConnection();var s=c.createStatement()){s.execute("ALTER TABLE rag_web_image_reference ADD CONSTRAINT reject_image CHECK(NOT(status='AVAILABLE' AND id LIKE '%/image/5'))");}
        var web=new WikiScraperService(){@Override public Resource fetchResource(String location,String referer,boolean image,Map<String,String> headers){return new Resource(200,location.equals(url)?html(16,false).getBytes(java.nio.charset.StandardCharsets.UTF_8):png,location.equals(url)?"text/html":"image/png",location,null,null);}};web.setResourceDatabase(db);
        try(var embedding=new EmbeddingService(null)) {
            var source=BuildRequest.Source.of(BuildRequest.Kind.URL,url);new KnowledgeContentStore(db).saveSource(source);
            assertThrows(java.sql.SQLException.class,()->new UrlBuildProcessor(web).process(source,context(db,embedding,new ImageAssetStore(temp),new KnowledgeBuilder.BuildResult())));
            assertEquals(0,FullWebMediaTest.count(db,"SELECT COUNT(*) FROM rag_image_occurrence"));assertEquals(0,FullWebMediaTest.count(db,"SELECT COUNT(*) FROM rag_web_image_reference WHERE status='AVAILABLE'"));
            assertEquals(16,FullWebMediaTest.count(db,"SELECT COUNT(*) FROM rag_web_image_download"),"Validated downloads survive an index transaction failure");
        }finally{db.shutdown();}
    }
}
