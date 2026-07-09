package yagen.waitmydawn.kb.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * 训练种子 — 记录构建知识库所需的资源清单。
 * .maa-seed.json 格式。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TrainingSeed {

    @JsonProperty("seedVersion")
    private String seedVersion = "1.0";

    @JsonProperty("name")
    private String name = "Untitled Knowledge Base";

    @JsonProperty("description")
    private String description = "";

    @JsonProperty("author")
    private String author = "";

    @JsonProperty("createdAt")
    private String createdAt = "";

    @JsonProperty("gameVersion")
    private String gameVersion = "1.21.1";

    @JsonProperty("loader")
    private String loader = "neoforge";

    @JsonProperty("mods")
    private List<SeedMod> mods = new ArrayList<>();

    @JsonProperty("webResources")
    private List<SeedWebResource> webResources = new ArrayList<>();

    // --- 种子中的模组条目 ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SeedMod {
        @JsonProperty("slug")
        public String slug;
        @JsonProperty("modId")
        public String modId;
        @JsonProperty("displayName")
        public String displayName;
        @JsonProperty("modVersion")
        public String modVersion;
        @JsonProperty("mcVersion")
        public String mcVersion;
        @JsonProperty("loader")
        public String loader = "neoforge";
        @JsonProperty("source")
        public String source = "modrinth";
        @JsonProperty("modrinthUrl")
        public String modrinthUrl;

        public SeedMod() {}

        public static SeedMod from(ModEntry e) {
            SeedMod sm = new SeedMod();
            sm.slug = e.getSlug();
            sm.modId = e.getModId();
            sm.displayName = e.getDisplayName();
            sm.modVersion = e.getVersion();
            sm.mcVersion = e.getMcVersion();
            sm.loader = e.getLoader();
            sm.source = e.getSource() != null ? e.getSource() : "unknown";
            sm.modrinthUrl = e.getModrinthUrl();
            return sm;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SeedWebResource {
        @JsonProperty("type")
        public String type;   // mcmod_wiki / mod_wiki / custom
        @JsonProperty("url")
        public String url;
        @JsonProperty("description")
        public String description;

        public SeedWebResource() {}

        public SeedWebResource(String type, String url, String description) {
            this.type = type; this.url = url; this.description = description;
        }
    }

    // --- getters / setters ---

    public String getSeedVersion() { return seedVersion; }
    public void setSeedVersion(String v) { this.seedVersion = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public String getAuthor() { return author; }
    public void setAuthor(String v) { this.author = v; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String v) { this.createdAt = v; }
    public String getGameVersion() { return gameVersion; }
    public void setGameVersion(String v) { this.gameVersion = v; }
    public String getLoader() { return loader; }
    public void setLoader(String v) { this.loader = v; }
    public List<SeedMod> getMods() { return mods; }
    public void setMods(List<SeedMod> v) { this.mods = v; }
    public List<SeedWebResource> getWebResources() { return webResources; }
    public void setWebResources(List<SeedWebResource> v) { this.webResources = v; }
}
