package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.config.*;
import yagen.waitmydawn.kb.model.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class BuildTraceTest {
    @TempDir Path temp;
    private final ObjectMapper json=new ObjectMapper();
    @AfterEach void closeLogging(){LogSetup.shutdown();Thread.interrupted();}
    private JsonNode report(Path root)throws Exception{return json.readTree(root.resolve("timing.json").toFile());}
    private JsonNode metric(JsonNode root,String kind){for(var row:root.path("workMetrics"))if(row.path("kind").asText().equals(kind))return row;return json.nullNode();}
    private JsonNode span(JsonNode root,String stage){for(var row:root.path("spans"))if(row.path("stage").asText().equals(stage))return row;return json.nullNode();}
    @Test void nestedSpansSeparateWallTimeFromWorkAndStripUrlCredentials()throws Exception {
        var trace=BuildTrace.begin(temp,null);
        try(var source=BuildTrace.phase("source.url","https://name:password@example.com/page?token=not-a-real-secret#fragment")) {
            try(var child=BuildTrace.step("child","parse")){BuildTrace.work("embedding.document",2_000_000,2);}
            BuildTrace.network(true,"https://name:password@example.com/image.png?token=not-a-real-secret",200,4_000_000,false,false);
            BuildTrace.network(false,"https://example.com/a",302,1_000_000,false,false);
            BuildTrace.work("network.wait",8_000_000,1);
        }
        var result=new KnowledgeBuilder.BuildResult();long wall=trace.finish("PUBLISHED",result);
        var report=report(temp);assertEquals("PUBLISHED",report.path("outcome").asText());assertEquals(wall,report.path("wallMs").asLong());
        assertEquals(2,metric(report,"embedding.document").path("units").asInt());assertEquals(1,metric(report,"http.image").path("units").asInt());
        assertEquals(1,metric(report,"http.redirect").path("units").asInt());
        assertEquals(span(report,"source.url").path("id"),span(report,"child").path("parentId"));
        String events=Files.readString(temp.resolve("events.jsonl"));assertFalse(events.contains("password"));assertFalse(events.contains("not-a-real-secret"));assertFalse(events.contains("name:"));
        assertTrue(Files.readString(temp.resolve("timing.md")).contains("不能把所有行相加"));
    }
    @Test void workerContextIsCarriedExplicitlyAndRestoredOnReuse()throws Exception {
        var executor=Executors.newSingleThreadExecutor();var trace=BuildTrace.begin(temp,null);
        try {
            try(var phase=BuildTrace.phase("images.pipeline","fixture")) {
                executor.submit(BuildTrace.carry(()->{BuildTrace.work("http.image",1_000_000,1);try(var child=BuildTrace.step("worker","download")){BuildTrace.work("network.wait",2_000_000,1);}})).get(5,TimeUnit.SECONDS);
                executor.submit(()->BuildTrace.work("outside",1_000_000,1)).get(5,TimeUnit.SECONDS);
            }
        }finally{executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));trace.finish("PUBLISHED",null);}
        var report=report(temp);assertTrue(metric(report,"outside").isNull());assertEquals(1,metric(report,"http.image").path("units").asInt());
        assertEquals(span(report,"images.pipeline").path("id"),span(report,"worker").path("parentId"));
    }
    @Test void persistenceFailureDoesNotTurnAPublishedBuildIntoAnException()throws Exception {
        var file=temp.resolve("not-a-directory");Files.writeString(file,"fixture");
        var trace=BuildTrace.begin(file,null);var result=new KnowledgeBuilder.BuildResult();
        assertDoesNotThrow(()->trace.finish("PUBLISHED",result));assertFalse(result.warnings.isEmpty());
        var next=BuildTrace.begin(temp.resolve("next"),null);BuildTrace.work("next",0,1);next.finish("UNCHANGED",null);
        assertEquals("UNCHANGED",report(temp.resolve("next")).path("outcome").asText());
    }
    @Test void unclosedStageAndCancellationRemainInspectable()throws Exception {
        var trace=BuildTrace.begin(temp,null);var scope=BuildTrace.phase("preflight","fixture");
        trace.failure(new CancellationException());trace.finish("CANCELLED",null);scope.close();
        var report=report(temp);assertEquals("CANCELLED",report.path("outcome").asText());assertEquals("CancellationException",report.path("failureClass").asText());
        assertTrue(Files.readString(temp.resolve("events.jsonl")).contains("STAGE_UNCLOSED"));
    }
    @Test void realIsolatedBuildAndUnchangedAppendPersistTheirOwnReports()throws Exception {
        var config=AppConfig.forIsolatedRun(false);config.setDataDir(temp.resolve("base"));var db=new DatabaseBuilder(config);db.initDatabase();
        var root=RuntimeBuildFixtures.create(temp.resolve("MKB-Export"),List.of(),"neoforge","1.21.1");
        var source=BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,root.toString());
        var documents=Files.createDirectory(temp.resolve("documents"));Files.writeString(documents.resolve("notes.txt"),"诊断测试：这一段独立正文用于验证向量推理与数据库写入计时。");
        var sources=List.of(source,BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,documents.toString()));
        try(var embedding=new EmbeddingService(null)) {
            var first=new KnowledgeBuilder(config,db,embedding).build(new BuildRequest(sources),null);
            var firstPath=Path.of(first.timingReport).getParent();var firstReport=report(firstPath);
            assertEquals("PUBLISHED",firstReport.path("outcome").asText());assertEquals(first.durationMs,firstReport.path("wallMs").asLong());
            assertEquals("REBUILD",firstReport.path("inputs").path("mode").asText());
            assertTrue(metric(firstReport,"embedding.document").path("units").asInt()>0);
            assertTrue(metric(firstReport,"vector.write").path("units").asInt()>0);
            assertTrue(firstReport.path("spans").findValuesAsText("stage").contains("cleanup"));
            var next=new KnowledgeBuilder(config,db,embedding).build(new BuildRequest(sources,BuildRequest.Mode.APPEND),null);
            assertNotEquals(first.timingReport,next.timingReport);
            assertEquals("UNCHANGED",report(Path.of(next.timingReport).getParent()).path("outcome").asText());
            assertEquals("APPEND",report(Path.of(next.timingReport).getParent()).path("inputs").path("mode").asText());
        }finally{db.shutdown();}
    }
    @Test void failedPreflightWritesReportAndKeepsTheOriginalDatabase()throws Exception {
        var config=AppConfig.forIsolatedRun(false);config.setDataDir(temp.resolve("base"));var db=new DatabaseBuilder(config);db.initDatabase();
        var empty=Files.createDirectory(temp.resolve("documents"));new VectorStore(db,384);String before=db.contentFingerprint();
        try(var embedding=new EmbeddingService(null)) {
            assertThrows(IllegalArgumentException.class,()->new KnowledgeBuilder(config,db,embedding).build(new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,empty.toString()))),null));
            assertEquals(before,db.contentFingerprint());
            try(var folders=Files.list(config.getDataDir().resolve("build-tasks"))) {
                var folder=folders.filter(Files::isDirectory).findFirst().orElseThrow();assertEquals("FAILED",report(folder).path("outcome").asText());
            }
        }finally{db.shutdown();}
    }
    @Test void cancelledBuildPersistsCleanupTimingWithoutPublishing()throws Exception {
        var config=AppConfig.forIsolatedRun(false);config.setDataDir(temp.resolve("base"));var db=new DatabaseBuilder(config);db.initDatabase();
        var root=RuntimeBuildFixtures.create(temp.resolve("MKB-Export"),List.of(),"neoforge","1.21.1");
        try(var embedding=new EmbeddingService(null)) {
            var builder=new KnowledgeBuilder(config,db,embedding);String before=db.contentFingerprint();
            assertThrows(CancellationException.class,()->builder.build(new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,root.toString()))),
                    (phase,current,total,message)->{if(phase.equals("runtime"))throw new CancellationException("fixture");}));
            assertEquals(before,db.contentFingerprint());assertFalse(Thread.currentThread().isInterrupted());
            try(var folders=Files.list(config.getDataDir().resolve("build-tasks"))) {
                var folder=folders.filter(Files::isDirectory).findFirst().orElseThrow();var report=report(folder);
                assertEquals("CANCELLED",report.path("outcome").asText());
                assertTrue(report.path("spans").findValuesAsText("stage").contains("cleanup"));
            }
        }finally{db.shutdown();}
    }
}
