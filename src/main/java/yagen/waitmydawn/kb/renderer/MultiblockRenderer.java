package yagen.waitmydawn.kb.renderer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;

/**
 * 多方块结构分层俯视图渲染器。
 */
public class MultiblockRenderer {

    private final ObjectMapper mapper = new ObjectMapper();
    private static final int CELL_SIZE = 48;
    private static final int PADDING = 20;

    public MultiblockRenderer() {}

    public BufferedImage render(String layersJson, List<String> texturePaths) {
        try {
            JsonNode root = mapper.readTree(layersJson);
            int width = root.path("width").asInt(3);
            int depth = root.path("depth").asInt(3);
            JsonNode layers = root.path("layers");
            JsonNode legend = root.path("legend");
            String title = root.path("structure_name").asText("Multiblock");

            if (!layers.isArray() || layers.size() == 0) return null;

            Map<String, Color> colorMap = buildLegendColors(legend);
            int layerCount = layers.size();
            int gridPixelW = width * CELL_SIZE;
            int gridPixelH = depth * CELL_SIZE;
            int layerHeight = gridPixelH + 40;
            int totalHeight = PADDING + 40 + layerCount * (layerHeight + 10) + PADDING + 80;
            int canvasWidth = gridPixelW + PADDING * 2 + 200;

            BufferedImage canvas = new BufferedImage(canvasWidth, totalHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = canvas.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(248, 250, 252));
            g.fillRect(0, 0, canvasWidth, totalHeight);
            g.setColor(new Color(30, 30, 30));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
            g.drawString(title, PADDING, PADDING + 25);

            int yOffset = PADDING + 45;
            for (int l = layerCount - 1; l >= 0; l--) {
                JsonNode layer = layers.get(l);
                int layerY = layer.path("y").asInt(l);
                JsonNode grid = layer.path("grid");
                g.setColor(new Color(80, 80, 80));
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
                g.drawString("Layer " + (layerCount - l) + " (y=" + layerY + ")", PADDING, yOffset + 20);

                for (int row = 0; row < depth; row++) {
                    for (int col = 0; col < width; col++) {
                        String code = grid.isArray() && row < grid.size()
                                && grid.get(row).isArray() && col < grid.get(row).size()
                                ? grid.get(row).get(col).asText(" ") : " ";
                        int cx = PADDING + col * CELL_SIZE;
                        int cy = yOffset + 30 + row * CELL_SIZE;
                        boolean empty = code.equals(" ") || code.equals(".") || code.equals("-");
                        g.setColor(empty ? new Color(200, 200, 200, 60)
                                : colorMap.getOrDefault(code, guessColor(code)));
                        g.fillRect(cx + 1, cy + 1, CELL_SIZE - 2, CELL_SIZE - 2);
                        g.setColor(new Color(180, 180, 180));
                        g.drawRect(cx, cy, CELL_SIZE, CELL_SIZE);
                        if (!empty) {
                            g.setColor(Color.WHITE);
                            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
                            FontMetrics fm = g.getFontMetrics();
                            g.drawString(code, cx + (CELL_SIZE - fm.stringWidth(code)) / 2,
                                    cy + (CELL_SIZE + fm.getAscent()) / 2);
                        }
                    }
                }
                yOffset += 30 + depth * CELL_SIZE + 15;
            }

            // Legend
            g.setColor(new Color(80, 80, 80));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
            g.drawString("Legend:", gridPixelW + PADDING * 2 + 20, PADDING + 45);
            int ly = PADDING + 65;
            for (Map.Entry<String, Color> e : colorMap.entrySet()) {
                g.setColor(e.getValue());
                g.fillRect(gridPixelW + PADDING * 2 + 20, ly, 16, 16);
                g.setColor(Color.BLACK);
                g.drawRect(gridPixelW + PADDING * 2 + 20, ly, 16, 16);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
                String bn = legend != null ? legend.path(e.getKey()).asText("") : "";
                g.drawString(e.getKey() + (bn.isEmpty() ? "" : " = " + bn),
                        gridPixelW + PADDING * 2 + 42, ly + 14);
                ly += 22;
            }
            g.dispose();
            return canvas;
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Color> buildLegendColors(JsonNode legend) {
        Map<String, Color> colors = new LinkedHashMap<>();
        Color[] palette = {new Color(239, 68, 68), new Color(59, 130, 246), new Color(34, 197, 94),
                new Color(251, 191, 36), new Color(168, 85, 247), new Color(236, 72, 153),
                new Color(14, 165, 233), new Color(245, 158, 11), new Color(100, 116, 139),
                new Color(180, 83, 9)};
        int idx = 0;
        if (legend != null && legend.isObject()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> lm = mapper.convertValue(legend, Map.class);
                for (String key : lm.keySet()) {
                    colors.put(key, palette[idx % palette.length]);
                    idx++;
                }
            } catch (Exception ignored) {}
        }
        return colors;
    }

    private Color guessColor(String code) {
        Color[] palette = {new Color(239, 68, 68), new Color(59, 130, 246), new Color(34, 197, 94),
                new Color(251, 191, 36), new Color(168, 85, 247), new Color(236, 72, 153)};
        return palette[Math.abs(code.hashCode()) % palette.length];
    }
}
