package yagen.waitmydawn.kb.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.agent.*;
import yagen.waitmydawn.kb.config.AppConfig;
import yagen.waitmydawn.kb.model.*;
import yagen.waitmydawn.kb.service.*;
import dev.langchain4j.model.chat.*;
import dev.langchain4j.model.chat.request.*;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.output.TokenUsage;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EvalAssetsTest {
    @TempDir Path temp;
    @Test void evaluationArchivesTheSameImagesItAssessesAndHistorySurvivesSourceRemoval() throws Exception {exercise(false);}
    @Test void configuredModelFailureCannotPassAsAnOfflineAnswer() throws Exception {exercise(true);}
    private void exercise(boolean failure) throws Exception {
        Path base=temp.resolve("base");Files.createDirectories(base);
        var config=AppConfig.forIsolatedRun(false);config.setDataDir(base);
        if(failure) config.setApiKeyForRun("unit-test-placeholder");
        var db=DatabaseBuilder.atPath(base.resolve("rag_data"));db.initDatabase();
        try(var embedder=new EmbeddingService(null)) {
            var store=new KnowledgeContentStore(db);var vectors=new VectorStore(db,384);
            var source=BuildRequest.Source.of(BuildRequest.Kind.URL,"https://example.com/guide");store.saveSource(source);
            store.setSourceScope(source.id(),"pack","MODPACK","测试整合包",null);
            store.saveDocument(new KnowledgeContent.Document("doc",source.id(),"组装指南",source.location(),"hash","text/html"));
            String body="核心组装需要先将底座固定，再安装框架和核心，最后确认部件之间的连接。";
            store.saveChunk(new KnowledgeContent.Chunk("seed","doc",body,"核心组装",null,0));
            vectors.storeBatch(List.of(new TextChunker.TextChunk("seed",body,null,"组装指南","web",source.location())),embedder);
            var asset=new ImageAssetStore(base).put(Files.readAllBytes(Path.of("src/test/resources/web/tiny.webp")));store.saveImage(asset);
            store.saveOccurrence(new KnowledgeContent.ImageOccurrence("o1","doc",asset.id(),"核心组装",null,null,0));
            store.saveLink(new KnowledgeContent.ChunkImageLink("seed","o1","same_block"));
            String sid="s"+BuildRequest.fingerprint("seed").substring(0,20),iid="i"+BuildRequest.fingerprint("o1").substring(0,20);
            ChatModel model=new ChatModel(){@Override public ChatResponse doChat(ChatRequest request) {
                if(failure) throw new IllegalStateException("Simulated model unavailable");
                return ChatResponse.builder().aiMessage(AiMessage.from("先固定底座，再安装框架和核心。[[source:"+sid+"]]\n\n[[image:"+iid+"]]"))
                        .tokenUsage(new TokenUsage(100,20)).build();
            }};
            var llm=new RagAgentService(config){@Override public ChatModel getChatModel(){return model;}};
            Path spec=temp.resolve("questions.json");Files.writeString(spec,"""
                    {"conversations":[{"id":"E1","turns":[{"id":"E1","question":"测试整合包如何组装核心", "assertions":[
                    {"kind":"answer_illustration_valid","required":true},{"kind":"answer_media_refs_valid","required":true}]}]}]}
                    """);
            var outcome=new EvalRunner(config,db,embedder,vectors,llm,new EvalRunner.Config(base,spec,Set.of(),"assets",1,false)).run();
            if(failure) {
                assertEquals(0,outcome.passedTurns());
                String record=Files.readString(outcome.runDir().resolve("turns.jsonl"));
                assertTrue(record.contains("\"answerGeneration\":\"MODEL_ERROR\""));assertTrue(record.contains("\"error\":true"));return;
            }
            assertEquals(1,outcome.passedTurns());
            var session=new ChatHistoryService(outcome.runDir()).listSessions().getFirst();
            assertEquals(outcome.runDir().toString(),session.dataDir);
            var message=session.messages.getLast();
            Files.delete(new ImageAssetStore(base).resolve(asset.id(),asset.relativePath()));
            assertEquals(1,AnswerMedia.assess(message.content(),message.artifacts(),outcome.runDir()).validIllustrations());
            assertEquals(0,AnswerMedia.assess(message.content(),message.artifacts(),base).validIllustrations());
            assertTrue(Files.readString(outcome.runDir().resolve("turns.jsonl")).contains("\"assetArchiveErrors\":[]"));
        } finally {db.shutdown();yagen.waitmydawn.kb.config.LogSetup.shutdown();}
    }
    @Test void missingOrEscapingAssetsAreNotReportedAsSuccessfulArchives() throws Exception {
        var image=new AnswerArtifacts.Picture("i","a".repeat(64),"../escape.png","图","s","ILLUSTRATION",List.of(),"");
        var artifacts=new AnswerArtifacts("p","p",List.of(),List.of(image));
        assertEquals(1,EvalAssets.archive(artifacts,temp.resolve("source"),temp.resolve("run")).size());
        assertFalse(Files.exists(temp.resolve("escape.png")));
    }
    @Test void judgesSeeExpandedEvidenceAndSourceAddressesInsteadOfOnlySeedFragments() {
        var result=new QaPipeline.QaResult();
        result.artifacts=new AnswerArtifacts("p","p",List.of(new AnswerArtifacts.Source("s1","d","指南","https://example.com/guide","操作",null,"NATIVE","完整步骤直到原文尾部标记")),List.of());
        result.vectorResults=List.of(new VectorStore.SearchResult("c","孤立种子片段",null,"指南","web",1,"https://example.com/guide"));
        String evidence=EvalRunner.retrievalEvidence(result);
        assertTrue(evidence.contains("原文尾部标记"));assertTrue(evidence.contains("https://example.com/guide"));
        assertFalse(evidence.contains("孤立种子片段"));
    }
    @Test void runtimeFailureCannotBeOffsetByMatchingAnswerKeywords() {
        var assertion=new GoldenSet.Assertion("answer_contains_any",List.of("命中词"),null,null,null,null,1,true);
        var turn=new GoldenSet.Turn("t","测试",null,List.of(),"basic","general",1,List.of(),List.of(),List.of(assertion));
        var obs=new RuleJudge.Observation("命中词",true,"模型错误",List.of(),List.of(),0,List.of(),List.of(),List.of(),List.of(),null,0,0,0,false);
        assertFalse(RuleJudge.judge(turn,obs).passed());
    }
    @Test void judgeUsageIsCapturedAndRelativeEvaluationRootsAreNormalized() {
        var config=new EvalRunner.Config(Path.of("target/p6-relative"),Path.of("test-set/old/golden-set-modpack.json"),Set.of(),"unit",1,false);
        assertTrue(config.dataDir().isAbsolute());assertTrue(config.goldenSetFile().isAbsolute());
        ChatModel model=new ChatModel(){@Override public ChatResponse doChat(ChatRequest request) {
            return ChatResponse.builder().aiMessage(AiMessage.from("{\"verdict\":\"pass\",\"faithfulness\":5,\"completeness\":5,\"correctness\":5}"))
                    .tokenUsage(new TokenUsage(321,24)).build();
        }};
        var turn=new GoldenSet.Turn("t","测试",null,List.of(),"basic","general",1,List.of(),List.of(),List.of());
        var judged=LlmJudge.judge(model,turn,"回答","实际证据");
        assertEquals(321,judged.tokenIn());assertEquals(24,judged.tokenOut());assertEquals("pass",judged.result().verdict());
        assertTrue(judged.prompt().contains("未提供像素"));
    }
}
