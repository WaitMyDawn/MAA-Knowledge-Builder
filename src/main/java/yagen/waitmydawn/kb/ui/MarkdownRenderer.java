package yagen.waitmydawn.kb.ui;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.*;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 简易 Markdown → JavaFX TextFlow 渲染器。
 * 支持: ## header, **bold**, `code`, bullet list, table
 */
public class MarkdownRenderer {

    private static final Color TEXT_COLOR = Color.web("#1f2937");
    private static final Color HEADER_COLOR = Color.web("#111827");
    private static final Color CODE_BG = Color.web("#f3f4f6");

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
        if (flow.getChildren().isEmpty()) {
            Text t = new Text(text);
            t.setFont(Font.font("Segoe UI", 13));
            t.setFill(TEXT_COLOR);
            flow.getChildren().add(t);
        }

        container.getChildren().add(flow);
    }
}
