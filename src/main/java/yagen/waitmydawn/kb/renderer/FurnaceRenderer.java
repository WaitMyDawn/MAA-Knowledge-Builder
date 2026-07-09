package yagen.waitmydawn.kb.renderer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import yagen.waitmydawn.kb.config.AppConfig;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * 熔炉/高炉/烟熏炉配方图渲染器。
 */
public class FurnaceRenderer {

    private final TemplateManager templateManager;
    private final AppConfig config;
    private final ObjectMapper mapper = new ObjectMapper();

    public FurnaceRenderer(TemplateManager templateManager, AppConfig config) {
        this.templateManager = templateManager;
        this.config = config;
    }

    public BufferedImage render(String recipeJson, List<String> texturePaths) {
        try {
            JsonNode recipe = mapper.readTree(recipeJson);
            String inputItem = resolveItemId(recipe, "ingredient");
            if (inputItem.isEmpty()) inputItem = resolveItemId(recipe, "input");
            JsonNode resultNode = recipe.path("result");
            String resultItem = resultNode.path("id").asText(null);
            if (resultItem == null) resultItem = resultNode.path("item").asText(null);
            if (resultItem == null) resultItem = resultNode.asText("");
            int cookingTime = recipe.path("cookingtime").asInt(recipe.path("cookingTime").asInt(200));
            double experience = recipe.path("experience").asDouble(0.0);

            BufferedImage template = templateManager.getTemplate("furnace");
            TemplateManager.SlotConfig sc = templateManager.getSlotConfig("furnace");

            int width = template.getWidth();
            int height = template.getHeight() + 50;
            BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = canvas.createGraphics();
            g.drawImage(template, 0, 0, null);

            Point inputPos = sc.inputSlots.get("input");
            if (inputPos != null) {
                BufferedImage tex = findTexture(inputItem, texturePaths);
                if (tex != null) {
                    int half = sc.slotSize / 2;
                    g.drawImage(ImageUtils.scale(tex, sc.slotSize, sc.slotSize),
                            inputPos.x - half, inputPos.y - half, null);
                }
            }

            if (sc.outputSlot != null) {
                BufferedImage outTex = findTexture(resultItem, texturePaths);
                if (outTex != null) {
                    int half = sc.outputSize / 2;
                    g.drawImage(ImageUtils.scale(outTex, sc.outputSize, sc.outputSize),
                            sc.outputSlot.x - half, sc.outputSlot.y - half, null);
                }
            }

            String type = recipe.path("type").asText("smelting");
            String label = (type.contains("blast") ? "Blast: " : type.contains("smok") ? "Smoker: " : "Furnace: ")
                    + inputItem + " -> " + resultItem + " | " + (cookingTime / 20) + "s"
                    + (experience > 0 ? " | XP: " + experience : "");
            ImageUtils.drawFooter(canvas, label, template.getHeight());
            g.dispose();
            return canvas;
        } catch (Exception e) {
            return null;
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
