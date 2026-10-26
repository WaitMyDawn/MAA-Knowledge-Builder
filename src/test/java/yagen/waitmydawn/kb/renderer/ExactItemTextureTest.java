package yagen.waitmydawn.kb.renderer;

import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExactItemTextureTest {
    @Test void differentNamespacesNeverSupplyAnIngredientOrOutputIcon() {
        var template=new BufferedImage(140,80,BufferedImage.TYPE_INT_ARGB);var poison=new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<16;y++)for(int x=0;x<16;x++)poison.setRGB(x,y,0xffff00ff);
        var textures=Map.of("wrong:widget",poison);
        var images=List.of(
                new CraftingRenderer().render("{\"type\":\"minecraft:crafting_shaped\",\"pattern\":[\"A\"],\"key\":{\"A\":{\"item\":\"right:widget\"}},\"result\":{\"id\":\"right:widget\"}}",template,textures),
                new FurnaceRenderer().render("{\"type\":\"minecraft:smelting\",\"ingredient\":{\"item\":\"right:widget\"},\"result\":{\"id\":\"right:widget\"}}",template,textures),
                new SmithingRenderer().render("{\"type\":\"minecraft:smithing_transform\",\"template\":{\"item\":\"right:widget\"},\"base\":{\"item\":\"right:widget\"},\"addition\":{\"item\":\"right:widget\"},\"result\":{\"id\":\"right:widget\"}}",template,textures));
        for(var image:images) {assertNotNull(image);for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)assertNotEquals(0xffff00ff,image.getRGB(x,y));}
    }
}
