package yagen.waitmydawn.kb.service;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class CatalogAndRecipeTest {
    @Test void wikiInfoboxStatsAreEvidenceWhileCommentsAndNavigationAreExcluded() {
        var body=WikiBodyExtractor.extract(Jsoup.parse("""
                <div id="main"><table class="infobox"><tr><th>生命值</th><td>200</td></tr></table>
                <p>只能受到玩家和爆炸伤害。</p><div class="comments"><p>虚构的失压死亡。</p></div>
                <div class="navbox"><p>无关导航。</p></div></div>
                """).getElementById("main"));
        assertTrue(body.contains("生命值 | 200")); assertTrue(body.contains("玩家和爆炸"));
        assertFalse(body.contains("失压死亡")); assertFalse(body.contains("无关导航"));
    }
    @Test void metadataCommandsDoNotPromoteBodyReferencesOrNbtItemsToTargets() {
        String html="""
                <div class="maintext"><span data-command='/give @p iceandfire:nest 64'></span>
                <span data-command='/summon iceandfire:fire_dragon ~ ~ ~ {HandItems:[{id:"minecraft:stone"}]}'></span></div>
                <div class="item-content"><span data-command='/give @p other:unrelated 1'></span></div>
                """;
        assertEquals(List.of("iceandfire:nest","iceandfire:fire_dragon"),new WikiScraperService().extractMcmodRegistries(html));
    }

    @Test void clearingBuildDataRemovesCatalogAndLeavesAnEmptyHealthyIndex() {
        var db=DatabaseBuilder.atPath(temp.resolve("clear"));db.initDatabase();new VectorStore(db,384);
        try {
            var store=new WebCatalogStore(db);
            store.save(new WebCatalogParser().parse(Jsoup.parse(HTML,CATEGORY_URL),"alexsmobs",CATEGORY_URL,4,"生物"));
            assertFalse(store.entries("alexsmobs",4).isEmpty());
            db.clearBuildData();
            assertTrue(store.entries("alexsmobs",4).isEmpty());
            assertDoesNotThrow(db::healthCheck);
        } finally { db.shutdown(); }
    }
    @Test void wikiDropTablesRetainHeadersIconsAndSourceSection() {
        var doc=Jsoup.parse("""
                <div id="mw-content-text"><h2>获取</h2><h3>生物掉落物</h3>
                <table class="wikitable"><tr><th>生物</th><th>无抢夺数量</th></tr>
                <tr><td><a title="末影人"><img alt=""></a></td><td>0–1</td></tr></table>
                <div class="navbox"><table><tr><td>无关导航</td></tr></table></div></div>
                """);
        String text=WikiBodyExtractor.extract(doc.selectFirst("#mw-content-text"));
        assertTrue(text.contains("生物掉落物 表格；表头：生物 | 无抢夺数量] 末影人 | 0–1"));
        assertFalse(text.contains("无关导航"));
    }

    @Test void enumerationMapsNaturalStructuresAndSpellsToActualCatalogIds() {
        assertEquals(8,new yagen.waitmydawn.kb.dto.RetrievalPlan("structures","mod",true,"STRUCTURE",List.of(),3,false).topCategory());
        assertEquals(229,new yagen.waitmydawn.kb.dto.RetrievalPlan("spells","mod",true,"SPELL",List.of(),3,false).topCategory());
    }
    @TempDir Path temp;
    private static final String CATEGORY_URL="https://www.mcmod.cn/item/list/3318-4.html";
    private static final String HTML="""
            <table><tr><th class="item-list-type-left">现实生物</th><td class="item-list-type-right">
            <table><tr><th class="item-list-type-left" rowspan="2">哺乳动物</th><th class="item-list-type-left">陆地</th><td class="item-list-type-right"><a href="/item/1.html" data-en="Gorilla">大猩猩</a></td></tr>
            <tr><th class="item-list-type-left">水生动物</th><td class="item-list-type-right"><a href="/item/2.html">虎鲸</a></td></tr>
            <tr><th class="item-list-type-left" colspan="2">水生动物</th><td class="item-list-type-right"><a href="/item/2.html">虎鲸</a><a href="/item/3.html">鲶鱼</a></td></tr></table></td></tr>
            <tr><th class="item-list-type-left">技术性实体</th><td class="item-list-type-right"><a href="/item/4.html">跨板</a></td></tr>
            <tr><th class="item-list-type-left">未完成</th><td class="item-list-type-right"><a href="/item/5.html">海熊</a></td></tr></table>
            """;

    @Test void nestedHeadersSpansAndMultipleMembershipsSurviveParsing() {
        var catalog=new WebCatalogParser().parse(Jsoup.parse(HTML,CATEGORY_URL),"alexsmobs",CATEGORY_URL,4,"生物");
        assertEquals(6,catalog.members().size());
        var whaleNodes=catalog.members().stream().filter(m -> m.chineseName().equals("虎鲸"))
                .map(m -> catalog.nodes().stream().filter(n -> n.id().equals(m.categoryId())).findFirst().orElseThrow()).toList();
        assertEquals(Set.of(List.of("生物","现实生物","哺乳动物","水生动物"),List.of("生物","现实生物","水生动物")),
                whaleNodes.stream().map(n -> n.path()).collect(java.util.stream.Collectors.toSet()));
        assertEquals("technical",catalog.members().stream().filter(m -> m.chineseName().equals("跨板")).findFirst().orElseThrow().role());
        assertEquals("unfinished",catalog.members().stream().filter(m -> m.chineseName().equals("海熊")).findFirst().orElseThrow().role());
        assertEquals(catalog,new WebCatalogParser().parse(Jsoup.parse(HTML,CATEGORY_URL),"alexsmobs",CATEGORY_URL,4,"生物"));
    }

    @Test void catalogPublishesWithBuildAndParticipatesInFingerprint() {
        var base=DatabaseBuilder.atPath(temp.resolve("base")); var stage=DatabaseBuilder.atPath(temp.resolve("stage"));
        base.initDatabase();stage.initDatabase();new VectorStore(base,384);new VectorStore(stage,384);
        try {
            String before=stage.contentFingerprint();
            new WebCatalogStore(stage).save(new WebCatalogParser().parse(Jsoup.parse(HTML,CATEGORY_URL),"alexsmobs",CATEGORY_URL,4,"生物"));
            assertNotEquals(before,stage.contentFingerprint());
            assertTrue(new WebCatalogStore(base).entries("alexsmobs",4).isEmpty());
            base.replaceBuildDataFrom(stage);
            assertEquals(6,new WebCatalogStore(base).entries("alexsmobs",4).size());
            assertTrue(new WebCatalogStore(base).entries("other",4).isEmpty());
        } finally { base.shutdown();stage.shutdown(); }
    }

    @Test void scopedBodySearchExcludesAliasesForeignModsAndOtherPages() {
        var db=DatabaseBuilder.atPath(temp.resolve("scope"));db.initDatabase();
        try {
            var embedder=new EmbeddingService(null);var store=new VectorStore(db,384);
            store.storeBatch(List.of(
                    new TextChunker.TextChunk("body","dragon taming detail","iceandfire","Ice","description","page-a"),
                    new TextChunker.TextChunk("alias","dragon taming detail","iceandfire","Ice","entity_alias","page-a"),
                    new TextChunker.TextChunk("foreign","dragon taming detail","other","Other","description","page-a"),
                    new TextChunker.TextChunk("page","dragon taming detail","iceandfire","Ice","description","page-b")),embedder);
            var hits=VectorStore.searchScoped(List.of(db),embedder.embed("dragon taming"),10,embedder.profile(),"iceandfire",Set.of("page-a"));
            assertEquals(List.of("body"),hits.stream().map(VectorStore.SearchResult::chunkId).toList());
            assertTrue(VectorStore.searchScoped(List.of(db),embedder.embed("dragon"),0,embedder.profile(),"iceandfire",Set.of()).isEmpty());
        } finally { db.shutdown(); }
    }

    @Test void shapedCountsUseSlotsAndPreserveTagAlternatives() {
        String summary=RecipeSummary.describe("""
                {"type":"minecraft:crafting_shaped","pattern":["AN","NA"],"key":{"A":{"item":"minecraft:andesite"},"N":[{"tag":"c:nuggets/iron"},{"item":"create:zinc_nugget"}]},"result":{"item":"create:andesite_alloy","count":2}}
                ""","create:andesite_alloy");
        assertTrue(summary.contains("minecraft:andesite × 2"));
        assertTrue(summary.contains("任选其一(标签 #c:nuggets/iron / create:zinc_nugget) × 2"));
        assertTrue(summary.contains("create:andesite_alloy × 2"));
        assertTrue(summary.contains("AN\nNA"));
    }

    @Test void repairRequiresExistingOutputAndUnknownTypesDoNotInventCounts() {
        assertTrue(RecipeSummary.describe("""
                {"type":"minecraft:crafting_shapeless","ingredients":[{"item":"explorerscompass:explorerscompass"},{"item":"minecraft:compass"}],"result":{"item":"explorerscompass:explorerscompass"}}
                ""","explorerscompass:explorerscompass").contains("不是首次制作"));
        assertTrue(RecipeSummary.describe("{\"type\":\"custom:unknown\"}","x:y").contains("不生成材料数量"));
    }
}
