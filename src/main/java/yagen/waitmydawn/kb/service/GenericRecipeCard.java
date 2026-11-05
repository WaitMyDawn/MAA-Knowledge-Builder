package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.JsonNode;
import yagen.waitmydawn.kb.renderer.VanillaCardStyle;
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
    private static final int CELL = 54, GAP = 10, MARGIN = 24, TOP = 108;
    private static final Color BG = VanillaCardStyle.BACKGROUND, SLOT = new Color(0xe4e8ee), TEXT = VanillaCardStyle.INK, MUTED = VanillaCardStyle.MUTED;

    private GenericRecipeCard() {}

    /** 有输入或输出就能画；机械合成由 MechanicalCraftingCard 处理。 */
    public static boolean supports(JsonNode definition) {
        return definition.has("input") || definition.has("inputs") || definition.has("result")
                || definition.has("results") || definition.has("output") || definition.has("outputs");
    }

    public static BufferedImage draw(JsonNode definition, String recipeId, ItemIconService.Snapshot icons) {
        return draw(definition, recipeId, icons, null);
    }

    public static BufferedImage draw(JsonNode definition, String recipeId, ItemIconService.Snapshot icons, java.util.function.Function<String, String> tagResolver) {
        var inputs = new ArrayList<>(collect(definition, List.of("input", "inputs", "ingredients")));
        addPatternInputs(inputs, definition);
        var outputs = collect(definition, List.of("result", "results", "output", "outputs"));
        var fluids = collectFluids(definition);
        var outputFluids = collectOutputFluids(definition);
        var legend = legend(inputs, outputs, fluids, outputFluids, tagResolver);
        String type = definition.path("type").asText("未知类型");
        String station = station(type);
        boolean mixing = type.contains("create:mixing");
        String heat = definition.path("heat_requirement").asText("");
        String heatLabel = mixing ? switch (heat) {
            case "heated" -> "热量：加热";
            case "superheated" -> "热量：超级加热";
            case "none" -> "热量：无需加热";
            default -> "热量：未导出";
        } : "";

        int legendHeight = legend.isEmpty() ? 0 : legend.size() * 14 + 10;
        int width = 620, height = TOP + Math.max(160, Math.max(inputs.size(), outputs.size()) * (CELL + GAP) + 40) + (mixing ? 26 : 0) + 60 + legendHeight;
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            VanillaCardStyle.background(g,width,height);
            g.setColor(TEXT); g.setFont(new Font("Microsoft YaHei", Font.BOLD, 22));
            g.drawString("通用配方", MARGIN, 42);
            g.setColor(MUTED); g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
            g.drawString("配方类型：" + type + "（通用渲染，不是逐格还原）", MARGIN, 62);
            if (mixing) {
                g.setColor(new Color(0x9a5b00));
                g.drawString(heatLabel + " · 工作站：动力搅拌器 + 工作盆", MARGIN, 80);
            }

            int leftX = MARGIN + 6, y = TOP;
            g.setColor(MUTED); g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
            g.drawString("输入", leftX, y - 6);
            int column = 0;
            for (var input : inputs) {
                drawEntry(g, input, icons, tagResolver, leftX, y + column * (CELL + GAP));
                column++;
                if (column >= 4) break;
            }
            if (inputs.isEmpty()) { g.setColor(TEXT); g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13)); g.drawString("（无输入字段）", leftX, y + 18); }

            int fluidY = y + Math.max(1, Math.min(4, inputs.size())) * (CELL + GAP) + 6;
            if (!fluids.isEmpty()) {
                g.setColor(MUTED); g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
                for (int i = 0; i < fluids.size() && i < 3; i++) {
                    var fluid = fluids.get(i);
                    int chipY = fluidY + i * 24;
                    g.setColor(new Color(0xe8edf4)); g.fillRoundRect(leftX, chipY, 150, 22, 8, 8);
                    g.setColor(VanillaCardStyle.ACCENT); g.fillOval(leftX + 4, chipY + 6, 10, 10);
                    g.setColor(TEXT); g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
                    g.drawString(shortId(fluid.id()) + " · " + fluid.amount() + " mB", leftX + 20, chipY + 14);
                }
            }

            int stationX = 300;
            g.setColor(MUTED); g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12)); g.drawString("工作站", stationX, y - 6);
            if (station != null) drawIconBox(g, icons, station, stationX, y, 56);
            g.setColor(TEXT); g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
            g.drawString(station == null ? "未识别工作站" : shortId(station) + (mixing ? " + 工作盆" : ""), stationX, y + 76);

            g.setColor(VanillaCardStyle.ACCENT); g.setFont(new Font("Segoe UI", Font.BOLD, 26));
            g.drawString("→", stationX + 84, y + 36);

            int outX = stationX + 130;
            g.setColor(MUTED); g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12)); g.drawString("输出", outX, y - 6);
            column = 0;
            for (var output : outputs) {
                drawEntry(g, output, icons, tagResolver, outX, y + column * (CELL + GAP));
                column++;
                if (column >= 4) break;
            }
            if (outputs.isEmpty()) { g.setColor(TEXT); g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13)); g.drawString("（无输出字段）", outX, y + 18); }
            if (!outputFluids.isEmpty()) {
                int fluidOutY = y + Math.max(3, Math.min(4, outputs.size())) * (CELL + GAP) + 6;
                g.setColor(MUTED); g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
                for (int i = 0; i < outputFluids.size() && i < 3; i++) {
                    var fluid = outputFluids.get(i);
                    int chipY = fluidOutY + i * 24;
                    g.setColor(new Color(0xe8edf4)); g.fillRoundRect(outX, chipY, 176, 22, 8, 8);
                    g.setColor(VanillaCardStyle.ACCENT); g.fillOval(outX + 4, chipY + 6, 10, 10);
                    g.setColor(TEXT); g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
                    g.drawString("输出流体 " + shortId(fluid.id()) + " · " + fluid.amount() + " mB", outX + 20, chipY + 14);
                }
            }

            if (!legend.isEmpty()) {
                int legendY = height - 30 - legend.size() * 14;
                g.setColor(MUTED); g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 10));
                for (int i = 0; i < legend.size(); i++) g.drawString(legend.get(i), MARGIN, legendY + i * 14);
            }
            g.setColor(MUTED); g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
            g.drawString(recipeId == null ? "" : recipeId, MARGIN, height - 16);
        } finally {
            g.dispose();
        }
        return image;
    }

    private record Entry(String id, String tag, JsonNode raw) {}

    private static List<String> legend(List<Entry> inputs, List<Entry> outputs, List<Fluid> fluids,
                                       List<Fluid> outputFluids, java.util.function.Function<String, String> tagResolver) {
        var lines = new LinkedHashSet<String>();
        for (var entry : concat(inputs, outputs)) {
            if (!entry.id().isBlank()) lines.add("物品 " + entry.id());
            else if (!entry.tag().isBlank()) {
                String member = tagResolver == null ? null : tagResolver.apply(entry.tag());
                lines.add("标签 #" + entry.tag() + (member == null || member.isBlank() ? "" : " → " + member));
            }
        }
        for (var fluid : fluids) lines.add("输入流体 " + fluid.id() + " · " + fluid.amount() + " mB");
        for (var fluid : outputFluids) lines.add("输出流体 " + fluid.id() + " · " + fluid.amount() + " mB");
        return List.copyOf(lines);
    }

    private static List<Entry> concat(List<Entry> first, List<Entry> second) {
        var result = new ArrayList<Entry>(first.size() + second.size());
        result.addAll(first); result.addAll(second); return result;
    }

    /** 机械合成的输入只存在于 pattern/key；通用卡至少把唯一 symbol 的材料列出来。 */
    private static void addPatternInputs(List<Entry> inputs, JsonNode definition) {
        if (!definition.path("key").isObject()) return;
        var seen = new LinkedHashSet<String>();
        for (JsonNode line : definition.path("pattern")) {
            String row = line.asText("");
            for (int i = 0; i < row.length(); i++) {
                char symbol = row.charAt(i);
                if (Character.isWhitespace(symbol)) continue;
                var entry = definition.path("key").path(String.valueOf(symbol));
                if (!entry.isObject()) continue;
                String id = entry.path("item").asText(entry.path("id").asText(""));
                String tag = entry.path("tag").asText("");
                if (id.isBlank() && tag.isBlank()) continue;
                if (seen.add(id + "|" + tag)) inputs.add(new Entry(id, tag, entry));
            }
        }
    }

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

    private static java.util.List<Fluid> collectFluids(JsonNode definition) {
        var fluids = new ArrayList<Fluid>();
        for (String field : java.util.List.of("fluid", "base_fluid")) addFluid(fluids, definition.path(field));
        for (String field : java.util.List.of("input", "inputs", "ingredients")) {
            var node = definition.path(field);
            if (node.isArray()) for (JsonNode item : node) addFluidEntry(fluids, item);
            else addFluidEntry(fluids, node);
        }
        return fluids;
    }

    private static void addFluid(List<Fluid> fluids, JsonNode node) {
        if (node == null || !node.isObject() || !node.has("id")) return;
        fluids.add(new Fluid(node.path("id").asText(""), node.path("amount").asInt(0)));
    }

    /** NeoForge 的流体条目既可写成 {fluid:{id,amount}}，也可写成 {fluid:"minecraft:water",amount:250}。 */
    private static void addFluidEntry(List<Fluid> fluids, JsonNode entry) {
        if (entry == null || !entry.isObject()) return;
        var fluid = entry.path("fluid");
        if (fluid.isTextual()) fluids.add(new Fluid(fluid.asText(""), entry.path("amount").asInt(0)));
        else if (fluid.isObject()) addFluid(fluids, fluid);
        else if (entry.has("amount")) addFluid(fluids, entry);
    }

    private static List<Fluid> collectOutputFluids(JsonNode definition) {
        var fluids = new ArrayList<Fluid>();
        for (String field : List.of("result", "results", "output", "outputs")) {
            var node = definition.path(field);
            if (node.isArray()) for (JsonNode item : node) addFluidEntry(fluids, item);
            else addFluidEntry(fluids, node);
        }
        return fluids;
    }

    private record Fluid(String id, int amount) {}

    private static final java.util.Map<String, String> KNOWN_STATIONS = java.util.Map.ofEntries(
        java.util.Map.entry("create:mixing", "create:mechanical_mixer"),
        java.util.Map.entry("create:compacting", "create:mechanical_press"),
        java.util.Map.entry("create:pressing", "create:mechanical_press"),
        java.util.Map.entry("create:crushing", "create:crushing_wheel"),
        java.util.Map.entry("create:milling", "create:millstone"),
        java.util.Map.entry("create:cutting", "create:mechanical_saw"),
        java.util.Map.entry("create:filling", "create:spout"),
        java.util.Map.entry("create:emptying", "create:item_drain"),
        java.util.Map.entry("create:deploying", "create:deployer"),
        java.util.Map.entry("create:splashing", "create:encased_fan"),
        java.util.Map.entry("create:haunting", "create:encased_fan"),
        java.util.Map.entry("create:mechanical_crafting", "create:mechanical_crafter"),
        java.util.Map.entry("create:smoking", "create:encased_fan"),
        java.util.Map.entry("create:blasting", "create:encased_fan"));
    private static String station(String type) {
        if (type == null || !type.contains(":")) return null;
        String known = KNOWN_STATIONS.get(type);
        if (known != null) return known;
        String path = type.substring(type.indexOf(':') + 1);
        for (String suffix : List.of("_brew", "_empty", "_fill", "_mixing", "_basin", "_spout", "_press")) {
            if (path.endsWith(suffix)) return type.substring(0, type.length() - suffix.length());
        }
        return null;
    }

    private static void drawEntry(Graphics2D g, Entry entry, ItemIconService.Snapshot icons, java.util.function.Function<String, String> tagResolver, int x, int y) {
        VanillaCardStyle.slot(g, x, y, CELL);
        boolean drawn = false;
        if (!entry.id().isBlank()) {
            var icon = icons == null ? null : icons.item(entry.id(), false, null);
            if (icon != null && icon.image() != null) { g.drawImage(icon.image(), x + 5, y + 5, CELL - 10, CELL - 10, null); drawn = true; }
            else label(g, shortId(entry.id()), x, y, CELL);
        } else {
            String member = tagResolver == null ? null : tagResolver.apply(entry.tag());
            if (member != null && !member.isBlank()) {
                var icon = icons == null ? null : icons.item(member, false, null);
                if (icon != null && icon.image() != null) { g.drawImage(icon.image(), x + 5, y + 5, CELL - 10, CELL - 10, null); drawn = true; }
            }
            if (!drawn) {
                int colon = entry.tag().indexOf(':');
                label(g, colon > 0 ? entry.tag().substring(colon + 1) : entry.tag(), x, y, CELL);
            }
        }
        drawBadge(g, amount(entry.raw()), x, y, CELL);
    }

    /** 数量角标：count/amount 都显示在右下角，缺省不画。 */
    private static void drawBadge(Graphics2D g, String text, int x, int y, int size) {
        if (text == null || text.isBlank()) return;
        g.setFont(new Font("Microsoft YaHei", Font.BOLD, 12));
        int width = g.getFontMetrics().stringWidth(text);
        g.setColor(new Color(255, 255, 255, 230));
        g.fillRoundRect(x + size - width - 8, y + size - 17, width + 6, 15, 6, 6);
        g.setColor(TEXT);
        g.drawString(text, x + size - width - 5, y + size - 5);
    }

    private static String amount(JsonNode node) {
        if (node == null) return "";
        int count = node.path("count").asInt(node.path("amount").asInt(0));
        return count > 1 ? "x" + count : "";
    }

    private static void drawIconBox(Graphics2D g, ItemIconService.Snapshot icons, String id, int x, int y, int size) {
        VanillaCardStyle.slot(g, x, y, size);
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
