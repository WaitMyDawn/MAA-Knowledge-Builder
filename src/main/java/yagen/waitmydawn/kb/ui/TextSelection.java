package yagen.waitmydawn.kb.ui;

import javafx.scene.*;
import javafx.scene.input.*;
import javafx.scene.paint.Color;
import javafx.scene.text.*;
import java.util.*;

/**
 * TextFlow 选区：用“整篇文档的全局字符偏移”建模，避免逐 Text 累计偏移出错。
 *
 * <p>JavaFX 的 TextFlow 不提供选区，但 Text 支持 selectionStart/End/Fill；
 * 这里把每个 TextFlow 视为一段文本（其 Text 子节点拼接），跨段选择时按 "\n" 连接。
 */
final class TextSelection {
    private TextSelection() {}

    static void install(Parent root) {
        var flows = new ArrayList<TextFlow>();
        collect(root, flows);
        if (flows.isEmpty()) return;
        root.setFocusTraversable(true);

        var segments = new ArrayList<String>();
        var starts = new ArrayList<Integer>();
        int total = 0;
        for (var flow : flows) {
            var text = new StringBuilder();
            for (var child : flow.getChildren()) if (child instanceof Text node) text.append(node.getText());
            segments.add(text.toString());
            starts.add(total);
            total += text.length() + 1;
        }

        int[] anchor = {-1};
        int[] caret = {-1};

        Runnable paint = () -> {
            int from = Math.min(anchor[0], caret[0]);
            int to = Math.max(anchor[0], caret[0]);
            if (from < 0 || to < 0) return;
            for (int i = 0; i < flows.size(); i++) {
                int flowStart = starts.get(i);
                int flowEnd = flowStart + segments.get(i).length();
                int offset = 0;
                for (var child : flows.get(i).getChildren()) {
                    if (!(child instanceof Text node)) continue;
                    int length = node.getText().length();
                    int selectionStart = clamp(from - flowStart - offset, length);
                    int selectionEnd = clamp(to - flowStart - offset, length);
                    node.setSelectionFill(Color.web("#3b6fd4", 0.45));
                    if (to <= flowStart || from >= flowEnd) {
                        node.setSelectionStart(0);
                        node.setSelectionEnd(0);
                    } else {
                        node.setSelectionStart(selectionStart);
                        node.setSelectionEnd(selectionEnd);
                    }
                    offset += length;
                }
            }
        };

        for (int i = 0; i < flows.size(); i++) {
            var flow = flows.get(i);
            int index = i;
            flow.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
                if (event.getButton() != MouseButton.PRIMARY) return;
                root.requestFocus();
                anchor[0] = caret[0] = globalOffset(flows, starts, index, event.getSceneX(), event.getSceneY());
                paint.run();
            });
        }

        root.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (anchor[0] < 0 || !event.isPrimaryButtonDown()) return;
            int best = -1;
            double distance = Double.MAX_VALUE;
            for (int i = 0; i < flows.size(); i++) {
                var flow = flows.get(i);
                var point = flow.sceneToLocal(event.getSceneX(), event.getSceneY());
                double dx = point.getX() < 0 ? -point.getX() : Math.max(0, point.getX() - flow.getWidth());
                double dy = point.getY() < 0 ? -point.getY() : Math.max(0, point.getY() - flow.getHeight());
                double d = dx * dx + dy * dy;
                if (d < distance) { distance = d; best = i; }
            }
            if (best >= 0) {
                caret[0] = globalOffset(flows, starts, best, event.getSceneX(), event.getSceneY());
                paint.run();
                event.consume();
            }
        });

        root.addEventFilter(MouseEvent.MOUSE_CLICKED, event -> {
            if (anchor[0] >= 0 && anchor[0] != caret[0]) event.consume();
        });

        root.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (!event.isShortcutDown() || event.getCode() != KeyCode.C || anchor[0] < 0) return;
            int from = Math.min(anchor[0], caret[0]);
            int to = Math.max(anchor[0], caret[0]);
            var selected = new StringBuilder();
            boolean first = true;
            for (int i = 0; i < flows.size(); i++) {
                int flowStart = starts.get(i);
                String text = segments.get(i);
                int flowEnd = flowStart + text.length();
                if (to <= flowStart || from >= flowEnd) continue;
                if (!first) selected.append('\n');
                first = false;
                int begin = Math.max(0, from - flowStart);
                int finish = Math.min(text.length(), to - flowStart);
                if (finish > begin) selected.append(text, begin, finish);
            }
            if (!selected.isEmpty()) {
                var content = new ClipboardContent();
                content.putString(selected.toString());
                Clipboard.getSystemClipboard().setContent(content);
                event.consume();
            }
        });
    }

    /** 场景坐标 → 该 TextFlow 在整篇文档中的全局字符偏移。 */
    private static int globalOffset(List<TextFlow> flows, List<Integer> starts, int index, double sceneX, double sceneY) {
        var flow = flows.get(index);
        var point = flow.sceneToLocal(sceneX, sceneY);
        var hit = flow.hitTest(point);
        int local;
        if (hit != null) {
            local = hit.getInsertionIndex();
        } else {
            local = point.getX() >= flow.getWidth() / 2 ? flowTextLength(flow) : 0;
        }
        return starts.get(index) + Math.max(0, Math.min(flowTextLength(flow), local));
    }

    private static int flowTextLength(TextFlow flow) {
        int total = 0;
        for (var child : flow.getChildren()) if (child instanceof Text node) total += node.getText().length();
        return total;
    }

    private static int clamp(int value, int length) {
        return Math.max(0, Math.min(length, value));
    }

    private static void collect(Node node, List<TextFlow> flows) {
        if (node instanceof TextFlow flow) flows.add(flow);
        else if (node instanceof Parent parent) for (var child : parent.getChildrenUnmodifiable()) collect(child, flows);
    }
}
