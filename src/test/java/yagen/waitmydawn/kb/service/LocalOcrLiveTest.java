package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.config.AppConfig;
import yagen.waitmydawn.kb.model.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 本机 OCR 验收不访问网络，使用程序生成的固定文字图片。 */
@EnabledIfSystemProperty(named="maa.live.localOcr", matches="true")
class LocalOcrLiveTest {
    @TempDir Path temp;
    @Test void recognizesGeneratedTextWithInstalledWindowsEngine() throws Exception {
        var result = LocalOcrEngine.windows().recognize(DocumentImportTest.textImage());
        assertEquals("OK", result.status());
        assertTrue(result.text().contains("GOLEM CORE 1234"));
        var chineseImage = new java.awt.image.BufferedImage(1000, 220, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = chineseImage.createGraphics();
        try {
            graphics.setColor(java.awt.Color.WHITE); graphics.fillRect(0, 0, 1000, 220);
            graphics.setColor(java.awt.Color.BLACK); graphics.setFont(new java.awt.Font("Microsoft YaHei", java.awt.Font.PLAIN, 72));
            graphics.drawString("傀儡核心 组装升级", 35, 140);
        } finally { graphics.dispose(); }
        var chinese = LocalOcrEngine.windows().recognize(chineseImage);
        assertEquals("OK", chinese.status()); assertTrue(chinese.text().contains("核心"));
        var config = AppConfig.forIsolatedRun(false); config.setDataDir(temp.resolve("data"));
        var folder = Files.createDirectories(temp.resolve("guides"));
        javax.imageio.ImageIO.write(DocumentImportTest.textImage(), "png", folder.resolve("screen.png").toFile());
        try (var pdf = new org.apache.pdfbox.pdmodel.PDDocument()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage(); pdf.addPage(page);
            try (var stream = new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf, page)) {
                stream.drawImage(org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(pdf, DocumentImportTest.textImage()), 20, 400, 550, 120);
            }
            pdf.save(folder.resolve("scan.pdf").toFile());
        }
        var db = DatabaseBuilder.atPath(temp.resolve("base")); db.initDatabase();
        try (var embedder = new EmbeddingService(null)) {
            var request = new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY, folder.toString())));
            var build = new KnowledgeBuilder(config, db, embedder).build(request, null);
            assertEquals(2, build.documents); assertEquals(2, build.ocrImages);
            int indexed;
            try (var c = db.getConnection(); var s = c.createStatement(); var rows = s.executeQuery("SELECT COUNT(*) FROM rag_content_chunk WHERE extraction_method='OCR' AND content LIKE '%GOLEM CORE 1234%'")) {
                rows.next(); indexed = rows.getInt(1); assertEquals(2, indexed);
            }
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of("target/stage3-ocr-verification.json").toFile(),
                    Map.of("verifiedAt", java.time.Instant.now().toString(), "status", result.status(), "language", result.language(), "chineseText", chinese.text(),
                            "text", result.text(), "documents", build.documents, "ocrImages", build.ocrImages, "indexedOcrChunks", indexed,
                            "visionEnabled", config.isVisionEnabled()));
        } finally { db.shutdown(); yagen.waitmydawn.kb.config.LogSetup.shutdown(); }
    }
}
