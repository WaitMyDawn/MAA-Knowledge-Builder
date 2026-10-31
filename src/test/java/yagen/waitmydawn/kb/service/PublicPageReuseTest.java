package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import yagen.waitmydawn.kb.model.*;
import static org.junit.jupiter.api.Assertions.*;

class PublicPageReuseTest {
    @TempDir Path temp;
    @AfterEach void telemetry(){RetrievalTelemetry.end();}
    @Test void normalUrlAgentUsesPublicStructuredChunksAcrossSessions()throws Exception {
        var db=DatabaseBuilder.atPath(temp.resolve("agent"));db.initDatabase();new VectorStore(db,384);String url="https://www.mcmod.cn/item/90000001.html";String text="安装核心后连接组件，右键启动机器并等待加工完成。";
        var web=new WikiScraperService(){@Override public Resource fetchResource(String path,String referer,boolean image,Map<String,String> headers){return new Resource(200,("<div class='item-content common-text'><p>"+text+"</p></div>").getBytes(java.nio.charset.StandardCharsets.UTF_8),"text/html",path,null,null);}};web.setResourceDatabase(db);web.fetchBuildResource(url,null,false);
        new PublicKnowledgeCache(db).put(url,new WikiScraperService.FetchedPage(new WikiScraperService.WebPage("核心",url,text,"mcmod",Map.of()),List.of("demo:core")));
        try(var embedding=new EmbeddingService(null);var first=new IncrementalDB(temp,"rag_data_first",384);var second=new IncrementalDB(temp,"rag_data_second",384)) {
            var llm=new RagAgentService(null){@Override public dev.langchain4j.model.chat.ChatModel getChatModel(){return null;}};
            var agent=new yagen.waitmydawn.kb.agent.UrlAgent(llm,db,embedding);agent.setMediaDirectory(temp);agent.setIncrementalDB(first);
            var hit=new yagen.waitmydawn.kb.agent.EntityAgent.AliasHit("核心如何安装","核心",url,"demo",1);
            RetrievalTelemetry.begin();var a=agent.fetchAndRetrieve(List.of(hit),List.of(),List.of("核心如何安装"));assertFalse(a.subQueryResults().isEmpty());assertEquals(0,RetrievalTelemetry.current().httpRequests);
            agent.setIncrementalDB(second);RetrievalTelemetry.begin();var b=agent.fetchAndRetrieve(List.of(hit),List.of(),List.of("核心如何安装"));assertEquals(a.subQueryResults(),b.subQueryResults());assertEquals(0,RetrievalTelemetry.current().documentEmbeddings);assertEquals(0,RetrievalTelemetry.current().httpRequests);assertEquals(0,first.getVectorStore().count());assertEquals(0,second.getVectorStore().count());
        }finally{db.shutdown();}
    }
    @Test void structuredPageSurvivesNewSessionAndDatabaseReopenWithoutNetworkOrEmbedding()throws Exception {
        Path file=temp.resolve("base");var db=DatabaseBuilder.atPath(file);db.initDatabase();new VectorStore(db,384);var calls=new AtomicInteger();String url="https://example.com/guide";
        var web=new WikiScraperService(){@Override public Resource fetchResource(String path,String referer,boolean image,Map<String,String> headers){calls.incrementAndGet();assertFalse(image);return new Resource(200,"<article><h1>核心组装</h1><p>安装核心后连接组件，右键启动机器。<img src='/core.png' alt='核心安装位置'></p></article>".getBytes(java.nio.charset.StandardCharsets.UTF_8),"text/html",path,null,null);}};web.setResourceDatabase(db);
        List<String> ids;
        try(var embedding=new EmbeddingService(null)) {
            var service=new WebPageMediaService(db,embedding,temp,web);var rows=service.acquirePage(url,"demo","示例模组","安装核心后连接组件");assertFalse(rows.isEmpty());assertEquals(1,calls.get());assertTrue(rows.stream().allMatch(r->"demo".equals(r.modId())));ids=rows.stream().map(TextChunker.TextChunk::id).toList();
            RetrievalTelemetry.begin();var again=new WebPageMediaService(db,embedding,temp,web).acquirePage(url,"demo","示例模组","安装核心后连接组件");assertEquals(ids,again.stream().map(TextChunker.TextChunk::id).toList());assertEquals(1,calls.get());assertEquals(0,RetrievalTelemetry.current().documentEmbeddings);assertEquals(1,RetrievalTelemetry.work().get("page.index.reused").count()/rows.size());
            var incremental=new IncrementalKnowledgeService(db);var hits=again.stream().map(r->new VectorStore.SearchResult(r.id(),r.text(),r.modId(),r.modName(),r.type(),1,r.sourcePath())).toList();assertEquals(rows.size(),incremental.pageEmbeddings(hits,embedding.profile()).size());
            try(var c=db.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM rag_vectors")){r.next();assertEquals(ids.size(),r.getInt(1));}
            var legacy=DatabaseBuilder.atPath(temp.resolve("legacy-session"));legacy.initDatabase();
            try{var old=List.of(new TextChunker.TextChunk("old","安装核心后连接组件","demo","示例","mcmod",url),new TextChunker.TextChunk("other","安装核心后连接组件","demo","示例","mcmod","https://example.com/other"));new VectorStore(legacy,384).storeBatch(old,embedding);
                var found=VectorStore.searchScoped(List.of(db,legacy),embedding.embed("安装核心"),20,embedding.profile(),"demo",Set.of());assertFalse(found.stream().anyMatch(h->h.chunkId().equals("old")));assertTrue(found.stream().anyMatch(h->h.chunkId().equals("other")));
            }finally{legacy.shutdown();}

        }finally{db.shutdown();}
        var reopened=DatabaseBuilder.atPath(file);reopened.initDatabase();var offline=new WikiScraperService(){@Override public Resource fetchResource(String p,String referer,boolean image,Map<String,String> headers){throw new AssertionError("Reopen must reuse indexed knowledge");}};offline.setResourceDatabase(reopened);
        try(var embedding=new EmbeddingService(null)) {
            RetrievalTelemetry.begin();var rows=new WebPageMediaService(reopened,embedding,temp,offline).acquirePage(url,"demo","示例模组","安装核心后连接组件");assertEquals(ids,rows.stream().map(TextChunker.TextChunk::id).toList());assertEquals(0,RetrievalTelemetry.current().documentEmbeddings);
            var hits=VectorStore.searchScoped(List.of(reopened),embedding.embed("安装核心"),10,embedding.profile(),"demo",Set.of());assertTrue(hits.stream().anyMatch(h->ids.contains(h.chunkId())));
        }finally{reopened.shutdown();}
    }
}
