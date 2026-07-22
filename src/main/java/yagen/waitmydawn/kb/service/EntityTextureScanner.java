package yagen.waitmydawn.kb.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 实体纹理扫描器 — 扫描 JAR 中 assets/{modid}/textures/entity/ 目录，
 * 提取实体注册名 (modid:folder_name 或 modid:file_name)，保存到 rag_entity_registry 表。
 *
 * 规则:
 *   - textures/entity/creeper/ → 构建 minecraft:creeper (文件夹名即实体名)
 *   - textures/entity/endermite.png → 构建 minecraft:endermite (文件名即实体名)
 *   - 文件夹内的子 png 不再展开 (它们是该实体的多面/多状态纹理变体)
 */
public class EntityTextureScanner {

    private static final Logger log = LoggerFactory.getLogger(EntityTextureScanner.class);

    /**
     * Scan a JAR file for entity textures and extract registry names.
     * @return Map of registryName → source path (for debugging)
     */
    public static Map<String, String> scan(Path jarPath, String modId) {
        Map<String, String> entities = new LinkedHashMap<>();

        String entityDir = "assets/" + modId + "/textures/entity/";

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Set<String> addedFolders = new HashSet<>();

            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();

                // Check both cases
                String relative = null;
                if (name.startsWith(entityDir)) {
                    relative = name.substring(entityDir.length());
                }
                if (relative == null || relative.isEmpty()) continue;

                // Skip sub-folders of entity dir (e.g., creeper/ sub-contents)
                int slashIdx = relative.indexOf('/');
                if (slashIdx > 0) {
                    // This is a file inside a sub-folder (e.g., creeper/creeper.png)
                    // Register the folder name, not individual files
                    String folderName = relative.substring(0, slashIdx);
                    if (addedFolders.add(folderName)) {
                        String registry = modId + ":" + folderName;
                        entities.put(registry, name);
                    }
                } else {
                    // This is a file directly in textures/entity/ (e.g., endermite.png)
                    // Strip extension
                    String baseName = relative;
                    int dotIdx = baseName.lastIndexOf('.');
                    if (dotIdx > 0) baseName = baseName.substring(0, dotIdx);
                    if (!baseName.isEmpty()) {
                        String registry = modId + ":" + baseName;
                        entities.put(registry, name);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Entity scan: {} — {}", modId, e.getMessage());
        }

        log.info("Entity scan: {} → {} entities", modId, entities.size());
        return entities;
    }
}
