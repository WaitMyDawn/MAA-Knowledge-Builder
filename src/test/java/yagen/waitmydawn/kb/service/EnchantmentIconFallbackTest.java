package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.config.*;
import yagen.waitmydawn.kb.model.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EnchantmentIconFallbackTest {
    @TempDir Path temp;
    private final ObjectMapper json=new ObjectMapper();
    private static final String ENCHANTED="{\"components\":{\"minecraft:enchantments\":{\"levels\":{\"minecraft:sharpness\":3}}}}";
    @AfterEach void logging(){LogSetup.shutdown();}

    @Test void onlyKnownGlintStatesQualifyForDefaultAppearance()throws Exception {
        var enchanted=json.readTree(ENCHANTED);
        assertTrue(ItemVisualState.enchantmentOnly("minecraft:diamond_sword",enchanted));
        assertTrue(ItemVisualState.enchantmentOnly("minecraft:diamond_helmet",enchanted));
        assertFalse(ItemVisualState.enchantmentOnly("example:magic_sword",enchanted));
        assertFalse(ItemVisualState.enchantmentOnly("minecraft:potion",enchanted));
        for(String field:List.of("minecraft:custom_model_data","minecraft:dyed_color","minecraft:trim","example:model")){
            var mixed=enchanted.deepCopy();((ObjectNode)mixed.path("components")).put(field,1);
            assertFalse(ItemVisualState.enchantmentOnly("minecraft:diamond_sword",mixed));
        }
        assertFalse(ItemVisualState.enchantmentOnly("minecraft:diamond_sword",json.readTree("{\"components\":{\"minecraft:enchantments\":{\"levels\":\"unknown\"}}}")));
    }

    @Test void legacyNbtRejectsAdditionalOrMalformedFields()throws Exception {
        for(String nbt:List.of("{Enchantments:[{id:\"minecraft:sharpness\",lvl:3s}]}","{'Enchantments':[{lvl:2s,id:'minecraft:unbreaking'}]}"))
            assertTrue(ItemVisualState.enchantmentOnly("minecraft:diamond_sword",json.createObjectNode().put("nbt",nbt)));
        for(String nbt:List.of("{Enchantments:[],CustomModelData:3}","{Enchantments:[{id:'minecraft:sharpness',lvl:3s,extra:1}]}","{Enchantments:[broken]}","{display:{color:1}}"))
            assertFalse(ItemVisualState.enchantmentOnly("minecraft:diamond_sword",json.createObjectNode().put("nbt",nbt)));
    }

    @Test void randomTradePreviewRequiresKnownFactoryAndAnUnmodifiedEquipmentOutput()throws Exception {
        var entry=(ObjectNode)json.readTree("{\"status\":\"RANDOM_RULE\",\"factory\":\"net.minecraft.world.entity.npc.VillagerTrades$EnchantedItemForEmeralds\",\"output\":{\"count\":1,\"dynamicState\":true,\"id\":\"minecraft:diamond_leggings\"}}");
        assertTrue(RuleCardService.randomEnchantmentOutput(entry));
        var mixed=entry.deepCopy();((ObjectNode)mixed.path("output")).putObject("components").put("minecraft:custom_model_data",1);assertFalse(RuleCardService.randomEnchantmentOutput(mixed));
        var unknown=entry.deepCopy();unknown.put("factory","example.RandomEquipment");assertFalse(RuleCardService.randomEnchantmentOutput(unknown));
        var modded=entry.deepCopy();((ObjectNode)modded.path("output")).put("id","example:shape_changing_sword");assertFalse(RuleCardService.randomEnchantmentOutput(modded));
    }

    @Test void actualLookupAndCardsPreserveExactStateThenUseAnnotatedDefault()throws Exception {
        Path pack=temp.resolve("pack");var fixture=new NativeIconPackTest();var manifest=fixture.fixture(pack);manifest.put("schemaVersion",2);
        var defaultIcon=(ObjectNode)manifest.path("icons").get(0);defaultIcon.put("itemId","minecraft:diamond_sword");
        var exact=defaultIcon.deepCopy();var exactState=json.readTree(ENCHANTED);((ObjectNode)exactState.path("components").path("minecraft:enchantments").path("levels")).put("minecraft:sharpness",4);
        exact.set("state",exactState);exact.put("variant",NativeIconPack.stateVariant(exactState));((ArrayNode)manifest.path("icons")).add(exact);fixture.write(pack,manifest);
        var config=AppConfig.forIsolatedRun(false);config.setDataDir(temp.resolve("data"));var db=new DatabaseBuilder(config);db.initDatabase();
        try(var embedder=new EmbeddingService(null)){
            new RuntimeBuildFixtures.IconArchiveImporter(config,db,embedder).build(new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,pack.toString())),BuildRequest.Mode.APPEND),null);
            var store=new ItemIconStore(db);store.selectProfile(store.profiles().getFirst().id());var icons=new ItemIconService(db,config.getDataDir()).snapshot();
            assertTrue(icons.item("minecraft:diamond_sword",true,exactState).detail().contains("精确状态"));
            var fallback=icons.item("minecraft:diamond_sword",true,json.readTree(ENCHANTED));assertNotNull(fallback.image());assertEquals("DEFAULT_ENCHANTMENT_FALLBACK",fallback.source());assertTrue(fallback.detail().contains("附魔状态未单独导出"));
            assertEquals(icons.item("minecraft:diamond_sword",false).assetId(),fallback.assetId());
            assertEquals("MISSING",icons.item("minecraft:diamond_sword",true,json.readTree("{\"components\":{\"minecraft:custom_model_data\":1}}" )).source());
            assertEquals("MISSING",icons.item("minecraft:diamond_sword",true,null).source());
            var trade=json.readTree("{\"entries\":[{\"status\":\"DECLARATIVE\",\"inputs\":[{\"item\":\"minecraft:emerald\"}],\"output\":{\"item\":\"minecraft:diamond_sword\",\"components\":{\"minecraft:enchantments\":{\"levels\":{\"minecraft:sharpness\":3}}}}}]}");
            assertTrue(RuleCardService.usesEnchantmentFallback(trade,icons));assertEquals(410,RuleCardService.draw("trades",trade,icons).getHeight());
            var random=trade.deepCopy();var entry=(ObjectNode)random.path("entries").get(0);entry.put("status","RANDOM_RULE").put("factory","net.minecraft.world.entity.npc.VillagerTrades$EnchantedItemForEmeralds");var randomOutput=(ObjectNode)entry.path("output");randomOutput.remove("components");randomOutput.put("dynamicState",true);
            assertTrue(RuleCardService.usesEnchantmentFallback(random,icons));assertEquals(410,RuleCardService.draw("trades",random,icons).getHeight());
            entry.put("factory","example.DynamicModel");assertFalse(RuleCardService.usesEnchantmentFallback(random,icons));assertEquals(380,RuleCardService.draw("trades",random,icons).getHeight());
            String recipe="{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[{\"item\":\"minecraft:diamond_sword\"}],\"result\":{\"id\":\"minecraft:diamond_sword\",\"components\":{\"minecraft:enchantments\":{\"levels\":{\"minecraft:sharpness\":3}}}}}";
            var card=new RendererService(config,db).renderDetailed(List.of(new QaPipeline.RecipeMatch(recipe,"minecraft:diamond_sword","minecraft","TEST_FIXTURE"))).cards().getFirst();
            assertTrue(card.plan().output().stateful());assertEquals(fallback.source(),card.output().source());assertTrue(card.notes().stream().anyMatch(n->n.contains("附魔状态未单独导出")));
        }finally{db.shutdown();}
    }
}
