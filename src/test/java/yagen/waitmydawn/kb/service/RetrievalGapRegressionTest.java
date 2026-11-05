package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.*;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import yagen.waitmydawn.kb.model.*;
import yagen.waitmydawn.kb.agent.*;
import yagen.waitmydawn.kb.config.LogSetup;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RetrievalGapRegressionTest {
    private final ObjectMapper json=new ObjectMapper();
    @TempDir Path temp;
    @AfterEach void cleanup(){LogSetup.shutdown();}
    @Test void structuredAndPlainWikiExtractionBothExcludeFlavorNodes(){
        var document=Jsoup.parse("<div class='item-content common-text'><p>空手右键尸体采集材料。<span class='uknowtoomuch'>把材料与石头发生神奇反应。</span></p><table><tr><td>合成</td><td>护甲<span class='uknow-too-much'>吐槽</span></td></tr></table></div>");
        String blocks=WebDocumentParser.parse(document,"https://www.mcmod.cn/item/123.html").blocks().toString();
        assertTrue(blocks.contains("空手右键"));assertTrue(blocks.contains("护甲"));assertFalse(blocks.contains("神奇反应"));assertFalse(blocks.contains("吐槽"));
        assertFalse(WikiBodyExtractor.extract(document.body()).contains("神奇反应"));assertTrue(document.text().contains("神奇反应"));
    }
    @Test void genericInteractionAndDependencyQuestionsRequireActualSteps(){
        String question="材料如何获取？请说明与尸体交互的具体步骤。";
        assertTrue(TaskEvidence.missing(question,"击杀生物后有概率掉落材料。").contains("交互操作"));
        assertFalse(TaskEvidence.missing(question,"空手右键尸体采集材料。").contains("交互操作"));
        String summon="介绍召唤流程和召唤物的获取方式。";
        assertTrue(TaskEvidence.missing(summon,"合成祭品，手持祭品右键祭坛召唤首领。").contains("前置物品的获取细节"));
        assertFalse(TaskEvidence.missing(summon,"合成材料：宝石与金锭，在工作台制作祭品，手持祭品右键祭坛。").contains("前置物品的获取细节"));
        assertFalse(TaskEvidence.missing(summon,"该首领自然生成，无需召唤物。").contains("前置物品的获取细节"));
        assertTrue(TaskEvidence.missing("等级怎么提升？","稀有度影响等级。").contains("升级操作与条件"));
    }
    @Test void recipeFloodCannotHideCreatureOrContainerSources()throws Exception {
        var sources=new ArrayList<JsonNode>();for(int i=0;i<30;i++)sources.add(json.readTree("{\"category\":\"recipes\",\"recordId\":\"example:r"+i+"\"}"));
        var creature=json.readTree("{\"category\":\"loot_tables\",\"recordId\":\"example:creature\",\"entrypoints\":[{\"sourceKind\":\"entity\",\"source\":\"example:beast\"}]}");
        var container=json.readTree("{\"category\":\"loot_tables\",\"recordId\":\"example:chest\",\"entrypoints\":[{\"sourceKind\":\"structure_template\",\"source\":\"example:ruin\"}]}");sources.add(creature);sources.add(container);
        var ordered=AcquisitionCoverage.interleave(sources);assertTrue(ordered.subList(0,3).containsAll(List.of(creature,container)));assertEquals(32,new HashSet<>(ordered).size());
        var coverage=new AcquisitionCoverage();coverage.offered("example:material",sources);coverage.read("example:material",sources.getFirst());assertEquals(2,coverage.missing().size());coverage.read("example:material",creature);assertEquals(1,coverage.missing().size());coverage.read("example:material",container);assertTrue(coverage.missing().isEmpty());
    }
    @Test void acquisitionToolCarriesEntrypointsAndCannotStopAfterOnlyRecipes()throws Exception {
        var db=DatabaseBuilder.atPath(temp.resolve("db"));db.initDatabase();String profile="c".repeat(64);
        try(var embedding=new EmbeddingService(null)){
            var descriptor=json.createObjectNode();for(String category:List.of("recipes","loot_tables","registries"))descriptor.with("categories").putObject(category).put("status","COMPLETE");
            try(var c=db.getConnection();var p=c.prepareStatement("INSERT INTO rag_runtime_snapshot VALUES('source',?,?)")){p.setString(1,profile);p.setString(2,descriptor.toString());p.executeUpdate();}
            var index=json.readTree("{\"id\":\"example:material\",\"sources\":[{\"category\":\"recipes\",\"recordId\":\"example:recipe\"},{\"category\":\"loot_tables\",\"recordId\":\"example:creature\",\"entrypoints\":[{\"sourceKind\":\"entity\",\"source\":\"example:beast\"}]},{\"category\":\"loot_tables\",\"recordId\":\"example:chest\",\"entrypoints\":[{\"sourceKind\":\"structure_template\",\"source\":\"example:ruin\"}]},{\"category\":\"loot_tables\",\"recordId\":\"example:table\",\"entrypoints\":[]}]}");
            var recipe=json.readTree("{\"id\":\"example:recipe\",\"definition\":{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[{\"item\":\"minecraft:diamond\"}],\"result\":{\"id\":\"example:material\"}}}");
            try(var c=db.getConnection();var p=c.prepareStatement("INSERT INTO rag_runtime_recipe VALUES(?,?,?,?,?,?,?,?)")){p.setString(1,"d".repeat(64));p.setString(2,profile);p.setString(3,"example:recipe");p.setString(4,recipe.toString());p.setString(5,"example:material");p.setString(6,"example");p.setString(7,"RUNTIME_SNAPSHOT");p.setString(8,"SUPPORTED");p.executeUpdate();}
            try(var c=db.getConnection();var p=c.prepareStatement("INSERT INTO rag_runtime_record VALUES(?,?,?,?)")){
                for(var row:List.of(new RuntimeStructuredQuery.Row(new RuntimeStructuredQuery.Key("acquisition_index","example:material"),index),new RuntimeStructuredQuery.Row(new RuntimeStructuredQuery.Key("recipes","example:recipe"),recipe),new RuntimeStructuredQuery.Row(new RuntimeStructuredQuery.Key("loot_tables","example:creature"),loot("example:creature")),new RuntimeStructuredQuery.Row(new RuntimeStructuredQuery.Key("loot_tables","example:chest"),loot("example:chest")),new RuntimeStructuredQuery.Row(new RuntimeStructuredQuery.Key("loot_tables","example:table"),loot("example:table")))){p.setString(1,profile);p.setString(2,row.key().category());p.setString(3,row.key().id());p.setString(4,row.data().toString());p.addBatch();}p.executeBatch();
            }
            var llm=new RagAgentService(null){@Override public dev.langchain4j.model.chat.ChatModel getChatModel(){return null;}};
            var turn=new QaAgentTurn(db,new VectorStore(db,384),new KnowledgeRetrievalService(db,embedding),new UrlAgent(llm,db,embedding),new QaPipeline.QaResult(),"材料有哪些制作和获取途径",null,null,()->{});
            turn.execute("query_recipes",json.readTree("{\"id\":\"example:material\"}"));assertTrue(turn.readinessGap().contains("loot_tables"));
            var acquired=json.readTree(turn.execute("query_acquisition",json.readTree("{\"id\":\"example:material\",\"limit\":4}")));
            assertTrue(acquired.path("unreadSourceCategories").isEmpty());assertTrue(acquired.toString().contains("example:beast"));assertTrue(acquired.toString().contains("example:ruin"));
            assertTrue(acquired.path("unresolvedAcquisitionSources").toString().contains("example:table"), acquired.toString());
            assertTrue(turn.readinessGap().contains("query_loot"), turn.readinessGap());
            turn.execute("query_loot",json.readTree("{\"id\":\"example:table\"}"));
            assertFalse(Objects.toString(turn.readinessGap(), "").contains("query_loot"));
        }finally{db.shutdown();}
    }
    private JsonNode loot(String id)throws Exception{return json.readTree("{\"id\":\""+id+"\",\"definition\":{\"pools\":[{\"rolls\":1,\"entries\":[{\"type\":\"minecraft:item\",\"name\":\"example:material\"}]}]}}");}
}
