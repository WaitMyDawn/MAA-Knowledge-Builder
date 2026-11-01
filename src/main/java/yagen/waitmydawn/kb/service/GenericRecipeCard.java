package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.*;

/**
 * 未适配配方类型的通用卡片：输入 → 工作站 → 输出，附加液体与数量。
 *
 * <p>只按字段名读取（input/results/fluid…），不猜测具体模组语义；卡片上标注原始配方类型，
 * 避免把通用渲染当成精确还原。
 */
public final class GenericRecipeCard {
    private static final int CELL = 42, GAP = 8, MARGIN = 20, TOP = 84;
    private static final Color BG = new Color(0x1b2230), SLOT = new Color(0x2a3446), TEXT = new Color(0xe6ecf5), MUTED = new Color(0x9aa7b8);

    private GenericRecipeCard() {}

    /** 有输入或输出就能画；机械合成由 MechanicalCraftingCard 处理。 */
    public static boolean supports(JsonNode definition) {
        return definition.has("input") || definition.has("inputs") || definition.has("result")
                || definition.has("results") || definition.has("output") || definition.has("outputs");
    }

    public static BufferedImage draw(JsonNode definition, String recipeId, ItemIconService.Snapshot icons) {
        var inputs = collect(definition, List.of("input", "inputs", "ingredients"));
        var outputs = collect(definition, List.of("result", "results", "output", "outputs"));
        var fluids = collectFluids(definition);
        String type = definition.path("type").asText("未知类型");
        String station = station(type);

        int width = 620, height = TOP + Math.max(120, Math.max(inputs.size(), outputs.size()) * (CELL + GAP) + 40) + 60;
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(BG); g.fillRect(0, 0, width, height);
            g.setColor(TEXT); g.setFont(new Font("Microsoft YaHei", Font.BOLD, 19));
            g.drawString("通用配方", MARGIN, 30);
            g.setColor(MUTED); g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
            g.drawString("配方类型：" + type + "（通用渲染，不是逐格还原）", MARGIN, 50);

            int leftX = MARGIN + 6, y = TOP;
            g.setColor(MUTED); g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
            g.drawString("输入", leftX, y - 6);
            int column = 0;
            for (var input : inputs) {
                drawEntry(g, input, icons, leftX, y + column * (CELL + GAP));
                column++;
                if (column >= 4) break;
            }
            if (inputs.isEmpty()) { g.setColor(TEXT); g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13)); g.drawString("（无输入字段）", leftX, y + 18); }

            int fluidY = y + Math.max(1, Math.min(4, inputs.size())) * (CELL + GAP) + 6;
            if (!fluids.isEmpty()) {
                g.setColor(MUTED); g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
                for (int i = 0; i < fluids.size() && i < 3; i++) g.drawString("液体：" + fluids.get(i), leftX, fluidY + i * 16);
            }

            int stationX = 300;
            g.setColor(MUTED); g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12)); g.drawString("工作站", stationX, y - 6);
            drawIconBox(g, icons, station, stationX, y, 56);
            g.setColor(TEXT); g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
            g.drawString(station == null ? type : station, stationX, y + 76);

            g.setColor(new Color(0x8fb4ff)); g.setFont(new Font("Segoe UI", Font.BOLD, 26));
            g.drawString("→", stationX + 84, y + 36);

            int outX = stationX + 130;
            g.setColor(MUTED); g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12)); g.drawString("输出", outX, y - 6);
            column = 0;
            for (var output : outputs) {
                drawEntry(g, output, icons, outX, y + column * (CELL + GAP));
                column++;
                if (column >= 4) break;
            }
            if (outputs.isEmpty()) { g.setColor(TEXT); g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13)); g.drawString("（无输出字段）", outX, y + 18); }

            g.setColor(MUTED); g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
            g.drawString(recipeId == null ? "" : recipeId, MARGIN, height - 16);
        } finally {
            g.dispose();
        }
        return image;
    }

    private record Entry(String id, String tag, JsonNode raw) {}

    private static java.util.List<Entry> collect(JsonNode definition, java.util.List<String> fields) {
        var entries = new ArrayList<Entry>();
        for (String field : fields) {
            var node = definition.path(field);
            if (node.isMissingNode()) continue;
            if (node.isArray()) for (JsonNode item : node) addEntry(entries, item);
            else addEntry(entries, node);
        }
        return entries;
    }

    private static void addEntry(java.util.List<Entry> entries, JsonNode node) {
        if (node == null || !node.isObject()) return;
        String id = node.path("item").asText(node.path("id").asText(""));
        String tag = node.path("tag").asText("");
        if (id.isBlank() && tag.isBlank()) return;
        entries.add(new Entry(id, tag, node));
    }

    private static java.util.List<String> collectFluids(JsonNode definition) {
        var fluids = new ArrayList<String>();
        for (String field : java.util.List.of("fluid", "base_fluid")) {
            var node = definition.path(field);
            if (node.isObject() && node.has("id")) fluids.add(shortId(node.path("id").asText()) + " " + node.path("amount").asInt(0) + " mB");
        }
        return fluids;
    }

    private static String station(String type) {
        if (type == null || !type.contains(":")) return null;
        String path = type.substring(type.indexOf(':') + 1);
        for (String suffix : List.of("_brew", "_empty", "_fill", "_mixing", "_basin", "_spout", "_press")) {
            if (path.endsWith(suffix)) return type.substring(0, type.length() - suffix.length());
        }
        return null;
    }

    private static void drawEntry(Graphics2D g, Entry entry, ItemIconService.Snapshot icons, int x, int y) {
        g.setColor(SLOT); g.fillRoundRect(x, y, CELL, CELL, 8, 8);
        if (!entry.id().isBlank()) {
            var icon = icons == null ? null : icons.item(entry.id(), false, null);
            if (icon != null && icon.image() != null) { g.drawImage(icon.image(), x + 5, y + 5, CELL - 10, CELL - 10, null); return; }
            label(g, shortId(entry.id()), x, y, CELL);
            return;
        }
        int colon = entry.tag().indexOf(':');
        label(g, colon > 0 ? entry.tag().substring(colon + 1) : entry.tag(), x, y, CELL);
    }

    private static void drawIconBox(Graphics2D g, ItemIconService.Snapshot icons, String id, int x, int y, int size) {
        g.setColor(SLOT); g.fillRoundRect(x, y, size, size, 10, 10);
        var icon = icons == null || id == null ? null : icons.item(id, false, null);
        if (icon != null && icon.image() != null) g.drawImage(icon.image(), x + 6, y + 6, size - 12, size - 12, null);
        else if (id != null) label(g, shortId(id), x, y, size);
    }

    private static void label(Graphics2D g, String text, int x, int y, int size) {
        g.setColor(MUTED); g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 10));
        String shown = text.length() > 9 ? text.substring(0, 9) : text;
        g.drawString(shown, x + Math.max(2, (size - g.getFontMetrics().stringWidth(shown)) / 2), y + size / 2 + 4);
    }

    private static String shortId(String id) {
        int colon = id.indexOf(':');
        return colon > 0 ? id.substring(colon + 1) : id;
    }
}
