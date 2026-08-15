package yagen.waitmydawn.kb.ui;

import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
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
 * 支持: ## header, **bold**, `code`, bullet list, table, 图片 ![alt](path)
 */
public class MarkdownRenderer {

    private static final Color TEXT_COLOR = Color.web("#1f2937");
    private static final Color HEADER_COLOR = Color.web("#111827");
    private static final Color CODE_BG = Color.web("#f3f4f6");
    private static final Pattern IMAGE_PATTERN = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)]+)\\)");
    private static final int IMAGE_FIT_WIDTH = 260;

    public static Node render(String markdown) {
        if (markdown == null || markdown.isBlank()) return new Label();

        VBox container = new VBox(4);
        String[] lines = markdown.split("\n");
        List<String> buffer = new ArrayList<>();

        for (String line : lines) {
            if (line.trim().isEmpty()) {
                flushBuffer(container, buffer);
                continue;
            }
            // Header
            if (line.trim().startsWith("## ") || line.trim().startsWith("### ")) {
                flushBuffer(container, buffer);
                String text = line.replaceAll("^#+\\s*", "");
                Label h = new Label(text);
                h.setFont(Font.font("Segoe UI", FontWeight.BOLD, 16));
                h.setTextFill(HEADER_COLOR);
                h.setPadding(new javafx.geometry.Insets(8, 0, 2, 0));
                container.getChildren().add(h);
                continue;
            }
            // Horizontal rule
            if (line.trim().matches("^-{3,}$") || line.trim().matches("^\\*{3,}$")) {
                flushBuffer(container, buffer);
                container.getChildren().add(new javafx.scene.control.Separator());
                continue;
            }
            buffer.add(line);
        }
        flushBuffer(container, buffer);
        return container;
    }

    private static void flushBuffer(VBox container, List<String> buffer) {
        if (buffer.isEmpty()) return;
        String text = String.join("\n", buffer);
        buffer.clear();

        TextFlow flow = new TextFlow();
        flow.setMaxWidth(780);
        flow.setLineSpacing(2);

        Matcher img = IMAGE_PATTERN.matcher(text);
        int last = 0;
        while (img.find()) {
            String before = text.substring(last, img.start());
            if (!before.isBlank()) appendInline(flow, before);
            appendImage(flow, img.group(1), img.group(2));
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
            t.setFill(TEXT_COLOR);
            flow.getChildren().add(t);
        }

        container.getChildren().add(flow);
    }

    /** 解析一段纯文本中的 **bold** / `code` / 普通文本。 */
    private static void appendInline(TextFlow flow, String text) {
        // Simple inline parsing: **bold**, `code`, plain text
        Pattern inline = Pattern.compile("(\\*\\*(.+?)\\*\\*)|(`(.+?)`)|([^*`]+)");
        Matcher m = inline.matcher(text);
        while (m.find()) {
            if (m.group(1) != null) {
                // **bold**
                Text t = new Text(m.group(2));
                t.setFont(Font.font("Segoe UI", FontWeight.BOLD, 13));
                t.setFill(TEXT_COLOR);
                flow.getChildren().add(t);
            } else if (m.group(3) != null) {
                // `code`
                Text t = new Text(m.group(4));
                t.setFont(Font.font("Consolas", 12));
                t.setFill(Color.web("#dc2626"));
                flow.getChildren().add(t);
            } else if (m.group(5) != null) {
                Text t = new Text(m.group(5));
                t.setFont(Font.font("Segoe UI", 13));
                t.setFill(TEXT_COLOR);
                flow.getChildren().add(t);
            }
        }
    }

    /** 渲染 ![alt](path) 为内联缩略图；图片不存在时退化为灰色替代文本。 */
    private static void appendImage(TextFlow flow, String alt, String rawPath) {
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

        if (file.exists() && file.isFile()) {
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
        fallback.setFill(Color.web("#9ca3af"));
        flow.getChildren().add(fallback);
    }

    /** 点击图片用系统默认程序打开原图。 */
    private static void openImage(File file) {
        try {
            java.awt.Desktop.getDesktop().open(file);
        } catch (Exception ignored) {}
    }
}
