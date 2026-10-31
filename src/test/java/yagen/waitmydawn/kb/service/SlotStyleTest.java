package yagen.waitmydawn.kb.service;
import org.junit.jupiter.api.Test;import java.awt.*;import java.awt.image.BufferedImage;import yagen.waitmydawn.kb.renderer.*;import static org.junit.jupiter.api.Assertions.*;
class SlotStyleTest {
 @Test void vanillaCornersAndAdjacentSlotsHaveNoBackgroundGap(){var image=new BufferedImage(180,90,2);var g=image.createGraphics();try{g.setColor(Color.MAGENTA);g.fillRect(0,0,180,90);MinecraftSlot.draw(g,0,0,5);MinecraftSlot.draw(g,90,0,5);}finally{g.dispose();}assertEquals(new Color(139,139,139).getRGB(),image.getRGB(85,0));assertEquals(new Color(139,139,139).getRGB(),image.getRGB(0,85));assertEquals(Color.WHITE.getRGB(),image.getRGB(89,45));assertEquals(new Color(55,55,55).getRGB(),image.getRGB(90,45));for(int y=0;y<90;y++)for(int x=0;x<180;x++)assertNotEquals(Color.MAGENTA.getRGB(),image.getRGB(x,y));}
 @Test void bundledRoundedFontHasChineseAndUsesRealFontMetrics(){var font=CardFont.at(Font.PLAIN,22);assertTrue(font.getFontName().contains("Medium"));assertTrue(font.canDisplay('池'));assertTrue(font.canDisplay('剑'));}
}
