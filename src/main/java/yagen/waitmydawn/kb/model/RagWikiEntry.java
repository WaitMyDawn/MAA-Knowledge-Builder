package yagen.waitmydawn.kb.model;

import java.time.LocalDateTime;

/** Wiki 知识条目 */
public class RagWikiEntry {
    private Long id;
    private String title;
    private String sourceUrl;
    private String content;
    private String embeddingJson;
    private String category;
    private String modSource;
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();

    public RagWikiEntry() {}

    // getters / setters
    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String v) { this.sourceUrl = v; }
    public String getContent() { return content; }
    public void setContent(String v) { this.content = v; }
    public String getEmbeddingJson() { return embeddingJson; }
    public void setEmbeddingJson(String v) { this.embeddingJson = v; }
    public String getCategory() { return category; }
    public void setCategory(String v) { this.category = v; }
    public String getModSource() { return modSource; }
    public void setModSource(String v) { this.modSource = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime v) { this.updatedAt = v; }
}
