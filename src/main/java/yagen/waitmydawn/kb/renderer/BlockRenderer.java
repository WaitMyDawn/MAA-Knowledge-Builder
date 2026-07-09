package yagen.waitmydawn.kb.renderer;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/**
 * 方块伪3D等距图标渲染器。
 */
public class BlockRenderer {

    public static BufferedImage renderIsometric(BufferedImage topTexture, BufferedImage frontTexture,
                                                 BufferedImage sideTexture, int size) {
        BufferedImage result = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);

        BufferedImage tex = topTexture != null ? topTexture : frontTexture != null ? frontTexture : sideTexture;
        if (tex != null && (topTexture == null || (frontTexture == null && sideTexture == null))) {
            g.drawImage(tex, 4, 4, size - 8, size - 8, null);
            g.dispose();
            return result;
        }
        if (topTexture == null && frontTexture == null && sideTexture == null) {
            g.dispose();
            return null;
        }

        int half = size / 2;
        if (topTexture != null) {
            try {
                AffineTransform at = new AffineTransform();
                at.translate(size * 0.15, -4);
                at.shear(-0.35, 0.15);
                at.scale(0.72, 0.72);
                g.setTransform(at);
                g.drawImage(topTexture, 0, 4, size, half, null);
                g.setTransform(new AffineTransform());
            } catch (Exception e) {
                g.drawImage(topTexture, 0, 0, size, half, null);
            }
        }
        if (frontTexture != null) {
            g.drawImage(frontTexture, (int)(size * 0.05), half, (int)(size * 0.72), half - 2, null);
        }
        if (sideTexture != null) {
            BufferedImage dark = new BufferedImage(sideTexture.getWidth(), sideTexture.getHeight(),
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D dg = dark.createGraphics();
            dg.drawImage(sideTexture, 0, 0, null);
            dg.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.65f));
            dg.setColor(new Color(0, 0, 0, 80));
            dg.fillRect(0, 0, dark.getWidth(), dark.getHeight());
            dg.dispose();
            g.drawImage(dark, (int)(size * 0.72), half, (int)(size * 0.23), half - 2, null);
        }
        g.setColor(new Color(0, 0, 0, 80));
        g.drawRect(0, 0, size - 1, size - 1);
        g.dispose();
        return result;
    }
}
