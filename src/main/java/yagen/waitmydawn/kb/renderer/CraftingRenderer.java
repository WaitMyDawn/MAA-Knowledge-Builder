package yagen.waitmydawn.kb.renderer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import yagen.waitmydawn.kb.config.AppConfig;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;

/**
 * 工作台 3x3 合成图渲染器。底板 + 纹理叠加。
 */
public class CraftingRenderer {

    private final TemplateManager templateManager;
    private final AppConfig config;
    private final ObjectMapper mapper = new ObjectMapper();

    public CraftingRenderer(TemplateManager templateManager, AppConfig config) {
        this.templateManager = templateManager;
        this.config = config;
    }

    public BufferedImage render(String recipeJson, List<String> texturePaths) {
        try {
            JsonNode recipe = mapper.readTree(recipeJson);
            String type = recipe.path("type").asText("minecraft:crafting_shaped");
            boolean isShapeless = type.contains("shapeless");

            Map<String, String> keyMap = parseKey(recipe);
            List<String> pattern = parsePattern(recipe, isShapeless);
            JsonNode resultNode = recipe.path("result");
            String outputItem = resultNode.path("id").asText(null);
            if (outputItem == null) outputItem = resultNode.path("item").asText(null);
            if (outputItem == null) outputItem = resultNode.asText("unknown");
            int outputCount = resultNode.path("count").asInt(1);

            BufferedImage template = templateManager.getTemplate("crafting_table_3x3");
            TemplateManager.SlotConfig sc = templateManager.getSlotConfig("crafting_table_3x3");

            int width = template.getWidth();
            int height = template.getHeight() + 50;
            BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = canvas.createGraphics();
            g.drawImage(template, 0, 0, null);

            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 3; col++) {
                    Point pos = sc.inputSlots.get("slot_" + col + "_" + row);
                    if (pos == null) continue;
                    char ch = row < pattern.size() && col < pattern.get(row).length()
                            ? pattern.get(row).charAt(col) : ' ';
                    if (ch == ' ') continue;
                    String itemName = keyMap.getOrDefault(String.valueOf(ch), "");
                    BufferedImage tex = findTexture(itemName, texturePaths);
                    if (tex != null) {
                        int half = sc.slotSize / 2;
                        g.drawImage(ImageUtils.scale(tex, sc.slotSize, sc.slotSize),
                                pos.x - half, pos.y - half, null);
                    }
                }
            }

            if (sc.outputSlot != null) {
                BufferedImage outTex = findTexture(outputItem, texturePaths);
                if (outTex != null) {
                    int half = sc.outputSize / 2;
                    g.drawImage(ImageUtils.scale(outTex, sc.outputSize, sc.outputSize),
                            sc.outputSlot.x - half, sc.outputSlot.y - half, null);
                }
            }

            String label = outputItem + (outputCount > 1 ? " x" + outputCount : "");
            ImageUtils.drawFooter(canvas, "Crafting: " + label, template.getHeight());
            g.dispose();
            return canvas;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseKey(JsonNode recipe) {
        Map<String, String> map = new HashMap<>();
        try {
            Map<String, Object> rm = mapper.convertValue(recipe, Map.class);
            Object ko = rm.get("key");
            if (ko instanceof Map) {
                Map<String, Object> km = (Map<String, Object>) ko;
                for (Map.Entry<String, Object> e : km.entrySet()) {
                    String item = "";
                    if (e.getValue() instanceof Map) {
                        Map<String, Object> vm = (Map<String, Object>) e.getValue();
                        // MC 1.21+ uses "id", 1.20- uses "item"
                        item = (String) vm.getOrDefault("id", "");
                        if (item == null || item.isBlank()) item = (String) vm.getOrDefault("item", "");
                        if (item == null || item.isBlank()) item = (String) vm.getOrDefault("tag", "");
                    }
                    if (item != null && !item.isBlank()) map.put(e.getKey(), item);
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
                int idx = 0;
                for (int i = 0; i < ings.size(); i++) sb.append((char) ('A' + idx++));
                return List.of(sb.toString(), "", "");
            }
        }
        return List.of("   ", "   ", "   ");
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
