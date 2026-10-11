package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import yagen.waitmydawn.kb.model.ModEntry;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class StorageIntegrityTest {
    @TempDir Path temp;
    private DatabaseBuilder database(String name) {
        var database = DatabaseBuilder.atPath(temp.resolve(name)); database.initDatabase();
        new VectorStore(database, 384); return database;
    }
    private void mod(DatabaseBuilder database, String id) {
        var entry = new ModEntry(); entry.setModId(id); entry.setLoader("fabric"); entry.setMcVersion("1.21.1");
        database.saveModEntry(entry);
    }

    @Test void boundedSearchMatchesFullSortAcrossDatabases() {
        var base = database("base"); var other = database("other");
        var embedder = new EmbeddingService(null);
        try {
            var all = new java.util.ArrayList<VectorStore.SearchResult>();
            for (var database : List.of(base, other)) {
                var chunks = java.util.stream.IntStream.range(0, 80).mapToObj(i ->
                        new TextChunker.TextChunk(database == base ? "a" + i : "b" + i,
                                "stone furnace item" + i, "minecraft", "Minecraft", "description", "source")).toList();
                var store = new VectorStore(database, 384); store.storeBatch(chunks, embedder);
                all.addAll(store.search(embedder.embed("stone furnace item42"), 100));
            }
            var expected = all.stream().map(VectorStore.SearchResult::score).sorted(java.util.Comparator.reverseOrder()).limit(7).toList();
            var actual = VectorStore.searchAcross(List.of(base, other), embedder.embed("stone furnace item42"), 7, embedder.profile());
            assertEquals(expected, actual.stream().map(VectorStore.SearchResult::score).toList());
            assertTrue(VectorStore.searchAcross(List.of(base), embedder.embed("stone"), 0).isEmpty());
        } finally { base.shutdown(); other.shutdown(); }
    }

    @Test void failedPublicationRollsBackAllPreviouslyReplacedTables() throws Exception {
        var base = database("base"); var stage = database("stage");
        try {
            mod(base, "old"); mod(stage, "new");
            try (var c = stage.getConnection(); var s = c.createStatement()) { s.execute("DROP TABLE rag_vectors"); }
            assertThrows(IllegalStateException.class, () -> base.replaceBuildDataFrom(stage));
            assertEquals("old", base.findAllModEntries().getFirst().getModId());
        } finally { base.shutdown(); stage.shutdown(); }
    }

    @Test void publicationReplacesManifestAndCarriesEmbeddingProfileAndGeneration() {
        var base = database("base"); var stage = database("stage");
        try {
            mod(base, "old"); mod(stage, "new"); stage.setBuildGeneration("build-test");
            var embedder = new EmbeddingService(null);
            new VectorStore(stage, 384).storeBatch(List.of(new TextChunker.TextChunk("new/chunk", "new corpus text", "new", "New", "description", "source")), embedder);
            base.replaceBuildDataFrom(stage);
            assertEquals(List.of("new"), base.findAllModEntries().stream().map(ModEntry::getModId).toList());
            assertEquals("build-test", base.buildGeneration());
            var vectors = new VectorStore(base, 384); assertEquals(1, vectors.count());
            vectors.verifyProfile(embedder.profile(), false);
            assertThrows(IllegalStateException.class, () -> vectors.verifyProfile("different-model", false));
        } finally { base.shutdown(); stage.shutdown(); }
    }

    @Test void legacyVectorsRequireRebuildAndBatchWritesAreIdempotent() throws Exception {
        var db = database("base");
        try {
            var vectors = new VectorStore(db, 384); var embedder = new EmbeddingService(null);
            var chunks = List.of(new TextChunker.TextChunk("id", "enough content for vector", "mod", "Mod", "description", "url"));
            vectors.storeBatch(chunks, embedder); vectors.storeBatch(chunks, embedder);
            assertEquals(1, vectors.count());
            try (var c = db.getConnection(); var s = c.createStatement()) { s.execute("DELETE FROM rag_vector_profile"); }
            assertThrows(IllegalStateException.class, () -> vectors.verifyProfile(embedder.profile(), false));
        } finally { db.shutdown(); }
    }

    @Test void switchingSessionsAndClosingDoesNotEraseKnowledge() {
        var base = database("base"); var manager = new MultiDBManager(temp, base);
        try {
            var embedder = new EmbeddingService(null);
            var a = manager.createIncrementalDB("a", 384);
            a.getVectorStore().storeBatch(List.of(new TextChunker.TextChunk("a", "knowledge belongs to A", "mod", "Mod", "description", "a")), embedder);
            manager.setCurrentSessionId("b"); assertEquals("rag_data_b", manager.getCurrentIncDB().getId());
            manager.setCurrentSessionId("a"); assertEquals(1, manager.getCurrentIncDB().getVectorStore().count());
            manager.close(); manager.setCurrentSessionId("a");
            assertEquals(1, manager.getCurrentIncDB().getVectorStore().count());
            assertTrue(manager.deleteBySessionId("a")); assertFalse(java.nio.file.Files.exists(a.getDbFile()));
        } finally { manager.close(); base.shutdown(); }
    }
}
