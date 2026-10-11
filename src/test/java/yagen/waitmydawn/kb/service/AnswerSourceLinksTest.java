package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AnswerSourceLinksTest {
    @TempDir Path temp;
    private VectorStore.SearchResult page(String name, String url) {
        return new VectorStore.SearchResult("id", "名称：" + name, "mod", "Mod", "mcmod", 1, url);
    }
    @Test void linksRepeatedNamesAndSurvivesHistoryRoundTrip() throws Exception {
        String answer = AnswerSourceLinks.enrich("| 名称 |\n| 炼金锅 |\n使用炼金锅。`mod:cauldron`",
                List.of(page("炼金锅 (Alchemist Cauldron)", "https://www.mcmod.cn/item/715507.html")));
        assertEquals(2, answer.split("\\[炼金锅\\]", -1).length - 1);
        assertTrue(answer.contains("`mod:cauldron`"));
        var history = new ChatHistoryService(temp);
        var session = history.newSession("links", temp);
        history.addMessage(session, "maa", answer); history.save(session);
        assertEquals(answer, history.load(session.sessionId).messages.getFirst().content());
        assertEquals(answer, AnswerSourceLinks.enrich(answer,
                List.of(page("炼金锅", "https://www.mcmod.cn/item/715507.html"))));
    }
    @Test void refusesAmbiguousNamesAndUnsafeSources() {
        assertEquals("墨水", AnswerSourceLinks.enrich("墨水", List.of(page("墨水", "https://example.com/a"), page("墨水", "https://example.com/b"))));
        assertEquals("墨水", AnswerSourceLinks.enrich("墨水", List.of(page("墨水", "javascript:alert(1)"))));
        assertFalse(AnswerSourceLinks.safeUrl("file:///C:/secret"));
    }
}
