package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yagen.waitmydawn.kb.config.AppConfig;
import yagen.waitmydawn.kb.dto.ClassificationResult;
import yagen.waitmydawn.kb.dto.RetrievalResult;
import yagen.waitmydawn.kb.renderer.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * 图片渲染调度器：根据配方/结构决定是否需要生成图片。
 */
public class RendererService {

    private static final Logger log = LoggerFactory.getLogger(RendererService.class);
    private final AppConfig config;
    private final ObjectMapper mapper = new ObjectMapper();

    private final CraftingRenderer craftingRenderer;
    private final FurnaceRenderer furnaceRenderer;
    private final SmithingRenderer smithingRenderer;
    private final MultiblockRenderer multiblockRenderer;
    private final TemplateManager templateManager;

    public RendererService(AppConfig config, TemplateManager templateManager) {
        this.config = config;
        this.templateManager = templateManager;
        this.craftingRenderer = new CraftingRenderer(templateManager, config);
        this.furnaceRenderer = new FurnaceRenderer(templateManager, config);
        this.smithingRenderer = new SmithingRenderer(templateManager, config);
        this.multiblockRenderer = new MultiblockRenderer();
    }

    /**
     * 预渲染：根据配方类型生成合成图/熔炉图/多方块图。
     * @return placeholder → 本地文件路径 映射
     */
    public Map<String, String> preRender(ClassificationResult classification, RetrievalResult retrieval) {
        Map<String, String> imagePaths = new HashMap<>();
        List<String> texturePaths = new ArrayList<>(retrieval.getTexturePaths().values());

        if (!retrieval.getRecipeJsons().isEmpty()) {
            for (int i = 0; i < retrieval.getRecipeJsons().size(); i++) {
                try {
                    String recipeJson = retrieval.getRecipeJsons().get(i);
                    JsonNode recipe = mapper.readTree(recipeJson);
                    String recipeType = recipe.path("type").asText("minecraft:crafting_shaped");
                    var resultNode = recipe.path("result");
                    String outputItem = resultNode.path("id").asText(null);
                    if (outputItem == null) outputItem = resultNode.path("item").asText(null);
                    if (outputItem == null) outputItem = resultNode.asText(null);
                    if (outputItem == null) outputItem = "item_" + i;

                    BufferedImage image = null;
                    String imageType = null;

                    if (recipeType.contains("crafting")) {
                        image = craftingRenderer.render(recipeJson, texturePaths);
                        imageType = "crafting";
                    } else if (recipeType.contains("smelting") || recipeType.contains("blasting") || recipeType.contains("smoking")) {
                        image = furnaceRenderer.render(recipeJson, texturePaths);
                        imageType = "furnace";
                    } else if (recipeType.contains("smithing")) {
                        image = smithingRenderer.render(recipeJson, texturePaths);
                        imageType = "smithing";
                    }

                    if (image != null && imageType != null) {
                        String safeName = outputItem.replace(':', '_');
                        String filename = safeName + ".png";
                        File outDir = config.getGeneratedDir().resolve(imageType).toFile();
                        outDir.mkdirs();
                        File outFile = new File(outDir, filename);
                        ImageIO.write(image, "PNG", outFile);

                        String placeholder = "[img:" + imageType + "/" + outputItem + "]";
                        imagePaths.put(placeholder, outFile.getAbsolutePath());
                    }
                } catch (Exception e) {
                    log.warn("图片生成失败: {}", e.getMessage());
                }
            }
        }

        if (!retrieval.getMultiblockJsons().isEmpty()) {
            for (int i = 0; i < retrieval.getMultiblockJsons().size(); i++) {
                try {
                    String mbJson = retrieval.getMultiblockJsons().get(i);
                    if (!mbJson.trim().startsWith("{")) continue;
                    BufferedImage image = multiblockRenderer.render(mbJson, texturePaths);
                    if (image != null) {
                        String filename = "multiblock_" + i + ".png";
                        File outDir = config.getGeneratedDir().resolve("multiblock").toFile();
                        outDir.mkdirs();
                        ImageIO.write(image, "PNG", new File(outDir, filename));
                        String placeholder = "[img:multiblock/" + i + "]";
                        imagePaths.put(placeholder, new File(outDir, filename).getAbsolutePath());
                    }
                } catch (Exception e) {
                    log.warn("多方块图生成失败: {}", e.getMessage());
                }
            }
        }

        return imagePaths;
    }
}
