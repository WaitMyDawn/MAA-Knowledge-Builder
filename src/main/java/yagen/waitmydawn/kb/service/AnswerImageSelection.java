package yagen.waitmydawn.kb.service;

import java.util.*;
import yagen.waitmydawn.kb.model.AnswerArtifacts;

/** 最终回答只保留回答实际引用段落附近的插图；不把同源文档其他段落的图带进来。 */
final class AnswerImageSelection {
    private AnswerImageSelection() {}

    static void apply(QaPipeline.QaResult result, String question) {
        if (result == null || result.artifacts == null || result.artifacts.pictures().isEmpty()) return;
        var cited = new LinkedHashSet<>(result.artifacts.citedSourceIds());
        boolean missingImage = result.answer != null
                && result.answer.matches("(?s).*(?:没有|未找到|未取得|未提供|无法提供).{0,60}(?:匹配|可用|对应)?.{0,12}图片.*|(?s).*NO_MATCHING_IMAGES.*");
        var kept = new ArrayList<AnswerArtifacts.Picture>();
        var assets = new HashSet<String>();
        for (var picture : result.artifacts.pictures()) {
            if (!"ILLUSTRATION".equals(picture.role())) {
                if (assets.add(key(picture))) kept.add(picture);
                continue;
            }
            if (missingImage) continue;
            if (picture.sourceId() != null && !picture.sourceId().isBlank() && !cited.isEmpty()
                    && !cited.contains(picture.sourceId())) continue;
            String context = String.join("\n", Objects.toString(picture.caption(), ""),
                    Objects.toString(picture.context(), ""),
                    picture.grounding() == null ? "" : Objects.toString(picture.grounding().stepText(), ""));
            if (!context.isBlank() && KnowledgeSearchText.overlap(Objects.toString(question, "") + "\n" + Objects.toString(result.answer, ""), context) <= 0)
                continue;
            if (!assets.add(key(picture))) continue;
            kept.add(picture);
        }
        result.artifacts = new AnswerArtifacts(result.artifacts.scopeId(), result.artifacts.scopeLabel(),
                result.artifacts.sources(), kept, result.artifacts.citedSourceIds());
    }

    private static String key(AnswerArtifacts.Picture picture) {
        if (picture.assetId() != null && !picture.assetId().isBlank()) return "asset:" + picture.assetId();
        if (picture.relativePath() != null && !picture.relativePath().isBlank()) return "path:" + picture.relativePath();
        return "url:" + Objects.toString(picture.originalUrl(), picture.id());
    }
}
