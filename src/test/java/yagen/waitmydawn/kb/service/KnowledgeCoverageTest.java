package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.model.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeCoverageTest {
    @TempDir Path temp;
    private KnowledgeRetrievalService.Retrieval sample(String id,String body) {
        var source=new AnswerArtifacts.Source("s"+id,"d","指南","https://example.com/guide","操作",2,"NATIVE",body);
        var hit=new VectorStore.SearchResult(id,body,null,"指南","web",1,source.location());
        return new KnowledgeRetrievalService.Retrieval(List.of(hit),new AnswerArtifacts("pack","资料",List.of(source),List.of()),body,1);
    }
    private KnowledgeRetrievalService.Retrieval combine(List<KnowledgeRetrievalService.Retrieval> items,String query) {
        var sources=items.stream().flatMap(i -> i.artifacts().sources().stream()).distinct().toList();
        var hits=items.stream().flatMap(i -> i.hits().stream()).distinct().toList();
        return new KnowledgeRetrievalService.Retrieval(hits,new AnswerArtifacts("pack","资料",sources,List.of()),"",1);
    }
    @Test void missingUpgradeTriggersFocusedSearchThenChecksActualMergedContext() {
        var initial=sample("a","傀儡组装时先加工各个零件，再安装核心并检查部件连接。");
        var queries=new ArrayList<String>();
        var result=KnowledgeCoverage.run("第2页如何组装并升级傀儡",initial,q -> {
            queries.add(q);assertTrue(q.contains("第2页"));assertTrue(q.contains("傀儡"));assertTrue(q.contains("升级"));
            return sample("b","升级前先扩充槽位，然后右键安装对应的升级模块并确认属性。");
        },this::combine);
        assertEquals(1,queries.size());assertEquals("CANDIDATES_FOR_ALL_FACETS",result.audit().stopReason());
        assertEquals(List.of("b"),result.audit().attempts().getFirst().addedChunkIds());
        assertTrue(result.audit().finalChecks().stream().allMatch(c -> !c.sourceIds().isEmpty()));
    }
    @Test void noProgressStopsAndNeverInventsEvidence() {
        var initial=sample("a","傀儡组装时先加工各个零件，再安装核心并检查部件连接。");
        var calls=new AtomicInteger();
        var result=KnowledgeCoverage.run("组装并升级傀儡",initial,q -> {calls.incrementAndGet();return initial;},this::combine);
        assertEquals(1,calls.get());assertEquals("NO_NEW_SEEDS",result.audit().stopReason());
        assertTrue(result.audit().finalChecks().stream().anyMatch(c -> c.facet().equals("升级") && c.sourceIds().isEmpty()));
    }
    @Test void genericCommonWordCannotHideAnUnknownCompoundTopic() {
        var initial=sample("a","傀儡组装以后先安装核心，再扩充槽位并安装升级模块。维护时应检查连接部件。");
        var result=KnowledgeCoverage.run("组装并升级傀儡以及量子跃迁维护规程",initial,q -> initial,this::combine);
        assertEquals("NO_NEW_SEEDS",result.audit().stopReason());
        assertEquals(1,result.audit().attempts().size());
        assertTrue(result.audit().finalChecks().stream().anyMatch(c -> c.facet().equals("量子跃迁维护规程") && c.sourceIds().isEmpty()));
    }
    @Test void queriesAreBoundedEvenWhenEverySearchAddsIrrelevantSeeds() {
        var initial=sample("a","当前摘录只描述欢迎信息，并没有说明任何具体操作方法。");
        var calls=new AtomicInteger();
        var result=KnowledgeCoverage.run("护甲装备+武器+饰品推荐",initial,q -> sample("x"+calls.incrementAndGet(),"这段原文讨论完全不同的天气变化，无法证明用户所问规则。"),this::combine);
        assertEquals(6,calls.get());assertEquals(2,result.audit().attempts().getLast().round());
        assertEquals(3,result.audit().finalChecks().stream().filter(c -> c.sourceIds().isEmpty()).count());
    }
    @Test void checklistDoesNotUseMetadataOrHeadingAsProofAndDoesNotRequireExtraCallsWhenCovered() {
        var facets=KnowledgeCoverage.plan("组装并升级傀儡",AnswerArtifacts.empty());
        var source=new AnswerArtifacts.Source("s","d","升级与组装","file","升级",null,"METADATA_ONLY","组装并升级傀儡的文件名和目录信息，没有提取正文。");
        assertTrue(KnowledgeCoverage.check(facets,new AnswerArtifacts("s","s",List.of(source),List.of())).stream().allMatch(c -> c.sourceIds().isEmpty()));
        var initial=sample("a","傀儡组装完成以后，需要先扩充槽位，再安装升级模块并确认属性。");
        var result=KnowledgeCoverage.run("组装并升级傀儡",initial,q -> {fail("已命中的要点不应重复检索");return null;},this::combine);
        assertTrue(result.audit().attempts().isEmpty());
    }
    @Test void combatVariantsAreConditionalOnRetrievedEvidenceAndGenericCompositeIsPreserved() {
        assertEquals(3,KnowledgeCoverage.plan("某首领怎么打",AnswerArtifacts.empty()).size());
        assertEquals(5,KnowledgeCoverage.plan("某首领怎么打",sample("x","主世界和下界的首领有不同阶段，应分别核对相关战斗规则。").artifacts()).size());
        assertEquals(2,KnowledgeCoverage.plan("月台操作以及星门维护",AnswerArtifacts.empty()).size());
        assertEquals(3,KnowledgeCoverage.plan("组装并升级傀儡以及月台维护",AnswerArtifacts.empty()).size());
    }
    @Test void filteredSourcesCannotRemainInChecklistAndUiDiagnosticsRoundTrip() throws Exception {
        var initial=sample("a","傀儡组装以后先安装核心，再扩充槽位并安装升级模块。");
        var result=KnowledgeCoverage.run("组装并升级傀儡",initial,q -> initial,this::combine);
        var restricted=KnowledgeCoverage.restrict(result.audit(),AnswerArtifacts.empty());
        assertTrue(restricted.finalChecks().stream().allMatch(c -> c.sourceIds().isEmpty()));
        var d=new KnowledgeRetrievalService.Diagnostics("test",null,1,1,Map.of(),List.of(),false,"test",List.of(),List.of(),0,null,result.audit(),
                new KnowledgeImageSearch.Audit(1,1,0,"RELEVANT_NOT_DOWNLOADED",false,List.of(),new KnowledgeImageHydrator.Audit(2,1,0,1,"FETCH_INCOMPLETE")));
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        assertEquals(d,mapper.readValue(mapper.writeValueAsString(d),KnowledgeRetrievalService.Diagnostics.class));
        var m=new yagen.waitmydawn.kb.dto.QaMetrics();m.knowledgeRetrieval=d;
        assertTrue(m.toUiSummary().contains("定向补检"));assertTrue(m.toUiSummary().contains("相关待缓存：1"));
    }
    @Test void supplementalQueriesPreserveScopePageAndPublishedFingerprint() {
        var db=DatabaseBuilder.atPath(temp.resolve("base"));db.initDatabase();
        try(var embedder=new EmbeddingService(null)) {
            var content=new KnowledgeContentStore(db);var vectors=new VectorStore(db,384);
            for(int i=0;i<3;i++) {
                var source=BuildRequest.Source.of(BuildRequest.Kind.URL,"https://example.com/"+i);content.saveSource(source);
                content.setSourceScope(source.id(),i==2?"other":"pack","MODPACK","资料"+i,null);
                String doc="d"+i,id="c"+i,body=i==0?"傀儡组装时先加工各个零件，再安装核心并检查部件连接。":"升级傀儡只需三百钻石，这条规则不在所选页码与资料范围内。";
                content.saveDocument(new KnowledgeContent.Document(doc,source.id(),"指南",source.location(),"hash","text/html"));
                content.saveChunk(new KnowledgeContent.Chunk(id,doc,body,"操作",i==1?3:2,0));
                vectors.storeBatch(List.of(new TextChunker.TextChunk(id,body,null,"指南","web",source.location())),embedder);
            }
            String fingerprint=db.contentFingerprint();var service=new KnowledgeRetrievalService(db,embedder);
            var result=service.retrieve("第2页组装并升级傀儡",service.resolve("","pack",null),false);
            assertFalse(result.diagnostics().coverage().attempts().isEmpty());
            assertTrue(result.hits().stream().allMatch(h -> h.chunkId().equals("c0")));
            assertFalse(result.evidence().contains("三百钻石"));assertEquals(fingerprint,db.contentFingerprint());
            assertTrue(result.evidence().contains("NOT_FOUND_IN_CONTEXT"));
        } finally {db.shutdown();}
    }
}
