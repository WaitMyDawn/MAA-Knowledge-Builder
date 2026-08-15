package yagen.waitmydawn.kb.renderer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Map;

/**
 * 锻造台配方图渲染器。
 *
 * 已裁剪底板 (108x58), 有效位点:
 *   template (1,41)   - 锻造模版
 *   base     (19,41)  - 被锻造物品
 *   addition (37,41)  - 锻造材料
 *   result   (91,41)  - 产物
 */
public class SmithingRenderer {

    private final ObjectMapper mapper = new ObjectMapper();
    private static final int SLOT_SIZE = 16;
    private static final int TMPL_X = 1, TMPL_Y = 41;
    private static final int BASE_X = 19, BASE_Y = 41;
    private static final int ADD_X = 37, ADD_Y = 41;
    private static final int OUT_X = 91, OUT_Y = 41;

    public SmithingRenderer() {}

    public BufferedImage render(String recipeJson, BufferedImage template,
                                 Map<String, BufferedImage> textureMap) {
        try {
            JsonNode recipe = mapper.readTree(recipeJson);
            String templateItem = resolveItemId(recipe, "template");
            String baseItem = resolveItemId(recipe, "base");
            String additionItem = resolveItemId(recipe, "addition");
            String resultItem = resolveResultItem(recipe);

            int w = template.getWidth();
            int h = template.getHeight();
            BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = canvas.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(template, 0, 0, null);

            drawSlot(g, templateItem, TMPL_X, TMPL_Y, textureMap);
            drawSlot(g, baseItem, BASE_X, BASE_Y, textureMap);
            drawSlot(g, additionItem, ADD_X, ADD_Y, textureMap);
            drawSlot(g, resultItem, OUT_X, OUT_Y, textureMap);

            g.dispose();
            return ImageUtils.scale(canvas, w * 2, h * 2);
        } catch (Exception e) {
            return null;
        }
    }

    private void drawSlot(Graphics2D g, String item, int x, int y,
                          Map<String, BufferedImage> textureMap) {
        if (item == null || item.isBlank()) return;
        BufferedImage tex = lookupTexture(item, textureMap);
        if (tex == null) tex = ImageUtils.createPlaceholder(item, Color.GRAY);
        g.drawImage(ImageUtils.scale(tex, SLOT_SIZE, SLOT_SIZE), x, y, null);
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
