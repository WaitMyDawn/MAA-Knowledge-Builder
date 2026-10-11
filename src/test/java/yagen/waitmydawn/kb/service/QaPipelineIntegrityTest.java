package yagen.waitmydawn.kb.service;

import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.agent.*;
import yagen.waitmydawn.kb.dto.QuestionType;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class QaPipelineIntegrityTest {
    @Test void validPlannerIntentDoesNotCallClassifierAgain() {
        var db=DatabaseBuilder.atPath(temp.resolve("plan")); db.initDatabase();
        var embedder=new EmbeddingService(null); var vectors=new VectorStore(db,384);
        var llm=new RagAgentService(null) { @Override public ChatModel getChatModel() { return null; } };
        var classifier=new ClassifyAgent(llm) { @Override public Classification classify(String q) { fail("有效规划不能重复调用分类器"); return null; } };
        var entities=new EntityAgent(llm,db,embedder,vectors) {
            @Override public EntityResolutionResult resolveEntities(String question,String context) {
                var plan=new yagen.waitmydawn.kb.dto.RetrievalPlan(question,"alexsmobs",false,"ENTITY",List.of(),3,false,QuestionType.ENTITY);
                return new EntityResolutionResult(List.of(),List.of(),List.of(),List.of(question),plan,null);
            }
        };
        var urls=new UrlAgent(llm,db,embedder) { @Override public UrlFetchResult fetchAndRetrieve(List<EntityAgent.AliasHit> a,List<String> t,List<String> q) { return new UrlFetchResult(List.of(),null); } };
        var answers=new AnswerAgent(llm) { @Override public String compose(AnswerContext context) { assertEquals(QuestionType.ENTITY,context.category()); return "证据不足"; } };
        try(var manager=new MultiDBManager(temp,db)) {
            var pipeline=new QaPipeline(classifier,entities,urls,answers,llm,vectors,embedder,db,manager,temp);
            assertEquals("planner",pipeline.process("水滴鱼是什么").metrics.classifySource);
        } finally { db.shutdown(); }
    }
    @TempDir Path temp;

    @Test void rerankerSelectionIsTheEvidenceActuallyPassedToAnswer() {
        checkRerank("3", 1);
    }

    @Test void rerankerNoneRemovesAllEvidenceFromAnswer() {
        checkRerank("NONE", 0);
    }

    private void checkRerank(String selection, int expected) {
        var db = DatabaseBuilder.atPath(temp.resolve("base")); db.initDatabase();
        try (var connection = db.getConnection(); var statement = connection.prepareStatement(
                "INSERT INTO rag_recipe(recipe_type, recipe_data, output_item, source_mod) VALUES ('crafting', ?, 'minecraft:stone', 'minecraft')")) {
            for (String json : List.of("{\"type\":\"minecraft:crafting_shapeless\",\"result\":\"minecraft:stone\"}",
                    "{\"type\":\"minecraft:smelting\",\"result\":\"minecraft:stone\"}")) {
                statement.setString(1, json); statement.executeUpdate();
            }
        } catch (java.sql.SQLException e) { throw new AssertionError(e); }
        var vectors = new VectorStore(db, 384);
        var embedder = new EmbeddingService(null);
        var llm = new RagAgentService(null) {
            @Override public ChatModel getChatModel() { return null; }
            @Override public LlmResult askWithUsage(String caller, String prompt) {
                return new LlmResult(caller.equals("Reranker") ? selection : "offline", null, "stop");
            }
        };
        var classify = new ClassifyAgent(llm) {
            @Override public Classification classify(String question) { return new Classification(QuestionType.GENERAL, "test"); }
        };
        var entity = new EntityAgent(llm, db, embedder, vectors) {
            @Override public EntityResolutionResult resolveEntities(String question, String context) {
                return new EntityResolutionResult(List.of(), List.of(),
                        List.of(new ResolvedEntity("minecraft:stone", "minecraft", 1, "test")), List.of(question));
            }
        };
        var hits = java.util.stream.IntStream.range(0, 12).mapToObj(i ->
                new VectorStore.SearchResult("id" + i, "evidence" + i, "minecraft", "Minecraft", "wiki", .8, "https://wiki.biligame.com/mc/stone")).toList();
        var url = new UrlAgent(llm, db, embedder) {
            @Override public UrlFetchResult fetchAndRetrieve(List<EntityAgent.AliasHit> aliases,
                    List<String> topics, List<String> questions) {
                return new UrlFetchResult(List.of(new SubQueryResult(questions.getFirst(), hits, List.of())), null);
            }
        };
        var context = new AtomicReference<AnswerAgent.AnswerContext>();
        var answer = new AnswerAgent(llm) {
            @Override public String compose(AnswerContext ctx) { context.set(ctx); return "answer"; }
        };
        try (var manager = new MultiDBManager(temp, db)) {
            var pipeline = new QaPipeline(classify, entity, url, answer, llm, vectors, embedder, db, manager, temp);
            pipeline.restoreHistory(List.of(new ChatHistoryService.ChatMessage("user", "previous", ""),
                    new ChatHistoryService.ChatMessage("maa", "previous answer", "")));
            var result = pipeline.process("stone purpose");
            assertEquals(2, result.metrics.conversationTurn);
            assertEquals(2, result.recipeResults.size());
            assertEquals(expected, result.vectorResults.size());
            assertEquals(expected, context.get().subQueryResults().getFirst().chunks().size());
            if (expected == 1) assertEquals("id3", context.get().subQueryResults().getFirst().chunks().getFirst().chunkId());
            assertEquals(0, result.metrics.answerLlmMs);
            assertEquals(0, result.metrics.ttftMs);
        } finally { db.shutdown(); }
    }
}
