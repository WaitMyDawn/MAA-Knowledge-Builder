package yagen.waitmydawn.kb.renderer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yagen.waitmydawn.kb.config.AppConfig;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 模板管理器 — 从原版 Minecraft GUI 纹理裁剪基底底板并缓存。
 *
 * 裁剪规则 (基于原版 Minecraft 1.21.1 的 gui/container 纹理):
 *   crafting_table.png → crop (29,16)-(145,70) → 116×54
 *   furnace.png        → crop (55,16)-(137,70) → 82×54
 *   smithing.png       → crop (7,7)-(115,65)    → 108×58
 *
 * 首次使用时裁剪并保存到 {dataDir}/templates/, 之后直接复用。
 */
public class TemplateManager {

    private static final Logger log = LoggerFactory.getLogger(TemplateManager.class);

    private final AppConfig config;
    private final Path templatesDir;
    private final Path texturesDir;
    private final Map<String, BufferedImage> templateCache = new HashMap<>();

    // Crop regions in the vanilla GUI texture
    private static final Map<String, Rectangle> CROP_REGIONS = Map.of(
            "crafting_table", new Rectangle(29, 16, 116, 54),
            "furnace",        new Rectangle(55, 16, 82, 54),
            "smithing",       new Rectangle(7, 7, 108, 58)
    );

    public TemplateManager(AppConfig config) {
        this.config = config;
        this.templatesDir = config.getTemplatesDir();
        this.texturesDir = config.getTexturesDir();
        try { Files.createDirectories(templatesDir); } catch (Exception ignored) {}
    }

    /**
     * Get (or create) a cropped base template for a recipe type.
     *
     * @param type one of "crafting_table", "furnace", "smithing"
     * @return cropped template image, or null if the vanilla texture is unavailable
     */
    public BufferedImage getOrCreateTemplate(String type) {
        // Check cache
        BufferedImage cached = templateCache.get(type);
        if (cached != null) return cached;

        // Check if already cropped and saved
        Path savedPath = templatesDir.resolve(type + "_template.png");
        if (Files.exists(savedPath)) {
            try {
                BufferedImage img = ImageIO.read(savedPath.toFile());
                templateCache.put(type, img);
                log.debug("Template loaded from cache: {}", savedPath);
                return img;
            } catch (Exception e) {
                log.warn("Failed to load cached template: {}", savedPath);
            }
        }

        // Try to crop from vanilla GUI texture
        // Path: {texturesDir}/minecraft/gui/container/{type}.png
        Path vanillaPath = texturesDir.resolve("minecraft/gui/container/" + type + ".png");
        if (!Files.exists(vanillaPath)) {
            // Try alternate: the slug might be different
            // Look for any gui/container/{type}.png under textures/
            log.warn("Vanilla GUI texture not found: {}", vanillaPath);
            return null;
        }

        BufferedImage vanilla;
        try {
            vanilla = ImageIO.read(vanillaPath.toFile());
        } catch (Exception e) {
            log.warn("Failed to read vanilla GUI texture: {}", vanillaPath);
            return null;
        }

        Rectangle crop = CROP_REGIONS.get(type);
        if (crop == null) {
            log.warn("No crop region defined for template type: {}", type);
            return null;
        }

        // Ensure crop is within bounds
        int x = Math.max(0, crop.x);
        int y = Math.max(0, crop.y);
        int w = Math.min(crop.width, vanilla.getWidth() - x);
        int h = Math.min(crop.height, vanilla.getHeight() - y);

        BufferedImage cropped = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = cropped.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(vanilla, 0, 0, w, h, x, y, x + w, y + h, null);
        g.dispose();

        // Save for reuse
        try {
            ImageIO.write(cropped, "PNG", savedPath.toFile());
            log.info("Template cropped and saved: {} ({}x{})", savedPath, w, h);
        } catch (Exception e) {
            log.warn("Failed to save cropped template: {}", savedPath);
        }

        templateCache.put(type, cropped);
        return cropped;
    }

    /** Clear in-memory cache (disk cache remains) */
    public void clearCache() {
        templateCache.clear();
    }
}
