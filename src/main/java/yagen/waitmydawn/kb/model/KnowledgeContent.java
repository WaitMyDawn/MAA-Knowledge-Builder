package yagen.waitmydawn.kb.model;

/** 稳定内容标识不使用数据库自增 ID，保证临时库发布及后续图片引用不改变身份。 */
public final class KnowledgeContent {
    private KnowledgeContent() {}

    public record Document(String id, String sourceId, String title, String location,
                           String contentHash, String mediaType) {}
    public record Chunk(String id, String documentId, String text, String heading,
                        Integer pageNumber, int ordinal) {}
    public record Block(String id,String documentId,String headingPath,String lineage,int ordinal,Integer pageNumber,
                        String kind,String tableId,String text,String method) {}
    public record ImageAsset(String id, String relativePath, String mediaType,
                             int width, int height, String ocrText, String description) {}
    /** 图注与位置属于图片在文档中的一次出现，而不是图片二进制本身。 */
    public record ImageOccurrence(String id, String documentId, String imageId, String caption,
                                  String originalLocation, Integer pageNumber, int ordinal) {}
    public record ChunkImageLink(String chunkId, String occurrenceId, String relation) {}
}
