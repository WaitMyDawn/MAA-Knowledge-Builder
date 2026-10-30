package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.config.*;
import yagen.waitmydawn.kb.model.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeMediaCacheTest {
    @TempDir Path temp;
    @AfterEach void logging(){LogSetup.shutdown();}
    @Test void indexReuseDoesNotRewriteAssetsAndGenerationChangeInvalidatesIt()throws Exception {
        var fixture=new EntityStateProtocolTest();fixture.temp=temp;
        var root=fixture.fixture(List.of(fixture.row("adult","red",false,256,true)));
        var config=AppConfig.forIsolatedRun(false).forBuildTarget(temp);var db=new DatabaseBuilder(config);db.initDatabase();
        try(var embedding=new EmbeddingService(null)) {
            new KnowledgeBuilder(config,db,embedding).build(new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,root.toString()))),null);
            var media=new RuntimeEntityMedia(db,temp,true);var first=new QaPipeline.QaResult();media.prepare(first,"展示 mkb_fixture:creature 成年实体外观");
            assertFalse(first.mediaPerformance.indexCacheHit());assertEquals(1,first.mediaPerformance.parsedRecords());assertEquals(1,first.mediaPerformance.reusedAssets());
            var picture=first.artifacts.pictures().getFirst();assertEquals("adult",picture.runtimeIdentity().attributes().get("age"));
            Path file=AnswerMedia.resolveImage(temp,picture);var modified=Files.getLastModifiedTime(file);String fingerprint=db.contentFingerprint();
            var second=new QaPipeline.QaResult();media.prepare(second,"展示 mkb_fixture:creature 成年实体外观");
            assertTrue(second.mediaPerformance.indexCacheHit());assertEquals(0,second.mediaPerformance.parsedRecords());assertEquals(first.artifacts,second.artifacts);
            assertEquals(modified,Files.getLastModifiedTime(file));assertEquals(fingerprint,db.contentFingerprint());
            db.setBuildGeneration("replacement");var third=new QaPipeline.QaResult();media.prepare(third,"展示 mkb_fixture:creature 成年实体外观");
            assertFalse(third.mediaPerformance.indexCacheHit());assertEquals(1,third.mediaPerformance.parsedRecords());
            byte[] corrupt=Files.readAllBytes(file);corrupt[0]^=1;Files.write(file,corrupt);
            var invalid=new QaPipeline.QaResult();media.prepare(invalid,"展示 mkb_fixture:creature 成年实体外观");
            assertTrue(invalid.mediaPerformance.indexCacheHit());assertTrue(invalid.artifacts.pictures().isEmpty());assertFalse(invalid.mediaWarnings.isEmpty());
        } finally {db.shutdown();}
    }
    @Test void latencyPercentilesUseNearestRankAndEmptyMeasurementsAreExplicit() {
        assertEquals(0,MultimodalAuditRunner.latency(List.of()).get("samples"));
        assertEquals(2.0,MultimodalAuditRunner.latency(List.of(1_000_000L,3_000_000L,2_000_000L)).get("p50Ms"));
        assertEquals(3.0,MultimodalAuditRunner.latency(List.of(1_000_000L,3_000_000L,2_000_000L)).get("p95Ms"));
    }
}
