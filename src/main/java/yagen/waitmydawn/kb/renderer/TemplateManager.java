package yagen.waitmydawn.kb.renderer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yagen.waitmydawn.kb.config.AppConfig;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * 管理 GUI 底板模板和槽位坐标。优先用户提供的底板，否则 Java AWT 自动生成。
 */
public class TemplateManager {

    private static final Logger log = LoggerFactory.getLogger(TemplateManager.class);
    private final AppConfig config;
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, BufferedImage> templateCache = new HashMap<>();
    private final Map<String, SlotConfig> slotConfigs = new HashMap<>();

    public TemplateManager(AppConfig config) {
        this.config = config;
        loadSlotConfig();
    }

    public static class SlotConfig {
        public int width = 512, height = 340;
        public int slotSize = 64;
        public Map<String, Point> inputSlots = new HashMap<>();
        public Point outputSlot;
        public int outputSize = 64;
    }

    private void loadSlotConfig() {
        File slotsFile = config.getTemplatesDir().resolve("slots.json").toFile();
        if (slotsFile.exists()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> raw = mapper.readValue(slotsFile, Map.class);
                for (Map.Entry<String, Object> entry : raw.entrySet()) {
                    JsonNode node = mapper.valueToTree(entry.getValue());
                    SlotConfig sc = new SlotConfig();
                    if (node.has("width")) sc.width = node.get("width").asInt();
                    if (node.has("height")) sc.height = node.get("height").asInt();
                    if (node.has("slotSize")) sc.slotSize = node.get("slotSize").asInt();
                    if (node.has("slots")) {
                        for (JsonNode slot : node.get("slots")) {
                            String key = "slot_" + slot.path("col").asInt(0) + "_" + slot.path("row").asInt(0);
                            sc.inputSlots.put(key, new Point(slot.get("cx").asInt(), slot.get("cy").asInt()));
                        }
                    }
                    if (node.has("outputSlot")) {
                        JsonNode out = node.get("outputSlot");
                        sc.outputSlot = new Point(out.get("cx").asInt(), out.get("cy").asInt());
                        if (out.has("size")) sc.outputSize = out.get("size").asInt();
                    }
                    slotConfigs.put(entry.getKey(), sc);
                }
                log.info("已加载 {} 个底板槽位配置", slotConfigs.size());
            } catch (Exception e) {
                log.warn("槽位配置解析失败: {}", e.getMessage());
            }
        }

        if (!slotConfigs.containsKey("crafting_table_3x3")) {
            slotConfigs.put("crafting_table_3x3", buildDefaultCraftingConfig());
        }
        if (!slotConfigs.containsKey("furnace")) {
            slotConfigs.put("furnace", buildDefaultFurnaceConfig());
        }
        if (!slotConfigs.containsKey("smithing_table")) {
            slotConfigs.put("smithing_table", buildDefaultSmithingConfig());
        }
    }

    public BufferedImage getTemplate(String name) {
        return templateCache.computeIfAbsent(name, k -> {
            // 1) User template
            File userTpl = config.getTemplatesDir().resolve(name + ".png").toFile();
            if (userTpl.exists()) {
                try { return ImageIO.read(userTpl); } catch (Exception ignored) {}
            }
            // 2) Vanilla GUI texture extracted from MC jar
            File vanillaTpl = config.getTexturesDir().resolve("mods/vanilla/gui_container_" + name.replace("crafting_table_3x3", "crafting_table") + ".png").toFile();
            if (name.equals("crafting_table_3x3")) {
                vanillaTpl = config.getTexturesDir().resolve("mods/vanilla/gui_container_crafting_table.png").toFile();
            } else if (name.equals("furnace")) {
                vanillaTpl = config.getTexturesDir().resolve("mods/vanilla/gui_container_furnace.png").toFile();
            } else if (name.equals("smithing_table")) {
                vanillaTpl = config.getTexturesDir().resolve("mods/vanilla/gui_container_smithing.png").toFile();
            }
            if (vanillaTpl.exists()) {
                try { return ImageIO.read(vanillaTpl); } catch (Exception ignored) {}
            }
            // 3) Auto-generate
            return generateDefaultTemplate(name);
        });
    }

    public SlotConfig getSlotConfig(String name) {
        return slotConfigs.getOrDefault(name, new SlotConfig());
    }

    private BufferedImage generateDefaultTemplate(String name) {
        SlotConfig sc = getSlotConfig(name);
        BufferedImage canvas = new BufferedImage(sc.width, sc.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(45, 45, 45, 240));
        g.fillRoundRect(0, 0, sc.width, sc.height, 12, 12);
        g.setColor(new Color(80, 80, 80));
        g.setStroke(new BasicStroke(3));
        g.drawRoundRect(2, 2, sc.width - 4, sc.height - 4, 12, 12);
        g.setColor(new Color(139, 139, 139, 80));
        for (Point p : sc.inputSlots.values()) {
            int half = sc.slotSize / 2;
            g.fillRect(p.x - half, p.y - half, sc.slotSize, sc.slotSize);
            g.setColor(new Color(90, 90, 90));
            g.drawRect(p.x - half, p.y - half, sc.slotSize, sc.slotSize);
            g.setColor(new Color(139, 139, 139, 80));
        }
        if (sc.outputSlot != null) {
            int half = sc.outputSize / 2;
            g.setColor(new Color(252, 211, 77, 100));
            g.fillRect(sc.outputSlot.x - half, sc.outputSlot.y - half, sc.outputSize, sc.outputSize);
            g.setColor(new Color(252, 211, 77));
            g.drawRect(sc.outputSlot.x - half, sc.outputSlot.y - half, sc.outputSize, sc.outputSize);
        }
        g.dispose();
        return canvas;
    }

    private SlotConfig buildDefaultCraftingConfig() {
        SlotConfig sc = new SlotConfig();
        sc.width = 512; sc.height = 340; sc.slotSize = 64; sc.outputSize = 64;
        int gsx = 80, gsy = 55;
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 3; col++)
                sc.inputSlots.put("slot_" + col + "_" + row,
                        new Point(gsx + col * 70 + 32, gsy + row * 70 + 32));
        sc.outputSlot = new Point(400, 152);
        return sc;
    }

    private SlotConfig buildDefaultFurnaceConfig() {
        SlotConfig sc = new SlotConfig();
        sc.width = 400; sc.height = 320; sc.slotSize = 64; sc.outputSize = 64;
        sc.inputSlots.put("input", new Point(120, 120));
        sc.inputSlots.put("fuel", new Point(120, 210));
        sc.outputSlot = new Point(320, 155);
        return sc;
    }

    private SlotConfig buildDefaultSmithingConfig() {
        SlotConfig sc = new SlotConfig();
        sc.width = 400; sc.height = 300; sc.slotSize = 48; sc.outputSize = 64;
        sc.inputSlots.put("template", new Point(60, 100));
        sc.inputSlots.put("base", new Point(60, 175));
        sc.inputSlots.put("addition", new Point(155, 175));
        sc.outputSlot = new Point(330, 140);
        return sc;
    }
}
