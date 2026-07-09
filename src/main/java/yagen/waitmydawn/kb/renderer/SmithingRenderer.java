package yagen.waitmydawn.kb.renderer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import yagen.waitmydawn.kb.config.AppConfig;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * 锻造台配方图渲染器。
 */
public class SmithingRenderer {

    private final TemplateManager templateManager;
    private final AppConfig config;
    private final ObjectMapper mapper = new ObjectMapper();

    public SmithingRenderer(TemplateManager templateManager, AppConfig config) {
        this.templateManager = templateManager;
        this.config = config;
    }

    public BufferedImage render(String recipeJson, List<String> texturePaths) {
        try {
            JsonNode recipe = mapper.readTree(recipeJson);
            String templateItem = resolveItemId(recipe, "template");
            String baseItem = resolveItemId(recipe, "base");
            String additionItem = resolveItemId(recipe, "addition");
            JsonNode resultNode = recipe.path("result");
            String resultItem = resultNode.path("id").asText(null);
            if (resultItem == null) resultItem = resultNode.path("item").asText(null);
            if (resultItem == null) resultItem = resultNode.asText("");

            BufferedImage template = templateManager.getTemplate("smithing_table");
            TemplateManager.SlotConfig sc = templateManager.getSlotConfig("smithing_table");

            int width = template.getWidth();
            int height = template.getHeight() + 50;
            BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = canvas.createGraphics();
            g.drawImage(template, 0, 0, null);

            drawSlot(g, sc, "template", templateItem, texturePaths);
            drawSlot(g, sc, "base", baseItem, texturePaths);
            drawSlot(g, sc, "addition", additionItem, texturePaths);

            if (sc.outputSlot != null && !resultItem.isBlank()) {
                BufferedImage outTex = findTexture(resultItem, texturePaths);
                if (outTex != null) {
                    int half = sc.outputSize / 2;
                    g.drawImage(ImageUtils.scale(outTex, sc.outputSize, sc.outputSize),
                            sc.outputSlot.x - half, sc.outputSlot.y - half, null);
                }
            }

            ImageUtils.drawFooter(canvas, "Smithing: " + baseItem + " + " + additionItem + " -> " + resultItem,
                    template.getHeight());
            g.dispose();
            return canvas;
        } catch (Exception e) {
            return null;
        }
    }

    private void drawSlot(Graphics2D g, TemplateManager.SlotConfig sc, String key,
                           String itemName, List<String> texturePaths) {
        Point pos = sc.inputSlots.get(key);
        if (pos != null && !itemName.isBlank()) {
            BufferedImage tex = findTexture(itemName, texturePaths);
            if (tex != null) {
                int half = sc.slotSize / 2;
                g.drawImage(ImageUtils.scale(tex, sc.slotSize, sc.slotSize),
                        pos.x - half, pos.y - half, null);
            }
        }
    }

    private String resolveItemId(JsonNode recipe, String key) {
        JsonNode node = recipe.path(key);
        if (node.isObject()) {
            String id = node.path("id").asText(null);
            if (id != null) return id;
            return node.path("item").asText("");
        }
        return node.asText("");
    }

    private BufferedImage findTexture(String itemName, List<String> texturePaths) {
        if (itemName == null || itemName.isBlank()) return null;
        String shortName = itemName.contains(":") ? itemName.substring(itemName.indexOf(':') + 1) : itemName;
        for (String path : texturePaths) {
            if (path.replace('\\', '/').toLowerCase().contains(shortName.toLowerCase())) {
                BufferedImage img = ImageUtils.loadImage(path);
                if (img != null) return img;
            }
        }
        return ImageUtils.createPlaceholder(shortName, ImageUtils.getColorForItemType("default"));
    }
}
