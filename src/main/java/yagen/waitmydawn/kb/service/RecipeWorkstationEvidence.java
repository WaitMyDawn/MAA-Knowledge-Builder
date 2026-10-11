package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import yagen.waitmydawn.kb.service.QaPipeline.RecipeMatch;
import yagen.waitmydawn.kb.agent.EntityAgent.AliasHit;
import java.util.*;

/** 自定义配方类型只在同模组目录中定位工作站，最多补充一跳机制证据。 */
final class RecipeWorkstationEvidence {
    private static final ObjectMapper JSON = new ObjectMapper();

    static List<AliasHit> select(String question, List<RecipeMatch> recipes,
                                 WebCatalogStore catalog, Set<String> acquiredUrls) {
        var result = new LinkedHashMap<String, AliasHit>();
        for (var recipe : recipes) {
            String type;
            try { type = JSON.readTree(recipe.recipeJson()).path("type").asText(); }
            catch (Exception invalidRecipe) { continue; }
            int colon = type.indexOf(':');
            if (colon < 1 || !type.substring(0, colon).equals(recipe.sourceMod())) continue;
            String machine = type.substring(colon + 1).toLowerCase(Locale.ROOT);
            for (var entry : catalog.entries(recipe.sourceMod(), 1)) {
                String english = entry.englishName();
                if (english == null || english.isBlank()) continue;
                String name = english.toLowerCase(Locale.ROOT).trim().replaceAll("[^a-z0-9]+", "_");
                if (name.length() < 6 || !(machine.equals(name) || machine.startsWith(name + "_"))) continue;
                if (acquiredUrls.contains(entry.url())) continue;
                result.putIfAbsent(entry.url(), new AliasHit(question + "；工作站的原料获取及加工机制",
                        entry.label(), entry.url(), recipe.sourceMod(), 1, "support"));
                if (result.size() == 2) return List.copyOf(result.values());
            }
        }
        return List.copyOf(result.values());
    }
}

