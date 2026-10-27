package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.config.*;
import yagen.waitmydawn.kb.model.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SourceLifecycleWebTest {
    @TempDir Path temp;
    AppConfig config;DatabaseBuilder db;EmbeddingService embedding;WikiScraperService scraper;
    final Map<String,String> pages=new HashMap<>();int network;boolean cancelTutorial;
    final String main="https://www.mcmod.cn/modpack/737.html",a="https://www.mcmod.cn/post/101.html",b="https://www.mcmod.cn/post/102.html",c="https://www.mcmod.cn/post/103.html";
    String root(String links){return "<div class='class-title'><h3>测试整合包</h3></div><div class='class-text common-text'>整合包主页。"+links+"</div>";}
    String post(String text){return "<div class='postname'><h1>傀儡教程</h1></div><div class='post-content common-text'>"+text+"</div>";}
    BuildRequest.Source source(String url){return BuildRequest.Source.of(BuildRequest.Kind.URL,url);}
    @BeforeEach void init()throws Exception {
        config=AppConfig.forIsolatedRun(false);config.setDataDir(temp.resolve("data"));db=new DatabaseBuilder(config);db.initDatabase();embedding=new EmbeddingService(null);
        pages.put(main,root("<a href='/post/101.html'>组装</a><a href='/post/102.html'>装备</a>"));pages.put(a,post("傀儡的旧攻略需要铜锭。"));pages.put(b,post("傀儡装备教程需要铁剑。"));
        scraper=new WikiScraperService(){@Override public Resource fetchResource(String url,String referer,boolean image,Map<String,String> headers){network++;if(cancelTutorial&&url.equals(a))throw new java.util.concurrent.CancellationException("cancelled tutorial");String html=pages.get(url);return new Resource(html==null?503:200,html==null?null:html.getBytes(StandardCharsets.UTF_8),"text/html",url,"version",null);}};
        new KnowledgeBuilder(config,db,embedding,null,scraper).build(new BuildRequest(List.of(source(main))),null);
    }
    @AfterEach void close(){embedding.close();db.shutdown();LogSetup.shutdown();}
    SourceLifecycleService manager(){return new SourceLifecycleService(config,db,embedding,null,scraper);}
    @Test void refreshBypassesFreshCacheAndUpdatesTutorialMembershipWithoutRemovingOtherOwners()throws Exception {
        var cache=new PublicKnowledgeCache(db);cache.put(a,new WikiScraperService.FetchedPage(new WikiScraperService.WebPage("旧页面",a,"旧页面正文。","mcmod",Map.of()),List.of()));
        String unrelated="https://example.com/unrelated";cache.put(unrelated,new WikiScraperService.FetchedPage(new WikiScraperService.WebPage("其他页面",unrelated,"其他来源的缓存。","web",Map.of()),List.of()));
        new KnowledgeBuilder(config,db,embedding,null,scraper).build(new BuildRequest(List.of(source(b)),BuildRequest.Mode.APPEND),null);
        pages.put(main,root("<a href='/post/101.html'>组装</a><a href='/post/103.html'>升级</a>"));pages.put(a,post("傀儡的新攻略需要金锭。"));pages.put(c,post("傀儡升级需要芯片。"));int before=network;
        var service=manager();assertEquals(1,service.preview(source(main).id(),null,SourceLifecycleService.Operation.REFRESH,null).sharedDocuments());
        service.apply(service.preview(source(main).id(),null,SourceLifecycleService.Operation.REFRESH,null),null);
        assertEquals(before+3,network);var store=new SourceLifecycleStore(db);assertEquals(3,store.documents(source(main).id()).size());assertTrue(store.documents(source(b).id()).contains(BuildRequest.fingerprint(b)));assertFalse(store.documents(source(main).id()).contains(BuildRequest.fingerprint(b)));
        assertNull(cache.find(a));assertNotNull(cache.find(unrelated));
        var text=store.strings("SELECT content FROM rag_content_chunk ORDER BY id");assertTrue(text.stream().anyMatch(t->t.contains("新攻略需要金锭")));assertFalse(text.stream().anyMatch(t->t.contains("旧攻略需要铜锭")));new KnowledgeContentStore(db).validate();
    }
    @Test void parserRebuildUsesCachedHtmlWithoutNetworkAndSinglePageIsBounded()throws Exception {
        int before=network;pages.clear();var service=manager();String doc=BuildRequest.fingerprint(a);
        service.apply(service.preview(source(main).id(),doc,SourceLifecycleService.Operation.REPARSE,null),null);assertEquals(before,network);assertEquals(3,new SourceLifecycleStore(db).documents(source(main).id()).size());
    }
    @Test void partialTutorialFailureRollsBackWholeRefresh() {
        var cache=new PublicKnowledgeCache(db);cache.put(a,new WikiScraperService.FetchedPage(new WikiScraperService.WebPage("已发布旧页面",a,"旧页面缓存应保留。","mcmod",Map.of()),List.of()));
        String before=db.contentFingerprint();pages.put(main,root("<a href='/post/101.html'>组装</a><a href='/post/103.html'>升级</a>"));pages.put(a,post("不应发布的新正文。"));
        var service=manager();assertThrows(IllegalStateException.class,()->service.apply(service.preview(source(main).id(),null,SourceLifecycleService.Operation.REFRESH,null),null));assertEquals(before,db.contentFingerprint());assertNotNull(cache.find(a));
    }
    @Test void cancellationAfterMainPageUpdateDoesNotPublishPartialRefresh() {
        String before=db.contentFingerprint();cancelTutorial=true;pages.put(main,root("未发布的新主页正文。<a href='/post/101.html'>组装</a>"));
        var service=manager();assertThrows(java.util.concurrent.CancellationException.class,()->service.apply(service.preview(source(main).id(),null,SourceLifecycleService.Operation.REFRESH,null),null));assertEquals(before,db.contentFingerprint());
    }
}
