package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.model.*;
import yagen.waitmydawn.kb.dto.QaMetrics;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RetrievalDiagnosticsTest {
    @TempDir Path temp;
    @Test void decisionsSeparateMissingVectorsThresholdsSectionAndResultLimitsAndPersist() throws Exception {
        var db=DatabaseBuilder.atPath(temp.resolve("base"));db.initDatabase();
        try(var embedder=new EmbeddingService(null)) {
            var vectors=new VectorStore(db,384);var content=new KnowledgeContentStore(db);
            var source=BuildRequest.Source.of(BuildRequest.Kind.URL,"https://example.com");content.saveSource(source);
            content.setSourceScope(source.id(),"mcmod:modpack:1","MODPACK","测试包",null);
            content.saveDocument(new KnowledgeContent.Document("d",source.id(),"指南",source.location(),"hash","text/html"));
            for(int i=0;i<20;i++) {
                String id=String.format(Locale.ROOT,"c%02d",i);String text="傀儡组装和傀儡升级，操作编号"+i;
                content.saveChunk(new KnowledgeContent.Chunk(id,"d",text,i<7?"共享章节":"章节"+i,null,i));
                vectors.storeBatch(List.of(new TextChunker.TextChunk(id,text,null,"测试包","web",source.location())),embedder);
            }
            content.saveChunk(new KnowledgeContent.Chunk("missing","d","傀儡组装","缺向量",null,21));
            content.saveChunk(new KnowledgeContent.Chunk("irrelevant","d","qqq","无关",null,22));
            vectors.store(new TextChunker.TextChunk("irrelevant","qqq",null,"测试包","web",source.location()),new float[384]);
            var service=new KnowledgeRetrievalService(db,embedder);
            var retrieval=service.retrieve("傀儡组装",service.resolve("傀儡组装","mcmod:modpack:1",null),false);
            var diagnostics=retrieval.diagnostics();
            assertEquals(22,diagnostics.scanned());assertEquals(10,diagnostics.selected());
            assertEquals(1,diagnostics.decisions().get("MISSING_VECTOR"));
            assertEquals(1,diagnostics.decisions().get("OUTSIDE_BRANCH_POOL"));
            assertTrue(diagnostics.decisions().getOrDefault("RESULT_LIMIT",0)>0);
            assertEquals(diagnostics.scanned(),diagnostics.decisions().values().stream().mapToInt(i->i).sum());
            assertEquals(diagnostics,service.hydrate(retrieval,"傀儡组装").diagnostics());
            var metrics=new QaMetrics();metrics.knowledgeRetrieval=diagnostics;metrics.knowledgeContextChunkIds=List.of("c0");metrics.knowledgeCitedSourceIds=List.of("s1");
            var history=new MetricsHistoryService(temp);history.save(metrics);
            var loaded=history.loadDate(LocalDate.now()).getFirst();
            assertEquals(diagnostics,loaded.knowledgeRetrieval);assertEquals(List.of("c0"),loaded.knowledgeContextChunkIds);
            assertEquals(List.of("s1"),loaded.knowledgeCitedSourceIds);
        } finally {db.shutdown();}
    }

    @Test void currentTokenizerIsComparedToIndependentOfficialFixturesWithoutChangingItsProfile() throws Exception {
        Path vocabulary=temp.resolve("vocab.txt");
        try(var input=getClass().getResourceAsStream("/models/all-MiniLM-L6-v2/vocab.txt")) {assertNotNull(input);Files.copy(input,vocabulary);}
        var reference=TokenizerDiagnostics.compare(vocabulary,Path.of("src/test/resources/embedding/minilm-tokenizer-reference.json"));
        assertTrue(reference.vocabularyMatches());assertEquals(18,reference.total());
        assertTrue(reference.matched()<reference.total(),"现有自制分词器与官方实现的已知差异应被报告");
        assertTrue(reference.cases().stream().anyMatch(c -> c.text().equals("Hello world!") && c.matches()));
        assertTrue(reference.cases().stream().anyMatch(c -> c.text().equals("Café naïve résumé") && !c.matches()));
        long unk=Files.readAllLines(vocabulary).indexOf("[UNK]");
        var stats=TokenizerDiagnostics.inspect("黑暗魔杖与缠魂木",new WordPieceTokenizer(vocabulary),unk,4);
        assertTrue(stats.unknownHanCharacters()>0);assertTrue(stats.truncated());
        assertEquals(8,stats.hanCharacters());
    }
}
