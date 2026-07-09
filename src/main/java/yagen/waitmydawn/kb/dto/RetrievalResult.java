package yagen.waitmydawn.kb.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多源检索结果，供 Composer Agent 组织最终回答。
 */
public class RetrievalResult {

    private boolean found = false;
    private List<String> recipeJsons = new ArrayList<>();
    private List<String> multiblockJsons = new ArrayList<>();
    private List<String> wikiSnippets = new ArrayList<>();
    private Map<String, String> texturePaths = new LinkedHashMap<>();  // registryName → localPath
    private String sourceDescription = "";

    public boolean isFound() { return found; }
    public void setFound(boolean v) { this.found = v; }
    public List<String> getRecipeJsons() { return recipeJsons; }
    public void setRecipeJsons(List<String> v) { this.recipeJsons = v; }
    public List<String> getMultiblockJsons() { return multiblockJsons; }
    public void setMultiblockJsons(List<String> v) { this.multiblockJsons = v; }
    public List<String> getWikiSnippets() { return wikiSnippets; }
    public void setWikiSnippets(List<String> v) { this.wikiSnippets = v; }
    public Map<String, String> getTexturePaths() { return texturePaths; }
    public void setTexturePaths(Map<String, String> v) { this.texturePaths = v; }
    public String getSourceDescription() { return sourceDescription; }
    public void setSourceDescription(String v) { this.sourceDescription = v; }
}
