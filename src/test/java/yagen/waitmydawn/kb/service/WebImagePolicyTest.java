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
    @Test void facetFallbackPrefersSectionAndCaptionOverUnrelatedLargeImages()throws Exception {
        var db=DatabaseBuilder.atPath(temp.resolve("facet"));db.initDatabase();
        try(var c=db.getConnection()) {
            block(c,"b10","d1",10,"傀儡的升级与装备","番外篇 > 傀儡使者 > 傀儡的升级与装备","升级：在铁砧上打出升级，并用扩充模版增加槽位。");
            reference(c,"d1/image/1","d1","https://example.com/upgrade.png","辅助升级：多才多艺","辅助升级：多才多艺","EXCLUDED_SMALL",10);
            reference(c,"d1/image/2","d1","https://example.com/armor.png","铁木傀儡护甲","","AVAILABLE",10);
            asset(c,"a2","256",256,256);occurrence(c,"d1/image/2","d1","a2","铁木傀儡护甲",10);
        }
        var artifacts=new AnswerArtifacts(null,null,List.of(new AnswerArtifacts.Source("s1","d1","指南","https://example.com/guide","傀儡的升级与装备",null,"NATIVE","先组装，再升级。")),List.of());
        var fallbacks=WebImagePolicy.facetFallbacks(db,artifacts,"龙之冒险如何组装并升级傀儡",List.of("升级"),1);
        assertEquals(1,fallbacks.size());assertEquals("d1/image/1",fallbacks.getFirst().referenceId());assertFalse(fallbacks.getFirst().cached());
        assertTrue(fallbacks.getFirst().score()>3.0,Double.toString(fallbacks.getFirst().score()));
        db.shutdown();
    }
    @Test void pageFallbackUsesLargestCachedPageImageWhenNoTextMatches()throws Exception {
        var db=DatabaseBuilder.atPath(temp.resolve("page"));db.initDatabase();
        try(var c=db.getConnection()) {
            block(c,"b5","d2",5,"画廊","画廊","表格行：");
            reference(c,"d2/image/1","d2","https://example.com/icon.png","","","AVAILABLE",5);
            reference(c,"d2/image/2","d2","https://example.com/shot.png","","","AVAILABLE",5);
            asset(c,"a1","11",256,256);occurrence(c,"d2/image/1","d2","a1","",5);
            asset(c,"a2","22",1000,520);occurrence(c,"d2/image/2","d2","a2","",5);
        }
        var artifacts=new AnswerArtifacts(null,null,List.of(new AnswerArtifacts.Source("s1","d2","灾变","https://example.com/mod","模组总览",null,"NATIVE","灾变是一个Boss模组。")),List.of());
        var fallbacks=WebImagePolicy.pageFallbacks(db,artifacts,"灾变模组有哪些主要 BOSS？请结合网页资料的相关插图介绍。",1);
        assertEquals(1,fallbacks.size());assertEquals("d2/image/2",fallbacks.getFirst().referenceId());
        assertTrue(WebImagePolicy.pageFallbacks(db,artifacts,"灾变有哪些 BOSS",1).isEmpty());
        db.shutdown();
    }
    @Test void facetFallbackReservesOneCandidatePerMissingStep()throws Exception {
        var db=DatabaseBuilder.atPath(temp.resolve("twofacet"));db.initDatabase();
        try(var c=db.getConnection()) {
            block(c,"b1","d1",10,"如何组装一个傀儡？","番外篇 > 傀儡使者 > 如何组装一个傀儡？","把胚料切成部件，再按图组装。");
            block(c,"b2","d1",20,"傀儡的升级与装备","番外篇 > 傀儡使者 > 傀儡的升级与装备","辅助升级：多才多艺，升级后第一次不消耗槽位。");
            reference(c,"d1/image/1","d1","https://example.com/assembly.png","组装步骤","","AVAILABLE",10);
            reference(c,"d1/image/2","d1","https://example.com/upgrade.png","辅助升级：多才多艺","辅助升级：多才多艺","EXCLUDED_SMALL",20);
            asset(c,"a1","11",640,360);occurrence(c,"d1/image/1","d1","a1","组装步骤",10);
        }
        var artifacts=new AnswerArtifacts(null,null,List.of(new AnswerArtifacts.Source("s1","d1","指南","https://example.com/guide","傀儡",null,"NATIVE","先组装，再升级。")),List.of());
        var fallbacks=WebImagePolicy.facetFallbacks(db,artifacts,"如何组装并升级傀儡",List.of("组装","升级"),2);
        assertEquals(2,fallbacks.size());
        assertEquals(java.util.Set.of("d1/image/1","d1/image/2"),fallbacks.stream().map(WebImagePolicy.Fallback::referenceId).collect(java.util.stream.Collectors.toSet()));
        db.shutdown();
    }

    @Test void fallbackPictureFetchesSourceIconWithoutPromotingReferenceStatus()throws Exception {
        var db=DatabaseBuilder.atPath(temp.resolve("icon"));db.initDatabase();
        try(var c=db.getConnection()) {
            block(c,"b10","d1",10,"傀儡的升级与装备","番外篇 > 傀儡使者 > 傀儡的升级与装备","辅助升级：多才多艺，升级后第一次不消耗槽位。");
            reference(c,"d1/image/1","d1","https://example.com/upgrade.png","辅助升级：多才多艺","辅助升级：多才多艺","EXCLUDED_SMALL",10);
        }
        final byte[] png=FullWebMediaTest.image(1,32,32);
        var web=new WikiScraperService(){@Override public Resource fetchResource(String url,String referer,boolean image,Map<String,String> headers){
            return new Resource(200,png,"image/png",url,null,null);
        }};web.setResourceDatabase(db);
        try(var embedding=new EmbeddingService(null)) {
            var service=new WebPageMediaService(db,embedding,temp,web);
            var artifacts=new AnswerArtifacts(null,null,List.of(new AnswerArtifacts.Source("s1","d1","指南","https://example.com/guide","傀儡的升级与装备",null,"NATIVE","先组装，再升级。")),List.of());
            var fallbacks=WebImagePolicy.facetFallbacks(db,artifacts,"如何组装并升级傀儡",List.of("升级"),1);
            var pictures=service.fallbackPictures(fallbacks,artifacts.sources());
            assertEquals(1,pictures.size());assertTrue(pictures.getFirst().caption().contains("来源图标"));
            assertTrue(new ImageAssetStore(temp).available(pictures.getFirst().assetId(),pictures.getFirst().relativePath()));
            assertEquals(1,FullWebMediaTest.count(db,"SELECT COUNT(*) FROM rag_web_image_reference WHERE status='EXCLUDED_SMALL'"));
        }finally{db.shutdown();}
    }

    private static void block(java.sql.Connection c,String id,String doc,int ordinal,String heading,String path,String body)throws Exception {
        try(var p=c.prepareStatement("INSERT INTO rag_content_block(id,document_id,heading_path,lineage,ordinal,kind,content,extraction_method) VALUES(?,?,?,?,?,?,?,?)")) {
            p.setString(1,id);p.setString(2,doc);p.setString(3,heading);p.setString(4,path);p.setInt(5,ordinal);p.setString(6,"PARAGRAPH");p.setString(7,body);p.setString(8,"NATIVE");p.executeUpdate();
        }
    }
    private static void reference(java.sql.Connection c,String id,String doc,String url,String caption,String step,String status,int ordinal)throws Exception {
        try(var p=c.prepareStatement("INSERT INTO rag_web_image_reference(id,document_id,url,caption,heading,block_ordinal,status,step_text) VALUES(?,?,?,?,?,?,?,?)")) {
            p.setString(1,id);p.setString(2,doc);p.setString(3,url);p.setString(4,caption);p.setString(5,"");p.setInt(6,ordinal);p.setString(7,status);p.setString(8,step);p.executeUpdate();
        }
    }
    private static void asset(java.sql.Connection c,String id,String path,int width,int height)throws Exception {
        try(var p=c.prepareStatement("INSERT INTO rag_image_asset(id,relative_path,media_type,width,height) VALUES(?,?,?,?,?)")) {
            p.setString(1,id);p.setString(2,path+".png");p.setString(3,"image/png");p.setInt(4,width);p.setInt(5,height);p.executeUpdate();
        }
    }
    private static void occurrence(java.sql.Connection c,String id,String doc,String image,String caption,int ordinal)throws Exception {
        try(var p=c.prepareStatement("INSERT INTO rag_image_occurrence(id,document_id,image_id,caption,ordinal,role,analysis_status) VALUES(?,?,?,?,?,?,?)")) {
            p.setString(1,id);p.setString(2,doc);p.setString(3,image);p.setString(4,caption);p.setInt(5,ordinal);p.setString(6,"ILLUSTRATION");p.setString(7,"CONTEXT_ONLY");p.executeUpdate();
        }
    }

    @Test void urlOverviewUsesRetrievedPageTitleButStillRequiresBoundContext()throws Exception {
        var db=DatabaseBuilder.atPath(temp.resolve("overview"));db.initDatabase();
        var web=new WikiScraperService(){@Override public Resource fetchResource(String url,String referer,boolean image,Map<String,String> headers){if(image)throw new AssertionError("Selection must not download");return new Resource(200,"<article><p>模组概览<img src='/overview.png' alt='示例模组-第1张图片'></p></article>".getBytes(java.nio.charset.StandardCharsets.UTF_8),"text/html",url,null,null);}};web.setResourceDatabase(db);
        try(var embedding=new EmbeddingService(null)) {
            new WebPageMediaService(db,embedding,temp,web).acquire("https://example.com/mod");String doc,id,body;
            try(var c=db.getConnection();var s=c.createStatement();var rows=s.executeQuery("SELECT r.document_id,r.id,b.content FROM rag_web_image_reference r JOIN rag_content_block b ON b.document_id=r.document_id AND b.ordinal=r.block_ordinal")){assertTrue(rows.next());doc=rows.getString(1);id=rows.getString(2);body=rows.getString(3);}
            var source=new AnswerArtifacts.Source("s",doc,"示例模组","https://example.com/mod","正文",null,"WEB",body);var artifacts=new AnswerArtifacts(null,null,List.of(source),List.of());
            assertEquals(Set.of(id),WebImagePolicy.select(db,artifacts,"请读取 https://example.com/mod ，说明这个模组是什么，配一张相关的图。"));
            var unbound=new AnswerArtifacts.Source("s",doc,"示例模组","https://example.com/mod","正文",null,"WEB","未提供该段落");assertTrue(WebImagePolicy.select(db,new AnswerArtifacts(null,null,List.of(unbound),List.of()),"请读取 https://example.com/mod ，说明这个模组是什么，配一张相关的图。").isEmpty());
        }finally{db.shutdown();}
    }
}
