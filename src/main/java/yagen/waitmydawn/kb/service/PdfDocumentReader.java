package yagen.waitmydawn.kb.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;

/** PDF 原生文字与扫描 OCR 按页互斥选取，图片坐标使用页面 PDF 坐标系。 */
final class PdfDocumentReader {
    void read(byte[] bytes, DocumentBlock.Sink sink) throws Exception {
        try (var document = Loader.loadPDF(bytes)) {
            if (!document.getCurrentAccessPermission().canExtractContent()) throw new IOException("PDF 不允许提取内容");
            int limit = Math.max(1, Integer.getInteger("maa.documents.maxPdfPages", 200));
            if (document.getNumberOfPages() > limit) throw new IOException("PDF 页数超过限制：" + limit);
            var stripper = new PDFTextStripper(); stripper.setSortByPosition(true);
            var renderer = new PDFRenderer(document); renderer.setSubsamplingAllowed(true);
            for (int index = 0; index < document.getNumberOfPages(); index++) {
                checkCancelled();
                stripper.setStartPage(index + 1); stripper.setEndPage(index + 1);
                String text = stripper.getText(document).strip();
                int meaningful = text.replaceAll("\\s", "").length();
                PDPage page = document.getPage(index);
                if (text.isBlank() && !page.hasContents()) continue;
                boolean scan = meaningful < 40;
                List<DocumentBlock.Picture> pictures;
                if (scan) {
                    var image = render(renderer, page, index);
                    pictures = List.of(new DocumentBlock.Picture(png(image), "第 " + (index + 1) + " 页扫描图",
                            "page " + (index + 1), null, "SCAN_PAGE"));
                } else {
                    var collector = new Images(page); collector.processPage(page);
                    pictures = new ArrayList<>(collector.pictures);
                    if (pictures.isEmpty() && collector.vectorPaths >= 3)
                        pictures.add(new DocumentBlock.Picture(png(render(renderer, page, index)), "第 " + (index + 1) + " 页布局预览",
                                "page " + (index + 1), null, "PAGE_PREVIEW"));
                }
                sink.accept(new DocumentBlock(text, "第 " + (index + 1) + " 页", index + 1, scan ? "SCAN" : "NATIVE", pictures));
            }
        }
    }

    private static BufferedImage render(PDFRenderer renderer, PDPage page, int index) throws IOException {
        var box = page.getCropBox();
        double pixels = Math.max(1, (double) box.getWidth() * box.getHeight());
        float scale = (float) Math.min(2.0, Math.sqrt(16_000_000.0 / pixels));
        if (!Float.isFinite(scale) || scale <= 0) throw new IOException("PDF 页面尺寸无效");
        return renderer.renderImage(index, scale);
    }
    static byte[] png(BufferedImage image) throws IOException {
        var out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) throw new IOException("图片无法转换为 PNG");
        if (out.size() > 32 * 1024 * 1024) throw new IOException("PDF 图片超过 32 MiB");
        return out.toByteArray();
    }
    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("PDF import cancelled");
    }

    private static final class Images extends PDFGraphicsStreamEngine {
        private final List<DocumentBlock.Picture> pictures = new ArrayList<>();
        private Point2D current = new Point2D.Float();
        private int vectorPaths;
        private long imageBytes;
        Images(PDPage page) { super(page); }
        @Override public void drawImage(PDImage image) throws IOException {
            checkCancelled();
            if ((long) image.getWidth() * image.getHeight() > 40_000_000) throw new IOException("PDF 内嵌图片像素数量超限");
            if (pictures.size() >= 256) throw new IOException("PDF 单页图片数量超过 256");
            var matrix = getGraphicsState().getCurrentTransformationMatrix();
            var p0 = matrix.transformPoint(0, 0); var p1 = matrix.transformPoint(1, 0);
            var p2 = matrix.transformPoint(0, 1); var p3 = matrix.transformPoint(1, 1);
            double minX = Math.min(Math.min(p0.x, p1.x), Math.min(p2.x, p3.x));
            double minY = Math.min(Math.min(p0.y, p1.y), Math.min(p2.y, p3.y));
            double maxX = Math.max(Math.max(p0.x, p1.x), Math.max(p2.x, p3.x));
            double maxY = Math.max(Math.max(p0.y, p1.y), Math.max(p2.y, p3.y));
            String bounds = String.format(Locale.ROOT, "{\"x\":%.3f,\"y\":%.3f,\"width\":%.3f,\"height\":%.3f}", minX, minY, maxX-minX, maxY-minY);
            byte[] bytes = png(image.getImage());
            if ((imageBytes += bytes.length) > 64L * 1024 * 1024) throw new IOException("PDF 单页图片总量超过 64 MiB");
            pictures.add(new DocumentBlock.Picture(bytes, "", "PDF image " + pictures.size(), bounds, "ILLUSTRATION"));
        }
        @Override public void appendRectangle(Point2D a, Point2D b, Point2D c, Point2D d) { current = a; }
        @Override public void clip(int windingRule) {}
        @Override public void moveTo(float x, float y) { current = new Point2D.Float(x, y); }
        @Override public void lineTo(float x, float y) { current = new Point2D.Float(x, y); }
        @Override public void curveTo(float a, float b, float c, float d, float x, float y) { current = new Point2D.Float(x, y); }
        @Override public Point2D getCurrentPoint() { return current; }
        @Override public void closePath() {}
        @Override public void endPath() {}
        @Override public void strokePath() { vectorPaths++; }
        @Override public void fillPath(int windingRule) { vectorPaths++; }
        @Override public void fillAndStrokePath(int windingRule) { vectorPaths++; }
        @Override public void shadingFill(COSName shadingName) { vectorPaths++; }
    }
}
