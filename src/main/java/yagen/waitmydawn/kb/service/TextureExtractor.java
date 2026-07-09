package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yagen.waitmydawn.kb.config.AppConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * JAR 纹理提取器。
 * <p>
 * 提取 categories: block, gui/container, item, mob_effect, particle
 * 保持源目录结构，为每类构建 注册名→本地路径 映射。
 * <p>
 * 注册名构建:
 * - 静态纹理: 文件名去 .png → 注册名 (angry.png → angry)
 * - 动态纹理: 去掉 _x 数字后缀 (big_smoke_0.png → big_smoke, clock_00.png → clock)
 * - block 纹理: 额外去除方向/状态后缀 (bee_nest_front_honey.png → bee_nest)
 */
public class TextureExtractor {

    private static final Logger log = LoggerFactory.getLogger(TextureExtractor.class);

    /**
     * 提取的纹理子目录
     */
    private static final String[] CATEGORIES = {"block", "item", "mob_effect", "particle", "gui/container"};

    /**
     * block 方向后缀
     */
    private static final Set<String> ORIENTATION = Set.of(
            "top", "bottom", "side", "front", "back", "left", "right",
            "inside", "outside", "up", "down", "end", "base", "overlay",
            "inner", "outer", "center", "edge"
    );

    /**
     * block 状态后缀
     */
    private static final Set<String> STATE = Set.of(
            "on", "off", "lit", "honey", "powered", "open",
            "locked", "empty", "filled", "triggered", "cracked",
            "mossy", "chiseled", "polished", "carved", "stripped",
            "wet", "dry", "occupied", "slightly", "very", "liquid",
            "ejecting", "vertical", "ominous"
//            , "on_ominous", "off_ominous", "ejecting_ominous"
    );


    private final AppConfig config;

    public TextureExtractor(AppConfig config) {
        this.config = config;
    }

