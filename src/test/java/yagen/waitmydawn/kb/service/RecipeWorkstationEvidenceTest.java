package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.service.QaPipeline.RecipeMatch;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import yagen.waitmydawn.kb.model.WebCatalog;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RecipeWorkstationEvidenceTest {
    @TempDir Path temp;

    @Test void customRecipeAddsOnlyExistingSameModWorkstationAndSkipsVisitedPages() {
        var db = DatabaseBuilder.atPath(temp.resolve("workstation")); db.initDatabase();
        try {
            var catalog = new WebCatalogStore(db);
            String url = "https://www.mcmod.cn/item/699147.html";
            catalog.save(new WebCatalog(List.of(new WebCatalog.Node("machine", null, "irons_spellbooks",
                    "https://www.mcmod.cn/item/list/9966-1.html", 1, "方块", List.of("方块"))),
                    List.of(new WebCatalog.Member("machine", url, "炼金锅(Alchemist Cauldron)",
                            "炼金锅", "Alchemist Cauldron", "member"))));
            var recipes = List.of(new RecipeMatch("{\"type\":\"irons_spellbooks:alchemist_cauldron_empty\"}",
                    "irons_spellbooks:common_ink", "irons_spellbooks"));
            var hits = RecipeWorkstationEvidence.select("墨水怎么制作", recipes, catalog, Set.of());
            assertEquals(1, hits.size()); assertEquals(url, hits.getFirst().url());
            assertEquals("support", hits.getFirst().role());
            assertTrue(RecipeWorkstationEvidence.select("墨水怎么制作", recipes, catalog, Set.of(url)).isEmpty());
            var foreign = List.of(new RecipeMatch("{\"type\":\"other:alchemist_cauldron_empty\"}", "ink", "irons_spellbooks"));
            assertTrue(RecipeWorkstationEvidence.select("墨水", foreign, catalog, Set.of()).isEmpty());
        } finally { db.shutdown(); }
    }
}

