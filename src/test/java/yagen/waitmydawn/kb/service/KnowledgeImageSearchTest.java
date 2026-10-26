package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.model.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeImageSearchTest {
    @TempDir Path temp;
    DatabaseBuilder db;
    KnowledgeContentStore store;
    ImageAssetStore assets;
    @BeforeEach void setup() {
        db=DatabaseBuilder.atPath(temp.resolve("base"));db.initDatabase();new VectorStore(db,384);store=new KnowledgeContentStore(db);assets=new ImageAssetStore(temp);
        var source=BuildRequest.Source.of(BuildRequest.Kind.URL,"https://example.com/guide");store.saveSource(source);
        store.saveDocument(new KnowledgeContent.Document("doc",source.id(),"指南",source.location(),"hash","text/html"));
        block("seed",0,"组装","/root/s1/","核心先装配到底座，再放置结构框架并检查部件连接。");
    }
    @AfterEach void close() {db.shutdown();}
    void block(String id,int ordinal,String heading,String lineage,String body) {
        store.saveChunk(new KnowledgeContent.Chunk(id,"doc",body,heading,null,ordinal));
        store.saveBlock(new KnowledgeContent.Block("b"+id,"doc",heading,lineage,ordinal,null,"PARAGRAPH",null,body,"NATIVE"));
        store.linkBlock(id,"b"+id);
    }
    KnowledgeContent.ImageAsset picture(String id,String chunk,String caption,String ocr,String role) throws Exception {
        var asset=assets.put(Files.readAllBytes(Path.of("src/test/resources/web/tiny.webp")));
        var withText=new KnowledgeContent.ImageAsset(asset.id(),asset.relativePath(),asset.mediaType(),asset.width(),asset.height(),ocr,null);
        store.saveImage(withText);store.saveOccurrence(new KnowledgeContent.ImageOccurrence(id,"doc",asset.id(),caption,"https://images.example/"+id+".webp",null,0));
        store.setOccurrenceMetadata(id,role,"body",null,"OK");store.saveLink(new KnowledgeContent.ChunkImageLink(chunk,id,"same_block"));return withText;
    }
    AnswerArtifacts artifacts() {
        return new AnswerArtifacts("pack","资料",List.of(new AnswerArtifacts.Source("s1","doc","指南","https://example.com/guide","组装",null,"NATIVE","核心先装配到底座，再放置结构框架并检查部件连接。")),List.of());
    }
    KnowledgeRetrievalService.Diagnostics diagnostics() {
        var context=new KnowledgeRetrievalService.ContextDiagnostics("structured-v1",List.of("bseed"),Map.of("bseed","s1"),Map.of("seed","s1"),40,12000,false);
        return new KnowledgeRetrievalService.Diagnostics("核心",null,1,1,Map.of(),List.of(),false,"test",List.of(),List.of(),0,context);
    }
    @Test void ocrIndependentlyRetrievesAnImageOutsideTheSeedButInsideSameChapter() throws Exception {
        block("image",12,"组装","/root/s1/","图示如下，具体操作见图中结构。");
        picture("o1","image","操作示意","CORE ASSEMBLY","ILLUSTRATION");
        var result=new KnowledgeImageSearch(db,temp).search("CORE ASSEMBLY",artifacts(),diagnostics());
        assertEquals(1,result.pictures().size());assertEquals("s1",result.pictures().getFirst().sourceId());
        assertTrue(result.pictures().getFirst().context().contains("OCR识别：CORE ASSEMBLY"));
        assertEquals("SECTION",result.audit().candidates().getFirst().relation());
        assertTrue(result.audit().candidates().getFirst().ocrScore()>0);
    }
    @Test void identicalHeadingInAnotherBranchCannotDonateItsPicture() throws Exception {
        block("wrong",1,"组装","/root/s2/","此章节描述其他系统的核心组装。");picture("bad","wrong","核心组装",null,"ILLUSTRATION");
        var result=new KnowledgeImageSearch(db,temp).search("核心组装",artifacts(),diagnostics());
        assertTrue(result.pictures().isEmpty());assertEquals("OUTSIDE_PROVIDED_SECTION",result.audit().candidates().getFirst().reason());
    }
    @Test void pendingDownloadCacheLossAndNoRelevantPictureHaveDifferentStates() throws Exception {
        var search=new KnowledgeImageSearch(db,temp);
        assertEquals("NO_RELEVANT_IMAGE_IN_CONTEXT",search.search("核心",artifacts(),diagnostics()).audit().status());
        store.saveImageReference("pending","doc","https://images.example/pending.webp","核心组装","组装",0,"DEFERRED",null);
        var pending=search.search("核心",artifacts(),diagnostics());
        assertEquals("RELEVANT_NOT_DOWNLOADED",pending.audit().status());assertEquals(1,pending.audit().pending());
        var asset=picture("o1","seed","核心组装",null,"ILLUSTRATION");
        Files.delete(AnswerMedia.resolveImage(temp,new AnswerArtifacts.Picture("i",asset.id(),asset.relativePath(),"核心","s1","ILLUSTRATION",List.of(),"")));
        var missing=search.search("核心",artifacts(),diagnostics());
        assertTrue(missing.pictures().isEmpty());assertEquals(1,missing.repairs().size());
        assertEquals(asset.id(),missing.repairs().getFirst().assetId());
        assertTrue(missing.audit().candidates().stream().anyMatch(c -> c.state().equals("CACHE_MISSING")));
    }
    @Test void unrelatedCaptionsAndPagePreviewsAreNotForcedAndAssetsAreDeduplicated() throws Exception {
        picture("bad","seed","无关植物的叶片",null,"ILLUSTRATION");
        assertTrue(new KnowledgeImageSearch(db,temp).search("核心组装",artifacts(),diagnostics()).pictures().isEmpty());
        picture("preview","seed","核心组装",null,"PAGE_PREVIEW");
        assertTrue(new KnowledgeImageSearch(db,temp).search("核心组装",artifacts(),diagnostics()).pictures().isEmpty());
        picture("good1","seed","核心组装",null,"ILLUSTRATION");picture("good2","seed","核心组装",null,"ILLUSTRATION");
        var result=new KnowledgeImageSearch(db,temp).search("核心组装",artifacts(),diagnostics());
        assertEquals(1,result.pictures().size());assertTrue(result.audit().candidates().stream().anyMatch(c -> c.reason().equals("DUPLICATE_ASSET")));
    }
    @Test void recordedSectionImageCanBeFetchedWithBudgetWithoutChangingPublishedKnowledge() throws Exception {
        block("image",12,"组装","/root/s1/","核心组装参考图。");
        store.saveImageReference("ref","doc","https://images.example/section.webp","核心组装","组装",12,"DEFERRED",null);
        var calls=new AtomicInteger();byte[] bytes=Files.readAllBytes(Path.of("src/test/resources/web/tiny.webp"));
        var scraper=new WikiScraperService() {
            @Override public Resource fetchResource(String url,String referer,boolean image,Map<String,String> headers) {
                calls.incrementAndGet();return new Resource(200,bytes,"image/webp",url,null,null);
            }
        };
        var hydrator=new KnowledgeImageHydrator(db,temp,scraper);String fingerprint=db.contentFingerprint();
        var hit=new VectorStore.SearchResult("seed","核心组装",null,"指南","web",1,"https://example.com/guide");
        var result=hydrator.hydrate("核心组装",List.of(hit),List.of(),Map.of("seed","s1"));
        assertEquals(1,calls.get());assertEquals(1,result.size());assertEquals("s1",result.getFirst().sourceId());
        assertEquals(fingerprint,db.contentFingerprint());assertEquals(1,hydrator.audit().resolved());
    }
    @Test void failedSectionDownloadsRemainMissingAndExposeFailureCounts() throws Exception {
        store.saveImageReference("ref","doc","https://images.example/fail.webp","核心组装","组装",0,"FAILED",null);
        var scraper=new WikiScraperService() {
            @Override public Resource fetchResource(String url,String referer,boolean image,Map<String,String> headers) {return new Resource(404,null,null,url,null,null);}
        };
        var hydrator=new KnowledgeImageHydrator(db,temp,scraper);
        assertTrue(hydrator.hydrate("核心组装",List.of(new VectorStore.SearchResult("seed","核心组装",null,"指南","web",1,"https://example.com/guide")),List.of(),Map.of("seed","s1")).isEmpty());
        assertEquals(1,hydrator.audit().failures());assertEquals("FETCH_INCOMPLETE",hydrator.audit().status());
    }
    @Test void recordedButUnrelatedCaptionNeverConsumesDownloadBudget() {
        store.saveImageReference("bad","doc","https://images.example/leaf.webp","无关植物的叶片","组装",0,"DEFERRED",null);
        var scraper=new WikiScraperService() {
            @Override public Resource fetchResource(String url,String referer,boolean image,Map<String,String> headers) {fail("相邻但明确无关的图注不应下载");return null;}
        };
        var hydrator=new KnowledgeImageHydrator(db,temp,scraper);
        assertTrue(hydrator.hydrate("核心组装",List.of(new VectorStore.SearchResult("seed","核心组装",null,"指南","web",1,"https://example.com/guide")),List.of(),Map.of("seed","s1")).isEmpty());
        assertEquals(0,hydrator.audit().attempts());
    }
    @Test void generatedCaptionsAndGenericRecipeWordsDoNotIllustrateUnknownMechanisms() throws Exception {
        picture("generic","seed","配方指南-第1张图片",null,"ILLUSTRATION");
        store.saveImageReference("pending","doc","https://images.example/recipe.webp","配方指南-第2张图片","组装",0,"DEFERRED",null);
        String query="量子跃迁维护规程，每一步用多少材料，请给出确定配方";
        var result=new KnowledgeImageSearch(db,temp).search(query,artifacts(),diagnostics());
        assertTrue(result.pictures().isEmpty());assertEquals(0,result.audit().pending());
        var scraper=new WikiScraperService() {
            @Override public Resource fetchResource(String url,String referer,boolean image,Map<String,String> headers) {fail("薄弱通用词匹配不能触发下载");return null;}
        };
        assertTrue(new KnowledgeImageHydrator(db,temp,scraper).hydrate(query,List.of(new VectorStore.SearchResult("seed","核心组装需要材料，具体配方如下。",null,"指南","web",1,"https://example.com/guide")),List.of(),Map.of("seed","s1")).isEmpty());
    }
    @Test void bodyMentionDoesNotMakeAnUnrelatedChapterIllustrateTheTopic() throws Exception {
        var source=new AnswerArtifacts.Source("s1","doc","流程攻略","https://example.com/guide","磁力采石场",null,"NATIVE","傀儡组装升级可以提高开采效率，这一段仍主要描述采石场。");
        var artifacts=new AnswerArtifacts("pack","资料",List.of(source),List.of());
        picture("o1","seed","流程攻略-第1张图片",null,"ILLUSTRATION");
        var result=new KnowledgeImageSearch(db,temp).search("傀儡组装升级",artifacts,diagnostics());
        assertTrue(result.pictures().isEmpty());assertEquals("WEAK_TOPIC_MATCH",result.audit().candidates().getFirst().reason());
    }
}
