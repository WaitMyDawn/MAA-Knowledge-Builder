package yagen.waitmydawn.kb.model;

import java.util.List;

/** 回答携带证据快照与相对资源路径，历史回放不依赖当前基础库的行 ID。 */
public record AnswerArtifacts(String scopeId, String scopeLabel, List<Source> sources, List<Picture> pictures,
                              List<String> citedSourceIds) {
    public AnswerArtifacts(String scopeId,String scopeLabel,List<Source> sources,List<Picture> pictures) {
        this(scopeId,scopeLabel,sources,pictures,List.of());
    }
    public AnswerArtifacts {
        sources = sources == null ? List.of() : List.copyOf(sources);
        pictures = pictures == null ? List.of() : List.copyOf(pictures);
        citedSourceIds=citedSourceIds==null?List.of():List.copyOf(citedSourceIds);
    }
    public static AnswerArtifacts empty() { return new AnswerArtifacts(null, null, List.of(), List.of()); }
    public record ImageGrounding(String purpose,String basis,String blockId,String stepText,String relation,String analysisStatus) {}
    public record Source(String id, String documentId, String title, String location, String heading,
                         Integer pageNumber, String method, String evidence) {}
    public record Picture(String id, String assetId, String relativePath, String caption, String sourceId,
                          String role, List<String> chunkIds, String context, String originalUrl, String referer,
                          @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) ImageGrounding grounding) {
        public Picture(String id,String assetId,String relativePath,String caption,String sourceId,String role,List<String> chunkIds,String context,String originalUrl,String referer) {
            this(id,assetId,relativePath,caption,sourceId,role,chunkIds,context,originalUrl,referer,null);
        }
        public Picture(String id,String assetId,String relativePath,String caption,String sourceId,String role,List<String> chunkIds,String context) {
            this(id,assetId,relativePath,caption,sourceId,role,chunkIds,context,null,null);
        }
        public Picture { chunkIds = chunkIds == null ? List.of() : List.copyOf(chunkIds); }
    }
}
