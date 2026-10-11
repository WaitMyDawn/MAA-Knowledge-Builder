package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class RecipeAndArchiveTest {
    @TempDir Path temp;
    @Test void fetchUrlsRejectPrivateAndUnexpectedDestinations() {
        WikiScraperService.validateFetchUrl("https://www.mcmod.cn/item/1.html");
        for (String url : new String[]{"http://www.mcmod.cn/", "https://127.0.0.1/", "https://mcmod.cn.evil.example/", "https://user@www.mcmod.cn/", "https://wiki.biligame.com:8443/"})
            assertThrows(IllegalArgumentException.class, () -> WikiScraperService.validateFetchUrl(url));
    }

    @Test void bothRecipeDirectoriesAndSameOutputVariantsAreRetained() throws Exception {
        Path jar = temp.resolve("mod.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String path : new String[]{"data/mod/recipes/planks_a.json", "data/mod/recipe/planks_b.json"}) {
                out.putNextEntry(new JarEntry(path));
                out.write("{\"type\":\"minecraft:crafting_shapeless\",\"result\":{\"item\":\"mod:planks\"}}".getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
            out.putNextEntry(new JarEntry("data/mod/recipe/custom.json"));
            out.write("{\"type\":\"mod:custom\"}".getBytes(StandardCharsets.UTF_8)); out.closeEntry();
        }
        var recipes = new RecipeExtractor().extract(jar, "mod");
        assertEquals(3, recipes.size());
        assertEquals(3, recipes.stream().map(RecipeExtractor.ParsedRecipe::recipeId).distinct().count());
        assertEquals(2, recipes.stream().filter(r -> "mod:planks".equals(r.outputItem())).count());
        var unknown = recipes.stream().filter(r -> r.recipeId().equals("mod:custom")).findFirst().orElseThrow();
        assertNull(unknown.outputItem()); assertEquals("UNKNOWN_OUTPUT", unknown.parseStatus());
    }
    @Test void traversalAbsoluteAndWindowsPathsAreRejected() throws Exception {
        assertEquals(temp.resolve("block/stone.png"), ArchivePaths.resolve(temp, "block/stone.png"));
        assertThrows(java.io.IOException.class, () -> ArchivePaths.resolve(temp, "block/../../escape.png"));
        assertThrows(java.io.IOException.class, () -> ArchivePaths.resolve(temp, "C:/escape.png"));
        assertThrows(java.io.IOException.class, () -> ArchivePaths.resolve(temp, "block\\..\\escape.png"));
    }
}
