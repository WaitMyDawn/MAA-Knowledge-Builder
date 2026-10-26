package yagen.waitmydawn.kb.service;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.xwpf.usermodel.*;
import java.io.ByteArrayInputStream;
import java.util.*;

/** 只解析 Word 正文与表格，不执行宏、不读取外部关系、不推测排版页码。 */
final class WordDocumentReader {
    private String heading = "正文";
    private final HeadingHierarchy headings=new HeadingHierarchy();
    private int position;
    private int tableSequence;

    void readDocx(byte[] bytes, DocumentBlock.Sink sink) throws Exception {
        validateArchive(bytes);
        try (var document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            String previous = "";
            for (var element : document.getBodyElements()) {
                checkCancelled();
                if (element instanceof XWPFParagraph paragraph) {
                    String style = paragraph.getStyle();
                    if(style!=null && style.toLowerCase(Locale.ROOT).matches(".*(heading|标题).*")) {
                        var level=java.util.regex.Pattern.compile("([1-9])").matcher(style);
                        headings.accept(level.find()?Integer.parseInt(level.group(1)):2,paragraph.getText());heading=headings.leaf();previous="";
                        var illustration=paragraph(paragraph,"标题 "+position++);
                        if(!illustration.pictures().isEmpty()) sink.accept(new DocumentBlock("",heading,null,"NATIVE",illustration.pictures(),headings.path(),headings.lineage(),"IMAGE",null));
                        continue;
                    }
                    var block = paragraph(paragraph, "段落 " + position++);
                    if (block.text().isBlank() && !block.pictures().isEmpty())
                        block = new DocumentBlock(previous,block.heading(),null,"ADJACENT",block.pictures(),block.headingPath(),block.lineage(),"IMAGE",null);
                    sink.accept(block);
                    if (!paragraph.getText().isBlank()) previous = paragraph.getText();
                } else if (element instanceof XWPFTable table) readTable(table, sink);
            }
        }
    }

    private DocumentBlock paragraph(XWPFParagraph paragraph, String location) throws Exception {
        var pictures = new ArrayList<DocumentBlock.Picture>();
        long imageBytes = 0;
        int run = 0;
        for (var textRun : paragraph.getRuns()) {
            for (var picture : textRun.getEmbeddedPictures()) {
                var data = picture.getPictureData();
                if (data == null) continue;
                byte[] bytes = data.getData();
                boundedPicture(bytes);
                if ((imageBytes += bytes.length) > 64L * 1024 * 1024) throw new java.io.IOException("Word 段落图片总量超过 64 MiB");
                String caption = picture.getCTPicture().getNvPicPr().getCNvPr().getDescr();
                pictures.add(new DocumentBlock.Picture(bytes, caption == null ? "" : caption,
                        location + " / run " + run, null, "ILLUSTRATION"));
            }
            run++;
        }
        return new DocumentBlock(paragraph.getText(),heading,null,"NATIVE",pictures,headings.path(),headings.lineage(),"PARAGRAPH",null);
    }

    private void readTable(XWPFTable table, DocumentBlock.Sink sink) throws Exception {
        int rowIndex = 0;String first="",tableId="table"+(++tableSequence);
        for (var row : table.getRows()) {
            checkCancelled();
            if(rowIndex>=10000) throw new java.io.IOException("Word 表格行数超过 10000");
            var cells = new ArrayList<String>();
            var pictures = new ArrayList<DocumentBlock.Picture>();
            int cellIndex = 0;
            for (var cell : row.getTableCells()) {
                cells.add(cell.getText());
                for (var paragraph : cell.getParagraphs()) pictures.addAll(paragraph(paragraph,
                        "表格 " + position + " / 行 " + rowIndex + " / 列 " + cellIndex).pictures());
                for (var nested : cell.getTables()) readTable(nested, sink);
                cellIndex++;
            }
            String rowText=String.join(" | ",cells);
            sink.accept(new DocumentBlock((first.isEmpty()?"":"首行（原文）："+first+"\n")+"表格行："+rowText,heading,null,"TABLE",pictures,
                    headings.path(),headings.lineage(),"TABLE_ROW",tableId));
            if(first.isEmpty()) first=rowText;
            rowIndex++;
        }
        position++;
    }

    void readDoc(byte[] bytes, DocumentBlock.Sink sink) throws Exception {
        try (var document = new HWPFDocument(new ByteArrayInputStream(bytes))) {
            var range = document.getRange();
            String previous = "";
            for (int index = 0; index < range.numParagraphs(); index++) {
                checkCancelled();
                var paragraph = range.getParagraph(index);
                String text = paragraph.text().replace('\u0007', '|').replaceAll("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f]", "").strip();
                var description=document.getStyleSheet().getStyleDescription(paragraph.getStyleIndex());
                String style=description==null?"":description.getName();
                if(style!=null && style.toLowerCase(Locale.ROOT).matches(".*(heading|标题).*")) {
                    var level=java.util.regex.Pattern.compile("([1-9])").matcher(style);
                    headings.accept(level.find()?Integer.parseInt(level.group(1)):2,text);heading=headings.leaf();previous="";continue;
                }
                var pictures = new ArrayList<DocumentBlock.Picture>();
                for (int run = 0; run < paragraph.numCharacterRuns(); run++) {
                    var character = paragraph.getCharacterRun(run);
                    if (!document.getPicturesTable().hasPicture(character)) continue;
                    var picture = document.getPicturesTable().extractPicture(character, true);
                    if (picture == null) continue;
                    byte[] data = picture.getContent(); boundedPicture(data);
                    pictures.add(new DocumentBlock.Picture(data, "", "段落 " + index + " / 字符 " + character.getStartOffset(), null, "ILLUSTRATION"));
                }
                boolean adjacent = text.isBlank() && !pictures.isEmpty();
                sink.accept(new DocumentBlock(adjacent?previous:text,heading,null,adjacent?"ADJACENT":"NATIVE",pictures,headings.path(),headings.lineage(),"PARAGRAPH",null));
                if (!text.isBlank()) previous = text;
            }
        }
    }

    private static void boundedPicture(byte[] data) throws java.io.IOException {
        if (data.length > 32 * 1024 * 1024) throw new java.io.IOException("Word 内嵌图片超过 32 MiB");
    }
    private static void validateArchive(byte[] bytes) throws java.io.IOException {
        long total = 0; int entries = 0;
        try (var zip = new java.util.zip.ZipInputStream(new ByteArrayInputStream(bytes))) {
            while (zip.getNextEntry() != null) {
                checkCancelled();
                if (++entries > 5000) throw new java.io.IOException("DOCX 归档条目过多");
                byte[] buffer = new byte[8192]; int count;
                while ((count = zip.read(buffer)) != -1) {
                    total += count;
                    if (total > 128L * 1024 * 1024) throw new java.io.IOException("DOCX 解压内容超过 128 MiB");
                }
            }
        }
    }
    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Word import cancelled");
    }
}
