package yagen.waitmydawn.kb.agent;

import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import yagen.waitmydawn.kb.model.ModEntry;
import yagen.waitmydawn.kb.service.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class EntityRoutingTest {
    @Test void exactCrossModNameWinsBeforeSameModLookalikeSuggestions() {
        var db=DatabaseBuilder.atPath(temp.resolve("cross-first"));db.initDatabase();
        try {
            var mod=new ModEntry();mod.setModId("alexsmobs");db.saveModEntry(mod);
            var embedder=new EmbeddingService(null);var vectors=new VectorStore(db,384);
            vectors.storeBatch(List.of(new TextChunker.TextChunk("hydra","九头蛇(Hydra)","iceandfire","冰火传说",
                    "entity_alias","https://www.mcmod.cn/item/235510.html")),List.of(new float[384]),embedder.profile());
            var llm=new RagAgentService(null) {
                @Override public ChatModel getChatModel() {return null;}
                @Override public LlmResult askWithUsage(String caller,String prompt) {
                    assertEquals("EntityAgent.analyze",caller);
                    return new LlmResult("{\"subjects\":[\"九头蛇\"],\"modId\":\"alexsmobs\",\"objectType\":\"ENTITY\",\"intent\":\"ENTITY\"}",null,"stop");
                }
            };
            var result=new EntityAgent(llm,db,embedder,vectors).resolveEntities("Alex's Mobs 的九头蛇怎么打？","");
            assertEquals(1,result.aliasHits().size());assertEquals("iceandfire",result.aliasHits().getFirst().modId());
            assertEquals("primary",result.aliasHits().getFirst().role());
        } finally {db.shutdown();}
    }
    @Test void curatedRelationsRemainScopedAndRequireARealCatalogTarget() {
        var db=DatabaseBuilder.atPath(temp.resolve("relations")); db.initDatabase();
        try {
            assertTrue(KnowledgeHints.match(db,"alexscaves","霸王龙","prototype").isEmpty());
            db.saveWebPages("alexscaves",List.of(new WikiScraperService.WebPage("Alex","https://www.mcmod.cn/class/12592.html","目录","mcmod",Map.of("撼地龙(Tremorsaurus)","https://www.mcmod.cn/item/750369.html"))));
            var relation=KnowledgeHints.match(db,"alexscaves","T-Rex","prototype").getFirst();
            assertEquals("prototype",relation.kind());
            assertTrue(KnowledgeHints.match(db,"alexsmobs","T-Rex","prototype").isEmpty());
        } finally { db.shutdown(); }
    }
    @Test void explicitVanillaInteractionIsRetrievedAlongsideModPages() {
        var db=DatabaseBuilder.atPath(temp.resolve("interaction")); db.initDatabase();
        try {
            var mod=new ModEntry(); mod.setModId("irons_spellbooks"); db.saveModEntry(mod);
            var embedder=new EmbeddingService(null); var vectors=new VectorStore(db,384);
            var llm=new RagAgentService(null) {
                @Override public ChatModel getChatModel() { return null; }
                @Override public LlmResult askWithUsage(String caller,String prompt) {
                    return new LlmResult("{\"subjects\":[\"法术\"],\"modId\":\"irons_spellbooks\",\"objectType\":\"SPELL\",\"intent\":\"MECHANISM\",\"vanillaTopics\":[\"末影龙\"]}",null,"stop");
                }
            };
            var result=new EntityAgent(llm,db,embedder,vectors).resolveEntities("Iron's Spellbooks 的法术能击杀末影龙吗？","");
            assertTrue(result.wikiTopics().contains("末影龙")); assertTrue(result.subQueries().contains("末影龙"));
        } finally { db.shutdown(); }
    }
    @Test void creatureTypePrefersCreaturePageOverSameNamedMaterial() {
        var db=DatabaseBuilder.atPath(temp.resolve("typed")); db.initDatabase();
        try {
            var mod=new ModEntry(); mod.setModId("alexsmobs"); db.saveModEntry(mod);
            db.saveWebPages("alexsmobs",List.of(new WikiScraperService.WebPage("Alex","https://www.mcmod.cn/class/3318.html","目录","mcmod",Map.of("水滴鱼(Blobfish)","https://www.mcmod.cn/item/398026.html"))));
            var catalog=new WebCatalogParser().parse(org.jsoup.Jsoup.parse("<table><tr><th class='item-list-type-left'>现实生物</th><td class='item-list-type-right'><a href='/item/398062.html' data-cn='水滴鱼' data-en='Blobfish'>水滴鱼</a></td></tr></table>"),"alexsmobs","https://www.mcmod.cn/item/list/3318-4.html",4,"生物/实体");
            new WebCatalogStore(db).save(catalog);
            var embedder=new EmbeddingService(null); var vectors=new VectorStore(db,384);
            var llm=new RagAgentService(null) {
                @Override public ChatModel getChatModel() { return null; }
                @Override public LlmResult askWithUsage(String caller,String prompt) {
                    assertEquals("EntityAgent.analyze",caller);
                    return new LlmResult("{\"subjects\":[\"水滴鱼\"],\"modId\":\"alexsmobs\",\"objectType\":\"ENTITY\",\"intent\":\"ENTITY\"}",null,"stop");
                }
            };
            var result=new EntityAgent(llm,db,embedder,vectors).resolveEntities("Alex's Mobs 的水滴鱼是什么？","");
            assertEquals("https://www.mcmod.cn/item/398062.html",result.aliasHits().getFirst().url());
            assertEquals(yagen.waitmydawn.kb.dto.QuestionType.ENTITY,result.plan().intent());
        } finally { db.shutdown(); }
    }
    @TempDir Path temp;

    @Test void unnamedSpellMechanismUsesSupportPagesWithoutInventingAPrimaryEntity() {
        var db=DatabaseBuilder.atPath(temp.resolve("mechanism"));db.initDatabase();
        try {
            var mod=new ModEntry();mod.setModId("irons_spellbooks");db.saveModEntry(mod);
            db.saveWebPages("irons_spellbooks",List.of(new WikiScraperService.WebPage("Iron","https://www.mcmod.cn/class/1.html","catalog","mcmod",
                    Map.of("奥术铁砧(Arcane Anvil)","https://www.mcmod.cn/item/1.html"))));
            var embedder=new EmbeddingService(null);var store=new VectorStore(db,384);
            var llm=new RagAgentService(null) {
                @Override public ChatModel getChatModel() { return null; }
                @Override public LlmResult askWithUsage(String caller,String prompt) {
                    String response=caller.equals("EntityAgent.analyze")?"{\"subjects\":[\"法术等级\"],\"modId\":\"irons_spellbooks\",\"enumerate\":false,\"objectType\":\"SPELL\"}":
                            prompt.contains("mechanism/support")?"[\"奥术铁砧(Arcane Anvil)\"]":"[]";
                    return new LlmResult(response,null,"stop");
                }
            };
            var result=new EntityAgent(llm,db,embedder,store).resolveEntities("Iron's Spellbooks 里怎么提升法术等级？","");
            assertEquals(1,result.aliasHits().size());
            assertEquals("support",result.aliasHits().getFirst().role());
            assertEquals("奥术铁砧(Arcane Anvil)",result.aliasHits().getFirst().label());
            assertTrue(result.inferredEntities().isEmpty());
        } finally { db.shutdown(); }
    }

    @Test void missingPrimaryObjectUsesCrossModCorrectionBeforeAddingSupportPages() {
        var db=DatabaseBuilder.atPath(temp.resolve("cross"));db.initDatabase();
        try {
            for(String id:List.of("alexsmobs","iceandfire")) {
                var mod=new ModEntry();mod.setModId(id);db.saveModEntry(mod);
            }
            db.saveWebPages("alexsmobs",List.of(new WikiScraperService.WebPage("Alex","https://www.mcmod.cn/class/3318.html","catalog","mcmod",
                    Map.of("动物图鉴(Animal Dictionary)","https://www.mcmod.cn/item/1.html"))));
            var embedder=new EmbeddingService(null);var store=new VectorStore(db,384);
            store.storeBatch(List.of(new TextChunker.TextChunk("hydra","九头蛇(Hydra)","iceandfire","Ice","entity_alias","https://www.mcmod.cn/item/2.html")),embedder);
            var llm=new RagAgentService(null) {
                @Override public ChatModel getChatModel() { return null; }
                @Override public LlmResult askWithUsage(String caller,String prompt) {
                    String response=caller.equals("EntityAgent.analyze")?"{\"subjects\":[\"九头蛇\"],\"modId\":\"alexsmobs\",\"enumerate\":false}":
                            prompt.contains("mechanism/support")?"[\"动物图鉴(Animal Dictionary)\"]":"[]";
                    return new LlmResult(response,null,"stop");
                }
            };
            var result=new EntityAgent(llm,db,embedder,store).resolveEntities("Alex's Mobs 里的九头蛇怎么打？","");
            assertEquals(1,result.aliasHits().size());
            assertEquals("iceandfire",result.aliasHits().getFirst().modId());
            assertEquals("九头蛇(Hydra)",result.aliasHits().getFirst().label());
        } finally { db.shutdown(); }
    }

    @Test void vanillaEnumerationKeepsWikiRouteWithoutAModCatalog() {
        var db=DatabaseBuilder.atPath(temp.resolve("vanilla"));db.initDatabase();
        try {
            var embedder=new EmbeddingService(null);var store=new VectorStore(db,384);
            var llm=new RagAgentService(null) {
                @Override public ChatModel getChatModel() { return null; }
                @Override public LlmResult askWithUsage(String caller,String prompt) {
                    return new LlmResult("{\"subjects\":[\"中立生物\"],\"modId\":\"minecraft\",\"enumerate\":true,\"objectType\":\"ENTITY\",\"filters\":[\"neutral\"]}",null,"stop");
                }
            };
            var result=new EntityAgent(llm,db,embedder,store).resolveEntities("原版有哪些中立生物？","");
            assertTrue(result.plan().enumerate());
            assertTrue(result.wikiTopics().contains("生物"));
            assertTrue(result.wikiTopics().contains("中立生物"));
            assertNull(result.catalogEvidence());
        } finally { db.shutdown(); }
    }
}
