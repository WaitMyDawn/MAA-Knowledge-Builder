package yagen.waitmydawn.kb.model;

import java.time.LocalDateTime;

/** 物品实体 */
public class RagItem {
    private Long id;
    private String registryName;
    private String displayName;
    private String modSource = "minecraft";
    private String itemType;
    private Integer maxStack = 64;
    private Integer durability;
    private String versions;
    private String texturePath;
    private String iconUrl;
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();

    public RagItem() {}

    public RagItem(String registryName, String displayName, String modSource, String itemType) {
        this.registryName = registryName;
        this.displayName = displayName;
        this.modSource = modSource;
        this.itemType = itemType;
    }

    // getters / setters
    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }
    public String getRegistryName() { return registryName; }
    public void setRegistryName(String v) { this.registryName = v; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String v) { this.displayName = v; }
    public String getModSource() { return modSource; }
    public void setModSource(String v) { this.modSource = v; }
    public String getItemType() { return itemType; }
    public void setItemType(String v) { this.itemType = v; }
    public Integer getMaxStack() { return maxStack; }
    public void setMaxStack(Integer v) { this.maxStack = v; }
    public Integer getDurability() { return durability; }
    public void setDurability(Integer v) { this.durability = v; }
    public String getVersions() { return versions; }
    public void setVersions(String v) { this.versions = v; }
    public String getTexturePath() { return texturePath; }
    public void setTexturePath(String v) { this.texturePath = v; }
    public String getIconUrl() { return iconUrl; }
    public void setIconUrl(String v) { this.iconUrl = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime v) { this.updatedAt = v; }
}
