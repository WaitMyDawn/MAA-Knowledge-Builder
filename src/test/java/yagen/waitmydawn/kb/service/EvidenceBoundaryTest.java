package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.agent.*;
import yagen.waitmydawn.kb.model.*;
import java.nio.file.Path;
import java.util.*;
import org.jsoup.Jsoup;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceBoundaryTest {
    @TempDir Path temp;
    @Test void vanillaCandidatesUseTheWikiParser(){
        String url="https://wiki.biligame.com/mc/Iron_Golem";
        String html="<title>铁傀儡</title><div id='mw-content-text'><p>使用铁块摆放身体，最后放上雕刻南瓜完成建造。</p></div>";
        assertEquals("mcwiki",UrlAgent.pageSource(url));
        assertTrue(WikiScraperService.toWebPage(Jsoup.parse(html),url,UrlAgent.pageSource(url)).content().contains("最后放上雕刻南瓜"));
        assertTrue(WikiScraperService.toWebPage(Jsoup.parse(html),url,"mcmod").content().isBlank());
    }
    @Test void globalRetrievalExcludesPackOverridesButExplicitPackScopeRetainsThem()throws Exception {
        var db=DatabaseBuilder.atPath(temp.resolve("base"));db.initDatabase();
        try(var embedder=new EmbeddingService(null)){
            var store=new KnowledgeContentStore(db);var vectors=new VectorStore(db,384);
            var pack=BuildRequest.Source.of(BuildRequest.Kind.URL,"https://www.mcmod.cn/modpack/123.html");store.saveSource(pack);store.setSourceScope(pack.id(),"pack:123","MODPACK","样本整合包",null);
            var mod=BuildRequest.Source.of(BuildRequest.Kind.URL,"https://www.mcmod.cn/class/321.html");store.saveSource(mod);store.setSourceScope(mod.id(),"mod:321","MOD","样本模组","sample");
            for(var source:List.of(pack,mod)){
                String id=source.id().substring(0,8);String body=source==pack?"利维坦战斗需要任务奖励的保护十护甲。":"利维坦的战斗需要躲避水雷，使用盾牌抵挡部分攻击。";
                store.saveDocument(new KnowledgeContent.Document(id,source.id(),"利维坦战斗",source.location()+"/detail",BuildRequest.fingerprint(body),"text/html"));store.saveChunk(new KnowledgeContent.Chunk("c"+id,id,body,"战斗",null,0));
                vectors.storeBatch(List.of(new TextChunker.TextChunk("c"+id,body,"sample","样本","web",source.location()+"/detail")),embedder);
            }
            var knowledge=new KnowledgeRetrievalService(db,embedder);
            var global=knowledge.retrieve("利维坦战斗",new KnowledgeRetrievalService.Resolution(null,null,null,false),false);
            assertFalse(global.hits().isEmpty());assertTrue(global.hits().stream().noneMatch(hit->hit.chunkText().contains("任务奖励")));
            var explicit=knowledge.retrieve("利维坦战斗",knowledge.resolve("利维坦战斗","pack:123",null),false);
            assertTrue(explicit.hits().stream().anyMatch(hit->hit.chunkText().contains("任务奖励")));
            assertEquals("pack:123",knowledge.resolve("组装好后如何升级",null,"pack:123").scope().id());
        }finally{db.shutdown();}
    }
}
