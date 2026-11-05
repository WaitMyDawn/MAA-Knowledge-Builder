package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.Test;
import yagen.waitmydawn.kb.model.AnswerArtifacts;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnswerImageSelectionTest {
    private static AnswerArtifacts.Picture picture(String id, String asset, String source, String caption, String role) {
        return new AnswerArtifacts.Picture(id, asset, "a/" + asset + ".png", caption, source, role,
                List.of(), caption, null, null);
    }

    @Test void keepsOnlyImagesBoundToCitedAnswerTextAndDeduplicatesAssets() {
        var sources = List.of(
                new AnswerArtifacts.Source("s1", "d1", "攻略", "https://example.com/guide", "步骤", null, "NATIVE", "组装傀儡需要铁砧。"),
                new AnswerArtifacts.Source("s2", "d2", "无关", "https://example.com/other", "其他", null, "NATIVE", "无关内容。"));
        var artifacts = new AnswerArtifacts("scope", "scope", sources, List.of(
                picture("i1", "a1", "s1", "组装步骤", "ILLUSTRATION"),
                picture("i2", "a2", "s2", "无关图片", "ILLUSTRATION"),
                picture("i3", "a1", "s1", "组装步骤重复", "ILLUSTRATION"),
                picture("recipe", "card1", "s1", "配方卡", "RECIPE")), List.of("s1"));
        var result = new QaPipeline.QaResult();
        result.artifacts = artifacts;
        result.answer = "组装傀儡需要铁砧。";
        AnswerImageSelection.apply(result, "龙之冒险如何组装傀儡？");
        assertEquals(List.of("i1", "recipe"), result.artifacts.pictures().stream().map(AnswerArtifacts.Picture::id).toList());
    }

    @Test void missingImageStateDoesNotFallBackToAnotherIllustration() {
        var artifacts = new AnswerArtifacts("scope", "scope",
                List.of(new AnswerArtifacts.Source("s1", "d1", "实体", "https://example.com/entity", "状态", null, "NATIVE", "成年牛")),
                List.of(picture("adult", "a1", "s1", "成年牛", "ILLUSTRATION")), List.of("s1"));
        var result = new QaPipeline.QaResult();
        result.artifacts = artifacts;
        result.answer = "本次快照没有与幼年牛匹配的可用图片。";
        AnswerImageSelection.apply(result, "展示幼年牛图片");
        assertTrue(result.artifacts.pictures().isEmpty());
    }
}
