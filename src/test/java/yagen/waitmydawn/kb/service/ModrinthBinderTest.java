package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ModrinthBinderTest {
    @TempDir Path temp;
    @Test void unavailableLookupPreservesPreviouslyBoundProject() {
        var previous = new yagen.waitmydawn.kb.model.ModEntry();
        previous.setSlug("known-project"); previous.setLoader("neoforge"); previous.setMcVersion("1.21.1");
        var entry = new yagen.waitmydawn.kb.model.ModEntry();
        entry.setSource("modrinth-unavailable"); entry.setLoader("neoforge"); entry.setMcVersion("1.21.1");
        ModrinthBinder.preserveKnownBinding(entry, previous);
        assertEquals("known-project", entry.getSlug());
        assertEquals("modrinth-unavailable", entry.getSource());
    }

    @Test void networkFailureIsNotNoMatchAndStopsRepeatedScanRequests() {
        var requests = new AtomicInteger();
        var binder = new ModrinthBinder(uri -> { requests.incrementAndGet(); throw new java.net.http.HttpConnectTimeoutException("connect timed out"); });
        assertEquals("modrinth-unavailable", binder.bind("mod", "Mod", "1.2", "1.21.1", "neoforge").source());
        assertEquals("modrinth-unavailable", binder.bind("other", "Other", "1.2", "1.21.1", "neoforge").source());
        assertEquals(1, requests.get());
    }
    @Test void successfulEmptySearchIsExplicitNoMatch() {
        var binder = new ModrinthBinder(uri -> new ModrinthBinder.Response(200, "{\"hits\":[]}"));
        assertEquals("modrinth-no-match", binder.bind("mod", "mod", "1.2", "1.21.1", "neoforge").source());
    }
    @Test void substringVersionDoesNotCreateFalseBinding() {
        var binder = new ModrinthBinder(uri -> uri.getPath().endsWith("/search")
                ? new ModrinthBinder.Response(200, "{\"hits\":[{\"slug\":\"mod\",\"project_id\":\"project\"}]}")
                : new ModrinthBinder.Response(200, "[{\"version_number\":\"1.20\"}]"));
        assertNull(binder.bind("mod", "mod", "1.2", "1.21.1", "neoforge").slug());
    }
    @Test void jarHashResolvesProjectWithoutSearchOrVersionGuessing() throws Exception {
        Path jar = Files.write(temp.resolve("mod.jar"), new byte[]{1,2,3});
        var requests = new AtomicInteger();
        var binder = new ModrinthBinder(uri -> {
            requests.incrementAndGet();
            if (uri.getPath().startsWith("/v2/version_file/")) return new ModrinthBinder.Response(200, "{\"project_id\":\"project\"}");
            assertEquals("/v2/project/project", uri.getPath());
            return new ModrinthBinder.Response(200, "{\"slug\":\"exact-project\"}");
        });
        assertEquals("exact-project", binder.bind(jar,"mod","Mod","different","1.21.1","neoforge").slug());
        assertEquals(2, requests.get());
    }
    @Test void searchFallsBackFromModIdToDisplayNameAndRetainsVersionChecks(){
        var queries=new java.util.ArrayList<String>();
        var binder=new ModrinthBinder(uri->{
            if(uri.getPath().endsWith("/search")){
                String query=java.net.URLDecoder.decode(uri.getRawQuery(),java.nio.charset.StandardCharsets.UTF_8);queries.add(query);
                return new ModrinthBinder.Response(200,query.contains("Farming for Blockheads")?"{\"hits\":[{\"slug\":\"farming-for-blockheads\",\"project_id\":\"farming\"}]}":"{\"hits\":[]}");
            }
            return new ModrinthBinder.Response(200,"[{\"version_number\":\"14.0.2\"}]");
        });
        assertEquals("farming-for-blockheads",binder.bind("farmingforblockheads","Farming for Blockheads","14.0.2","1.20.1","forge").slug());
        assertEquals(2,queries.size());assertTrue(queries.get(0).contains("query=farmingforblockheads"));assertTrue(queries.get(1).contains("query=Farming for Blockheads"));
    }
    @Test void confirmedEnvironmentControlsSearchFiltersDespiteJarLoaderMetadata(){
        var environment=new yagen.waitmydawn.kb.model.GameEnvironment(temp.resolve("Minecraft.jar").toString(),"neoforge","1.21.1");
        var binder=new ModrinthBinder(uri->{String query=java.net.URLDecoder.decode(uri.getRawQuery(),java.nio.charset.StandardCharsets.UTF_8);assertTrue(query.contains("categories:neoforge"));assertTrue(query.contains("versions:1.21.1"));return new ModrinthBinder.Response(200,"{\"hits\":[]}");});
        assertEquals("modrinth-no-match",binder.bind(null,"mod","Mod","1",environment).source());
    }
    @Test void acceptsOnlyCompleteKnownEnvironmentSuffixRatherThanSubstringVersions(){
        for(String published:java.util.List.of("14.0.2+forge-1.20.1","14.0.20+forge-1.20.1","14.0.2+fabric-1.20.1","14.0.2+forge-1.19.2","14.0.2+addon")){
            var binder=new ModrinthBinder(uri->uri.getPath().endsWith("/search")?new ModrinthBinder.Response(200,"{\"hits\":[{\"slug\":\"farming-for-blockheads\",\"project_id\":\"farming\"}]}"):new ModrinthBinder.Response(200,"[{\"version_number\":\""+published+"\"}]"));
            var actual=binder.bind("farmingforblockheads","Farming for Blockheads","14.0.2","1.20.1","forge").slug();
            if(published.equals("14.0.2+forge-1.20.1"))assertEquals("farming-for-blockheads",actual);else assertNull(actual);
        }
    }
    @Test void rateLimitCannotBeReportedAsAbsentProject() {
        var binder = new ModrinthBinder(uri -> new ModrinthBinder.Response(429,"{}"));
        assertEquals("modrinth-unavailable", binder.bind("mod","mod","1","1.21.1","neoforge").source());
    }
    @Test void searchIncludesModTypeAndVersionFilters() {
        var binder = new ModrinthBinder(uri -> {
            assertTrue(uri.getQuery().contains("project_type:mod"));
            assertTrue(uri.getQuery().contains("categories:neoforge"));
            assertTrue(uri.getQuery().contains("versions:1.21.1"));
            return new ModrinthBinder.Response(200, "{\"hits\":[]}");
        });
        binder.bind("mod","mod","1","1.21.1","neoforge");
    }
}
