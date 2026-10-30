package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ResourceTelemetryTest {
    @AfterEach void cleanup(){RetrievalTelemetry.end();}
    @Test void resourceKindsWaitsAndReasonsAreCountedWithoutPersistingQuerySecrets() {
        RetrievalTelemetry.begin();
        try(var scope=RetrievalTelemetry.resourceCause("ENTITY_DETAILS_GAP")) {
            RetrievalTelemetry.httpAttempt();RetrievalTelemetry.resourceAttempt(false,"https://example.com/page?token=private",200,5_000_000,false,false);
            RetrievalTelemetry.httpAttempt();RetrievalTelemetry.resourceAttempt(true,"https://example.com/image.png?key=private",503,6_000_000,false,false);
            RetrievalTelemetry.httpAttempt();RetrievalTelemetry.resourceAttempt(true,"https://example.com/image.png?key=private",200,7_000_000,true,true);
        }
        RetrievalTelemetry.waitTime(8_000_000);RetrievalTelemetry.backoffTime(9_000_000);
        var audit=RetrievalTelemetry.resources();assertEquals(3,audit.requests());assertEquals(1,audit.documents());assertEquals(2,audit.images());assertEquals(1,audit.retries());assertEquals(1,audit.redirects());assertEquals(8,audit.waitMs());assertEquals(9,audit.backoffMs());assertEquals(18,audit.fetchMs());
        assertFalse(audit.events().toString().contains("private"));assertEquals("ENTITY_DETAILS_GAP",audit.events().getFirst().cause());
    }
    @Test void boundedEventsDoNotTruncateTotalsAndWorkerContextDoesNotLeak()throws Exception {
        RetrievalTelemetry.begin();var executor=Executors.newSingleThreadExecutor();
        try {
            try(var scope=RetrievalTelemetry.resourceCause("BODY_IMAGE_CACHE")) {executor.submit(RetrievalTelemetry.carry(()->{for(int i=0;i<230;i++){RetrievalTelemetry.httpAttempt();RetrievalTelemetry.resourceAttempt(true,"https://example.com/a.png",200,1,false,false);}})).get();}
            var audit=RetrievalTelemetry.resources();assertEquals(230,audit.requests());assertEquals(200,audit.events().size());assertTrue(audit.truncated());
            assertNull(executor.submit(RetrievalTelemetry::current).get());
        }finally{executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
    }
}
