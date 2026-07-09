package yagen.waitmydawn.kb.model;

import java.time.LocalDateTime;

/** 纹理缓存 */
public class RagTextureCache {
    private Long id;
    private String registryName;
    private String localPath;
    private String source = "jar_extract";
    private String modSource;
    private LocalDateTime createdAt = LocalDateTime.now();

    public RagTextureCache() {}

    // getters / setters
    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }
    public String getRegistryName() { return registryName; }
    public void setRegistryName(String v) { this.registryName = v; }
    public String getLocalPath() { return localPath; }
    public void setLocalPath(String v) { this.localPath = v; }
    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
    public String getModSource() { return modSource; }
    public void setModSource(String v) { this.modSource = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
