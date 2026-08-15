package yagen.waitmydawn.kb.renderer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Map;

/**
 * 熔炉/高炉/烟熏炉配方图渲染器。
 *
 * 已裁剪底板 (82x54), 有效位点:
 *   input  (1,1)  - 被熔炼物品
 *   fuel   (1,37)  - 燃料
 *   output (61,19) - 产物
 */
public class FurnaceRenderer {

    private final ObjectMapper mapper = new ObjectMapper();
    private static final int SLOT_SIZE = 16;
    private static final int IN_X = 1, IN_Y = 1;
    private static final int FUEL_X = 1, FUEL_Y = 37;
    private static final int OUT_X = 61, OUT_Y = 19;

    public FurnaceRenderer() {}

    public BufferedImage render(String recipeJson, BufferedImage template,
                                 Map<String, BufferedImage> textureMap) {
        try {
            JsonNode recipe = mapper.readTree(recipeJson);
            String inputItem = resolveItemId(recipe, "ingredient");
            if (inputItem.isEmpty()) inputItem = resolveItemId(recipe, "input");
            String outputItem = resolveResultItem(recipe);

            int w = template.getWidth();
            int h = template.getHeight();
            BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = canvas.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(template, 0, 0, null);

            // Draw input
            if (!inputItem.isEmpty()) {
                BufferedImage tex = lookupTexture(inputItem, textureMap);
                if (tex == null) tex = ImageUtils.createPlaceholder(inputItem, Color.GRAY);
                g.drawImage(ImageUtils.scale(tex, SLOT_SIZE, SLOT_SIZE), IN_X, IN_Y, null);
            }

            // Draw fuel (generic flame/coal indicator)
            // We don't force a fuel texture — fuel is contextual

            // Draw output
            if (!outputItem.isEmpty()) {
                BufferedImage tex = lookupTexture(outputItem, textureMap);
                if (tex == null) tex = ImageUtils.createPlaceholder(outputItem, Color.ORANGE);
                g.drawImage(ImageUtils.scale(tex, SLOT_SIZE, SLOT_SIZE), OUT_X, OUT_Y, null);
            }

            g.dispose();
            return ImageUtils.scale(canvas, w * 2, h * 2);
        } catch (Exception e) {
            return null;
        }
    }

    private BufferedImage lookupTexture(String registryName, Map<String, BufferedImage> textureMap) {
        if (registryName == null || registryName.isBlank()) return null;
        if (textureMap.containsKey(registryName)) return textureMap.get(registryName);
        String shortName = registryName.contains(":")
                ? registryName.substring(registryName.indexOf(':') + 1) : registryName;
        for (var e : textureMap.entrySet()) {
            if (e.getKey().endsWith(":" + shortName)) return e.getValue();
        }
        return null;
    }

    private String resolveItemId(JsonNode recipe, String key) {
        JsonNode node = recipe.path(key);
        if (node.isObject()) {
            String id = node.path("id").asText(null);
            if (id != null) return id;
            return node.path("item").asText("");
        }
        if (node.isArray() && !node.isEmpty()) {
            JsonNode first = node.get(0);
            String id = first.path("id").asText(null);
            if (id != null) return id;
            return first.path("item").asText("");
        }
        return node.asText("");
    }

    private String resolveResultItem(JsonNode recipe) {
        JsonNode result = recipe.path("result");
        if (result.isObject()) {
            String id = result.path("id").asText(null);
            if (id != null) return id;
            return result.path("item").asText("");
        }
        return result.asText("");
    }
}
