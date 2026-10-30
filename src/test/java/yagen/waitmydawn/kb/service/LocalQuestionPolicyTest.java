package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import yagen.waitmydawn.kb.agent.*;
import yagen.waitmydawn.kb.config.AppConfig;
import yagen.waitmydawn.kb.model.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalQuestionPolicyTest {
    @TempDir Path temp;
    @org.junit.jupiter.api.AfterEach void logging(){yagen.waitmydawn.kb.config.LogSetup.shutdown();}

    @Test void separatesAppearanceAndRecipeFromAcquisitionAndExplicitWebRequests() {
        for(String q:List.of("钻石剑怎么合成","展示猫的品种图片","展示戴钻石头盔的牛，缺失就说明","展示猫图片并标注来源","比较一阶和五阶火龙外观","不要联网，展示钻石剑图标"))
            assertTrue(LocalQuestionPolicy.decide(q).localOnly(),q);
        for(String q:List.of("钻石剑怎么合成，钻石哪里找","钻石剑合成需要的材料如何获取","猫怎么驯服，展示图片","请联网查询钻石剑配方","根据 https://example.com/guide 展示配方","猫有什么用"))
            assertFalse(LocalQuestionPolicy.decide(q).localOnly(),q);
    }

    @Test void recipesStatesAndMissingEquipmentNeverEnterWebOrPlannerBranches() throws Exception {
        var fixture=new EntityStateProtocolTest();fixture.temp=temp;
        Path root=fixture.fixture(List.of(fixture.row(null,null,false,256,true),fixture.row("baby","red",false,256,true)));
        var config=AppConfig.forIsolatedRun(false);config.setDataDir(temp.resolve("database"));
        var db=new DatabaseBuilder(config);db.initDatabase();
        try(var embedding=new EmbeddingService(null);var manager=new MultiDBManager(config.getDataDir(),db)) {
            new KnowledgeBuilder(config,db,embedding).build(new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,root.toString()))),null);
            var vectors=new VectorStore(db,384);
            var llm=new RagAgentService(null){@Override public dev.langchain4j.model.chat.ChatModel getChatModel(){return null;}};
            var entities=new EntityAgent(llm,db,embedding,vectors){@Override public EntityResolutionResult resolveEntities(String q,String context){throw new AssertionError("Local question entered entity planner: "+q);}};
            var urls=new UrlAgent(llm,db,embedding){@Override public UrlFetchResult fetchAndRetrieve(List<EntityAgent.AliasHit> a,List<String> t,List<String> q){throw new AssertionError("Local question entered URL acquisition: "+q);}};
            var answers=new AnswerAgent(llm){@Override public String compose(AnswerContext context){return "当前快照的本地证据。";}};
            var pipeline=new QaPipeline(new ClassifyAgent(llm),entities,urls,answers,llm,vectors,embedding,db,manager,config.getDataDir());
            var recipe=pipeline.process("实例中的名称怎么合成");
            assertEquals(1,recipe.recipeResults.size());assertEquals("RUNTIME_SNAPSHOT",recipe.recipeResults.getFirst().sourceType());
            assertFalse(recipe.artifacts.pictures().isEmpty());assertEquals(0,recipe.metrics.httpRequestCount);
            var state=pipeline.process("展示 mkb_fixture:creature 红色幼年实体图片");
            assertEquals(1,state.artifacts.pictures().size());assertEquals(0,state.metrics.httpRequestCount);
            var followup=pipeline.process("它的默认外观图片呢？");
            assertEquals(1,followup.artifacts.pictures().size());assertEquals("default",followup.artifacts.pictures().getFirst().runtimeIdentity().stateKey());
            var missing=pipeline.process("展示穿戴 minecraft:diamond_helmet 的 mkb_fixture:creature 实体图片，缺失就说明");
            assertTrue(missing.artifacts.pictures().stream().noneMatch(p->p.role().equals("ENTITY")));
            assertFalse(missing.mediaWarnings.isEmpty());assertEquals(0,missing.metrics.httpRequestCount);
            var absentRecipe=pipeline.process("minecraft:unknown_item 的合成配方");
            assertTrue(absentRecipe.recipeResults.isEmpty());assertEquals(0,absentRecipe.metrics.httpRequestCount);
        } finally {db.shutdown();}
    }

    @Test void materialAcquisitionAndExplicitWebRequestsKeepTheOnlineBranch() throws Exception {
        var db=DatabaseBuilder.atPath(temp.resolve("mixed"));db.initDatabase();
        try(var embedding=new EmbeddingService(null);var manager=new MultiDBManager(temp,db)) {
            var vectors=new VectorStore(db,384);var requests=new java.util.concurrent.atomic.AtomicInteger();
            var llm=new RagAgentService(null){@Override public dev.langchain4j.model.chat.ChatModel getChatModel(){return null;} @Override public String rawAsk(String caller,String prompt){return "[]";}};
            var classifier=new ClassifyAgent(llm){@Override public Classification classify(String q){return new Classification(yagen.waitmydawn.kb.dto.QuestionType.GENERAL,"test");}};
            var entities=new EntityAgent(llm,db,embedding,vectors){@Override public EntityResolutionResult resolveEntities(String q,String context){return new EntityResolutionResult(List.of(new AliasHit(q,"核心","https://example.com/core","sample",1,"target")),List.of(),List.of(),List.of(q));}};
            var urls=new UrlAgent(llm,db,embedding){@Override public UrlFetchResult fetchAndRetrieve(List<EntityAgent.AliasHit> aliases,List<String> topics,List<String> queries){
                if(aliases.isEmpty()&&topics.isEmpty())return new UrlFetchResult(List.of(),null);
                requests.incrementAndGet();return new UrlFetchResult(List.of(new SubQueryResult(queries.getFirst(),List.of(new VectorStore.SearchResult("source","核心的材料可在洞穴获得。","sample","示例","web",.9,"https://example.com/core")),List.of())),null);
            }};
            var answer=new AnswerAgent(llm){@Override public String compose(AnswerContext context){return "使用查询到的来源。";}};
            var pipeline=new QaPipeline(classifier,entities,urls,answer,llm,vectors,embedding,db,manager,temp);
            pipeline.process("核心怎么合成，材料哪里找");assertTrue(requests.get()>0);
            int before=requests.get();pipeline.process("请联网查询核心配方");assertTrue(requests.get()>before);
        }finally{db.shutdown();}
    }
}
