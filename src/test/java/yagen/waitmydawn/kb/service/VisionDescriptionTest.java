package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.config.AppConfig;
import yagen.waitmydawn.kb.model.*;
import java.net.http.HttpRequest;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class VisionDescriptionTest {
    @TempDir Path temp;
    @org.junit.jupiter.api.AfterEach void closeLog() { yagen.waitmydawn.kb.config.LogSetup.shutdown(); }

    @Test void defaultDisabledNeverSendsAnImageAndTextModelCredentialIsNotReused() throws Exception {
        var config = AppConfig.forIsolatedRun(false);
        config.setApiKeyForRun("text-model-fixture");
        var calls = new AtomicInteger();
        var service = new VisionDescriptionService(config) {
            @Override protected String send(HttpRequest request) {
                calls.incrementAndGet();
                assertEquals("Bearer vision-fixture", request.headers().firstValue("Authorization").orElseThrow());
                return "{\"choices\":[{\"message\":{\"content\":\"可见一个核心部件，具体型号不确定。\"}}]}";
            }
        };
        assertEquals("", service.describe(new byte[]{1})); assertEquals(0, calls.get());
        config.setVisionSettings(true, "https://vision.example/v1/chat/completions", "image-model", "vision-fixture");
        assertTrue(service.describe(new byte[]{1}).contains("不确定")); assertEquals(1, calls.get());
        config.setVisionSettings(false, "", "", "");
        assertEquals("", service.describe(new byte[]{1})); assertEquals(1, calls.get());
    }

    @Test void rejectsUnsafeEndpointsAndMissingModelBeforeSending() throws Exception {
        var config = AppConfig.forIsolatedRun(false);
        var service = new VisionDescriptionService(config) {
            @Override protected String send(HttpRequest request) { fail("无效配置不能上传图片"); return ""; }
        };
        config.setVisionSettings(true, "http://remote.example/api", "vision", "fixture");
        assertThrows(IllegalArgumentException.class, () -> service.describe(new byte[]{1}));
        config.setVisionSettings(true, "https://vision.example/api", "", "fixture");
        assertThrows(IllegalArgumentException.class, () -> service.describe(new byte[]{1}));
    }

    @Test void inferredDescriptionIsIndexedWithExplicitProvenanceAndImageEvidence() throws Exception {
        var config = AppConfig.forIsolatedRun(false); config.setDataDir(temp.resolve("data"));
        config.setVisionSettings(true, "http://localhost/api", "vision-fixture", "");
        var service = new VisionDescriptionService(config) {
            @Override protected String send(HttpRequest request) {
                return "{\"choices\":[{\"message\":{\"content\":\"图片中似乎是傀儡核心。\"}}]}";
            }
        };
        var db = DatabaseBuilder.atPath(temp.resolve("base")); db.initDatabase();
        var folder = Files.createDirectories(temp.resolve("guides"));
        ImageIO.write(DocumentImportTest.textImage(), "png", folder.resolve("core.png").toFile());
        LocalOcrEngine engine = image -> new LocalOcrEngine.Result("", "OK", "test");
        try (var embedder = new EmbeddingService(null)) {
            var request = new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY, folder.toString())));
            var result = new KnowledgeBuilder(config, db, embedder,
                    List.of(new DocumentDirectoryProcessor(config, engine, service))).build(request, null);
            assertEquals(1, result.visionImages);
            try (var c = db.getConnection(); var s = c.createStatement(); var r = s.executeQuery("SELECT content,extraction_method FROM rag_content_chunk")) {
                assertTrue(r.next()); assertTrue(r.getString(1).contains("模型图像描述（推断）"));
                assertTrue(r.getString(2).contains("VISION"));
            }
        } finally { db.shutdown(); }
    }
}
