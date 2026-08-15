package yagen.waitmydawn.kb.service;

import com.electronwill.nightconfig.core.file.FileConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yagen.waitmydawn.kb.model.ModEntry;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 JAR 文件中解析模组元数据。
 * 支持: neoforge.mods.toml, mods.toml, fabric.mod.json。
 * 包含 MC 版本推断逻辑。
 */
public class ModMetadataParser {

    private static final Logger log = LoggerFactory.getLogger(ModMetadataParser.class);
    private static final ObjectMapper mapper = new ObjectMapper();


    // versionRange pattern: [21.1.230,)  or [21.1,)  or (20.4,21.1]
    private static final Pattern VERSION_RANGE_P =
            Pattern.compile("\\d+\\.\\d+(\\.\\d+)?");

    /**
     * 解析 JAR 文件，提取元数据。
     * 返回 ModEntry (可能为 null 表示无法解析)。
     */
    public ModEntry parse(Path jarPath) {
        ModEntry entry = new ModEntry();
        entry.setJarPath(jarPath.toAbsolutePath().toString());
        entry.setJarFileName(jarPath.getFileName().toString());

        try (JarFile jar = new JarFile(jarPath.toFile())) {

            // 优先级 1: neoforge.mods.toml
            JarEntry neoforgeEntry = jar.getJarEntry("META-INF/neoforge.mods.toml");
            if (neoforgeEntry != null) {
                parseToml(jar, neoforgeEntry, entry, "neoforge");
                log.debug("Analysis successful (neoforge): {} : modId={}", jarPath.getFileName(), entry.getModId());
                return entry;
            }

            // 优先级 2: mods.toml (Forge)
            JarEntry forgeEntry = jar.getJarEntry("META-INF/mods.toml");
            if (forgeEntry != null) {
                parseToml(jar, forgeEntry, entry, "forge");
                log.debug("Analysis successful (forge): {} → modId={}", jarPath.getFileName(), entry.getModId());
                return entry;
            }

            // 优先级 3: fabric.mod.json
            JarEntry fabricEntry = jar.getJarEntry("fabric.mod.json");
            if (fabricEntry != null) {
                parseFabricJson(jar, fabricEntry, entry);
                log.debug("Analysis successful (fabric): {} → modId={}", jarPath.getFileName(), entry.getModId());
                return entry;
            }

            // 优先级 4: 检测是否为 Minecraft 本体 JAR (无 mod metadata 但有 data/minecraft/)
            if (isVanillaMinecraftJar(jar)) {
                entry.setModId("minecraft");
                entry.setDisplayName("Minecraft");
                entry.setLoader("vanilla");
                entry.setSource("vanilla");
                entry.setMcVersion(detectMcVersionFromJarName(jarPath.getFileName().toString()));
                log.debug("识别为 Minecraft 本体: {} → mcVersion={}", jarPath.getFileName(), entry.getMcVersion());
                return entry;
            }

            log.warn("未找到模组元数据文件: {}", jarPath.getFileName());
            return null;

        } catch (IOException e) {
            log.error("解析 JAR 失败: {} — {}", jarPath.getFileName(), e.getMessage());
            return null;
        }
    }

