package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jsoup.Jsoup;
import yagen.waitmydawn.kb.agent.*;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PublicKnowledgeCacheTest {
    @TempDir Path temp;
    private static final String URL="https://www.mcmod.cn/item/398062.html";
    @Test void snapshotSurvivesRestartExpiresAndDoesNotChangeBaseFingerprint() {
        Path path=temp.resolve("base"); var db=DatabaseBuilder.atPath(path); db.initDatabase();
        new VectorStore(db,384);
        String fingerprint=db.contentFingerprint();
        var now=Clock.fixed(Instant.parse("2026-10-11T00:00:00Z"),ZoneOffset.UTC);
        var cache=new PublicKnowledgeCache(db,now);
        var page=new WikiScraperService.FetchedPage(new WikiScraperService.WebPage("水滴鱼",URL,"名称：水滴鱼\n这是一种被动型生物。","mcmod",Map.of()),List.of("alexsmobs:blobfish"));
        cache.put(URL,page);
        assertEquals(fingerprint,db.contentFingerprint()); db.shutdown();
        db=DatabaseBuilder.atPath(path);
        try {
            assertEquals(page,new PublicKnowledgeCache(db,Clock.offset(now,Duration.ofDays(6))).find(URL));
            assertNull(new PublicKnowledgeCache(db,Clock.offset(now,Duration.ofDays(7))).find(URL));
        } finally { db.shutdown(); }
    }
    @Test void newSessionReusesVectorsAndPageWithoutHttpOrDocumentInference() {
        var db=DatabaseBuilder.atPath(temp.resolve("base")); db.initDatabase();
        var embedder=new EmbeddingService(null);
        String body="名称：水滴鱼\n## 生成\n水滴鱼是一种被动型生物，生成在海洋群系中。\n## 操作\n水滴鱼和其它鱼类一样也可以被装在桶里，用粘液球右击后可以在陆地存活。";
        new PublicKnowledgeCache(db).put(URL,new WikiScraperService.FetchedPage(new WikiScraperService.WebPage("水滴鱼",URL,body,"mcmod",Map.of()),List.of("alexsmobs:blobfish")));
        var llm=new RagAgentService(null) { @Override public dev.langchain4j.model.chat.ChatModel getChatModel() { return null; } };
        var url=new UrlAgent(llm,db,embedder);
        var hit=new EntityAgent.AliasHit("水滴鱼","水滴鱼(Blobfish)",URL,"alexsmobs",1);
        try(var first=new IncrementalDB(temp,"rag_data_one",384); var second=new IncrementalDB(temp,"rag_data_two",384)) {
            url.setIncrementalDB(first); var cold=RetrievalTelemetry.begin();
            var a=url.fetchAndRetrieve(List.of(hit),List.of(),List.of("水滴鱼"));
            assertEquals(0,cold.httpRequests); assertTrue(cold.documentEmbeddings>0); RetrievalTelemetry.end();
            url.setIncrementalDB(second); var warm=RetrievalTelemetry.begin();
            var b=url.fetchAndRetrieve(List.of(hit),List.of(),List.of("水滴鱼"));
            assertEquals(0,warm.httpRequests); assertEquals(0,warm.documentEmbeddings); assertTrue(warm.reusedEmbeddings>0);
            assertEquals(a.subQueryResults(),b.subQueryResults());
            assertEquals(first.getVectorStore().count(),second.getVectorStore().count());
        } finally { RetrievalTelemetry.end(); embedder.close(); db.shutdown(); }
    }
    @Test void differentModelProfileCannotReuseCachedVectors() {
        var db=DatabaseBuilder.atPath(temp.resolve("models")); db.initDatabase();
        var chunks=new TextChunker().fromWebPage("网页正文用于稳定查询和不同向量模型的空间隔离验证。","mod","Mod","mcmod",URL);
        var cache=new PublicKnowledgeCache(db);
        var a=new EmbeddingService(null);
        var b=new EmbeddingService(null) { @Override public String profile() { return "other-test-profile"; } };
        try {
            cache.embeddings(chunks,a); var counters=RetrievalTelemetry.begin();
            cache.embeddings(chunks,b); assertEquals(chunks.size(),counters.documentEmbeddings); assertEquals(0,counters.reusedEmbeddings);
        } finally { RetrievalTelemetry.end(); db.shutdown(); }
    }
    @Test void articleExtractionNeverFallsBackToComments() {
        var comment=Jsoup.parse("<title>水滴鱼</title><body><div class='comment'>落魄法师：水滴鱼失压死亡后可以进行荒野仪式。</div></body>");
        assertTrue(WikiScraperService.toWebPage(comment,URL,"mcmod").content().isEmpty());
        var article=Jsoup.parse("<div class='itemname'><div class='name'><h5>水滴鱼</h5></div></div><div class='item-content common-text'><p>水滴鱼在海洋生成，可装进桶里。</p></div><div class='comment'><p>失压死亡后进行荒野仪式。</p></div>");
        String body=WikiScraperService.toWebPage(article,URL,"mcmod").content();
        assertTrue(body.contains("可装进桶里")); assertFalse(body.contains("荒野仪式"));
    }
    @Test void everyWebSectionKeepsItsTitleAndHeading() {
        var chunks=new TextChunker().fromWebPage("名称：撼地龙\n## 生成\n"+"原始洞穴的生成条件。".repeat(35)+"\n## 驯服\n右击喂食之后认主。","alexscaves","Alex's Caves","mcmod",URL);
        assertTrue(chunks.stream().allMatch(c->c.text().startsWith("名称：撼地龙") && c.text().length()<=180));
        assertTrue(chunks.stream().filter(c->c.text().contains("右击喂食")).allMatch(c->c.text().contains("章节：驯服")));
    }
    @Test void rowHeadersDoNotAccumulateIntoQuadraticTableText() {
        StringBuilder html=new StringBuilder("<div><h2>获取</h2><table><tr><th>生物</th><th>掉落物</th></tr>");
        for(int i=0;i<100;i++) html.append("<tr><th>生物").append(i).append("</th><td>龙鳞</td></tr>");
        html.append("</table></div>");
        String body=WikiBodyExtractor.extract(Jsoup.parse(html.toString()).selectFirst("div"));
        assertTrue(body.length()<8000,"行标题不能作为列标题历史反复附加");
        assertTrue(body.contains("生物99 | 龙鳞"));
        assertEquals(100,body.split("表头：",-1).length-1);
    }
}
