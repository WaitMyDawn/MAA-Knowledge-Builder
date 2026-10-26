package yagen.waitmydawn.kb.service;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.util.Units;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.config.AppConfig;
import yagen.waitmydawn.kb.model.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DocumentImportTest {
    @TempDir Path temp;
    @org.junit.jupiter.api.AfterEach void closeLog() { yagen.waitmydawn.kb.config.LogSetup.shutdown(); }

    private AppConfig config() throws Exception { var c = AppConfig.forIsolatedRun(false); c.setDataDir(temp.resolve("data")); return c; }
    private DatabaseBuilder database() { var db = DatabaseBuilder.atPath(temp.resolve("base")); db.initDatabase(); new VectorStore(db, 384); return db; }
    private BuildRequest request(Path folder) { return new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY, folder.toString()))); }
    private long count(DatabaseBuilder db, String sql) throws Exception {
        try (var c = db.getConnection(); var s = c.createStatement(); var r = s.executeQuery(sql)) { r.next(); return r.getLong(1); }
    }
    static BufferedImage textImage() {
        var image = new BufferedImage(1100, 240, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        try { g.setColor(Color.WHITE); g.fillRect(0, 0, 1100, 240); g.setColor(Color.BLACK); g.setFont(new Font("Arial", Font.PLAIN, 82)); g.drawString("GOLEM CORE 1234", 35, 150); }
        finally { g.dispose(); }
        return image;
    }
    private byte[] icon() throws Exception { return PdfDocumentReader.png(new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB)); }

    private void docx(Path file) throws Exception {
        try (var document = new XWPFDocument()) {
            var heading = document.createParagraph(); heading.setStyle("Heading1"); heading.createRun().setText("傀儡组装");
            document.createParagraph().createRun().setText("先安装核心，再安装升级模块。");
            var image = document.createParagraph();
            image.createRun().addPicture(new ByteArrayInputStream(icon()), XWPFDocument.PICTURE_TYPE_PNG, "core.png", Units.toEMU(32), Units.toEMU(32));
            var table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("材料"); table.getRow(0).getCell(1).setText("数量");
            table.getRow(1).getCell(0).setText("铁锭"); table.getRow(1).getCell(1).setText("8");
            try (var output = Files.newOutputStream(file)) { document.write(output); }
        }
    }

    private void pdf(Path file, boolean illustration, boolean scan) throws Exception {
        try (var document = new PDDocument()) {
            var page = new PDPage(); document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                content.newLineAtOffset(40, 730); content.showText("Native recipe instructions: assemble the golem core before adding upgrade modules."); content.endText();
                if (illustration) content.drawImage(LosslessFactory.createFromImage(document, ImageIO.read(new ByteArrayInputStream(icon()))), 50, 400, 100, 80);
            }
            if (scan) {
                var scanned = new PDPage(); document.addPage(scanned);
                try (var content = new PDPageContentStream(document, scanned)) { content.drawImage(LosslessFactory.createFromImage(document, textImage()), 20, 400, 550, 120); }
            }
            document.save(file.toFile());
        }
    }

    @Test void importsMixedFormatsWithPageAndImageEvidence() throws Exception {
        var config = config(); var db = database(); var folder = Files.createDirectories(temp.resolve("guides"));
        Files.write(folder.resolve("说明.txt"), "中文材料说明：使用铁锭组装核心。".getBytes(Charset.forName("GB18030")));
        docx(folder.resolve("组装.docx")); pdf(folder.resolve("guide.pdf"), true, true);
        Files.createDirectories(folder.resolve("textures"));
        ImageIO.write(textImage(), "png", folder.resolve("textures/screen.png").toFile());
        Files.copy(Path.of("src/test/resources/documents/simple.doc"), folder.resolve("legacy.doc"));
        var calls = new AtomicInteger();
        LocalOcrEngine engine = image -> { calls.incrementAndGet(); return new LocalOcrEngine.Result(image.getWidth() > 500 ? "GOLEM CORE 1234" : "CORE LABEL", "OK", "test"); };
        try (var embedder = new EmbeddingService(null)) {
            var result = new KnowledgeBuilder(config, db, embedder,
                    List.of(new DocumentDirectoryProcessor(config, engine, new VisionDescriptionService(config)))).build(request(folder), null);
            assertEquals(5, result.documents);
            assertTrue(result.localImages >= 4); assertTrue(calls.get() >= 3);
            assertEquals(5, count(db, "SELECT COUNT(*) FROM rag_document_import WHERE status='INDEXED'"));
            assertTrue(count(db, "SELECT COUNT(*) FROM rag_content_chunk WHERE page_number=2 AND extraction_method='OCR' AND content LIKE '%GOLEM CORE%' ") > 0);
            assertEquals(0, count(db, "SELECT COUNT(*) FROM rag_content_chunk c JOIN rag_document d ON c.document_id=d.id WHERE d.media_type LIKE '%wordprocessingml%' AND c.page_number IS NOT NULL"));
            assertTrue(count(db, "SELECT COUNT(*) FROM rag_content_chunk WHERE content LIKE '%铁锭 | 8%' OR content LIKE '%铁锭|8%' ") > 0);
            assertTrue(count(db, "SELECT COUNT(*) FROM rag_image_occurrence WHERE bounds_json IS NOT NULL AND page_number=1") > 0);
            assertTrue(count(db, "SELECT COUNT(*) FROM rag_chunk_image l JOIN rag_vectors v ON l.chunk_id=v.chunk_id") >= 4);
            assertEquals(5, count(db, "SELECT COUNT(*) FROM rag_document_source"));
            new ImageAssetStore(config.getDataDir()).validate(new KnowledgeContentStore(db), db);
        } finally { db.shutdown(); }
    }

    @Test void nativePdfDoesNotRunPageOcrAndScanTextIsIndexedOnce() throws Exception {
        var config = config(); var db = database(); var folder = Files.createDirectories(temp.resolve("guides"));
        pdf(folder.resolve("native-and-scan.pdf"), false, true);
        var calls = new AtomicInteger();
        LocalOcrEngine engine = image -> { calls.incrementAndGet(); return new LocalOcrEngine.Result("SCANNED UNIQUE PHRASE", "OK", "test"); };
        try (var embedder = new EmbeddingService(null)) {
            new KnowledgeBuilder(config, db, embedder, List.of(new DocumentDirectoryProcessor(config, engine, new VisionDescriptionService(config)))).build(request(folder), null);
            assertEquals(1, calls.get());
            assertEquals(1, count(db, "SELECT COUNT(*) FROM rag_content_chunk WHERE page_number=2 AND content LIKE '%SCANNED UNIQUE PHRASE%'"));
            assertEquals(1, count(db, "SELECT COUNT(*) FROM rag_content_chunk WHERE page_number=1 AND extraction_method='NATIVE'"));
            assertEquals("same text", DocumentDirectoryProcessor.mergeText("same text", "same text"));
        } finally { db.shutdown(); }
    }

    @Test void unavailableOcrKeepsImageAndMarksMetadataOnlyWithoutPretendingToUnderstandIt() throws Exception {
        var config = config(); var db = database(); var folder = Files.createDirectories(temp.resolve("guides"));
        ImageIO.write(textImage(), "png", folder.resolve("unknown.png").toFile());
        LocalOcrEngine engine = image -> new LocalOcrEngine.Result("", "UNAVAILABLE", "");
        try (var embedder = new EmbeddingService(null)) {
            var result = new KnowledgeBuilder(config, db, embedder, List.of(new DocumentDirectoryProcessor(config, engine, new VisionDescriptionService(config)))).build(request(folder), null);
            assertEquals(1, result.documents); assertFalse(result.warnings.isEmpty());
            assertEquals(1, count(db, "SELECT COUNT(*) FROM rag_document_import WHERE status='PARTIAL'"));
            assertEquals(1, count(db, "SELECT COUNT(*) FROM rag_content_chunk WHERE extraction_method='METADATA_ONLY'"));
            assertEquals(1, count(db, "SELECT COUNT(*) FROM rag_image_occurrence WHERE analysis_status='OCR_UNAVAILABLE;VISION_DISABLED'"));
        } finally { db.shutdown(); }
    }

    @Test void corruptSupportedFileRetainsPublishedKnowledgeAndSensitiveDirectoriesAreExcluded() throws Exception {
        var config = config(); var db = database(); var folder = Files.createDirectories(temp.resolve("guides"));
        Files.writeString(folder.resolve("guide.txt"), "保留的旧资料正文。");
        Files.createDirectories(folder.resolve("secrets")); Files.writeString(folder.resolve("secrets/bad.pdf"), "invalid fixture");
        Files.writeString(folder.resolve("credentials.txt"), "excluded fixture");
        try (var embedder = new EmbeddingService(null)) {
            var builder = new KnowledgeBuilder(config, db, embedder);
            builder.build(request(folder), null);
            assertEquals(1, count(db, "SELECT COUNT(*) FROM rag_document"));
            String before = db.contentFingerprint();
            Files.writeString(folder.resolve("corrupt.pdf"), "invalid fixture");
            assertThrows(IllegalStateException.class, () -> builder.build(request(folder), null));
            assertEquals(before, db.contentFingerprint());
        } finally { db.shutdown(); }
    }

    @Test void realLegacyDocParserExtractsWordBinaryText() throws Exception {
        var blocks = new java.util.ArrayList<DocumentBlock>();
        new WordDocumentReader().readDoc(Files.readAllBytes(Path.of("src/test/resources/documents/simple.doc")), blocks::add);
        assertTrue(blocks.stream().anyMatch(b -> b.text().contains("This is a simple file")));
        assertTrue(blocks.stream().allMatch(b -> b.page() == null));
    }

    @Test void detectsTextBomAndLegacyChineseEncoding() throws Exception {
        assertEquals("傀儡", DocumentDirectoryProcessor.decodeText("傀儡".getBytes(StandardCharsets.UTF_8)));
        var bytes = new ByteArrayOutputStream(); bytes.write(new byte[]{(byte)0xff, (byte)0xfe}); bytes.write("傀儡".getBytes(StandardCharsets.UTF_16LE));
        assertEquals("傀儡", DocumentDirectoryProcessor.decodeText(bytes.toByteArray()));
        assertEquals("傀儡", DocumentDirectoryProcessor.decodeText("傀儡".getBytes(Charset.forName("GB18030"))));
    }

    @Test void cancelledOcrDoesNotPublishPartiallyImportedImages() throws Exception {
        var config = config(); var db = database(); var folder = Files.createDirectories(temp.resolve("guides"));
        ImageIO.write(textImage(), "png", folder.resolve("scan.png").toFile());
        LocalOcrEngine engine = image -> { throw new java.util.concurrent.CancellationException("cancelled fixture"); };
        String before = db.contentFingerprint();
        try (var embedder = new EmbeddingService(null)) {
            assertThrows(java.util.concurrent.CancellationException.class, () -> new KnowledgeBuilder(config, db, embedder,
                    List.of(new DocumentDirectoryProcessor(config, engine, new VisionDescriptionService(config)))).build(request(folder), null));
            assertEquals(before, db.contentFingerprint());
        } finally { db.shutdown(); }
    }

    @Test void directPdfUrlUsesTheSameDocumentParserAndKeepsWebProvenance() throws Exception {
        var config = config(); var db = database();
        Path file = temp.resolve("guide.pdf"); pdf(file, false, false); byte[] bytes = Files.readAllBytes(file);
        String url = "https://example.com/guide.pdf";
        var web = new WikiScraperService() {
            @Override public Resource fetchResource(String address, String referer, boolean image, java.util.Map<String,String> headers) {
                assertEquals(url, address); return new Resource(200, bytes, "application/pdf", address, null, null);
            }
        };
        web.setResourceDatabase(db);
        LocalOcrEngine engine = image -> { fail("原生文字 PDF 不应调用 OCR"); return null; };
        try (var embedder = new EmbeddingService(null)) {
            var request = new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.URL, url)));
            new KnowledgeBuilder(config, db, embedder, List.of(new UrlBuildProcessor(web,
                    new DocumentDirectoryProcessor(config, engine, new VisionDescriptionService(config))))).build(request, null);
            assertEquals(1, count(db, "SELECT COUNT(*) FROM rag_document WHERE location='https://example.com/guide.pdf' AND media_type='application/pdf'"));
            assertEquals(1, count(db, "SELECT COUNT(*) FROM rag_knowledge_source WHERE scope_kind='WEB_DOCUMENT'"));
            assertEquals(1, count(db, "SELECT COUNT(*) FROM rag_content_chunk WHERE page_number=1 AND extraction_method='NATIVE'"));
        } finally { db.shutdown(); }
    }

    @Test void complexVectorPageIsMarkedAsPreviewAndNeverReOcred() throws Exception {
        var config = config(); var db = database(); var folder = Files.createDirectories(temp.resolve("guides"));
        Path file = folder.resolve("diagram.pdf"); pdf(file, false, false);
        try (var document = org.apache.pdfbox.Loader.loadPDF(file.toFile())) {
            try (var stream = new PDPageContentStream(document, document.getPage(0), PDPageContentStream.AppendMode.APPEND, true)) {
                for (int n = 0; n < 3; n++) { stream.addRect(50 + n*100, 300, 60, 60); stream.stroke(); }
            }
            document.save(folder.resolve("rendered.pdf").toFile());
        }
        Files.delete(file);
        LocalOcrEngine engine = image -> { fail("原生文字页预览不应再做整页 OCR"); return null; };
        try (var embedder = new EmbeddingService(null)) {
            new KnowledgeBuilder(config, db, embedder, List.of(new DocumentDirectoryProcessor(config, engine, new VisionDescriptionService(config)))).build(request(folder), null);
            assertEquals(1, count(db, "SELECT COUNT(*) FROM rag_image_occurrence WHERE role='PAGE_PREVIEW' AND analysis_status='NATIVE_TEXT_ONLY'"));
            assertEquals(1, count(db, "SELECT COUNT(*) FROM rag_content_chunk WHERE extraction_method='NATIVE'"));
        } finally { db.shutdown(); }
    }
}
