package yagen.waitmydawn.kb.renderer;

import java.awt.image.BufferedImage;
import java.util.Map;

/** 熔炼、切石卡片与其余配方使用相同的材料和数量语义。 */
public class FurnaceRenderer {
    public BufferedImage render(String recipeJson,BufferedImage template,Map<String,BufferedImage> textureMap) {
        try {return RecipeCardRenderer.renderBasic(recipeJson,textureMap);}catch(IllegalArgumentException e){return null;}
    }
}
