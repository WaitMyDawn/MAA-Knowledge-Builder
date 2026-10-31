package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import yagen.waitmydawn.kb.model.*;
import static org.junit.jupiter.api.Assertions.*;

class WebImagePolicyTest {
    @TempDir Path temp;
    @Test void targetAcquisitionDoesNotAutomaticallyExpandRecipeMaterials()throws Exception {
        var calls=new AtomicInteger();
        var llm=new RagAgentService(yagen.waitmydawn.kb.config.AppConfig.forIsolatedRun(false)) {
            @Override public String rawAsk(String caller,String prompt){calls.incrementAndGet();return "[]";}
        };
        var recipes=List.of(new QaPipeline.RecipeMatch("{}","minecraft:diamond_sword","minecraft","RUNTIME_SNAPSHOT"));
        OneHopEvidence.select("钻石剑怎么制作和获取", "minecraft",List.of(),recipes,null,Set.of(),llm);
        assertEquals(0,calls.get());
        OneHopEvidence.select("钻石剑合成材料如何获取", "minecraft",List.of(),recipes,null,Set.of(),llm);
        assertEquals(1,calls.get());
    }
    @Test void strictDimensions()throws Exception {
        assertFalse(WebImagePolicy.allowed(160,300));assertFalse(WebImagePolicy.allowed(300,160));
        assertFalse(WebImagePolicy.allowed(160,160));assertTrue(WebImagePolicy.allowed(161,161));
        assertThrows(java.io.IOException.class,()->WebImagePolicy.check(FullWebMediaTest.image(1,160,300)));
        WebImagePolicy.check(FullWebMediaTest.image(1,161,161));
    }
    @Test void acquisitionDoesNotFetchImagesAndOnlySelectedReferencesAreDownloaded()throws Exception {
        var db=DatabaseBuilder.atPath(temp.resolve("selected"));db.initDatabase();var calls=new AtomicInteger();final byte[] png=FullWebMediaTest.image(1,192,192);
        var web=new WikiScraperService(){@Override public Resource fetchResource(String url,String referer,boolean image,Map<String,String> headers){
            if(image){calls.incrementAndGet();return new Resource(200,png,"image/png",url,null,null);}
            return new Resource(200,"<article><h1>说明</h1><p>安装核心结构<img src='/a.png' alt='核心安装'></p><p>无关插图<img src='/b.png'></p></article>".getBytes(java.nio.charset.StandardCharsets.UTF_8),"text/html",url,null,null);
        }};web.setResourceDatabase(db);
        try(var embedding=new EmbeddingService(null)) {
            var service=new WebPageMediaService(db,embedding,temp,web);service.acquire("https://example.com/guide");assertEquals(0,calls.get());
            String doc,id;try(var c=db.getConnection();var s=c.createStatement();var rows=s.executeQuery("SELECT document_id,id FROM rag_web_image_reference ORDER BY id")){assertTrue(rows.next());doc=rows.getString(1);id=rows.getString(2);}
            var source=new AnswerArtifacts.Source("s",doc,"说明","https://example.com/guide","",null,"NATIVE","安装核心结构");
            var artifacts=new AnswerArtifacts(null,null,List.of(source),List.of());
            assertEquals(Set.of(),WebImagePolicy.select(db,artifacts,"安装核心有什么用"));
            assertEquals(Set.of(id),WebImagePolicy.select(db,artifacts,"展示核心安装结构图片"));
            service.completeSelected(List.of(doc),Set.of());assertEquals(0,calls.get());
            service.completeSelected(List.of(doc),Set.of(id));assertEquals(1,calls.get());
            service.completeSelected(List.of(doc),Set.of(id));assertEquals(1,calls.get());
            assertEquals(1,FullWebMediaTest.count(db,"SELECT COUNT(*) FROM rag_web_image_reference WHERE status='AVAILABLE'"));
            String second;try(var c=db.getConnection();var st=c.createStatement();var rows=st.executeQuery("SELECT id FROM rag_web_image_reference ORDER BY id DESC")){rows.next();second=rows.getString(1);}
            var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                var first=pool.submit(()->new WebPageMediaService(db,embedding,temp,web).completeSelected(List.of(doc),Set.of(second)));
                var other=pool.submit(()->new WebPageMediaService(db,embedding,temp,web).completeSelected(List.of(doc),Set.of(second)));
                first.get(10,java.util.concurrent.TimeUnit.SECONDS);other.get(10,java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(2,calls.get());assertEquals(1,FullWebMediaTest.count(db,"SELECT COUNT(*) FROM rag_image_asset"));
            }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));}

        }finally{db.shutdown();}
    }
    @Test void smallImageRejectionSurvivesServiceRecreation()throws Exception {
        var db=DatabaseBuilder.atPath(temp.resolve("small"));db.initDatabase();var calls=new AtomicInteger();final byte[] png=FullWebMediaTest.image(1,160,192);
        var web=new WikiScraperService(){@Override public Resource fetchResource(String url,String referer,boolean image,Map<String,String> headers){
            if(image){calls.incrementAndGet();return new Resource(200,png,"image/png",url,null,null);}
            return new Resource(200,"<article><p>说明<img src='/a.png'></p></article>".getBytes(java.nio.charset.StandardCharsets.UTF_8),"text/html",url,null,null);
        }};web.setResourceDatabase(db);
        try(var embedding=new EmbeddingService(null)) {
            var service=new WebPageMediaService(db,embedding,temp,web);service.acquire("https://example.com/small");
            String doc,id;try(var c=db.getConnection();var s=c.createStatement();var rows=s.executeQuery("SELECT document_id,id FROM rag_web_image_reference")){assertTrue(rows.next());doc=rows.getString(1);id=rows.getString(2);}
            service.completeSelected(List.of(doc),Set.of(id));new WebPageMediaService(db,embedding,temp,web).completeSelected(List.of(doc),Set.of(id));
            assertEquals(1,calls.get());assertEquals(0,FullWebMediaTest.count(db,"SELECT COUNT(*) FROM rag_image_asset"));
            assertEquals(1,FullWebMediaTest.count(db,"SELECT COUNT(*) FROM rag_web_image_reference WHERE status='EXCLUDED_SMALL'"));
        }finally{db.shutdown();}
    }
}
