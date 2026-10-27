package yagen.waitmydawn.kb.service;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.config.*;
import yagen.waitmydawn.kb.model.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ModUrlBindingTest {
    @TempDir Path temp;
    static ModEntry mod(String id,String loader){var m=new ModEntry();m.setModId(id);m.setLoader(loader);m.setVersion("1");return m;}
    static ModUrlBinding binding(String id,Long views,String origin){return new ModUrlBinding("https://www.mcmod.cn/class/"+id+".html","海灵物语","Aquamirae",views,"FOUND",origin,null,null);}
    @Test void metadataUsesExactTotalViewsRatherThanOtherNumericStatistics(){
        var html=Jsoup.parse("<div class='class-title'><h3>海灵物语</h3><h4>Aquamirae</h4></div><div class='infos'><div class='span' title='999999'>总评分</div><div class='span' title='269467'><span>总浏览</span>26.94万</div></div>");
        var b=ModUrlBinder.parse(html,"https://www.mcmod.cn/class/3318.html","AUTO");
        assertEquals("海灵物语（Aquamirae）",b.alias());assertEquals(269467L,b.views());
        html.select(".infos").remove();assertNull(ModUrlBinder.parse(html,b.url(),"AUTO").views());
        html.select("h4").remove();assertEquals("海灵物语",ModUrlBinder.parse(html,b.url(),"AUTO").alias());
    }
    @Test void canonicalizationRejectsNonModPagesAndForeignHosts(){
        assertEquals("https://www.mcmod.cn/class/3318.html",ModUrlBinding.normalize("http://mcmod.cn/class/3318.html?x=1#name"));
        for(String url:List.of("https://www.mcmod.cn/modpack/737.html","https://evil.test/class/1.html","https://www.mcmod.cn:443/class/1.html","https://evil@www.mcmod.cn/class/1.html"))assertThrows(IllegalArgumentException.class,()->ModUrlBinding.normalize(url));
    }
    @Test void reviewSortKeepsVanillaFirstThenViewsThenMissingBindings(){
        var vanilla=mod("minecraft","vanilla");var high=mod("z","forge");high.setMcmodBinding(binding("1",269467L,"AUTO"));var low=mod("a","forge");low.setMcmodBinding(binding("2",5L,"AUTO"));var unknown=mod("b","forge");unknown.setMcmodBinding(binding("3",null,"AUTO"));
        var entries=new ArrayList<>(List.of(mod("d","forge"),unknown,low,mod("c","forge"),high,vanilla));entries.sort(ModUrlBinding.reviewOrder());
        assertEquals(List.of("minecraft","z","a","b","c","d"),entries.stream().map(ModEntry::getModId).toList());
    }
    @Test void binderPreservesManualBindingsAndDistinguishesNoResultFromFailure(){
        var manual=mod("one","forge");manual.setMcmodBinding(binding("10",null,"MANUAL"));
        var noResult=new ModUrlBinder(new WikiScraperService(){@Override public String findModCandidateUrl(String id){return null;}});
        assertSame(manual.getMcmodBinding(),noResult.bind(manual));assertEquals("NOT_FOUND",noResult.bind(mod("two","forge")).status());
        var failed=new ModUrlBinder(new WikiScraperService(){@Override public String findModCandidateUrl(String id){throw new IllegalStateException("offline");}});
        assertEquals("FAILED",failed.bind(mod("two","forge")).status());
    }
    @Test void encyclopediaFallsBackToDisplayNameOnlyAfterSuccessfulEmptyModIdSearch(){
        var queries=new ArrayList<String>();
        var scraper=new WikiScraperService(){
            @Override public String findModCandidateUrl(String query){queries.add(query);return query.equals("Farming for Blockheads")?"https://www.mcmod.cn/class/123.html":null;}
            @Override public Resource fetchBuildResource(String url,String referer,boolean image){return new Resource(200,"<div class='class-title'><h3>农场贸易</h3><h4>Farming for Blockheads</h4></div>".getBytes(java.nio.charset.StandardCharsets.UTF_8),"text/html",url,null,null);}
        };
        var entry=mod("farmingforblockheads","forge");entry.setDisplayName("Farming for Blockheads");var result=new ModUrlBinder(scraper).bind(entry);
        assertEquals("FOUND",result.status());assertEquals(List.of("farmingforblockheads","Farming for Blockheads"),queries);
        queries.clear();entry.setDisplayName("FARMINGFORBLOCKHEADS");assertEquals("NOT_FOUND",new ModUrlBinder(scraper).bind(entry).status());assertEquals(1,queries.size());
        var failures=new ArrayList<String>();var unavailable=new ModUrlBinder(new WikiScraperService(){@Override public String findModCandidateUrl(String query){failures.add(query);throw new IllegalStateException("offline");}});
        entry.setDisplayName("Farming for Blockheads");assertEquals("FAILED",unavailable.bind(entry).status());assertEquals(List.of("farmingforblockheads"),failures);
    }
    @Test void draftsAreLoaderScopedPreserveExplicitClearsAndDoNotChangePublishedFingerprint()throws Exception {
        var cfg=AppConfig.forIsolatedRun(false);cfg.setDataDir(temp);var db=new DatabaseBuilder(cfg);db.initDatabase();new VectorStore(db,384);
        try {
            var published=mod("one","forge");published.setSlug("old");published.setMcmodBinding(binding("10",1L,"AUTO"));db.saveModEntry(published);String before=db.contentFingerprint();
            var desired=mod("one","forge");desired.setMcmodBinding(binding("20",2L,"MANUAL"));var store=new ModBindingStore(db);store.save(desired);
            assertEquals(before,db.contentFingerprint());assertEquals("10",db.findModEntry("one").getMcmodBinding().classId());
            var scanned=mod("one","forge");scanned.setVersion("2");assertTrue(store.apply(scanned,published));assertNull(scanned.getSlug());assertEquals("20",scanned.getMcmodBinding().classId());assertFalse(ModBindingStore.shouldLookupSlug(scanned));assertTrue(scanned.getMcmodBinding().note().contains("版本"));
            var different=mod("one","neoforge");assertFalse(store.apply(different,published));assertNull(different.getMcmodBinding());
            var seed=TrainingSeed.SeedMod.from(scanned);var mapper=new com.fasterxml.jackson.databind.ObjectMapper();assertEquals(seed.mcmodBinding,mapper.readValue(mapper.writeValueAsString(seed),TrainingSeed.SeedMod.class).mcmodBinding);
        } finally{db.shutdown();LogSetup.shutdown();}
    }
}
