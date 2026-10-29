package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.config.AppConfig;
import yagen.waitmydawn.kb.model.BuildRequest;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import yagen.waitmydawn.kb.model.KnowledgeContent.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;

class MultiSourceBuildTest {
    @TempDir Path temp;

    @org.junit.jupiter.api.AfterEach void closeLogging() { yagen.waitmydawn.kb.config.LogSetup.shutdown(); }

    private AppConfig config() throws Exception {
        var config = AppConfig.forIsolatedRun(false);
        config.setDataDir(temp.resolve("data"));
        return config;
    }

    private DatabaseBuilder database(String name) {
        var db = DatabaseBuilder.atPath(temp.resolve(name));
        db.initDatabase(); new VectorStore(db, 384);
        return db;
    }

    private BuildRequest request(Path directory) {
        return new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY, directory.toString())));
    }

    private byte[] png() throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB), "png", out);
        return out.toByteArray();
    }

    @Test void canonicalSourceIdentityDeduplicatesFragmentsAndDefaultPorts() {
        var a = BuildRequest.Source.of(BuildRequest.Kind.URL, "HTTPS://WWW.MCMOD.CN:443/modpack/737.html#intro");
        var b = BuildRequest.Source.of(BuildRequest.Kind.URL, "https://www.mcmod.cn/modpack/737.html");
        assertEquals(a, b);
        assertEquals(1, new BuildRequest(List.of(a, b)).sources().size());
        assertThrows(IllegalArgumentException.class, () -> BuildRequest.Source.of(BuildRequest.Kind.URL, "file:///tmp/file"));
        assertThrows(IllegalArgumentException.class, () -> BuildRequest.Source.of(BuildRequest.Kind.URL, "https://user:password@example.com"));
        assertThrows(IllegalArgumentException.class, () -> new BuildRequest(List.of()));
    }

    @Test void textOnlyBuildPublishesSearchableContentAndRestoresDraft() throws Exception {
        var config = config(); var db = database("base");
        var docs = Files.createDirectories(temp.resolve("guides"));
        Files.writeString(docs.resolve("golem.txt"), "傀儡组装指南：使用组装台安装核心，然后为傀儡增加升级模块。");
        Files.createDirectories(docs.resolve("nested"));
        Files.writeString(docs.resolve("nested/maid.txt"), "女仆可以协助完成任务，具体功能以教程说明为准。");
        Files.createDirectories(docs.resolve("secrets"));
        Files.writeString(docs.resolve("secrets/ignored.txt"), "excluded fixture");
        Files.writeString(docs.resolve("ignored.xyz"), "unsupported fixture");
        var request = request(docs); var content = new KnowledgeContentStore(db);
        try (var embedder = new EmbeddingService(null)) {
            content.saveDraft(request);
            var result = new KnowledgeBuilder(config, db, embedder).build(RuntimeBuildFixtures.withSnapshot(request,config.getDataDir()), null);
            assertEquals(2, result.documents);
            assertEquals(1, result.warnings.size());
            assertEquals(request, content.draft().orElseThrow());
            assertEquals(Set.copyOf(RuntimeBuildFixtures.withSnapshot(request,config.getDataDir()).sources()), Set.copyOf(content.publishedRequest().orElseThrow().sources()));
            content.validate();
            var hits = new VectorStore(db, 384).search(embedder.embed("傀儡组装"), 5);
            assertEquals(2, hits.size());
            assertTrue(hits.stream().anyMatch(h -> h.chunkText().contains("组装台")));
            assertTrue(hits.stream().allMatch(h -> h.modId() == null));
            assertNotEquals("legacy", db.buildGeneration());
            assertDoesNotThrow(db::contentFingerprint);
        } finally { db.shutdown(); }
    }

    @Test void emptyUnsupportedAndCancelledBuildsRetainPublishedKnowledgeAndDraft() throws Exception {
        var config = config(); var db = database("base");
        var docs = Files.createDirectories(temp.resolve("guides"));
        Files.writeString(docs.resolve("guide.txt"), "保留的旧知识正文，介绍机器与配方。");
        try (var embedder = new EmbeddingService(null)) {
            var builder = new KnowledgeBuilder(config, db, embedder);
            var oldRequest = request(docs);
            builder.build(RuntimeBuildFixtures.withSnapshot(oldRequest,config.getDataDir()), null);
            String before = db.contentFingerprint();
            var urls = new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.URL, "https://www.mcmod.cn/modpack/737.html")));
            var content = new KnowledgeContentStore(db);
            content.saveDraft(urls);
            assertThrows(IllegalArgumentException.class, () -> new KnowledgeBuilder(config, db, embedder,
                    List.of(new DocumentDirectoryProcessor(config))).build(RuntimeBuildFixtures.withSnapshot(urls,config.getDataDir()), null));
            assertEquals(before, db.contentFingerprint());
            assertEquals(urls, content.draft().orElseThrow());
            assertEquals(Set.copyOf(RuntimeBuildFixtures.withSnapshot(oldRequest,config.getDataDir()).sources()), Set.copyOf(content.publishedRequest().orElseThrow().sources()));
            var empty = request(Files.createDirectories(temp.resolve("empty")));
            assertThrows(IllegalArgumentException.class, () -> builder.build(empty, null));
            assertEquals(before, db.contentFingerprint());
            assertThrows(CancellationException.class, () -> builder.build(RuntimeBuildFixtures.withSnapshot(oldRequest,config.getDataDir()), (phase, current, total, message) -> {
                if (phase.equals("documents")) Thread.currentThread().interrupt();
            }));
            Thread.interrupted();
            assertEquals(before, db.contentFingerprint());
        } finally { Thread.interrupted(); db.shutdown(); }
    }

    @Test void imagesAndOccurrencesKeepStableIdsAcrossPublicationAndFailedReplacement() throws Exception {
        var base = database("base"); var stage = database("stage");
        var store = new ImageAssetStore(temp);
        try {
            var first = store.put(png()); var second = store.put(png());
            assertEquals(first.id(), second.id());
            assertEquals(first.relativePath(), second.relativePath());
            var content = new KnowledgeContentStore(stage);
            var source = BuildRequest.Source.of(BuildRequest.Kind.URL, "https://www.mcmod.cn/modpack/737.html");
            content.saveSource(source);
            content.saveDocument(new Document("doc", source.id(), "教程", source.location(), BuildRequest.fingerprint("text"), "text/html"));
            content.saveChunk(new Chunk("chunk", "doc", "组装步骤", "组装", 1, 0));
            content.saveImage(first);
            content.saveOccurrence(new ImageOccurrence("figure1", "doc", first.id(), "组装前", "image.png", 1, 0));
            content.saveOccurrence(new ImageOccurrence("figure2", "doc", first.id(), "组装后", "image.png", 2, 1));
            content.saveLink(new ChunkImageLink("chunk", "figure1", "adjacent"));
            store.validate(content, stage);
            base.replaceBuildDataFrom(stage);
            store.validate(new KnowledgeContentStore(base), base);
            try (var c = base.getConnection(); var s = c.createStatement();
                 var r = s.executeQuery("SELECT caption FROM rag_image_occurrence ORDER BY ordinal")) {
                assertTrue(r.next()); assertEquals("组装前", r.getString(1));
                assertTrue(r.next()); assertEquals("组装后", r.getString(1));
            }
            String before = base.contentFingerprint();
            try (var c = stage.getConnection(); var s = c.createStatement()) { s.execute("DROP TABLE rag_chunk_image"); }
            assertThrows(IllegalStateException.class, () -> base.replaceBuildDataFrom(stage));
            assertEquals(before, base.contentFingerprint());
            Path imagePath = temp.resolve("assets/images").resolve(first.relativePath());
            Files.delete(imagePath);
            assertThrows(java.io.IOException.class, () -> store.validate(new KnowledgeContentStore(base), base));
        } finally { base.shutdown(); stage.shutdown(); }
    }

    @Test void invalidImageLinksAndEscapingAssetPathsAreRejected() throws Exception {
        var db = database("base"); var content = new KnowledgeContentStore(db);
        try {
            content.saveLink(new ChunkImageLink("missing", "missing", "adjacent"));
            assertThrows(IllegalStateException.class, content::validate);
            try (var c = db.getConnection(); var s = c.createStatement()) { s.execute("DELETE FROM rag_chunk_image"); }
            content.saveImage(new ImageAsset("bad", "../../escape.png", "image/png", 8, 8, null, null));
            assertThrows(java.io.IOException.class, () -> new ImageAssetStore(temp).validate(content, db));
            assertThrows(java.io.IOException.class, () -> new ImageAssetStore(temp).put(new byte[]{1, 2, 3}));
        } finally { db.shutdown(); }
    }

    @Test void processorCannotPublishMissingImagesAndSuccessfulImagesSurviveNextBuild() throws Exception {
        var config = config(); var db = database("base");
        var source = BuildRequest.Source.of(BuildRequest.Kind.URL, "https://example.com/guide");
        var request = new BuildRequest(List.of(source));
        var bytes = png();
        var corrupt = new java.util.concurrent.atomic.AtomicBoolean(false);
        BuildSourceProcessor processor = new BuildSourceProcessor() {
            @Override public boolean supports(BuildRequest.Kind kind) { return kind == BuildRequest.Kind.URL; }
            @Override public void process(BuildRequest.Source input, Context c) throws Exception {
                var image = c.images().put(bytes);
                c.content().saveDocument(new Document("doc", input.id(), "教程", input.location(), BuildRequest.fingerprint("body"), "text/html"));
                c.content().saveChunk(new Chunk("chunk", "doc", "傀儡组装步骤", null, null, 0));
                c.vectors().storeBatch(List.of(new TextChunker.TextChunk("chunk", "傀儡组装步骤", null, "教程", "web", input.location())), c.embedder());
                c.content().saveImage(image);
                c.content().saveOccurrence(new ImageOccurrence("figure", "doc", image.id(), "组装图", "image.png", null, 0));
                c.content().saveLink(new ChunkImageLink("chunk", "figure", "adjacent"));
                if (corrupt.get()) c.content().saveImage(new ImageAsset("missing", "00/missing.png", "image/png", 8, 8, null, null));
            }
        };
        try (var embedder = new EmbeddingService(null)) {
            var builder = new KnowledgeBuilder(config, db, embedder, List.of(processor));
            builder.build(RuntimeBuildFixtures.withSnapshot(request,config.getDataDir()), null);
            String before = db.contentFingerprint();
            corrupt.set(true);
            assertThrows(IllegalStateException.class, () -> builder.build(RuntimeBuildFixtures.withSnapshot(request,config.getDataDir()), null));
            assertEquals(before, db.contentFingerprint());
            Path image;
            try (var c = db.getConnection(); var s = c.createStatement(); var r = s.executeQuery("SELECT relative_path FROM rag_image_asset")) {
                assertTrue(r.next()); image = config.getDataDir().resolve("assets/images").resolve(r.getString(1));
            }
            var folder = Files.createDirectories(temp.resolve("next"));
            Files.writeString(folder.resolve("guide.txt"), "另一个资料集的有效正文。");
            new KnowledgeBuilder(config, db, embedder).build(RuntimeBuildFixtures.withSnapshot(request(folder),config.getDataDir()), null);
            assertTrue(Files.isRegularFile(image));
            assertEquals(1, new VectorStore(db, 384).count());
        } finally { db.shutdown(); }
    }
}
