package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

/**
 * 大于 3×3 的机械合成（create:mechanical_crafting）配方卡。
 *
 * <p>按 pattern 的实际行列画输入区，空格子不画槽位；右侧画工作站与产物，
 * 工作站右下角标注所需数量（pattern 非空格子数）。其他未适配类型由通用卡片处理。
 */
public final class MechanicalCraftingCard {
    private static final int CELL = 44, GAP = 3, MARGIN = 22, TOP = 58;
    private static final Color BG = new Color(0x1b2230), SLOT = new Color(0x2a3446), TEXT = new Color(0xe6ecf5), MUTED = new Color(0x9aa7b8);

    private MechanicalCraftingCard() {}

    /** 该定义是否由本卡处理。 */
    public static boolean supports(JsonNode definition) {
        String type = definition.path("type").asText(definition.path("serializer").asText(""));
        if (type.contains("mechanical_crafting")) return true;
        return definition.has("pattern") && definition.path("pattern").isArray() && definition.has("result");
    }

    public static BufferedImage draw(JsonNode definition, ItemIconService.Snapshot icons) {
        var pattern = new ArrayList<String>();
        for (JsonNode line : definition.path("pattern")) pattern.add(line.asText(""));
        int rows = pattern.size();
        int columns = pattern.stream().mapToInt(String::length).max().orElse(0);
        if (rows == 0 || columns == 0) throw new IllegalArgumentException("机械合成配方缺少 pattern");
        var key = definition.path("key");
        int used = 0;
        for (String line : pattern) for (int i = 0; i < line.length(); i++) if (!Character.isWhitespace(line.charAt(i))) used++;

        int gridWidth = columns * (CELL + GAP) - GAP, gridHeight = rows * (CELL + GAP) - GAP;
        int sideWidth = 190, width = MARGIN * 2 + gridWidth + 56 + sideWidth, height = TOP + gridHeight + 96;
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(BG); g.fillRect(0, 0, width, height);
            g.setColor(TEXT); g.setFont(new Font("Microsoft YaHei", Font.BOLD, 20));
            g.drawString("动力合成", MARGIN, 32);
            g.setColor(MUTED); g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
            g.drawString("需要 " + used + " 个动力合成器 · pattern " + columns + "×" + rows, MARGIN, 50);

            for (int r = 0; r < rows; r++) {
                String line = pattern.get(r);
                for (int c = 0; c < line.length(); c++) {
                    char symbol = line.charAt(c);
                    int x = MARGIN + c * (CELL + GAP), y = TOP + r * (CELL + GAP);
                    if (Character.isWhitespace(symbol)) continue;
                    g.setColor(SLOT); g.fillRoundRect(x, y, CELL, CELL, 8, 8);
                    drawIngredient(g, key.path(String.valueOf(symbol)), icons, x, y);
                }
            }

            int sideX = MARGIN + gridWidth + 44;
            drawIconBox(g, icons, "create:mechanical_crafter", sideX, TOP + 8, 64);
            g.setColor(TEXT); g.setFont(new Font("Microsoft YaHei", Font.BOLD, 22));
            String count = Integer.toString(used);
            g.drawString(count, sideX + 64 - g.getFontMetrics().stringWidth(count), TOP + 8 + 64);
            g.setColor(MUTED); g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
            g.drawString("动力合成器", sideX - 6, TOP + 8 + 84);

            g.setColor(new Color(0x8fb4ff)); g.setFont(new Font("Segoe UI", Font.BOLD, 26));
            g.drawString("→", sideX + 18, TOP + 132);

            var result = definition.path("result");
            drawIconBox(g, icons, result.path("id").asText(""), sideX + 52, TOP + 104, 56);
            String amount = result.path("count").asInt(1) > 1 ? "×" + result.path("count").asInt(1) : "";
            g.setColor(TEXT); g.setFont(new Font("Microsoft YaHei", Font.BOLD, 18));
            g.drawString(amount, sideX + 52 + 56 - g.getFontMetrics().stringWidth(amount), TOP + 104 + 56);

            g.setColor(MUTED); g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
            g.drawString("配方类型：create:mechanical_crafting（按 pattern 实际尺寸渲染）", MARGIN, height - 18);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static void drawIngredient(Graphics2D g, JsonNode entry, ItemIconService.Snapshot icons, int x, int y) {
        String id = entry.path("item").asText("");
        if (!id.isBlank()) {
            var icon = icons == null ? null : icons.item(id, false, null);
            if (icon != null && icon.image() != null) { g.drawImage(icon.image(), x + 6, y + 6, CELL - 12, CELL - 12, null); return; }
            label(g, id.substring(id.indexOf(':') + 1), x, y);
            return;
        }
        String tag = entry.path("tag").asText("");
        if (!tag.isBlank()) { int colon = tag.indexOf(':'); label(g, colon > 0 ? tag.substring(colon + 1) : tag, x, y); return; }
        label(g, "?", x, y);
    }

    private static void label(Graphics2D g, String text, int x, int y) {
        g.setColor(MUTED); var font = new Font("Microsoft YaHei", Font.PLAIN, 10); g.setFont(font);
        String shown = text.length() > 8 ? text.substring(0, 8) : text;
        g.drawString(shown, x + Math.max(2, (CELL - g.getFontMetrics().stringWidth(shown)) / 2), y + CELL / 2 + 4);
    }

    private static void drawIconBox(Graphics2D g, ItemIconService.Snapshot icons, String id, int x, int y, int size) {
        g.setColor(SLOT); g.fillRoundRect(x, y, size, size, 10, 10);
        var icon = icons == null || id.isBlank() ? null : icons.item(id, false, null);
        if (icon != null && icon.image() != null) g.drawImage(icon.image(), x + 6, y + 6, size - 12, size - 12, null);
        else label(g, id.isBlank() ? "?" : id.substring(id.indexOf(':') + 1), x, y);
    }
}
