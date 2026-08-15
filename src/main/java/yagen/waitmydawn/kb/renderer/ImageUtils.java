package yagen.waitmydawn.kb.renderer;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

public final class ImageUtils {

    private ImageUtils() {}

    public static BufferedImage loadImage(String path) {
        if (path == null) return null;
        try {
            File f = new File(path);
            if (f.exists()) return ImageIO.read(f);
        } catch (IOException ignored) {}
        return null;
    }

    public static BufferedImage scale(BufferedImage src, int w, int h) {
        if (src == null) return null;
        BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return scaled;
    }

    public static BufferedImage createPlaceholder(String label, Color color) {
        int size = 64;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 200));
        g.fillRect(2, 2, size - 4, size - 4);
        g.setColor(color.darker());
        g.drawRect(2, 2, size - 4, size - 4);
        String display = label.length() > 3 ? label.substring(0, 3) : label;
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        FontMetrics fm = g.getFontMetrics();
        int x = (size - fm.stringWidth(display)) / 2;
        int y = (size + fm.getAscent()) / 2 - 2;
        g.drawString(display, x, y);
        g.dispose();
        return img;
    }

    public static Color getColorForItemType(String itemType) {
        if (itemType == null) return new Color(156, 163, 175);
        return switch (itemType.toLowerCase()) {
            case "tool", "weapon" -> new Color(139, 139, 139);
            case "gem", "diamond" -> new Color(74, 237, 217);
            case "gold" -> new Color(252, 211, 77);
            case "iron" -> new Color(209, 213, 219);
            case "food" -> new Color(249, 168, 212);
            case "plant" -> new Color(74, 222, 128);
            case "block", "building" -> new Color(146, 64, 14);
            case "redstone", "tech" -> new Color(239, 68, 68);
            case "magic" -> new Color(168, 85, 247);
            default -> new Color(156, 163, 175);
        };
    }

    public static void drawFooter(BufferedImage canvas, String text, int y) {
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(30, 30, 30, 220));
        g.fillRect(0, y, canvas.getWidth(), 40);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        FontMetrics fm = g.getFontMetrics();
        int tx = (canvas.getWidth() - fm.stringWidth(text)) / 2;
        g.drawString(text, tx, y + 25);
        g.dispose();
    }
}
