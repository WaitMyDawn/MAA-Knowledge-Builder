package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.config.AppConfig;
import yagen.waitmydawn.kb.config.LogSetup;
import yagen.waitmydawn.kb.model.BuildRequest;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 显式启用的真实站点验收；默认测试不得向百科发送请求。 */
@EnabledIfSystemProperty(named = "maa.live.modpack737", matches = "true")
class UrlBuildLiveTest {
    @TempDir Path temp;

    @Test void importsSixRealTutorialsAndReferencedImages() throws Exception {
        var config = AppConfig.forIsolatedRun(false); config.setDataDir(temp.resolve("data"));
        var db = new DatabaseBuilder(config); db.initDatabase();
        String imageBudget = System.getProperty("maa.build.maxWebImages");
        String pageBudget = System.getProperty("maa.build.maxWebImagesPerPage");
        System.setProperty("maa.build.maxWebImages", "14");
        System.setProperty("maa.build.maxWebImagesPerPage", "2");
        try (var embedder = new EmbeddingService(null)) {
            var request = new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.URL, "https://www.mcmod.cn/modpack/737.html")));
            var result = new KnowledgeBuilder(config, db, embedder).build(request, null);
            var tutorials = new ArrayList<String>();
            int chunks, images, webp;
            try (var c = db.getConnection(); var s = c.createStatement()) {
                try (var r = s.executeQuery("SELECT location FROM rag_document WHERE location LIKE 'https://www.mcmod.cn/post/%' ORDER BY location")) {
                    while (r.next()) tutorials.add(r.getString(1));
                }
                try (var r = s.executeQuery("SELECT COUNT(*) FROM rag_content_chunk")) { r.next(); chunks = r.getInt(1); }
                try (var r = s.executeQuery("SELECT COUNT(*) FROM rag_image_asset")) { r.next(); images = r.getInt(1); }
                try (var r = s.executeQuery("SELECT COUNT(*) FROM rag_image_occurrence o JOIN rag_image_asset i ON o.image_id=i.id WHERE o.original_location LIKE '%.webp' AND i.media_type='image/png'")) { r.next(); webp = r.getInt(1); }
            }
            var report = new LinkedHashMap<String, Object>();
            report.put("verifiedAt", java.time.Instant.now().toString());
            report.put("url", request.sources().getFirst().location());
            report.put("expectedTutorialCount", 6); report.put("tutorialUrls", tutorials);
            report.put("documents", result.documents); report.put("chunks", chunks);
            report.put("vectors", new VectorStore(db, 384).count());
            report.put("imageAssets", images); report.put("webpConverted", webp);
            report.put("uncachedImageOccurrences", result.deferredWebImages);
            report.put("imageDownloadBudget", 14);
            var questions=yagen.waitmydawn.kb.eval.GoldenSet.load(Path.of("golden-set-modpack.json"));
            var retrievals=new ArrayList<Map<String,Object>>();
            var retrieval=new KnowledgeRetrievalService(db,embedder,config.getDataDir());
            for(var conversation:questions.conversations()) for(var turn:conversation.turns()) {
                var scope=retrieval.resolve(turn.question(),null,null);
                assertNotNull(scope.scope());assertEquals("mcmod:modpack:737",scope.scope().id());
                var evidence=retrieval.retrieve(turn.question(),scope);
                assertFalse(evidence.hits().isEmpty(),turn.question());
                retrievals.add(Map.of("question",turn.question(),"chunks",evidence.hits().size(),"pictures",evidence.artifacts().pictures().size(),
                        "sources",evidence.artifacts().sources().stream().map(yagen.waitmydawn.kb.model.AnswerArtifacts.Source::location).distinct().toList()));
            }
            report.put("questionRetrieval",retrievals);
            report.put("answerFactsVerified",false);
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of("target/stage2-live-report.json").toFile(), report);
            assertEquals(6, result.tutorials); assertEquals(7, result.documents);
            assertEquals(Set.of("4727", "4984", "5267", "5355", "5454", "5557"), tutorials.stream()
                    .map(url -> java.net.URI.create(url).getPath().replaceAll("[^0-9]", "")).collect(java.util.stream.Collectors.toSet()));
            assertTrue(chunks > 100); assertTrue(images > 0); assertTrue(webp > 0);
            new ImageAssetStore(config.getDataDir()).validate(new KnowledgeContentStore(db), db);
        } finally {
            if (imageBudget == null) System.clearProperty("maa.build.maxWebImages"); else System.setProperty("maa.build.maxWebImages", imageBudget);
            if (pageBudget == null) System.clearProperty("maa.build.maxWebImagesPerPage"); else System.setProperty("maa.build.maxWebImagesPerPage", pageBudget);
            db.shutdown(); LogSetup.shutdown();
        }
    }
}
