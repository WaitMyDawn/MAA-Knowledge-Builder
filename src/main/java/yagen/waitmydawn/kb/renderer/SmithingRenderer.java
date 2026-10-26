package yagen.waitmydawn.kb.renderer;

import java.awt.image.BufferedImage;
import java.util.Map;

/** 无精确产物的纹饰配方保持动态结果，不借用默认装备图标。 */
public class SmithingRenderer {
    public BufferedImage render(String recipeJson,BufferedImage template,Map<String,BufferedImage> textureMap) {
        try {return RecipeCardRenderer.renderBasic(recipeJson,textureMap);}catch(IllegalArgumentException e){return null;}
    }
}
