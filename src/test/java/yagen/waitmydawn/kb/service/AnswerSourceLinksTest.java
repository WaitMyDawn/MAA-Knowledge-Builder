package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.Test;
import yagen.waitmydawn.kb.model.AnswerArtifacts;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 同一页面的多条来源行合并为一个入口。 */
class AnswerSourceLinksTest {
    private static AnswerArtifacts.Source source(String id, String document, String location, String heading) {
        return new AnswerArtifacts.Source(id, document, "标题", location, heading, null, "WEB", "证据");
    }

    @Test void mergesSamePageAndKeepsOtherPages() {
        var merged = AnswerSourceLinks.dedupeByLocation(List.of(
                source("s1", "d1", "https://example.com/a", "第一节"),
                source("s2", "d1", "https://example.com/a", "第二节"),
                source("s3", "d2", "https://example.com/b", "第三节")));
        assertEquals(2, merged.size());
        assertEquals("s1", merged.get(0).id());
        assertEquals("s3", merged.get(1).id());
    }
}