    /**
     * 解析 neoforge.mods.toml 或 mods.toml。
     * @param loader 取值为 neoforge/forge
     */
    private void parseToml(JarFile jar, JarEntry entry, ModEntry modEntry, String loader) throws IOException {
        modEntry.setLoader(loader);

        // 将 TOML 内容写入临时字符串，用 night-config 解析
        String content;
        try (InputStream is = jar.getInputStream(entry)) {
            content = new String(is.readAllBytes());
        }

        // night-config toml 不支持直接从字符串解析，写入临时文件
        Path tmpFile = null;
        try {
            tmpFile = Files.createTempFile("maa_toml_", ".toml");
            Files.writeString(tmpFile, content);

            try (FileConfig toml = FileConfig.of(tmpFile)) {
                toml.load();

                // [[mods]] section
                List<com.electronwill.nightconfig.core.Config> modsList = toml.get("mods");
                if (modsList != null && !modsList.isEmpty()) {
                    com.electronwill.nightconfig.core.Config modSection = modsList.get(0);
                    modEntry.setModId(modSection.get("modId"));
                    modEntry.setDisplayName(modSection.get("displayName"));
                    modEntry.setVersion(modSection.get("version"));
                    modEntry.setDescription(modSection.get("description"));
                    modEntry.setAuthors(modSection.get("authors"));
                    modEntry.setLogoFile(modSection.get("logoFile"));
                }

                // 根级别读取
                modEntry.setLicense(toml.get("license"));
                modEntry.setIssueTrackerUrl(toml.get("issueTrackerURL"));

                // [[dependencies.{modId}]] → versionRange → MC 版本推断
                inferMcVersionFromToml(toml, modEntry, loader);
            }
        } finally {
            if (tmpFile != null) {
                try { Files.deleteIfExists(tmpFile); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * 从 neoforge.mods.toml / mods.toml 的 [[dependencies]] 推断 MC 版本和 Loader。
     * 通过 minecraft 依赖直接给出 MC 版本
     */
    private void inferMcVersionFromToml(FileConfig toml, ModEntry entry, String initialLoader) {
        // 收集所有依赖项
        List<com.electronwill.nightconfig.core.Config> allDeps = new ArrayList<>();

        // [[dependencies.{modId}]]
        if (entry.getModId() != null) {
            List<com.electronwill.nightconfig.core.Config> deps = toml.get("dependencies." + entry.getModId());
            if (deps != null) allDeps.addAll(deps);
        }

        // 通过依赖关系获得MC版本
        for (com.electronwill.nightconfig.core.Config dep : allDeps) {
            String depModId = dep.get("modId");
            String versionRange = dep.get("versionRange");
            if ("minecraft".equalsIgnoreCase(depModId) && versionRange != null) {
                String mcVersion = extractMcFromRange(versionRange);
                if (mcVersion != null) {
                    entry.setMcVersion(mcVersion);
                    return;
                }
            }
        }
        entry.setMcVersion(null);
    }

    /** Extract MC version directly from versionRange like "[1.21.1,)" */
    private String extractMcFromRange(String versionRange) {
        if (versionRange == null) return null;
        Matcher m = VERSION_RANGE_P.matcher(versionRange);
        if (m.find())
            return m.group(0);
        return null;
    }

    /**
     * 解析 fabric.mod.json。
     * 未确认：不熟悉且不用fabric
     */
    private void parseFabricJson(JarFile jar, JarEntry entry, ModEntry modEntry) throws IOException {
        modEntry.setLoader("fabric");

        String content;
        try (InputStream is = jar.getInputStream(entry)) {
            content = new String(is.readAllBytes());
        }

        JsonNode root = mapper.readTree(content);

        modEntry.setModId(root.path("id").asText(null));
        modEntry.setDisplayName(root.path("name").asText(null));
        modEntry.setVersion(root.path("version").asText(null));
        modEntry.setDescription(root.path("description").asText(null));

        // authors
        JsonNode authorsArr = root.path("authors");
        if (authorsArr.isArray()) {
            List<String> names = new ArrayList<>();
            for (JsonNode a : authorsArr) {
                if (a.isObject()) names.add(a.path("name").asText(""));
                else names.add(a.asText());
            }
            modEntry.setAuthors(String.join(", ", names));
        } else if (authorsArr.isTextual()) {
            modEntry.setAuthors(authorsArr.asText());
        }

        modEntry.setLicense(root.path("license").asText(null));
        modEntry.setLogoFile(root.path("icon").asText(null));

        // contact
        JsonNode contact = root.path("contact");
        modEntry.setIssueTrackerUrl(contact.path("issues").asText(null));

        // MC version from depends
        JsonNode depends = root.path("depends");
        if (depends.has("minecraft")) {
            modEntry.setMcVersion(depends.path("minecraft").asText(null));
        }

        // If no direct mc version, try to find it from version name
        if (modEntry.getMcVersion() == null) {
            String ver = modEntry.getVersion();
            if (ver != null && ver.contains("+")) {
                modEntry.setMcVersion(ver.substring(ver.lastIndexOf('+') + 1));
            }
        }
    }

    /** Check if JAR looks like a vanilla Minecraft JAR (has data/minecraft/ structure) */
    private boolean isVanillaMinecraftJar(JarFile jar) {
        // Check for data/minecraft/ directory (vanilla recipes, tags, etc.)
        JarEntry dataDir = jar.getJarEntry("data/minecraft/");
        if (dataDir != null && dataDir.isDirectory()) return true;

        // Check for assets/minecraft/ (textures, models, etc.)
        JarEntry assetsDir = jar.getJarEntry("assets/minecraft/");
        if (assetsDir != null && assetsDir.isDirectory()) return true;

        // Check for version.json (vanilla version manifest)
        JarEntry versionJson = jar.getJarEntry("version.json");
        if (versionJson != null) return true;

        return false;
    }

    /** Extract MC version from JAR filename like "1.21.1-NeoForge.jar" or "server-1.21.1.jar" */
    private String detectMcVersionFromJarName(String jarName) {
        // Match patterns: "1.21.1-NeoForge", "server-1.21.1", "1.21.1"
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+\\.\\d+(\\.\\d+)?)")
                .matcher(jarName);
        if (m.find()) return m.group(1);
        return null;
    }
}
