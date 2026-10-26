package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 从 JAR 的 data/{modId}/recipe/ 中提取配方 JSON。
 * 兼容 MC 1.20- 和 1.21+ 两种配方格式。
 */
public class RecipeExtractor {

    private static final Logger log = LoggerFactory.getLogger(RecipeExtractor.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    public List<ParsedRecipe> extract(Path jarPath, String modId) {
        List<ParsedRecipe> recipes = new ArrayList<>();

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            var recipePath = java.util.regex.Pattern.compile("data/([^/]+)/recipes?/(.+)\\.json");

            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();

                if (!name.endsWith(".json")) continue;
                if (!recipePath.matcher(name).matches()) continue;

                try (InputStream is = jar.getInputStream(entry)) {
                    byte[] bytes = is.readNBytes(2 * 1024 * 1024 + 1);
                    if (bytes.length > 2 * 1024 * 1024) throw new IOException("Recipe exceeds size limit");
                    String json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                    ParsedRecipe pr = parseRecipeJson(json, modId, name);
                    if (pr != null) {
                        recipes.add(pr);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Recipe extraction failed: " + jarPath.getFileName(), e);
        }

        log.info("Extracted {} recipes from {}", recipes.size(), jarPath.getFileName());
        return recipes;
    }

    private ParsedRecipe parseRecipeJson(String json, String modId, String fullPath) {
        try {
            JsonNode root = mapper.readTree(json);
            String type = root.path("type").asText("crafting");
            String recipeType = mapRecipeType(type);

            // Extract output item (supports MC 1.20- and 1.21+)
            String outputItem = null;
            int outputCount = 1;

            JsonNode result = root.path("result");
            if (!result.isMissingNode()) {
                if (result.isObject()) {
                    // 1.21+: {"id":"modid:item","count":1}
                    outputItem = result.path("id").asText(null);
                    if (outputItem == null) {
                        // 1.20-: {"item":"modid:item","count":1}
                        outputItem = result.path("item").asText(null);
                    }
                    outputCount = result.path("count").asInt(1);
                } else if (result.isTextual()) {
                    // 1.19-: "result":"modid:item"
                    outputItem = result.asText();
                    outputCount = root.path("count").asInt(1);
                } else if (result.isInt() || result.isNumber()) {
                    // numeric result is a count, item name from elsewhere
                    outputCount = result.asInt(1);
                }
            }

            String resource = fullPath.replaceFirst("^data/[^/]+/recipes?/", "").replaceFirst("\\.json$", "");
            String namespace = fullPath.split("/", 3)[1];
            return new ParsedRecipe(recipeType, outputItem, outputCount, json, modId, namespace + ":" + resource,
                    type, outputItem == null || outputItem.isBlank() ? "UNKNOWN_OUTPUT" : "PARSED");
        } catch (Exception e) {
            log.debug("Failed to parse recipe JSON: {} - {}", fullPath, e.getMessage());
            return null;
        }
    }

    private String mapRecipeType(String type) {
        String normalized = type.contains(":") ? type : "minecraft:" + type;
        return switch (normalized) {
            case "minecraft:crafting_shaped", "minecraft:crafting_shapeless" -> "crafting";
            case "minecraft:smelting" -> "furnace";
            case "minecraft:blasting" -> "blasting";
            case "minecraft:smoking" -> "smoking";
            case "minecraft:smithing_transform", "minecraft:smithing_trim" -> "smithing";
            case "minecraft:stonecutting" -> "stonecutting";
            case "minecraft:campfire_cooking" -> "campfire_cooking";
            default -> type;
        };
    }

    public record ParsedRecipe(
            String recipeType,
            String outputItem,
            int outputCount,
            String recipeJson,
            String sourceMod,
            String recipeId,
            String rawType,
            String parseStatus
    ) {}
}
