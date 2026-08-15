package yagen.waitmydawn.kb.renderer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;

/**
 * 工作台 3x3 合成图渲染器。
 *
 * 使用已裁剪的底板模板 (116x54)，有效位点 (左上角原点):
 *   输入槽位: 3x3 网格, slot 0 起点 (1,1), 每格 18x18 间隔
 *   输出槽位: 起点 (95,19), 24x24 -> 16x16 范围
 */
public class CraftingRenderer {

    private final ObjectMapper mapper = new ObjectMapper();

    // Slot positions relative to cropped template (1,1 is slot 0 top-left)
    private static final int SLOT_W = 18;
    private static final int SLOT_H = 18;
    private static final int SLOT_SIZE = 16;
    private static final int OUT_X = 95, OUT_Y = 19;

    public CraftingRenderer() {}

    /**
     * Render a crafting recipe onto the cropped template.
     *
     * @param recipeJson   raw recipe JSON
     * @param template     cropped crafting_table template (116x54 expected)
     * @param textureMap   registryName -> BufferedImage for ingredients
     * @return rendered image (2x scaled), or null on failure
     */
    public BufferedImage render(String recipeJson, BufferedImage template,
                                 Map<String, BufferedImage> textureMap) {
        try {
            JsonNode recipe = mapper.readTree(recipeJson);
            boolean isShapeless = recipe.path("type").asText("").contains("shapeless");

            Map<Character, String> keyMap = parseKey(recipe);
            List<String> pattern = parsePattern(recipe, isShapeless);
            String outputItem = extractOutputItem(recipe);

            int w = template.getWidth();
            int h = template.getHeight();
            BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = canvas.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(template, 0, 0, null);

            // Draw input slots
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 3; col++) {
                    char ch = row < pattern.size() && col < pattern.get(row).length()
                            ? pattern.get(row).charAt(col) : ' ';
                    if (ch == ' ') continue;
                    String item = keyMap.getOrDefault(ch, "");
                    if (item.isEmpty()) continue;
                    BufferedImage tex = lookupTexture(item, textureMap);
                    if (tex == null) tex = ImageUtils.createPlaceholder(item, Color.GRAY);
                    int sx = 1 + col * SLOT_W;
                    int sy = 1 + row * SLOT_H;
                    g.drawImage(ImageUtils.scale(tex, SLOT_SIZE, SLOT_SIZE), sx, sy, null);
                }
            }

            // Draw output
            if (outputItem != null && !outputItem.isEmpty()) {
                BufferedImage outTex = lookupTexture(outputItem, textureMap);
                if (outTex == null) outTex = ImageUtils.createPlaceholder(outputItem, Color.ORANGE);
                g.drawImage(ImageUtils.scale(outTex, SLOT_SIZE, SLOT_SIZE), OUT_X, OUT_Y, null);
            }

            g.dispose();

            // Scale 2x
            return ImageUtils.scale(canvas, w * 2, h * 2);
        } catch (Exception e) {
            return null;
        }
    }

    private BufferedImage lookupTexture(String registryName, Map<String, BufferedImage> textureMap) {
        if (registryName == null || registryName.isBlank()) return null;
        // Exact match first
        if (textureMap.containsKey(registryName)) return textureMap.get(registryName);
        // Try short name
        String shortName = registryName.contains(":")
                ? registryName.substring(registryName.indexOf(':') + 1) : registryName;
        for (var e : textureMap.entrySet()) {
            if (e.getKey().endsWith(":" + shortName)) return e.getValue();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<Character, String> parseKey(JsonNode recipe) {
        Map<Character, String> map = new LinkedHashMap<>();
        try {
            Map<String, Object> rm = mapper.convertValue(recipe, Map.class);
            Object ko = rm.get("key");
            if (ko instanceof Map) {
                Map<String, Object> km = (Map<String, Object>) ko;
                for (Map.Entry<String, Object> e : km.entrySet()) {
                    if (e.getKey().isEmpty()) continue;
                    String item = "";
                    if (e.getValue() instanceof Map vm) {
                        item = (String) vm.getOrDefault("id", "");
                        if (item == null || item.isBlank()) item = (String) vm.getOrDefault("item", "");
                        if (item == null || item.isBlank()) item = (String) vm.getOrDefault("tag", "");
                    }
                    if (item != null && !item.isBlank()) map.put(e.getKey().charAt(0), item);
                }
            }
        } catch (Exception ignored) {}
        return map;
    }

    private List<String> parsePattern(JsonNode recipe, boolean isShapeless) {
        JsonNode pn = recipe.path("pattern");
        if (pn.isArray()) {
            List<String> rows = new ArrayList<>();
            for (JsonNode row : pn) rows.add(row.asText());
            return rows;
        }
        if (isShapeless) {
            JsonNode ings = recipe.path("ingredients");
            if (ings.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.min(ings.size(), 9); i++) sb.append((char) ('A' + i));
                return List.of(sb.toString(), "", "");
            }
        }
        return List.of("   ", "   ", "   ");
    }

    private String extractOutputItem(JsonNode recipe) {
        JsonNode result = recipe.path("result");
        String id = result.path("id").asText(null);
        if (id != null) return id;
        id = result.path("item").asText(null);
        if (id != null) return id;
        return result.asText(null);
    }
}
