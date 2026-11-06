package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.Test;
import yagen.waitmydawn.kb.model.AnswerArtifacts;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 尾部堆图按图注/上下文回到相关段落。 */
class AnswerMediaIllustrationTest {
    private static AnswerArtifacts artifacts(AnswerArtifacts.Picture picture) {
        return new AnswerArtifacts("scope", "scope", List.of(), List.of(picture));
    }

    private static AnswerArtifacts.Picture picture(String id, String caption, String context) {
        return new AnswerArtifacts.Picture(id, "asset-" + id, "assets/" + id + ".png", caption, null,
                "ILLUSTRATION", List.of(), context, "", "");
    }

    @Test void movesTailIllustrationNextToMatchingParagraph() {
        String text = "铁傀儡由铁块和南瓜组成，摆放顺序固定。\n\n## 外观对照\n\n![铁傀儡](kb-image:i1)";
        String moved = AnswerMedia.relocateTrailingIllustrations(text, artifacts(picture("i1", "铁傀儡", "铁傀儡 铁块 南瓜 摆放")));
        assertTrue(moved.contains("铁傀儡由铁块和南瓜组成，摆放顺序固定。\n\n![铁傀儡](kb-image:i1)"), moved);
        assertTrue(moved.contains("## 外观对照"), moved);
    }

    @Test void keepsUnmatchedTailIllustrationInPlace() {
        String text = "附魔台需要黑曜石、钻石和书。\n\n## 外观对照\n\n![无关图](kb-image:i2)";
        String same = AnswerMedia.relocateTrailingIllustrations(text, artifacts(picture("i2", "无关图", "完全无关的内容")));
        assertEquals(text, same);
    }
}
