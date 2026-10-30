package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.agent.*;
import yagen.waitmydawn.kb.config.*;
import yagen.waitmydawn.kb.model.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeEvidencePolicyTest {
    @TempDir Path temp;
    @AfterEach void logging(){LogSetup.shutdown();}
    @Test void selectedManifestDefinesExactVersionEvenWhenOldIconProfilesRemain()throws Exception {
        Path root=RuntimeBuildFixtures.create(temp.resolve("MKB-Export"),List.of(),"neoforge","1.21.1");
        var config=AppConfig.forIsolatedRun(false);config.setDataDir(temp.resolve("data"));var db=new DatabaseBuilder(config);db.initDatabase();
        try(var embedding=new EmbeddingService(null)) {
            new KnowledgeBuilder(config,db,embedding).build(new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,root.toString()))),null);
            try(var c=db.getConnection();var s=c.createStatement();var rows=s.executeQuery("SELECT * FROM rag_item_icon_profile LIMIT 1")) {
                assertTrue(rows.next());var manifest=new com.fasterxml.jackson.databind.ObjectMapper().readTree(rows.getString("manifest_json"));((com.fasterxml.jackson.databind.node.ObjectNode)manifest.path("environment")).put("minecraftVersion","1.20.1");
                try(var insert=c.prepareStatement("INSERT INTO rag_item_icon_profile VALUES(?,?,?,?,?,?,?)")){insert.setString(1,"0".repeat(64));insert.setString(2,rows.getString("source_id"));insert.setString(3,"f".repeat(64));insert.setString(4,rows.getString("environment_hash"));insert.setString(5,"GAME_GUI");insert.setString(6,"UNVERIFIED");insert.setString(7,manifest.toString());insert.executeUpdate();}
            }
            var snapshot=RuntimeEvidencePolicy.snapshot(db);assertEquals("1.21.1",snapshot.minecraftVersion());assertEquals("neoforge",snapshot.loader());
            String context=RuntimeEvidencePolicy.context(snapshot);assertTrue(context.contains("Minecraft Java Edition=1.21.1"));assertTrue(context.contains("不得用模型记忆"));assertTrue(context.contains("网页默认配方不得覆盖快照"));
            assertTrue(RuntimeEvidencePolicy.context(snapshot,true).contains("本轮按所选文档或整合包教程介绍其自身内容"));
        }finally{db.shutdown();}
    }
    @Test void sparseLegacyAnswersCannotTriggerASecondFreeFormMemoryAnswer()throws Exception {
        var db=DatabaseBuilder.atPath(temp.resolve("sparse"));db.initDatabase();
        try(var embedding=new EmbeddingService(null);var manager=new MultiDBManager(temp,db)) {
            var llm=new RagAgentService(null){@Override public dev.langchain4j.model.chat.ChatModel getChatModel(){return null;} @Override public String rawAsk(String caller,String prompt){if(caller.equals("Fallback"))fail("Memory fallback must not run");return "";} @Override public LlmResult askWithUsage(String caller,String prompt){if(caller.equals("Fallback"))fail("Memory fallback must not run");return new LlmResult("",null,"stop");}};
            var vectors=new VectorStore(db,384);var entities=new EntityAgent(llm,db,embedding,vectors){@Override public EntityResolutionResult resolveEntities(String q,String history){return new EntityResolutionResult(List.of(),List.of(),List.of(),List.of(q));}};
            var urls=new UrlAgent(llm,db,embedding){@Override public UrlFetchResult fetchAndRetrieve(List<EntityAgent.AliasHit> a,List<String> b,List<String> q){return new UrlFetchResult(List.of(),null);}};
            var classify=new ClassifyAgent(llm){@Override public Classification classify(String q){return new Classification(yagen.waitmydawn.kb.dto.QuestionType.GENERAL,"fixture");}};
            var answer=new AnswerAgent(llm){@Override public String compose(AnswerContext c){return "本轮资料不足，无法确认。";}};
            var result=new QaPipeline(classify,entities,urls,answer,llm,vectors,embedding,db,manager,temp).process("未收录对象是什么");
            assertEquals("本轮资料不足，无法确认。",result.answer);assertEquals(0,result.metrics.fallbackLlmMs);assertEquals(0,result.metrics.resourceAccess.requests());
        }finally{db.shutdown();}
    }
}
