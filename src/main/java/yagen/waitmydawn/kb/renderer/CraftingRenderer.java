package yagen.waitmydawn.kb.renderer;

import java.awt.image.BufferedImage;
import java.util.Map;

/** 兼容既有调用；配方槽位与数量统一由 RecipePlan 解释。 */
public class CraftingRenderer {
    public BufferedImage render(String recipeJson,BufferedImage template,Map<String,BufferedImage> textureMap) {
        try {return RecipeCardRenderer.renderBasic(recipeJson,textureMap);}catch(IllegalArgumentException e){return null;}
    }
}
