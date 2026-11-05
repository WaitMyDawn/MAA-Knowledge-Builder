package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.Test;
import yagen.waitmydawn.kb.model.AnswerArtifacts;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 纯外观展示必须复用 prepare 阶段的选图结果，正文与配图不能互相矛盾。
 */
class PlayerMediaPresentationTest {
    private static QaPipeline.QaResult resultWith(MediaRequestPlanner.Request request, String mappedPicture) {
        var result = new QaPipeline.QaResult();
        result.mediaPlan = new MediaRequestPlanner.Plan(List.of(request), false, false, List.of());
        var picture = new AnswerArtifacts.Picture("i1", "a1", "assets/i1.png", "牛（幼年）", "s1", "ENTITY",
                List.of(), "注册名：minecraft:cow", "", "");
        result.artifacts = new AnswerArtifacts(null, null, List.of(), List.of(picture));
        if (mappedPicture != null) result.mediaRequestPictures.put(request.query(), mappedPicture);
        return result;
    }

    private static MediaRequestPlanner.Request request() {
        var subject = new MediaRequestPlanner.Subject("minecraft:cow", "ENTITY", List.of("牛"));
        return new MediaRequestPlanner.Request(subject, "ENTITY", "minecraft:cow age=baby 展示图片 实体", "牛 · age=baby");
    }

    @Test void matchedRequestRendersItsImageOnly() {
        var text = PlayerMediaPresentation.render(resultWith(request(), "i1"));
        assertEquals("[[image:i1]]", text);
        assertFalse(text.contains("没有与"));
    }

    @Test void unmatchedRequestStatesTheMissingState() {
        var text = PlayerMediaPresentation.render(resultWith(request(), null));
        assertTrue(text.contains("本次快照没有与“牛 · age=baby”匹配的可用图片。"));
    }
}
