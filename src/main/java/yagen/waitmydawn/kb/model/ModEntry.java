package yagen.waitmydawn.kb.model;

/**
 * 从 JAR 元数据解析出的模组身份信息。
 */
public class ModEntry {

    private String jarPath;          // JAR 文件绝对路径
    private String jarFileName;      // JAR 文件名
    private String modId;            // 内部标识, e.g. "iceandfire"
    private String displayName;      // 显示名, e.g. "Ice And Fire Community Edition"
    private String version;          // Mod 版本, e.g. "2.0-beta.17"
    private String description;      // 模组描述文本
    private String authors;          // 作者
    private String license;          // 许可证, e.g. "LGPL-3.0"
    private String logoFile;         // logo 文件名 (在 JAR 内的路径)
    private String issueTrackerUrl;  // 问题追踪 URL
    private String loader;           // neoforge / forge / fabric / quilt
    private String mcVersion;        // 推断的 Minecraft 版本, e.g. "1.21.1"
    private String slug;             // Modrinth slug (Phase 2 绑定)
    private String modrinthUrl;      // Modrinth 项目 URL
    private String source;           // modrinth / curseforge / unknown

    public ModEntry() {}

    // --- getters / setters ---

    public String getJarPath() { return jarPath; }
    public void setJarPath(String v) { this.jarPath = v; }

    public String getJarFileName() { return jarFileName; }
    public void setJarFileName(String v) { this.jarFileName = v; }

    public String getModId() { return modId; }
    public void setModId(String v) { this.modId = v; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String v) { this.displayName = v; }

    public String getVersion() { return version; }
    public void setVersion(String v) { this.version = v; }

    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }

    public String getAuthors() { return authors; }
    public void setAuthors(String v) { this.authors = v; }

    public String getLicense() { return license; }
    public void setLicense(String v) { this.license = v; }

    public String getLogoFile() { return logoFile; }
    public void setLogoFile(String v) { this.logoFile = v; }

    public String getIssueTrackerUrl() { return issueTrackerUrl; }
    public void setIssueTrackerUrl(String v) { this.issueTrackerUrl = v; }

    public String getLoader() { return loader; }
    public void setLoader(String v) { this.loader = v; }

    public String getMcVersion() { return mcVersion; }
    public void setMcVersion(String v) { this.mcVersion = v; }

    public String getSlug() { return slug; }
    public void setSlug(String v) { this.slug = v; }

    public String getModrinthUrl() { return modrinthUrl; }
    public void setModrinthUrl(String v) { this.modrinthUrl = v; }

    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }

    @Override
    public String toString() {
        return String.format("ModEntry[modId=%s, display=%s, version=%s, mc=%s, loader=%s]",
                modId, displayName, version, mcVersion, loader);
    }
}
