package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.JsonNode;
import yagen.waitmydawn.kb.renderer.VanillaCardStyle;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;

/**
 * 大于 3×3 的机械合成（create:mechanical_crafting）配方卡。
 *
 * <p>按 pattern 的实际行列画输入区，空格子不画槽位；右侧画工作站与产物，
 * 工作站右下角标注所需数量（pattern 非空格子数）。卡片使用与原版工作台卡相同的浅色底和 18px 槽位。
 */
public final class MechanicalCraftingCard {
    private static final int CELL = 54, GAP = 4, MARGIN = 24, TOP = 76;
    private static final Color TEXT = VanillaCardStyle.INK, MUTED = VanillaCardStyle.MUTED;

    private MechanicalCraftingCard() {}

    /** 该定义是否由本卡处理。 */
    public static boolean supports(JsonNode definition) {
        String type = definition.path("type").asText(definition.path("serializer").asText(""));
        return type.contains("mechanical_crafting");
    }

    public static BufferedImage draw(JsonNode definition, ItemIconService.Snapshot icons) {
        return draw(definition, icons, null);
    }

    public static BufferedImage draw(JsonNode definition, ItemIconService.Snapshot icons, java.util.function.Function<String, String> tagResolver) {
        var pattern = new ArrayList<String>();
        for (JsonNode line : definition.path("pattern")) pattern.add(line.asText(""));
        int rows = pattern.size();
        int columns = pattern.stream().mapToInt(String::length).max().orElse(0);
        if (rows == 0 || columns == 0) throw new IllegalArgumentException("机械合成配方缺少 pattern");
        var key = definition.path("key");
        int used = 0;
        for (String line : pattern) for (int i = 0; i < line.length(); i++) if (!Character.isWhitespace(line.charAt(i))) used++;

        var legend = legend(pattern, key, definition.path("result"), tagResolver);
        int gridWidth = columns * (CELL + GAP) - GAP, gridHeight = rows * (CELL + GAP) - GAP;
        int sideWidth = 230, width = MARGIN * 2 + gridWidth + 56 + sideWidth;
        int height = TOP + gridHeight + 108 + legend.size() * 16;
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            VanillaCardStyle.background(g, width, height);
            g.setColor(TEXT); g.setFont(new Font("Microsoft YaHei", Font.BOLD, 24));
            g.drawString("动力合成", MARGIN, 44);
            g.setColor(MUTED); g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
            g.drawString("需要 " + used + " 个动力合成器 · pattern " + columns + "×" + rows, MARGIN, 63);

            for (int r = 0; r < rows; r++) {
                String line = pattern.get(r);
                for (int c = 0; c < line.length(); c++) {
                    char symbol = line.charAt(c);
                    int x = MARGIN + c * (CELL + GAP), y = TOP + r * (CELL + GAP);
                    if (Character.isWhitespace(symbol)) continue;
                    VanillaCardStyle.slot(g, x, y, CELL);
                    int amount = key.path(String.valueOf(symbol)).path("count").asInt(1);
                    drawIngredient(g, key.path(String.valueOf(symbol)), icons, tagResolver, x, y);
                    if (amount > 1) badge(g, "x" + amount, x, y);
                }
            }

            int sideX = MARGIN + gridWidth + 44;
            drawIconBox(g, icons, "create:mechanical_crafter", sideX, TOP + 8, 64);
            g.setColor(TEXT); g.setFont(new Font("Microsoft YaHei", Font.BOLD, 22));
            String count = Integer.toString(used);
            g.drawString(count, sideX + 64 - g.getFontMetrics().stringWidth(count), TOP + 8 + 64);
            g.setColor(MUTED); g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
            g.drawString("动力合成器", sideX - 6, TOP + 8 + 84);

            g.setColor(VanillaCardStyle.ACCENT); g.setFont(new Font("Segoe UI", Font.BOLD, 26));
            g.drawString("→", sideX + 18, TOP + 132);

            var result = definition.path("result");
            drawIconBox(g, icons, result.path("id").asText(""), sideX + 52, TOP + 104, 56);
            String amount = result.path("count").asInt(1) > 1 ? "×" + result.path("count").asInt(1) : "";
            g.setColor(TEXT); g.setFont(new Font("Microsoft YaHei", Font.BOLD, 18));
            g.drawString(amount, sideX + 52 + 56 - g.getFontMetrics().stringWidth(amount), TOP + 104 + 56);
            g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
            g.drawString("产物 " + Objects.toString(result.path("id").asText(), "?"), sideX + 52, TOP + 180);

            g.setColor(MUTED); g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
            int legendY = height - 34 - legend.size() * 16;
            for (int i = 0; i < legend.size(); i++) g.drawString(legend.get(i), MARGIN, legendY + i * 16);
            g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
            g.drawString("配方类型：create:mechanical_crafting（按 pattern 实际尺寸渲染）", MARGIN, height - 16);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static java.util.List<String> legend(java.util.List<String> pattern, JsonNode key, JsonNode result, java.util.function.Function<String, String> tagResolver) {
        var lines = new LinkedHashSet<String>();
        for (String line : pattern) for (int i = 0; i < line.length(); i++) {
            char symbol = line.charAt(i);
            if (Character.isWhitespace(symbol)) continue;
            var entry = key.path(String.valueOf(symbol));
            String id = entry.path("item").asText("");
            String tag = entry.path("tag").asText("");
            if (!id.isBlank()) lines.add("物品 " + id);
            else if (!tag.isBlank()) {
                String member = tagResolver == null ? null : tagResolver.apply(tag);
                lines.add("标签 #" + tag + (member == null || member.isBlank() ? "" : " → " + member));
            }
        }
        String output = result.path("id").asText("");
        if (!output.isBlank()) lines.add("产物 " + output);
        return java.util.List.copyOf(lines);
    }

    private static void drawIngredient(Graphics2D g, JsonNode entry, ItemIconService.Snapshot icons, java.util.function.Function<String, String> tagResolver, int x, int y) {
        String id = entry.path("item").asText("");
        if (!id.isBlank()) {
            var icon = icons == null ? null : icons.item(id, false, null);
            if (icon != null && icon.image() != null) { g.drawImage(icon.image(), x + 6, y + 6, CELL - 12, CELL - 12, null); return; }
            label(g, id.substring(id.indexOf(':') + 1), x, y);
            return;
        }
        String tag = entry.path("tag").asText("");
        if (!tag.isBlank()) {
            String member = tagResolver == null ? null : tagResolver.apply(tag);
            if (member != null && !member.isBlank()) {
                var icon = icons == null ? null : icons.item(member, false, null);
                if (icon != null && icon.image() != null) { g.drawImage(icon.image(), x + 6, y + 6, CELL - 12, CELL - 12, null); return; }
            }
            int colon = tag.indexOf(':');
            label(g, colon > 0 ? tag.substring(colon + 1) : tag, x, y);
            return;
        }
        label(g, "?", x, y);
    }

    private static void badge(Graphics2D g, String text, int x, int y) {
        g.setFont(new Font("Microsoft YaHei", Font.BOLD, 12));
        int width = g.getFontMetrics().stringWidth(text);
        g.setColor(new Color(255, 255, 255, 230));
        g.fillRoundRect(x + CELL - width - 8, y + CELL - 17, width + 6, 15, 6, 6);
        g.setColor(TEXT);
        g.drawString(text, x + CELL - width - 5, y + CELL - 5);
    }

    private static void label(Graphics2D g, String text, int x, int y) {
        g.setColor(MUTED); var font = new Font("Microsoft YaHei", Font.PLAIN, 10); g.setFont(font);
        String shown = text.length() > 8 ? text.substring(0, 8) : text;
        g.drawString(shown, x + Math.max(2, (CELL - g.getFontMetrics().stringWidth(shown)) / 2), y + CELL / 2 + 4);
    }

    private static void drawIconBox(Graphics2D g, ItemIconService.Snapshot icons, String id, int x, int y, int size) {
        VanillaCardStyle.slot(g, x, y, size);
        var icon = icons == null || id.isBlank() ? null : icons.item(id, false, null);
        if (icon != null && icon.image() != null) g.drawImage(icon.image(), x + 6, y + 6, size - 12, size - 12, null);
        else label(g, id.isBlank() ? "?" : id.substring(id.indexOf(':') + 1), x, y);
    }
}
