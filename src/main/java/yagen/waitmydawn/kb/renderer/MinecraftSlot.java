package yagen.waitmydawn.kb.renderer;
import java.awt.*;
/** 原版18像素槽位：暗上左沿、亮下右沿，两处对角使用内部灰色。 */
public final class MinecraftSlot {
    private MinecraftSlot(){}
    public static void draw(Graphics2D g,int x,int y,int scale){
        int size=18*scale;g.setColor(new Color(139,139,139));g.fillRect(x,y,size,size);
        g.setColor(new Color(55,55,55));g.fillRect(x,y,17*scale,scale);g.fillRect(x,y,scale,17*scale);
        g.setColor(Color.WHITE);g.fillRect(x+17*scale,y+scale,scale,17*scale);g.fillRect(x+scale,y+17*scale,17*scale,scale);
    }
}
