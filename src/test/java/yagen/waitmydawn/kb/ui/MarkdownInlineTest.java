package yagen.waitmydawn.kb.ui;

import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownInlineTest {
    @Test void boldCanSpanLineBreakWithoutLeakingMarkers() {
        var flow = new TextFlow();
        MarkdownRenderer.appendStyled(flow, "**第一行\n第二行** 后续");
        assertEquals(2, flow.getChildren().size());
        var bold = (Text) flow.getChildren().get(0);
        assertEquals("第一行\n第二行", bold.getText());
        assertTrue(bold.getFont().getStyle().toLowerCase().contains("bold"));
        var normal = (Text) flow.getChildren().get(1);
        assertEquals(" 后续", normal.getText());
        assertFalse(normal.getText().contains("**"));
    }

    @Test void inlineCodeAndBoldAreSeparated() {
        var flow = new TextFlow();
        MarkdownRenderer.appendStyled(flow, "**A** `B` C");
        var texts = flow.getChildren().stream().map(node -> ((Text) node).getText()).toList();
        assertTrue(texts.contains("A"));
        assertTrue(texts.contains("B"));
        assertEquals("A B C", String.join(" ", texts).replaceAll("\\s+", " ").trim());
    }
}
