package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import yagen.waitmydawn.kb.model.WebCatalog;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 英文别名先查网页目录的中英对照，再退回命名空间/注册名推导。 */
class NameNormalizerTest {
    @TempDir Path temp;

    @Test void englishAliasUsesWebCatalogChineseNameBeforeRegistryComposition() {
        var db = DatabaseBuilder.atPath(temp.resolve("catalog-alias"));
        db.initDatabase();
        try {
            new WebCatalogStore(db).save(new WebCatalog(
                    List.of(new WebCatalog.Node("top", null, "irons_spellbooks",
                            "https://example.invalid/catalog", 1, "物品/方块", List.of("物品/方块"))),
                    List.of(new WebCatalog.Member("top", "https://example.invalid/cauldron",
                            "炼金锅(Alchemist Cauldron)", "炼金锅", "Alchemist Cauldron", "entry"))));
            String answer = "炼金坩埚（Alchemist Cauldron）";
            assertEquals("炼金锅（Alchemist Cauldron）",
                    NameNormalizer.apply(db, "p", answer, Set.of("irons_spellbooks"), Set.of()));
            assertEquals("炼金锅（Alchemist Cauldron）",
                    NameNormalizer.apply(db, "p", answer, Set.of(), Set.of("irons_spellbooks")));
        } finally {
            db.shutdown();
        }
    }

    @Test void ambiguousCatalogMatchIsNotRewritten() {
        var db = DatabaseBuilder.atPath(temp.resolve("catalog-ambiguous"));
        db.initDatabase();
        try {
            new WebCatalogStore(db).save(new WebCatalog(
                    List.of(new WebCatalog.Node("top", null, "alpha",
                                    "https://example.invalid/alpha", 1, "物品/方块", List.of("物品/方块")),
                            new WebCatalog.Node("top2", null, "beta",
                                    "https://example.invalid/beta", 1, "物品/方块", List.of("物品/方块"))),
                    List.of(new WebCatalog.Member("top", "https://example.invalid/a",
                                    "甲(Shared English)", "甲", "Shared English", "entry"),
                            new WebCatalog.Member("top2", "https://example.invalid/b",
                                    "乙(Shared English)", "乙", "Shared English", "entry"))));
            String answer = "某物（Shared English）";
            assertEquals(answer, NameNormalizer.apply(db, "p", answer, Set.of("alpha", "beta"), Set.of()));
        } finally {
            db.shutdown();
        }
    }
}
