package yagen.waitmydawn.kb.model;

import java.time.LocalDateTime;

/** 多方块结构实体 */
public class RagMultiblock {
    private Long id;
    private String structureName;
    private String displayName;
    private String modSource;
    private String layersJson;
    private String description;
    private String materialsJson;
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();

    public RagMultiblock() {}

    // getters / setters
    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }
    public String getStructureName() { return structureName; }
    public void setStructureName(String v) { this.structureName = v; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String v) { this.displayName = v; }
    public String getModSource() { return modSource; }
    public void setModSource(String v) { this.modSource = v; }
    public String getLayersJson() { return layersJson; }
    public void setLayersJson(String v) { this.layersJson = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public String getMaterialsJson() { return materialsJson; }
    public void setMaterialsJson(String v) { this.materialsJson = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime v) { this.updatedAt = v; }
}