    /**
     * 提取单个 JAR 的纹理，返回 类别 → (注册名 → 本地路径)。
     */
    public Map<String, Map<String, String>> extract(Path jarPath, String modId, String modSlug) {
        if (modSlug == null) modSlug = modId;
        Path modDir = config.getTexturesDir().resolve(modSlug);
        Map<String, Map<String, String>> all = new LinkedHashMap<>();

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Files.createDirectories(modDir);

            // Determine the asset root inside the JAR
            String assetRoot = locateAssetRoot(jar, modId);
            if (assetRoot == null) return all;

            log.debug("TextureExtractor: assetRoot={} for {}", assetRoot, modId);

            for (String cat : CATEGORIES) {
                String prefix = assetRoot + "textures/" + cat + "/";
                // temp: baseRegName → (maxFrame, filePath)
                Map<String, FrameEntry> pending = new LinkedHashMap<>();

                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry je = entries.nextElement();
                    String name = je.getName();
                    if (!name.startsWith(prefix) || !name.endsWith(".png") || je.isDirectory()) continue;

                    String relPath = name.substring((assetRoot + "textures/").length());
                    Path outFile = modDir.resolve(relPath);
                    Files.createDirectories(outFile.getParent());
                    try (InputStream is = jar.getInputStream(je)) {
                        Files.copy(is, outFile, StandardCopyOption.REPLACE_EXISTING);
                    }

                    String fileName = outFile.getFileName().toString();
                    String baseName = stripPngAndFrame(fileName);
                    int frameNum = extractFrameNumber(fileName);
                    String regName = buildRegistryNameFromBase(baseName, cat, modId);
                    if (regName == null) continue;

                    FrameEntry prev = pending.get(regName);
                    if (prev == null || frameNum >= prev.maxFrame) {
                        pending.put(regName, new FrameEntry(frameNum, outFile.toAbsolutePath().toString()));
                    }
                    // If frameNum < prev.maxFrame, don't bother copying — already have better
                    // But we already wrote the file above. Minor waste, acceptable.
                }

                // Convert to final map
                Map<String, String> mapping = new LinkedHashMap<>();
                for (var e : pending.entrySet()) {
                    mapping.put(e.getKey(), e.getValue().filePath);
                }

                if (!mapping.isEmpty()) {
                    all.put(cat, mapping);
                    log.debug("  {} → {} textures ({} deduped)", cat, mapping.size(),
                            pending.size());
                }
            }
        } catch (IOException e) {
            log.warn("Texture extraction failed: {}", e.getMessage());
        }
        return all;
    }

    // ==================== helpers ====================

    /**
     * Immutable tuple for tracking max-frame animations
     */
    private record FrameEntry(int maxFrame, String filePath) {
    }

    /**
     * Strip .png and animation frame suffix. Returns base name without modId prefix.
     */
    private static String stripPngAndFrame(String fileName) {
        String name = fileName;
        if (name.endsWith(".png")) name = name.substring(0, name.length() - 4);
        // Strip trailing _digits (animation frame): clock_59 → clock, compass_31 → compass
        return name.replaceFirst("_\\d+$", "");
    }

    /**
     * Extract the frame number from filename suffix, or 0 if none
     */
    private static int extractFrameNumber(String fileName) {
        String name = fileName;
        if (name.endsWith(".png")) name = name.substring(0, name.length() - 4);
        int idx = name.lastIndexOf('_');
        if (idx < 0) return 0;
        String tail = name.substring(idx + 1);
        if (tail.isEmpty()) return 0;
        for (int i = 0; i < tail.length(); i++) {
            if (!Character.isDigit(tail.charAt(i))) return 0;
        }
        try {
            return Integer.parseInt(tail);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Build registry name from a base name that already had .png and frames stripped
     */
    private String buildRegistryNameFromBase(String baseName, String category, String modId) {
        if (baseName == null || baseName.isBlank()) return null;
        if ("block".equals(category)) {
            baseName = stripOrientationAndState(baseName);
        }
        if (baseName.isEmpty()) return null;
        return modId + ":" + baseName;
    }

    /**
     * 将纹理映射合并写入累积的 JSON 文件。
     * 多次调用会逐步追加所有 mod 的数据到同一个 texture_map.json。
     */
    @SuppressWarnings("unchecked")
    public static void dumpMapping(Map<String, Map<String, String>> allTex, Path dataDir) {
        try {
            ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            Path out = dataDir.resolve("texture_map.json");

            Map<String, Map<String, String>> existing = new LinkedHashMap<>();
            if (Files.exists(out)) {
                existing = mapper.readValue(out.toFile(), Map.class);
            }

            for (var catEntry : allTex.entrySet()) {
                existing.merge(catEntry.getKey(), catEntry.getValue(), (old, add) -> {
                    old.putAll(add);
                    return old;
                });
            }

            mapper.writeValue(out.toFile(), existing);
            log.info("Texture mapping updated: {} ({} categories total)", out, countTotal(existing));
        } catch (IOException e) {
            log.warn("Failed to dump texture mapping: {}", e.getMessage());
        }
    }

    private static int countTotal(Map<String, Map<String, String>> all) {
        return all.values().stream().mapToInt(Map::size).sum();
    }

    /**
     * Find the asset root — tries exact, lowercase, and prefix-scan fallback
     */
    private String locateAssetRoot(JarFile jar, String modId) {
        // Direct directory check
        String exact = "assets/" + modId + "/";
        if (jar.getJarEntry(exact) != null) return exact;
        String lower = "assets/" + modId.toLowerCase() + "/";
        if (jar.getJarEntry(lower) != null) return lower;

        // Some JARs lack directory entries — scan for any file under the asset prefix
        String prefix1 = "assets/" + modId + "/";
        String prefix2 = "assets/" + modId.toLowerCase() + "/";
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.startsWith(prefix1)) return prefix1;
            if (name.startsWith(prefix2)) return prefix2;
        }
        return null;
    }

    /**
     * Strip at most ONE orientation suffix and ONE state suffix from the end.
     * Only strips if removing them leaves a non-empty name.
     * e.g. bee_nest_front_honey → front (orientation) + honey (state) → bee_nest
     * acacia_door_top → top (orientation) → acacia_door
     * blue_candle_lit → lit (state) → blue_candle
     * furnace_front_on → on (state) + front (orientation) → furnace
     */
    /**
     * Strip suffixes from the end of a block texture name.
     * Rules: at most ONE orientation, unlimited consecutive STATE suffixes.
     * e.g. vault_front_on_ominous -> front (orient) + on (state) + ominous (state) -> vault
     */
    private String stripOrientationAndState(String name) {
        String[] parts = name.split("_");
        if (parts.length <= 1) return name;

        boolean removedOrient = false;
        int endIdx = parts.length;

        // Peel from the end: states, then optionally one orientation
        while (endIdx > 1) {
            String tail = parts[endIdx - 1];
            if (!removedOrient && ORIENTATION.contains(tail)) {
                endIdx--;
                removedOrient = true;
            } else if (STATE.contains(tail)) {
                endIdx--;
            } else {
                break; // neither orientation nor state -> stop
            }
        }

        if (endIdx == parts.length) return name;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < endIdx; i++) {
            if (i > 0) sb.append('_');
            sb.append(parts[i]);
        }
        String result = sb.toString();
        return result.isEmpty() ? name : result;
    }
}
