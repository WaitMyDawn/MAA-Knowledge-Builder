package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.config.*;
import yagen.waitmydawn.kb.model.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class WebImageEnricherTest {
    @TempDir Path temp;
    final AtomicInteger ocrCalls=new AtomicInteger(),fetches=new AtomicInteger();
    @AfterEach void cleanup(){System.clearProperty("maa.web.maxOcrImages");LogSetup.shutdown();}
    private byte[] png()throws Exception {
        var image=new java.awt.image.BufferedImage(240,100,2);var g=image.createGraphics();g.setColor(java.awt.Color.WHITE);g.fillRect(0,0,240,100);g.setColor(java.awt.Color.BLACK);g.drawString("UPGRADE SLOT",12,42);g.dispose();var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",bytes);return bytes.toByteArray();
    }
    private WikiScraperService web()throws Exception {
        byte[] image=png();return new WikiScraperService(){@Override public Resource fetchBuildResource(String url,String referer,boolean picture) {
            fetches.incrementAndGet();if(url.endsWith(".png"))return new Resource(200,image,"image/png",url,null,null);
            String html="<article><h1>傀儡手册</h1><h2>傀儡操作</h2><p>第1步：组装傀儡，把核心装配到底座。<img src='/one.png' alt='傀儡组装结构示意'></p><p>第2步：升级傀儡，在菜单中安装升级芯片。<img src='/two.png' alt='傀儡升级界面'></p></article>";
            return new Resource(200,html.getBytes(StandardCharsets.UTF_8),"text/html",url,null,null);
        }};
    }
    private long count(DatabaseBuilder db,String sql)throws Exception {try(var c=db.getConnection();var s=c.createStatement();var r=s.executeQuery(sql)){r.next();return r.getLong(1);}}
    private AppConfig config()throws Exception {var config=AppConfig.forIsolatedRun(false);config.setDataDir(temp.resolve("data"));return config;}
    @Test void defaultWebImagesUseContextWithoutOcrAndRemainSearchableAfterAppend()throws Exception {
        var config=config();var db=new DatabaseBuilder(config);db.initDatabase();
        try(var embedding=new EmbeddingService(null)) {
            var builder=new KnowledgeBuilder(config,db,embedding,List.of(new UrlBuildProcessor(web())));
            var source=BuildRequest.Source.of(BuildRequest.Kind.URL,"https://example.com/guide");
            var request=RuntimeBuildFixtures.withSnapshot(new BuildRequest(List.of(source),BuildRequest.Mode.APPEND),config.getDataDir());
            var result=builder.build(request,null);
            assertEquals(0,result.webOcrAttempts);assertEquals(0,result.webOcrChunks);assertEquals(0,result.deferredImageAnalyses);
            assertEquals(2,count(db,"SELECT COUNT(*) FROM rag_image_grounding"));
            assertEquals(0,count(db,"SELECT COUNT(*) FROM rag_image_analysis"));
            assertEquals(0,count(db,"SELECT COUNT(*) FROM rag_content_block WHERE kind='IMAGE_OCR'"));
            var retrieval=new KnowledgeRetrievalService(db,embedding,config.getDataDir(),false);
            var answer=retrieval.retrieve("傀儡在菜单中安装升级芯片",new KnowledgeRetrievalService.Resolution(null,null,null,false),false);
            assertFalse(answer.artifacts().pictures().isEmpty());
            int fetched=fetches.get();var second=builder.build(request,null);
            assertEquals(fetched,fetches.get());assertEquals(0,second.webOcrAttempts);
            new KnowledgeContentStore(db).validate();
        }finally{db.shutdown();}
    }

    @Test void clearingLegacyWebOcrKeepsImagesAndLocalPdfOcr()throws Exception {
        var config=config();var db=new DatabaseBuilder(config);db.initDatabase();
        try(var embedding=new EmbeddingService(null)) {
            var builder=new KnowledgeBuilder(config,db,embedding,List.of(new UrlBuildProcessor(web(),null,new WebImageEnricher(image->new LocalOcrEngine.Result("UPGRADE SLOT 升级芯片安装","OK","zh-Hans")))));
            builder.build(RuntimeBuildFixtures.withSnapshot(new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.URL,"https://example.com/guide"))),config.getDataDir()),null);
            var store=new KnowledgeContentStore(db);var pdf=BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,temp.toString());store.saveSource(pdf);
            store.saveDocument(new KnowledgeContent.Document("pdf",pdf.id(),"扫描资料","fixture.pdf","hash","application/pdf"));
            store.saveBlock(new KnowledgeContent.Block("pdf-ocr","pdf","扫描页","扫描页",0,1,"IMAGE_OCR",null,"本地扫描资料的识别结果","OCR"));
            store.saveChunk(new KnowledgeContent.Chunk("pdf/ocr/chunk/0","pdf","本地扫描资料的识别结果","扫描页",1,0));store.linkBlock("pdf/ocr/chunk/0","pdf-ocr");
            long images=count(db,"SELECT COUNT(*) FROM rag_image_occurrence");
            assertTrue(new ImageCacheMaintenance(db,config.getDataDir()).cleanWebOcrIndex()>0);
            assertEquals(images,count(db,"SELECT COUNT(*) FROM rag_image_occurrence"));
            assertEquals(1,count(db,"SELECT COUNT(*) FROM rag_content_block WHERE kind='IMAGE_OCR'"));
            assertEquals(1,count(db,"SELECT COUNT(*) FROM rag_content_chunk WHERE id='pdf/ocr/chunk/0'"));store.validate();
        }finally{db.shutdown();}
    }
    @Test void webOcrIsCachedButPurposeAndStepBelongToEachOccurrence()throws Exception {
        var config=config();var db=new DatabaseBuilder(config);db.initDatabase();
        try(var embedder=new EmbeddingService(null)) {
            var enricher=new WebImageEnricher(image->{ocrCalls.incrementAndGet();return new LocalOcrEngine.Result("UPGRADE SLOT 安装升级芯片","OK","zh-Hans");});
            var builder=new KnowledgeBuilder(config,db,embedder,List.of(new UrlBuildProcessor(web(),null,enricher)));
            var request=new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.URL,"https://example.com/guide")),BuildRequest.Mode.APPEND);
            var result=builder.build(RuntimeBuildFixtures.withSnapshot(request,config.getDataDir()),null);assertEquals(1,ocrCalls.get());assertEquals(1,result.webOcrImages);assertTrue(result.webOcrChunks>=2);
            assertEquals(2,count(db,"SELECT COUNT(*) FROM rag_image_grounding"));assertEquals(1,count(db,"SELECT COUNT(*) FROM rag_image_analysis"));
            assertTrue(count(db,"SELECT COUNT(*) FROM rag_vectors WHERE chunk_id LIKE '%/ocr/chunk/%'")>=2,"OCR vectors must survive native web index replacement");
            String document=BuildRequest.fingerprint("https://example.com/guide");var index=new GuideImageIndex(db);
            assertEquals("STRUCTURE",index.find(document+"/image/0").purpose());assertEquals("UI",index.find(document+"/image/1").purpose());
            assertTrue(index.find(document+"/image/0").stepText().contains("第1步"));assertTrue(index.find(document+"/image/1").stepText().contains("第2步"));
            int before=fetches.get();String fingerprint=db.contentFingerprint();builder.build(RuntimeBuildFixtures.withSnapshot(request,config.getDataDir()),null);
            assertEquals(before,fetches.get());assertEquals(1,ocrCalls.get());assertEquals(fingerprint,db.contentFingerprint());
            new KnowledgeContentStore(db).validate();
        }finally{db.shutdown();}
    }
    @Test void appendBackfillsDeferredOcrWithoutFetchingOrReembeddingNativeText()throws Exception {
        var config=config();var db=new DatabaseBuilder(config);db.initDatabase();
        try(var embedder=new EmbeddingService(null)) {
            var builder=new KnowledgeBuilder(config,db,embedder,List.of(new UrlBuildProcessor(web(),null,new WebImageEnricher(image->{ocrCalls.incrementAndGet();return new LocalOcrEngine.Result("傀儡升级芯片安装位置","OK","zh-Hans");}))));
            var request=new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.URL,"https://example.com/guide")),BuildRequest.Mode.APPEND);
            System.setProperty("maa.web.maxOcrImages","0");builder.build(RuntimeBuildFixtures.withSnapshot(request,config.getDataDir()),null);int fetched=fetches.get();long before=count(db,"SELECT COUNT(*) FROM rag_vectors WHERE chunk_id NOT LIKE '%/ocr/chunk/%'");
            System.clearProperty("maa.web.maxOcrImages");var result=builder.build(RuntimeBuildFixtures.withSnapshot(request,config.getDataDir()),null);
            assertEquals(fetched,fetches.get());assertEquals(1,ocrCalls.get());assertTrue(result.addedEmbeddings>0);assertEquals(before,count(db,"SELECT COUNT(*) FROM rag_vectors WHERE chunk_id NOT LIKE '%/ocr/chunk/%'"));
            String fingerprint=db.contentFingerprint();builder.build(RuntimeBuildFixtures.withSnapshot(request,config.getDataDir()),null);assertEquals(fingerprint,db.contentFingerprint());
        }finally{db.shutdown();}
    }
    @Test void unavailableOcrPreservesUsableImagesWithoutInventingText()throws Exception {
        var config=config();var db=new DatabaseBuilder(config);db.initDatabase();
        try(var embedder=new EmbeddingService(null)) {
            var builder=new KnowledgeBuilder(config,db,embedder,List.of(new UrlBuildProcessor(web(),null,new WebImageEnricher(image->new LocalOcrEngine.Result("","UNAVAILABLE","")))));
            var result=builder.build(RuntimeBuildFixtures.withSnapshot(new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.URL,"https://example.com/guide"))),config.getDataDir()),null);
            assertEquals(2,result.webImages);assertEquals(0,result.webOcrChunks);assertEquals(1,count(db,"SELECT COUNT(*) FROM rag_image_analysis WHERE ocr_status='OCR_UNAVAILABLE'"));assertEquals(2,count(db,"SELECT COUNT(*) FROM rag_image_grounding"));
        }finally{db.shutdown();}
    }
    @Test void cancelledOcrNeverPublishesPartialSource()throws Exception {
        var config=config();var db=new DatabaseBuilder(config);db.initDatabase();new VectorStore(db,384);String before=db.contentFingerprint();
        try(var embedder=new EmbeddingService(null)) {
            var builder=new KnowledgeBuilder(config,db,embedder,List.of(new UrlBuildProcessor(web(),null,new WebImageEnricher(image->{throw new java.util.concurrent.CancellationException("cancelled");}))));
            assertThrows(java.util.concurrent.CancellationException.class,()->builder.build(RuntimeBuildFixtures.withSnapshot(new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.URL,"https://example.com/guide"))),config.getDataDir()),null));assertEquals(before,db.contentFingerprint());
        }finally{db.shutdown();}
    }
    @Test void numericGridNoiseDoesNotBecomeSearchableMechanics()throws Exception {
        var config=config();var db=new DatabaseBuilder(config);db.initDatabase();
        try(var embedder=new EmbeddingService(null)) {
            var builder=new KnowledgeBuilder(config,db,embedder,List.of(new UrlBuildProcessor(web(),null,new WebImageEnricher(image->new LocalOcrEngine.Result("0 / 0 070\n0 + 0","OK","zh-Hans")))));
            var result=builder.build(RuntimeBuildFixtures.withSnapshot(new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.URL,"https://example.com/guide"))),config.getDataDir()),null);
            assertEquals(2,result.webImages);assertEquals(0,result.webOcrChunks);assertEquals(1,count(db,"SELECT COUNT(*) FROM rag_image_analysis WHERE ocr_status='OCR_LOW_SIGNAL'"));
            assertEquals(0,count(db,"SELECT COUNT(*) FROM rag_vectors WHERE chunk_id LIKE '%/ocr/chunk/%'"));
        }finally{db.shutdown();}
    }
    @Test void legacyInlinePositionsComeFromSharedCacheOutsideStagingWithoutNetwork()throws Exception {
        var config=config();var db=new DatabaseBuilder(config);db.initDatabase();byte[] image=png();
        var network=new AtomicInteger();
        var scraper=new WikiScraperService(){@Override public Resource fetchResource(String url,String referer,boolean picture,Map<String,String> headers) {
            network.incrementAndGet();if(url.endsWith(".png"))return new Resource(200,image,"image/png",url,null,null);
            return new Resource(200,"<article><h1>傀儡</h1><p>先制作胚料。<img src='one.png'>然后放入切石机切为部件。<img src='two.png'></p></article>".getBytes(StandardCharsets.UTF_8),"text/html",url,null,null);
        }};
        try(var embedder=new EmbeddingService(null)) {
            var processor=new UrlBuildProcessor(scraper,null,new WebImageEnricher(i->new LocalOcrEngine.Result("切石机部件","OK","zh-Hans")));
            var builder=new KnowledgeBuilder(config,db,embedder,List.of(processor),scraper);var source=BuildRequest.Source.of(BuildRequest.Kind.URL,"https://example.com/guide");
            var request=new BuildRequest(List.of(source),BuildRequest.Mode.APPEND);builder.build(RuntimeBuildFixtures.withSnapshot(request,config.getDataDir()),null);
            try(var c=db.getConnection();var s=c.createStatement()){s.executeUpdate("UPDATE rag_web_image_reference SET step_text=NULL");s.executeUpdate("DELETE FROM rag_image_grounding");}
            int before=network.get();var result=builder.build(RuntimeBuildFixtures.withSnapshot(request,config.getDataDir()),null);assertEquals(before,network.get());assertEquals(2,result.imageStepBackfills);
            String doc=BuildRequest.fingerprint(source.location());
            assertTrue(new GuideImageIndex(db).find(doc+"/image/0").stepText().contains("先制作胚料"));
            assertFalse(new GuideImageIndex(db).find(doc+"/image/0").stepText().contains("切石机"));
            assertTrue(new GuideImageIndex(db).find(doc+"/image/1").stepText().contains("切石机"));
        }finally{db.shutdown();}
    }
}
