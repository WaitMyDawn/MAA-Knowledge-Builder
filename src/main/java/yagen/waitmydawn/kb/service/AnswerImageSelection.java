package yagen.waitmydawn.kb.service;

import java.util.*;
import yagen.waitmydawn.kb.model.AnswerArtifacts;

/** 最终回答只保留回答实际引用段落附近的插图；不把同源文档其他段落的图带进来。 */
final class AnswerImageSelection {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AnswerImageSelection.class);
    private AnswerImageSelection() {}

    static void apply(QaPipeline.QaResult result, String question) {
        if (result == null || result.artifacts == null || result.artifacts.pictures().isEmpty()) return;
        var cited = new LinkedHashSet<>(result.artifacts.citedSourceIds());
        var citedLocations = new HashSet<String>();
        for (var source : result.artifacts.sources())
            if (cited.contains(source.id()) && source.location() != null && !source.location().isBlank())
                citedLocations.add(source.location());
        boolean missingImage = saysNoImage(result.answer);
        var kept = new ArrayList<AnswerArtifacts.Picture>();
        var assets = new HashSet<String>();
        for (var picture : result.artifacts.pictures()) {
            if (!"ILLUSTRATION".equals(picture.role())) {
                if (assets.add(key(picture))) kept.add(picture);
                continue;
            }
            String reason = dropReason(picture, question, result, cited, citedLocations, missingImage);
            if (reason != null) {
                log.debug("illustration dropped: {} reason={} source={}", picture.id(), reason, picture.sourceId());
                continue;
            }
            if (!assets.add(key(picture))) continue;
            kept.add(picture);
        }
        result.artifacts = new AnswerArtifacts(result.artifacts.scopeId(), result.artifacts.scopeLabel(),
                result.artifacts.sources(), kept, result.artifacts.citedSourceIds());
    }

    private static String dropReason(AnswerArtifacts.Picture picture, String question, QaPipeline.QaResult result,
                                     Set<String> cited, Set<String> citedLocations, boolean missingImage) {
        if (missingImage) return "MISSING_IMAGE";
        if (picture.sourceId() != null && !picture.sourceId().isBlank() && !cited.isEmpty() && !cited.contains(picture.sourceId())) {
            // 同一页面会拆成多条 source 行；回答引用了该页面的任意一行，就视为该页已被引用。
            var location = result.artifacts.sources().stream()
                    .filter(source -> picture.sourceId().equals(source.id()))
                    .map(AnswerArtifacts.Source::location).findFirst().orElse(null);
            if (location == null || !citedLocations.contains(location)) return "SOURCE_NOT_CITED";
        }
        String context = String.join("\n", Objects.toString(picture.caption(), ""),
                Objects.toString(picture.context(), ""),
                picture.grounding() == null ? "" : Objects.toString(picture.grounding().stepText(), ""));
        if (!context.isBlank() && KnowledgeSearchText.overlap(Objects.toString(question, "") + "\n" + Objects.toString(result.answer, ""), context) <= 0)
            return "LOW_OVERLAP";
        return null;
    }

    /** 只有回答明确说“没有可用图片”时才清理插图；按句判断，且已引用图片时永不清理。 */
    static boolean saysNoImage(String answer) {
        if (answer == null) return false;
        if (answer.contains("kb-image:")) return false;
        for (String sentence : answer.split("[。！？\\r\\n]"))
            if (sentence.matches("(?s).*(?:没有|未找到|未取得|未提供|无法提供).{0,40}(?:匹配|可用|对应)?.{0,12}图片.*")) return true;
        return false;
    }

    private static String key(AnswerArtifacts.Picture picture) {
        if (picture.assetId() != null && !picture.assetId().isBlank()) return "asset:" + picture.assetId();
        if (picture.relativePath() != null && !picture.relativePath().isBlank()) return "path:" + picture.relativePath();
        return "url:" + Objects.toString(picture.originalUrl(), picture.id());
    }
}
