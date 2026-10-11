package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yagen.waitmydawn.kb.config.AppConfig;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import yagen.waitmydawn.kb.renderer.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

/**
 * 配方图片渲染调度器 — 按需生成合成图/熔炉图/锻造图。
 *
 * 工作流:
 *   1. 从 rag_texture_cache 构建注册名→纹理路径映射
 *   2. 通过 TemplateManager 获取裁剪后的基底模板
 *   3. 解析配方 JSON, 按类型分发给对应渲染器
 *   4. 输出 2x 放大图片到 generated/{crafting|furnace|smithing}/
 *
 * 缓存策略: 如果目标文件已存在则跳过渲染。
 */
public class RendererService {

    private static final Logger log = LoggerFactory.getLogger(RendererService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final AppConfig config;
    private final DatabaseBuilder db;
    private final TemplateManager templateManager;
    private final CraftingRenderer craftingRenderer;
    private final FurnaceRenderer furnaceRenderer;
    private final SmithingRenderer smithingRenderer;

    // registryName → local file path (lazily loaded from rag_texture_cache)
    private Map<String, String> registryPathMap;
    private String registryGeneration;

    public RendererService(AppConfig config, DatabaseBuilder db) {
        this.config = config;
        this.db = db;
        this.templateManager = new TemplateManager(config, db);
        this.craftingRenderer = new CraftingRenderer();
        this.furnaceRenderer = new FurnaceRenderer();
        this.smithingRenderer = new SmithingRenderer();
    }

    // ==================== Main API ====================

    /**
     * Render recipe images for a list of recipe matches.
     * Skips rendering if the output file already exists.
     *
     * @param recipeMatches list of (recipeJson, outputItem, sourceMod)
     * @return map of outputItem#contentFingerprint → absolute image file path
     */
    public Map<String, String> renderRecipes(List<QaPipeline.RecipeMatch> recipeMatches) {
        Map<String, String> rendered = new LinkedHashMap<>();
        if (recipeMatches.isEmpty()) return rendered;

        // Ensure texture map is fresh
        ensureRegistryMap();

        // Pre-load template images (null if vanilla textures unavailable)
        BufferedImage craftingTpl = templateManager.getOrCreateTemplate("crafting_table");
        BufferedImage furnaceTpl = templateManager.getOrCreateTemplate("furnace");
        BufferedImage smithingTpl = templateManager.getOrCreateTemplate("smithing");

        // Load all available textures into memory for this batch
        Map<String, BufferedImage> textureCache = new HashMap<>();

        for (QaPipeline.RecipeMatch match : recipeMatches) {
            String recipeJson = match.recipeJson();
            String outputItem = match.outputItem();
            String sourceMod = match.sourceMod();

            if (recipeJson == null || outputItem == null) continue;

            String type = detectRecipeType(recipeJson);
            if (type == null) {
                // 只渲染 Minecraft 原版标准配方类型；自定义类型（如 iceandfire:dragonforge、
                // farmersdelight:cooking）没有对应模板，直接跳过并告警，
                // 避免用错误模板硬渲染出无意义图片（2026-09-07 修复）。
                String head = recipeJson.replace('\n', ' ');
                if (head.length() > 120) head = head.substring(0, 120) + "...";
                log.warn("Unsupported recipe type, skipping {} (recipe: {})", outputItem, head);
                continue;
            }
            BufferedImage template = switch (type) {
                case "crafting" -> craftingTpl;
                case "furnace" -> furnaceTpl;
                case "smithing" -> smithingTpl;
                default -> null;
            };

            // Skip if template unavailable (vanilla GUI texture not extracted)
            if (template == null) {
                log.warn("No template available for type={}, skipping {}", type, outputItem);
                continue;
            }

            // Check if output file already exists
            String safeName = outputItem.replaceAll("[^a-zA-Z0-9_-]", "_") + "_" + cacheFingerprint(recipeJson);
            Path outDir = config.getGeneratedDir().resolve(type);
            Path outFile = outDir.resolve(safeName + ".png");

            if (Files.exists(outFile) && outFile.toFile().length() > 0) {
                rendered.put(outputItem + "#" + cacheFingerprint(recipeJson), outFile.toAbsolutePath().toString());
                continue;
            }

            // Render
            try {
                Files.createDirectories(outDir);

                // Build texture map for this recipe's ingredients
                Map<String, BufferedImage> texMap = buildTextureMap(recipeJson, textureCache, registryPathMap);

                BufferedImage image = switch (type) {
                    case "crafting" -> craftingRenderer.render(recipeJson, template, texMap);
                    case "furnace" -> furnaceRenderer.render(recipeJson, template, texMap);
                    case "smithing" -> smithingRenderer.render(recipeJson, template, texMap);
                    default -> null;
                };

                if (image != null) {
                    ImageIO.write(image, "PNG", outFile.toFile());
                    rendered.put(outputItem + "#" + cacheFingerprint(recipeJson), outFile.toAbsolutePath().toString());
                    log.info("Rendered {}: {} ({}x{})", type, safeName, image.getWidth(), image.getHeight());
                }
            } catch (Exception e) {
                log.warn("Render failed for {}: {}", outputItem, e.getMessage());
            }
        }

        return rendered;
    }

    private String cacheFingerprint(String recipeJson) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest((db.buildGeneration() + "\n" + recipeJson).getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 20);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    // ==================== Recipe Type Detection ====================

    private String detectRecipeType(String recipeJson) {
        try {
            JsonNode recipe = mapper.readTree(recipeJson);
            String type = recipe.path("type").asText("").toLowerCase();
            // 去掉命名空间：minecraft:crafting_shaped 与 crafting_shaped 等价
            int colon = type.indexOf(':');
            String simple = colon >= 0 ? type.substring(colon + 1) : type;
            switch (simple) {
                case "crafting_shaped":
                case "crafting_shapeless":
                    return "crafting";
                case "smelting":
                case "blasting":
                case "smoking":
                case "campfire_cooking":
                    return "furnace";
                case "smithing_transform":
                case "smithing_trim":
                    return "smithing";
                default:
                    return null;
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ==================== Texture Lookup ====================

    /** Build registryName → localPath map from rag_texture_cache */
    private void ensureRegistryMap() {
        String generation = db.buildGeneration();
        if (registryPathMap != null && Objects.equals(registryGeneration, generation)) return;
        registryPathMap = new LinkedHashMap<>();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT registry_name, local_path FROM rag_texture_cache");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String reg = rs.getString("registry_name");
                String path = rs.getString("local_path");
                if (reg != null && path != null) {
                    registryPathMap.putIfAbsent(reg, path);
                }
            }
            log.debug("Registry map built: {} entries", registryPathMap.size());
        } catch (Exception e) {
            log.warn("Failed to build registry map: {}", e.getMessage());
        }
        registryGeneration = generation;
    }

    /**
     * Build a BufferedImage map for all ingredients referenced in a recipe.
     * Lazily loads images from disk and caches them in textureCache.
     */
    private Map<String, BufferedImage> buildTextureMap(String recipeJson,
                                                        Map<String, BufferedImage> textureCache,
                                                        Map<String, String> pathMap) {
        Map<String, BufferedImage> result = new LinkedHashMap<>();
        Set<String> needed = extractIngredientItems(recipeJson);
        String outputItem = extractOutputItem(recipeJson);
        if (outputItem != null) needed.add(outputItem);

        for (String item : needed) {
            if (textureCache.containsKey(item)) {
                result.put(item, textureCache.get(item));
                continue;
            }
            BufferedImage img = loadItemTexture(item, pathMap);
            if (img != null) {
                textureCache.put(item, img);
                result.put(item, img);
            }
        }
        return result;
    }

    /** Load a single item texture by registry name */
    private BufferedImage loadItemTexture(String registryName, Map<String, String> pathMap) {
        // Exact match
        String path = pathMap.get(registryName);
        if (path != null) {
            BufferedImage img = ImageUtils.loadImage(path);
            if (img != null) return img;
        }
        // Try short name match
        String shortName = registryName.contains(":") ?
                registryName.substring(registryName.indexOf(':') + 1) : registryName;
        for (var e : pathMap.entrySet()) {
            if (e.getKey().endsWith(":" + shortName)) {
                BufferedImage img = ImageUtils.loadImage(e.getValue());
                if (img != null) return img;
            }
        }
        return null;
    }

    /** Extract all ingredient item names from a recipe JSON */
    private Set<String> extractIngredientItems(String recipeJson) {
        Set<String> items = new LinkedHashSet<>();
        try {
            JsonNode recipe = mapper.readTree(recipeJson);
            // From "key" map
            JsonNode key = recipe.path("key");
            if (key.isObject()) {
                var it = key.fields();
                while (it.hasNext()) {
                    var entry = it.next();
                    JsonNode val = entry.getValue();
                    String id = val.path("id").asText(val.path("item").asText(null));
                    if (id != null) items.add(id);
                }
            }
            // From "ingredients" array
            JsonNode ings = recipe.path("ingredients");
            if (ings.isArray()) {
                for (JsonNode ing : ings) {
                    String id = ing.path("id").asText(ing.path("item").asText(null));
                    if (id != null) items.add(id);
                }
            }
            // Single ingredient
            JsonNode ing = recipe.path("ingredient");
            if (ing.isObject()) {
                String id = ing.path("id").asText(ing.path("item").asText(null));
                if (id != null) items.add(id);
            }
            // Smithing
            for (String k : new String[]{"template", "base", "addition"}) {
                JsonNode n = recipe.path(k);
                if (n.isObject()) {
                    String id = n.path("id").asText(n.path("item").asText(null));
                    if (id != null) items.add(id);
                }
            }
        } catch (Exception ignored) {}
        return items;
    }

    private String extractOutputItem(String recipeJson) {
        try {
            JsonNode recipe = mapper.readTree(recipeJson);
            JsonNode result = recipe.path("result");
            String id = result.path("id").asText(null);
            if (id != null) return id;
            id = result.path("item").asText(null);
            if (id != null) return id;
            return result.asText(null);
        } catch (Exception e) { return null; }
    }

    // ==================== Helpers ====================

    /** Generate the markdown image reference for a rendered recipe image */
    public String imageMarkdown(String outputItem, String imagePath) {
        outputItem = outputItem.split("#", 2)[0];
        String name = outputItem.contains(":") ? outputItem.substring(outputItem.indexOf(':') + 1) : outputItem;
        return "![recipe:" + name + "](" + imagePath.replace('\\', '/') + ")";
    }

    /** 清除与当前知识库代次关联的纹理及模板缓存。 */
    public void clearCache() {
        registryPathMap = null;
        templateManager.clearCache();
    }


}
