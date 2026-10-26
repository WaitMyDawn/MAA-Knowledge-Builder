package yagen.waitmydawn.kb.ui;

import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.*;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 简易 Markdown → JavaFX TextFlow 渲染器。
 * 支持: ## header, **bold**, 行内与围栏代码、bullet list、table、图片 ![alt](path)
 */
public class MarkdownRenderer {

    private static final Pattern IMAGE_PATTERN = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)]+)\\)");
    private static final int IMAGE_FIT_WIDTH = 260;

    public static Node render(String markdown) {
        return render(markdown, List.of());
    }

    public static Node render(String markdown, List<Path> imageRoots) {
        if (markdown == null || markdown.isBlank()) return new Label();

        VBox container = new VBox(4);
        String[] lines = markdown.split("\n");
        List<String> buffer = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            var fence=Pattern.compile("^\\s{0,3}(`{3,}|~{3,}).*$").matcher(line);
            if(fence.matches()) {
                flushBuffer(container,buffer,imageRoots);
                String delimiter=fence.group(1);var code=new StringBuilder();
                while(++i<lines.length && !lines[i].strip().matches(Pattern.quote(delimiter.charAt(0)+"")+"{"+delimiter.length()+",}\\s*"))
                    code.append(lines[i]).append('\n');
                var flow=new TextFlow();var value=new Text(code.toString());value.setFont(Font.font("Consolas",13));
                value.getStyleClass().add("markdown-code");flow.getChildren().add(value);flow.setMaxWidth(780);container.getChildren().add(flow);
                continue;
            }
            if (line.trim().isEmpty()) {
                flushBuffer(container, buffer, imageRoots);
                continue;
            }
            // Header
            if (line.trim().startsWith("## ") || line.trim().startsWith("### ")) {
                flushBuffer(container, buffer, imageRoots);
                String text = line.replaceAll("^#+\\s*", "");
                TextFlow h = buildCellFlow(text, true);
                h.setPadding(new javafx.geometry.Insets(8, 0, 2, 0));
                h.getStyleClass().add("markdown-heading");
                container.getChildren().add(h);
                continue;
            }
            // Horizontal rule
            if (line.trim().matches("^-{3,}$") || line.trim().matches("^\\*{3,}$")) {
                flushBuffer(container, buffer, imageRoots);
                container.getChildren().add(new javafx.scene.control.Separator());
                continue;
            }

            // 表格块：连续包含 | 的行作为一个候选块，只有解析成功才按表格渲染
            if (line.contains("|")) {
                List<String> tableLines = new ArrayList<>();
                int j = i;
                while (j < lines.length && lines[j].contains("|")
                        && !lines[j].trim().isEmpty()) {
                    tableLines.add(lines[j]);
                    j++;
                }
                Node table = buildTable(tableLines);
                if (table != null) {
                    flushBuffer(container, buffer, imageRoots);
                    container.getChildren().add(table);
                    i = j - 1;
                    continue;
                }
            }

            buffer.add(line);
        }
        flushBuffer(container, buffer, imageRoots);
        return container;
    }

    /** 解析一个 Markdown 表格块，非法时返回 null。 */
    private static Node buildTable(List<String> tableLines) {
        if (tableLines.size() < 2) return null;

        List<List<String>> rows = new ArrayList<>();
        int columns = -1;
        for (String line : tableLines) {
            List<String> cells = splitTableRow(line);
            if (cells == null || cells.isEmpty()) return null;
            if (columns == -1) {
                columns = cells.size();
            } else if (cells.size() != columns) {
                return null;
            }
            rows.add(cells);
        }

        // 第二行必须是分隔行（--- / :--- / :---: 等）
        if (rows.size() < 2 || !isSeparatorRow(rows.get(1))) return null;

        GridPane grid = new GridPane();
        grid.setHgap(0);
        grid.setVgap(0);
        grid.setPadding(new javafx.geometry.Insets(6, 0, 6, 0));
        grid.setMaxWidth(780);

        double cellMax = Math.max(120, Math.min(360, 760.0 / columns));

        // 第一行是表头，从第二行开始是数据（分隔行本身不渲染）
        for (int r = 0; r < rows.size(); r++) {
            if (r == 1) continue;
            boolean header = (r == 0);
            List<String> cells = rows.get(r);
            for (int c = 0; c < cells.size(); c++) {
                TextFlow cellFlow = buildCellFlow(cells.get(c), header);
                cellFlow.setMaxWidth(cellMax);
                VBox cell = new VBox(cellFlow);
                cell.getStyleClass().add("markdown-cell");
                if (header) {
                    cell.getStyleClass().add("markdown-header");
                }
                grid.add(cell, c, r > 1 ? r - 1 : r);
            }
        }
        return grid;
    }

    /** 把一行表格切成单元格。 */
    private static List<String> splitTableRow(String line) {
        String s = line.trim();
        if (!s.contains("|")) return null;
        if (s.startsWith("|")) s = s.substring(1);
        if (s.endsWith("|")) s = s.substring(0, s.length() - 1);

        List<String> cells = new ArrayList<>();
        for (String part : s.split("\\|", -1)) {
            cells.add(part.trim());
        }
        return cells;
    }

    /** 判断一行单元格是否为 Markdown 表头分隔行。 */
    private static boolean isSeparatorRow(List<String> cells) {
        for (String cell : cells) {
            if (!cell.matches(":?-+:?")) return false;
        }
        return true;
    }

    /** 构建单元格内容，表头强制加粗，其他单元格继续支持行内 **bold** 与 `code`。 */
    private static TextFlow buildCellFlow(String text, boolean bold) {
        TextFlow flow = new TextFlow();
        String source = bold && !text.isBlank() ? "**" + text + "**" : text;
        appendInline(flow, source);
        if (flow.getChildren().isEmpty()) {
            Text t = new Text(text);
            t.setFont(Font.font("Segoe UI", 13));
            t.getStyleClass().add("markdown-text");
            flow.getChildren().add(t);
        }
        return flow;
    }

    private static void flushBuffer(VBox container, List<String> buffer, List<Path> imageRoots) {
        if (buffer.isEmpty()) return;
        String text = String.join("\n", buffer);
        buffer.clear();

        TextFlow flow = new TextFlow();
        flow.setMaxWidth(780);
        flow.setLineSpacing(2);

        Matcher img = IMAGE_PATTERN.matcher(yagen.waitmydawn.kb.service.AnswerMedia.referenceText(text));
        int last = 0;
        while (img.find()) {
            String before = text.substring(last, img.start());
            if (!before.isBlank()) appendInline(flow, before);
            appendImage(flow, img.group(1), img.group(2), imageRoots);
            last = img.end();
        }
        if (last == 0) {
            appendInline(flow, text);
        } else if (last < text.length()) {
            String trailing = text.substring(last);
            if (!trailing.isBlank()) appendInline(flow, trailing);
        }

        if (flow.getChildren().isEmpty()) {
            Text t = new Text(text);
            t.setFont(Font.font("Segoe UI", 13));
            t.getStyleClass().add("markdown-text");
            flow.getChildren().add(t);
        }

        container.getChildren().add(flow);
    }

    /** 解析一段纯文本中的 **bold** / `code` / 普通文本。 */
    private static void appendInline(TextFlow flow, String text) {
        var links = Pattern.compile("(?<!!)\\[([^\\]]+)\\]\\((https?://[^\\s)]+)\\)").matcher(yagen.waitmydawn.kb.service.AnswerMedia.referenceText(text));
        int end = 0;
        boolean found = false;
        while (links.find()) {
            found = true;
            appendStyled(flow, text.substring(end, links.start()));
            String url = links.group(2);
            String label = links.group(1).replace("**", "").replace("`", "");
            if (yagen.waitmydawn.kb.service.AnswerSourceLinks.safeUrl(url)) {
                var link = SourceLinks.create(label, url);
                link.setPadding(javafx.geometry.Insets.EMPTY);
                flow.getChildren().add(link);
            } else appendStyled(flow, label);
            end = links.end();
        }
        if (found) appendStyled(flow, text.substring(end));
        else appendStyled(flow, text);
    }

    private static void appendStyled(TextFlow flow, String text) {
        // Simple inline parsing: **bold**, `code`, plain text
        Pattern inline = Pattern.compile("(\\*\\*(.+?)\\*\\*)|(`(.+?)`)|([^*`]+)");
        Matcher m = inline.matcher(text);
        while (m.find()) {
            if (m.group(1) != null) {
                // **bold**
                Text t = new Text(m.group(2));
                t.setFont(Font.font("Segoe UI", FontWeight.BOLD, 13));
                t.getStyleClass().add("markdown-text");
                flow.getChildren().add(t);
            } else if (m.group(3) != null) {
                // `code`
                Text t = new Text(m.group(4));
                t.setFont(Font.font("Consolas", 12));
                t.getStyleClass().add("markdown-code");
                flow.getChildren().add(t);
            } else if (m.group(5) != null) {
                Text t = new Text(m.group(5));
                t.setFont(Font.font("Segoe UI", 13));
                t.getStyleClass().add("markdown-text");
                flow.getChildren().add(t);
            }
        }
    }

    /** 渲染 ![alt](path) 为内联缩略图；图片不存在时退化为灰色替代文本。 */
    private static void appendImage(TextFlow flow, String alt, String rawPath, List<Path> imageRoots) {
        String path = rawPath.trim();
        if (path.startsWith("<") && path.endsWith(">")) {
            path = path.substring(1, path.length() - 1).trim();
        }
        if ((path.startsWith("\"") && path.endsWith("\""))
                || (path.startsWith("'") && path.endsWith("'"))) {
            path = path.substring(1, path.length() - 1).trim();
        }

        File file = new File(path);
        if (!file.isAbsolute() && path.startsWith("file:")) {
            try {
                file = Path.of(URI.create(path)).toFile();
            } catch (Exception ignored) {}
        }
        if (!file.isAbsolute()) {
            // 相对路径按当前工作目录解析，兼容历史数据
            file = new File(System.getProperty("user.dir"), path);
        }

        boolean allowed;
        try {allowed=isAllowedImage(file.toPath(),imageRoots);}
        catch(java.nio.file.InvalidPathException e) {allowed=false;}
        if (allowed) {
            try {
                Image image = new Image(file.toURI().toString());
                ImageView view = new ImageView(image);
                view.setFitWidth(IMAGE_FIT_WIDTH);
                view.setPreserveRatio(true);
                view.setPickOnBounds(true);
                view.setStyle("-fx-cursor: hand; -fx-border-color: #d1d5db; "
                        + "-fx-border-width: 1; -fx-background-radius: 6;");
                final File imageFile = file;
                view.setOnMouseClicked(ev -> openImage(imageFile));
                flow.getChildren().add(view);
                // 图片后换行，避免与后续文字挤在同一行
                flow.getChildren().add(new Text("\n"));
                return;
            } catch (Exception ignored) {}
        }

        Text fallback = new Text(alt == null || alt.isBlank() ? path : alt);
        fallback.setFont(Font.font("Segoe UI", 12));
        fallback.getStyleClass().add("markdown-muted");
        flow.getChildren().add(fallback);
    }

    static boolean isAllowedImage(Path image, List<Path> roots) {
        try {
            Path actual = image.toRealPath();
            String name = actual.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            if (!name.matches(".*\\.(png|jpe?g|gif)")) return false;
            if (!java.nio.file.Files.isRegularFile(actual) || java.nio.file.Files.size(actual) > 32L * 1024 * 1024)
                return false;
            for (Path root : roots) if (actual.startsWith(root.toRealPath())) return true;
        } catch (Exception ignored) { }
        return false;
    }

    /** 点击图片用系统默认程序打开原图。 */
    private static void openImage(File file) {
        try {
            java.awt.Desktop.getDesktop().open(file);
        } catch (Exception ignored) {}
    }
}
