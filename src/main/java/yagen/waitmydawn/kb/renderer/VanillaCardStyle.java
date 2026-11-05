package yagen.waitmydawn.kb.renderer;

import java.awt.*;

/** 配方卡统一使用接近原版工作台卡片的浅色背景和 18px 槽位。 */
public final class VanillaCardStyle {
    public static final Color BACKGROUND = new Color(244, 247, 252);
    public static final Color CARD = Color.WHITE;
    public static final Color INK = new Color(26, 29, 33);
    public static final Color MUTED = new Color(65, 72, 82);
    public static final Color ACCENT = new Color(24, 76, 145);
    public static final Color DIVIDER = new Color(224, 232, 243);
    public static final Color LEGEND = new Color(237, 239, 242);

    private VanillaCardStyle() {}

    public static void background(Graphics2D g, int width, int height) {
        g.setColor(BACKGROUND);
        g.fillRect(0, 0, width, height);
        g.setColor(CARD);
        g.fillRoundRect(12, 12, width - 24, height - 24, 24, 24);
        g.setColor(DIVIDER);
        g.drawRoundRect(12, 12, width - 24, height - 24, 24, 24);
    }

    public static void slot(Graphics2D g, int x, int y, int size) {
        MinecraftSlot.draw(g, x, y, Math.max(1, size / 18));
    }
}
