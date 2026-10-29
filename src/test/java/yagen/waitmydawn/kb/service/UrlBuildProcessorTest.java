package yagen.waitmydawn.kb.service;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.config.AppConfig;
import yagen.waitmydawn.kb.model.BuildRequest;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UrlBuildProcessorTest {
    @Test void imagesInOneParagraphKeepTheirOwnOperationText() {
        var parsed=WebDocumentParser.parse(Jsoup.parse("<article><h1>组装</h1><p>先制作胚料。<img src='one.png'>随后放入切石机切成身体。<img src='two.png'>最后安装核心。</p></article>"),"https://example.com/guide");
        var images=parsed.blocks().getFirst().images();assertEquals(2,images.size());
        assertTrue(images.getFirst().stepText().contains("先制作胚料"));assertFalse(images.getFirst().stepText().contains("切石机"));
        assertTrue(images.getLast().stepText().contains("切石机"));assertFalse(images.getLast().stepText().contains("先制作"));
    }
    @TempDir Path temp;
    @org.junit.jupiter.api.AfterEach void closeLogging() { yagen.waitmydawn.kb.config.LogSetup.shutdown(); }

    private static class FakeWeb extends WikiScraperService {
        final Map<String, Resource> pages = new HashMap<>();
        final List<String> requests = new ArrayList<>();
        void page(String url, String html) { pages.put(url, new Resource(200, html.getBytes(StandardCharsets.UTF_8), "text/html", url, "etag", null)); }
        @Override public Resource fetchResource(String url, String referer, boolean image, Map<String, String> headers) {
            requests.add(url); return pages.getOrDefault(url, new Resource(404, null, "text/html", url, null, null));
        }
    }

    private DatabaseBuilder db(String name) {
        var db = DatabaseBuilder.atPath(temp.resolve(name)); db.initDatabase(); new VectorStore(db, 384); return db;
    }
    private String fixture() throws Exception {
        return Files.readString(Path.of("src/test/resources/web/modpack-737-links.html"));
    }
    private long count(DatabaseBuilder db, String sql) throws Exception {
        try (var c = db.getConnection(); var s = c.createStatement(); var r = s.executeQuery(sql)) { r.next(); return r.getLong(1); }
    }

    @Test void discoversSixTutorialsFromBothRegionsAndIgnoresCommentRecommendations() throws Exception {
        var doc = Jsoup.parse(fixture());
        var links = McmodLinkDiscovery.tutorials(doc, "https://www.mcmod.cn/modpack/737.html");
        assertEquals(Set.of("4727", "4984", "5267", "5355", "5454", "5557"), links.keySet().stream()
                .map(url -> java.net.URI.create(url).getPath().replaceAll("[^0-9]", "")).collect(java.util.stream.Collectors.toSet()));
        assertEquals(6, links.size());
    }

    @Test void modCategoriesUseOnlyPresentLinksIncludingUnknownCategoryIds() {
        var doc = Jsoup.parse("""
                <a href='/item/list/3318-1.html'>物品</a><a href='//www.mcmod.cn/item/list/3318-4.html'>生物</a>
                <a href='/item/list/3318-987.html'>新增分类</a><a href='/item/list/3318-0.html'>全部</a>
                <a href='/item/list/3318-4.html#x'>重复</a><a href='/item/list/999-2.html'>其他模组</a>
                <a href='https://evil.example/item/list/3318-5.html'>外部地址</a>
                """);
        assertEquals(List.of(1, 4, 987), McmodLinkDiscovery.categories(doc, "3318", "https://www.mcmod.cn/class/3318.html")
                .stream().map(McmodLinkDiscovery.Category::id).toList());
    }

    @Test void parserKeepsLazyImagesTheirHeadingsAndOnlyArticleContent() {
        var doc = Jsoup.parse("""
                <div class='postname'><h1>傀儡教程</h1></div><div class='post-user-name'>作者甲</div>
                <div class='post-content common-text'><div class='text'>
                <p><span class='common-text-title'>组装</span></p><p>安装核心。</p>
                <p><img src='/loading.gif' data-src='//i.mcmod.cn/guide.webp' alt='核心位置'></p>
                <p>接着升级。</p><table><tr><th>材料</th><th>数量</th></tr><tr><td>铁锭</td><td>8</td></tr></table>
                </div></div><div class='comment'><p>错误教程信息</p><img src='/wrong.png'></div>
                """);
        var parsed = WebDocumentParser.parse(doc, "https://www.mcmod.cn/post/1.html");
        assertEquals("傀儡教程", parsed.title()); assertEquals("作者甲", parsed.author());
        assertTrue(parsed.blocks().stream().allMatch(b -> b.heading().equals("组装")));
        var images = parsed.blocks().stream().flatMap(b -> b.images().stream()).toList();
        assertEquals(1, images.size()); assertEquals("https://i.mcmod.cn/guide.webp", images.getFirst().url());
        assertTrue(parsed.blocks().stream().anyMatch(b -> b.text().contains("铁锭 | 8")));
        assertFalse(parsed.blocks().toString().contains("错误教程信息"));
        assertTrue(WebDocumentParser.parse(Jsoup.parse("<div class='comment'><p>只有评论</p></div>"), "https://www.mcmod.cn/post/1.html").blocks().isEmpty());
    }

    @Test void buildImportsMainAndSixFullTutorialsAndReusesRawCacheOnRebuild() throws Exception {
        var config = AppConfig.forIsolatedRun(false); config.setDataDir(temp.resolve("data"));
        var db = db("base"); var web = new FakeWeb(); web.setResourceDatabase(db);
        String main = "https://www.mcmod.cn/modpack/737.html";
        web.page(main, fixture());
        var links = McmodLinkDiscovery.tutorials(Jsoup.parse(fixture()), main);
        for (String url : links.keySet()) web.page(url, "<div class='postname'>教程</div><div class='post-content common-text'><div class='text'><p>教程完整内容：傀儡组装与升级。</p><h2>结尾</h2><p>末尾段落同样需要索引。</p></div></div>");
        var request = new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.URL, main)));
        try (var embedder = new EmbeddingService(null)) {
            var builder = new KnowledgeBuilder(config, db, embedder, List.of(new UrlBuildProcessor(web)));
            var result = builder.build(RuntimeBuildFixtures.withSnapshot(request,config.getDataDir()), null);
            assertEquals(6, result.tutorials); assertEquals(7, result.documents);
            assertEquals(7, count(db, "SELECT COUNT(*) FROM rag_document"));
            assertEquals(6, count(db, "SELECT COUNT(*) FROM rag_content_chunk WHERE content LIKE '%末尾段落%'"));
            assertEquals(1, count(db, "SELECT COUNT(*) FROM rag_knowledge_source WHERE scope_id='mcmod:modpack:737' AND display_name='龙之冒险：新征程' AND mod_id IS NULL"));
            assertEquals(7, web.requests.size());
            builder.build(RuntimeBuildFixtures.withSnapshot(request,config.getDataDir()), null); assertEquals(7, web.requests.size());
            assertEquals(7, count(db, "SELECT COUNT(*) FROM rag_document"));
        } finally { db.shutdown(); }
    }

    @Test void failedTutorialDoesNotReplacePreviouslyPublishedKnowledge() throws Exception {
        var config = AppConfig.forIsolatedRun(false); config.setDataDir(temp.resolve("data"));
        var db = db("base"); var web = new FakeWeb(); web.setResourceDatabase(db);
        String main = "https://www.mcmod.cn/modpack/737.html";
        web.page(main, fixture());
        var request = new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.URL, main)));
        String before = db.contentFingerprint();
        try (var embedder = new EmbeddingService(null)) {
            assertThrows(IllegalStateException.class, () -> new KnowledgeBuilder(config, db, embedder, List.of(new UrlBuildProcessor(web))).build(RuntimeBuildFixtures.withSnapshot(request,config.getDataDir()), null));
            assertEquals(before, db.contentFingerprint());
            assertEquals(0, count(db, "SELECT COUNT(*) FROM rag_document"));
        } finally { db.shutdown(); }
    }

    @Test void cachedConditionalRequestAndCooldownDoNotPublishStaleData() throws Exception {
        var db = db("base"); String url = "https://example.com/article";
        var headers = new java.util.concurrent.atomic.AtomicReference<Map<String, String>>();
        var status = new java.util.concurrent.atomic.AtomicInteger(200);
        var scraper = new WikiScraperService() {
            @Override public Resource fetchResource(String address, String referer, boolean image, Map<String,String> requestHeaders) {
                headers.set(requestHeaders);
                return new Resource(status.get(), status.get() == 200 ? "<p>正文</p>".getBytes(StandardCharsets.UTF_8) : null,
                        "text/html", address, "etag", null);
            }
        };
        try {
            var cache = new WebResourceCache(db, scraper); cache.fetch(url, null, false);
            try (var c = db.getConnection(); var s = c.createStatement()) { s.executeUpdate("UPDATE rag_public_resource SET fetched_at=0"); }
            status.set(304); assertEquals(200, cache.fetch(url, null, false).status());
            assertEquals("etag", headers.get().get("If-None-Match"));
            try (var c = db.getConnection(); var s = c.createStatement()) { s.executeUpdate("UPDATE rag_public_resource SET fetched_at=0"); }
            status.set(429); assertEquals(429, cache.fetch(url, null, false).status());
        } finally { db.shutdown(); }
    }

    @Test void publicResourcePolicyRejectsCredentialsFilesAndPrivateAddresses() {
        assertThrows(IllegalArgumentException.class, () -> WebUrlPolicy.normalize("file:///etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> WebUrlPolicy.normalize("https://user:pass@example.com"));
        assertThrows(IllegalArgumentException.class, () -> WebUrlPolicy.normalize("http://localhost/path"));
        assertThrows(IllegalArgumentException.class, () -> WebUrlPolicy.validateResolved("http://127.0.0.1/path"));
        assertThrows(IllegalArgumentException.class, () -> WebUrlPolicy.validateResolved("http://192.168.0.1/path"));
    }

    @Test void documentDecodingRespectsHttpCharsetWithoutMetaTag() throws Exception {
        var resource = new WikiScraperService.Resource(200,
                "<article><p>中文教程正文。</p></article>".getBytes(java.nio.charset.Charset.forName("GB18030")),
                "text/html; charset=GB18030", "https://example.com/article", null, null);
        assertTrue(WebDocumentParser.parse(resource.document(), resource.url()).blocks().getFirst().text().contains("中文教程正文"));
    }

    @Test void webpAssetsAndImageLinksSurvivePublicationAndBudgetedReferencesRemainVisible() throws Exception {
        var config = AppConfig.forIsolatedRun(false); config.setDataDir(temp.resolve("data"));
        var db = db("base"); var web = new FakeWeb(); web.setResourceDatabase(db);
        String url = "https://example.com/guide";
        web.page(url, "<article><h1>机器教程</h1><h2>组装</h2><p>安装核心</p><p><img data-src='/core.webp' alt='核心位置'></p><p><img src='/missing.png' alt='升级位置'></p></article>");
        web.pages.put("https://example.com/core.webp", new WikiScraperService.Resource(200,
                Files.readAllBytes(Path.of("src/test/resources/web/tiny.webp")), "image/webp", "https://example.com/core.webp", null, null));
        String budget = System.getProperty("maa.build.maxWebImages"); System.setProperty("maa.build.maxWebImages", "1");
        try (var embedder = new EmbeddingService(null)) {
            var source = BuildRequest.Source.of(BuildRequest.Kind.URL, url);
            var request = new BuildRequest(List.of(source));
            var result = new KnowledgeBuilder(config, db, embedder, List.of(new UrlBuildProcessor(web))).build(RuntimeBuildFixtures.withSnapshot(request,config.getDataDir()), null);
            assertEquals(1, result.webImages); assertEquals(1, result.deferredWebImages);
            assertEquals(2, count(db, "SELECT COUNT(*) FROM rag_image_asset WHERE media_type='image/png' AND relative_path LIKE '%.png'"));
            assertTrue(count(db, "SELECT COUNT(*) FROM rag_chunk_image l JOIN rag_vectors v ON l.chunk_id=v.chunk_id") > 0);
            assertEquals(1, count(db, "SELECT COUNT(*) FROM rag_web_image_reference WHERE status='DEFERRED'"));
            assertFalse(web.requests.contains("https://example.com/missing.png"));
            new ImageAssetStore(config.getDataDir()).validate(new KnowledgeContentStore(db), db);
        } finally {
            if (budget == null) System.clearProperty("maa.build.maxWebImages"); else System.setProperty("maa.build.maxWebImages", budget);
            db.shutdown();
        }
    }

    @Test void modScraperVisitsOnlyDiscoveredCategoriesAndKeepsAliasDirectory() {
        var web = new FakeWeb();
        String main = "https://www.mcmod.cn/class/3318.html";
        web.page(main, "<div class='class-text'><div class='class-title'><h3>Alex 的生物</h3></div><div class='common-text'><p>模组介绍。</p></div></div><a href='/item/list/3318-4.html'>生物</a>");
        web.page("https://www.mcmod.cn/item/list/3318-4.html", "<table><tr><th class='item-list-type-left'>生物</th><td class='item-list-type-right'><a href='/item/1.html' data-cn='水滴鱼' data-en='Blobfish'>水滴鱼</a></td></tr></table>");
        var result = web.scrapeModCatalog("alexsmobs", "3318", null);
        assertEquals(List.of(main, "https://www.mcmod.cn/item/list/3318-4.html"), web.requests);
        assertEquals(2, result.pages().size());
        assertEquals("https://www.mcmod.cn/item/1.html", result.pages().getFirst().subWebPage().get("水滴鱼(Blobfish)"));
        assertFalse(result.catalog().members().isEmpty());
    }

    @Test void explicitPagesDoNotFollowArbitraryLinksAndRepeatedDocumentsKeepAllSourceMemberships() throws Exception {
        var config = AppConfig.forIsolatedRun(false); config.setDataDir(temp.resolve("data"));
        var db = db("base"); var web = new FakeWeb(); web.setResourceDatabase(db);
        String main = "https://www.mcmod.cn/modpack/1.html", tutorial = "https://www.mcmod.cn/post/7.html";
        web.page(main, "<div class='class-title'><h3>样本整合包</h3></div><div class='class-text'><div class='common-text'><p>主页介绍。</p></div></div><div class='post-block'><a href='/post/7.html'>教程</a></div>");
        web.page(tutorial, "<div class='post-content common-text'><p>完整教程正文。</p><a href='/post/8.html'>其他教程</a></div>");
        var request = new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.URL, main), BuildRequest.Source.of(BuildRequest.Kind.URL, tutorial)));
        try (var embedder = new EmbeddingService(null)) {
            new KnowledgeBuilder(config, db, embedder, List.of(new UrlBuildProcessor(web))).build(RuntimeBuildFixtures.withSnapshot(request,config.getDataDir()), null);
            assertEquals(2, count(db, "SELECT COUNT(*) FROM rag_document"));
            assertEquals(3, count(db, "SELECT COUNT(*) FROM rag_document_source"));
            assertEquals(2, web.requests.size());
            assertFalse(web.requests.contains("https://www.mcmod.cn/post/8.html"));
        } finally { db.shutdown(); }
    }

    @Test void urlModReplacesLegacyJarPageVectorsAndKeepsRealModIdentity() throws Exception {
        var config = AppConfig.forIsolatedRun(false); config.setDataDir(temp.resolve("data"));
        var db = db("base"); var web = new FakeWeb(); web.setResourceDatabase(db);
        String url = "https://www.mcmod.cn/class/3318.html";
        web.page(url, "<div class='class-title'><h3>Alex 的生物</h3></div><div class='class-text'><div class='common-text'><p>模组完整介绍。</p></div></div>");
        BuildSourceProcessor legacy = new BuildSourceProcessor() {
            @Override public boolean supports(BuildRequest.Kind kind) { return kind == BuildRequest.Kind.DOCUMENT_DIRECTORY; }
            @Override public void process(BuildRequest.Source source, Context c) {
                var mod = new yagen.waitmydawn.kb.model.ModEntry(); mod.setModId("alexsmobs"); c.database().saveModEntry(mod);
                c.database().saveWebPages("alexsmobs", List.of(new WikiScraperService.WebPage("模组", url, "旧正文", "mcmod", Map.of())));
                c.vectors().storeBatch(List.of(new TextChunker.TextChunk("legacy", "旧正文", "alexsmobs", "模组", "mcmod", url)), c.embedder());
            }
        };
        var request = new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY, temp.toString()),
                BuildRequest.Source.of(BuildRequest.Kind.URL, url)));
        try (var embedder = new EmbeddingService(null)) {
            new KnowledgeBuilder(config, db, embedder, List.of(legacy, new UrlBuildProcessor(web))).build(RuntimeBuildFixtures.withSnapshot(request,config.getDataDir()), null);
            assertEquals(0, count(db, "SELECT COUNT(*) FROM rag_vectors WHERE chunk_id='legacy'"));
            assertEquals(1, count(db, "SELECT COUNT(*) FROM rag_knowledge_source WHERE scope_id='mcmod:class:3318' AND mod_id='alexsmobs'"));
            assertEquals(count(db, "SELECT COUNT(*) FROM rag_content_chunk"), count(db, "SELECT COUNT(*) FROM rag_vectors"));
            assertEquals(0, count(db, "SELECT COUNT(*) FROM rag_vectors WHERE mod_id IS NULL OR mod_id<>'alexsmobs'"));
        } finally { db.shutdown(); }
    }
}
