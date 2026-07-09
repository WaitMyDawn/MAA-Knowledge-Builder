package yagen.waitmydawn.kb.model;

import java.time.LocalDateTime;

/** 配方实体 */
public class RagRecipe {
    private Long id;
    private String recipeType;
    private String outputItem;
    private Integer outputCount = 1;
    private String recipeData;
    private String sourceMod = "minecraft";
    private String sourcePack;
    private String versionRange;
    private String versionExact;
    private Boolean isModified = false;
    private String modVersion;
    private String loader = "neoforge";
    private String sourceUrl;
    private Integer confirmCount = 1;
    private String sourceType = "JAR_PARSE";
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();

    public RagRecipe() {}

    // getters / setters
    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }
    public String getRecipeType() { return recipeType; }
    public void setRecipeType(String v) { this.recipeType = v; }
    public String getOutputItem() { return outputItem; }
    public void setOutputItem(String v) { this.outputItem = v; }
    public Integer getOutputCount() { return outputCount; }
    public void setOutputCount(Integer v) { this.outputCount = v; }
    public String getRecipeData() { return recipeData; }
    public void setRecipeData(String v) { this.recipeData = v; }
    public String getSourceMod() { return sourceMod; }
    public void setSourceMod(String v) { this.sourceMod = v; }
    public String getSourcePack() { return sourcePack; }
    public void setSourcePack(String v) { this.sourcePack = v; }
    public String getVersionRange() { return versionRange; }
    public void setVersionRange(String v) { this.versionRange = v; }
    public String getVersionExact() { return versionExact; }
    public void setVersionExact(String v) { this.versionExact = v; }
    public Boolean getIsModified() { return isModified; }
    public void setIsModified(Boolean v) { this.isModified = v; }
    public String getModVersion() { return modVersion; }
    public void setModVersion(String v) { this.modVersion = v; }
    public String getLoader() { return loader; }
    public void setLoader(String v) { this.loader = v; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String v) { this.sourceUrl = v; }
    public Integer getConfirmCount() { return confirmCount; }
    public void setConfirmCount(Integer v) { this.confirmCount = v; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String v) { this.sourceType = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime v) { this.updatedAt = v; }
}
