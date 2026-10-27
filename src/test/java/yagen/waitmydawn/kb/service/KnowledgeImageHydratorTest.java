package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.model.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeImageHydratorTest {
    @TempDir Path temp;
    @Test void transientOcrPreservesInlineStepAndDoesNotPublishVectors() throws Exception {
        var db=DatabaseBuilder.atPath(temp.resolve("transient"));db.initDatabase();new VectorStore(db,384);
        try {
            var content=new KnowledgeContentStore(db);var source=BuildRequest.Source.of(BuildRequest.Kind.URL,"https://example.com/upgrade");content.saveSource(source);
            content.saveDocument(new KnowledgeContent.Document("doc",source.id(),"傀儡指南",source.location(),"hash","text/html"));
            String step="第2步：将傀儡升级芯片放入菜单的升级槽位。";
            content.saveChunk(new KnowledgeContent.Chunk("seed","doc",step,"傀儡升级",null,0));
            content.saveBlock(new KnowledgeContent.Block("anchor","doc","傀儡升级","/root/upgrade/",0,null,"PARAGRAPH",null,step,"NATIVE"));
            content.linkBlock("seed","anchor");
            content.saveImageReference("correct","doc","https://images.example/upgrade.png","傀儡指南-第1张图片","傀儡升级",0,"DEFERRED",null);
            content.setImageStep("correct",step);
            content.saveImageReference("wrong","doc","https://images.example/assembly.png","傀儡指南-第2张图片","傀儡升级",0,"DEFERRED",null);
            content.setImageStep("wrong","第1步：组装傀儡，将核心放入底座。");
            var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(DocumentImportTest.textImage(),"png",bytes);var calls=new AtomicInteger();
            var scraper=new WikiScraperService(){
                @Override public Resource fetchResource(String url,String referer,boolean image,Map<String,String> headers){assertTrue(url.endsWith("upgrade.png"));return new Resource(200,bytes.toByteArray(),"image/png",url,null,null);}
            };
            LocalOcrEngine ocr=image->{calls.incrementAndGet();return new LocalOcrEngine.Result("升级菜单 升级槽位","OK","zh-Hans");};
            String before=db.contentFingerprint();
            var hydrator=new KnowledgeImageHydrator(db,temp,scraper,ocr);
            var pictures=hydrator.hydrate("傀儡如何升级",List.of(new VectorStore.SearchResult("seed",step,null,"傀儡指南","web",0.9,source.location())),List.of(),Map.of("seed","s1"));
            assertEquals(1,pictures.size());assertEquals(1,calls.get());
            var grounding=pictures.getFirst().grounding();assertEquals("UI",grounding.purpose());assertEquals("OCR",grounding.basis());
            assertEquals("傀儡升级\n"+step,grounding.stepText());assertEquals("INLINE_STEP",grounding.relation());
            assertEquals(before,db.contentFingerprint());
        } finally {db.shutdown();}
    }
    @Test void structuredImageLookupDoesNotFetchAnAdjacentDifferentChapter() throws Exception {
        var db=DatabaseBuilder.atPath(temp.resolve("boundary"));db.initDatabase();
        try {
            var content=new KnowledgeContentStore(db);var source=BuildRequest.Source.of(BuildRequest.Kind.URL,"https://example.com/guide");content.saveSource(source);
            content.saveDocument(new KnowledgeContent.Document("doc",source.id(),"指南",source.location(),"hash","text/html"));
            content.saveChunk(new KnowledgeContent.Chunk("seed","doc","核心组装。","T3 > 组装",null,0));
            content.saveBlock(new KnowledgeContent.Block("anchor","doc","T3 > 组装","/root/s1/s2/",0,null,"PARAGRAPH",null,"核心组装。","NATIVE"));
            content.saveBlock(new KnowledgeContent.Block("image","doc","T4 > 装备","/root/s3/s4/",1,null,"IMAGE",null,"装备图。","ADJACENT"));
            content.linkBlock("seed","anchor");
            content.saveImageReference("bad","doc","https://images.example/wrong.png","错误章节","T4 > 装备",1,"DEFERRED",null);
            var scraper=new WikiScraperService() {
                @Override public Resource fetchResource(String url,String referer,boolean image,Map<String,String> headers) {fail("不能请求相邻但不同章节的图片");return null;}
            };
            var hydrator=new KnowledgeImageHydrator(db,temp,scraper);
            assertTrue(hydrator.hydrate("核心",List.of(new VectorStore.SearchResult("seed","核心组装。",null,"指南","web",0.9,source.location())),List.of(),Map.of("seed","s1")).isEmpty());
        } finally {db.shutdown();}
    }
    @Test void boundedImageFetchUsesRecordedUrlsCacheAndDoesNotMutatePublishedKnowledge() throws Exception {
        var db=DatabaseBuilder.atPath(temp.resolve("base"));db.initDatabase();var vectors=new VectorStore(db,384);
        var embedder=new EmbeddingService(null);var content=new KnowledgeContentStore(db);
        var source=BuildRequest.Source.of(BuildRequest.Kind.URL,"https://example.com/guide");content.saveSource(source);
        content.saveDocument(new KnowledgeContent.Document("doc",source.id(),"傀儡指南",source.location(),BuildRequest.fingerprint("body"),"text/html"));
        content.saveChunk(new KnowledgeContent.Chunk("chunk","doc","傀儡核心组装方法。","组装",null,10000));
        vectors.storeBatch(List.of(new TextChunker.TextChunk("chunk","傀儡核心组装方法。",null,"指南","web",source.location())),embedder);
        for(int i=0;i<4;i++) content.saveImageReference("r"+i,"doc","https://images.example/"+i+".webp","核心组装图","组装",1,"DEFERRED",null);
        byte[] bytes=Files.readAllBytes(Path.of("src/test/resources/web/tiny.webp"));var attempts=new AtomicInteger();
        var scraper=new WikiScraperService() {
            @Override public Resource fetchResource(String url,String referer,boolean image,Map<String,String> headers) {
                assertTrue(url.startsWith("https://images.example/"));assertTrue(image);assertEquals(source.location(),referer);
                attempts.incrementAndGet();return new Resource(200,bytes,"image/webp",url,null,null);
            }
        };
        try {
            String before=db.contentFingerprint();
            var hydrator=new KnowledgeImageHydrator(db,temp,scraper);
            var hit=new VectorStore.SearchResult("chunk","傀儡核心组装方法。",null,"指南","web",0.9,source.location());
            var pictures=hydrator.hydrate("傀儡核心",List.of(hit),List.of(),Map.of("chunk","s1"));
            assertEquals(2,attempts.get());assertEquals(1,pictures.size());
            assertTrue(Files.isRegularFile(AnswerMedia.resolveImage(temp,pictures.getFirst())));
            assertEquals(before,db.contentFingerprint());
            hydrator.hydrate("傀儡核心",List.of(hit),List.of(),Map.of("chunk","s1"));
            assertEquals(2,attempts.get());
            var selected=pictures.getFirst();
            Files.writeString(AnswerMedia.resolveImage(temp,selected),"corrupt");
            var repairs=hydrator.hydrate("傀儡核心",List.of(hit),pictures,Map.of("chunk","s1"));
            assertTrue(repairs.isEmpty());assertTrue(Files.isRegularFile(AnswerMedia.resolveImage(temp,selected)));
            assertEquals(before,db.contentFingerprint());assertEquals(2,attempts.get());
        } finally {db.shutdown();embedder.close();}
    }
}
