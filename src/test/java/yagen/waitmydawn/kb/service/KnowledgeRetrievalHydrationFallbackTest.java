package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.model.AnswerArtifacts;
import yagen.waitmydawn.kb.model.DatabaseBuilder;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 选图三步之后的两条通用兜底：复合步骤补缺、整轮零图时保留该页正文图。 */
class KnowledgeRetrievalHydrationFallbackTest {
    @TempDir Path temp;

    @Test void facetFallbackAddsUpgradeImageNextToExistingAssemblyPicture() throws Exception {
        var db = DatabaseBuilder.atPath(temp.resolve("facet-db"));
        db.initDatabase();
        var store = new ImageAssetStore(temp);
        try (var c = db.getConnection()) {
            block(c, "b10", "d1", 10, "番外篇 > 傀儡使者 > 傀儡的升级与装备", "辅助升级：多才多艺，升级后第一次不消耗槽位。");
            reference(c, "d1/image/1", "d1", "https://example.com/upgrade.png", "辅助升级：多才多艺", "辅助升级：多才多艺，升级后第一次不消耗槽位。", "AVAILABLE", 10);
            var asset = store.put(FullWebMediaTest.image(1, 400, 400));
            asset(c, asset.id(), asset.relativePath(), 400, 400);
            occurrence(c, "d1/image/1", "d1", asset.id(), "辅助升级：多才多艺", 10);
        }
        var assembly = store.put(FullWebMediaTest.image(2, 640, 360));
        var source = new AnswerArtifacts.Source("s1", "d1", "龙之冒险：新征程", "https://www.mcmod.cn/post/5454.html",
                "番外篇 > 傀儡使者 > 如何组装一个傀儡？", null, "NATIVE",
                "组装傀儡从收集黏土球开始，制作傀儡装配台与傀儡胚料。");
        var assemblyPicture = new AnswerArtifacts.Picture("i-assembly", assembly.id(), assembly.relativePath(),
                "傀儡组装步骤", "s1", "ILLUSTRATION", List.of(), "组装傀儡从收集黏土球开始",
                null, null, new AnswerArtifacts.ImageGrounding("ILLUSTRATION", "CONTEXT_INFERRED", "b9",
                "如何组装一个傀儡？\n组装傀儡从收集黏土球开始，制作傀儡装配台与傀儡胚料。", "INLINE_STEP", "CONTEXT_ONLY"));
        var artifacts = new AnswerArtifacts(null, null, List.of(source), List.of(assemblyPicture));
        var retrieval = new KnowledgeRetrievalService.Retrieval(List.of(new VectorStore.SearchResult("c1",
                "傀儡的组装与升级", "integration", "龙之冒险：新征程", "web", 0.9, "https://www.mcmod.cn/post/5454.html")),
                artifacts, "", 1.0, null);
        try (var embedding = new EmbeddingService(null)) {
            var service = new KnowledgeRetrievalService(db, embedding, temp);
            var result = service.hydrate(retrieval, "龙之冒险新征程如何组装并升级傀儡？请结合相关步骤图详细介绍。");
            assertEquals(2, result.artifacts().pictures().size());
            assertTrue(result.artifacts().pictures().stream().anyMatch(picture -> picture.caption().contains("辅助升级")));
            assertTrue(GuideIllustrationCoverage.missing("龙之冒险新征程如何组装并升级傀儡？请结合相关步骤图详细介绍。",
                    result.artifacts().pictures()).isEmpty());
        } finally {
            db.shutdown();
        }
    }

    @Test void pageFallbackKeepsLargestPageImageWhenNoImageWasSelected() throws Exception {
        var db = DatabaseBuilder.atPath(temp.resolve("page-db"));
        db.initDatabase();
        var store = new ImageAssetStore(temp);
        String assetId;
        try (var c = db.getConnection()) {
            block(c, "b5", "d2", 5, "画廊", "表格行：");
            reference(c, "d2/image/7", "d2", "https://example.com/gallery-7.png", "", "", "AVAILABLE", 5);
            var asset = store.put(FullWebMediaTest.image(7, 1000, 520));
            assetId = asset.id();
            asset(c, asset.id(), asset.relativePath(), 1000, 520);
            occurrence(c, "d2/image/7", "d2", asset.id(), "", 5);
        }
        var source = new AnswerArtifacts.Source("s1", "d2", "灾变", "https://www.mcmod.cn/class/5214.html",
                "模组总览", null, "NATIVE", "灾变是增加了多种地牢与 Boss 的模组。");
        var artifacts = new AnswerArtifacts(null, null, List.of(source), List.of());
        var retrieval = new KnowledgeRetrievalService.Retrieval(List.of(new VectorStore.SearchResult("c1",
                "灾变模组总览", "cataclysm", "灾变", "web", 0.9, "https://www.mcmod.cn/class/5214.html")),
                artifacts, "", 1.0, null);
        try (var embedding = new EmbeddingService(null)) {
            var service = new KnowledgeRetrievalService(db, embedding, temp);
            var result = service.hydrate(retrieval,
                    "灾变模组有哪些主要 BOSS？请结合网页资料的相关插图介绍。");
            assertEquals(1, result.artifacts().pictures().size());
            var picture = result.artifacts().pictures().getFirst();
            assertEquals(assetId, picture.assetId());
            assertTrue(picture.caption().contains("灾变"), picture.caption());
            assertTrue(picture.caption().contains("画廊"), picture.caption());
        } finally {
            db.shutdown();
        }
    }

    private static void block(java.sql.Connection c, String id, String doc, int ordinal, String heading, String body) throws Exception {
        try (var p = c.prepareStatement("INSERT INTO rag_content_block(id,document_id,heading_path,lineage,ordinal,kind,content,extraction_method) VALUES(?,?,?,?,?,?,?,?)")) {
            p.setString(1, id); p.setString(2, doc); p.setString(3, heading); p.setString(4, heading);
            p.setInt(5, ordinal); p.setString(6, "PARAGRAPH"); p.setString(7, body); p.setString(8, "NATIVE"); p.executeUpdate();
        }
    }

    private static void reference(java.sql.Connection c, String id, String doc, String url, String caption, String step, String status, int ordinal) throws Exception {
        try (var p = c.prepareStatement("INSERT INTO rag_web_image_reference(id,document_id,url,caption,heading,block_ordinal,status,step_text) VALUES(?,?,?,?,?,?,?,?)")) {
            p.setString(1, id); p.setString(2, doc); p.setString(3, url); p.setString(4, caption);
            p.setString(5, ""); p.setInt(6, ordinal); p.setString(7, status); p.setString(8, step); p.executeUpdate();
        }
    }

    private static void asset(java.sql.Connection c, String id, String path, int width, int height) throws Exception {
        try (var p = c.prepareStatement("INSERT INTO rag_image_asset(id,relative_path,media_type,width,height) VALUES(?,?,?,?,?)")) {
            p.setString(1, id); p.setString(2, path); p.setString(3, "image/png");
            p.setInt(4, width); p.setInt(5, height); p.executeUpdate();
        }
    }

    private static void occurrence(java.sql.Connection c, String id, String doc, String image, String caption, int ordinal) throws Exception {
        try (var p = c.prepareStatement("INSERT INTO rag_image_occurrence(id,document_id,image_id,caption,ordinal,role,analysis_status) VALUES(?,?,?,?,?,?,?)")) {
            p.setString(1, id); p.setString(2, doc); p.setString(3, image); p.setString(4, caption);
            p.setInt(5, ordinal); p.setString(6, "ILLUSTRATION"); p.setString(7, "CONTEXT_ONLY"); p.executeUpdate();
        }
    }
}
