package yagen.waitmydawn.kb.service;

import org.jsoup.nodes.Document;
import yagen.waitmydawn.kb.model.BuildRequest;
import yagen.waitmydawn.kb.model.KnowledgeContent;
import java.net.URI;
import java.util.*;

/** 用户网址只扩展站点定义的模组分类、整合包教程与正文图片。 */
public final class UrlBuildProcessor implements BuildSourceProcessor {
    private final WikiScraperService scraper;
    private final DocumentDirectoryProcessor documents;
    private final WebImageEnricher imageEnricher;
    private int prewarmPages;
    private boolean downloadImages=true;
    UrlBuildProcessor withoutImageDownloads(){downloadImages=false;return this;}
    UrlBuildProcessor withPrewarmPages(int count){prewarmPages=Math.max(0,Math.min(30,count));return this;}
    public UrlBuildProcessor(WikiScraperService scraper) { this(scraper, null); }
    void useResourceDatabase(yagen.waitmydawn.kb.model.DatabaseBuilder database){scraper.setResourceDatabase(database);}
    UrlBuildProcessor(WikiScraperService scraper, DocumentDirectoryProcessor documents) { this(scraper,documents,new WebImageEnricher()); }
    UrlBuildProcessor(WikiScraperService scraper,DocumentDirectoryProcessor documents,WebImageEnricher enricher){this.scraper=scraper;this.documents=documents;this.imageEnricher=enricher;}
    @Override public boolean supports(BuildRequest.Kind kind) { return kind == BuildRequest.Kind.URL; }
    void enrichExisting(BuildRequest.Source source,Context context)throws Exception {
        new GuideImageStepBackfill(context.database(),scraper.resourceDatabase()).run(source,context);
        new WebImageCompletion(scraper,imageEnricher).completeSource(source,context);
        String path=URI.create(source.location()).getPath();
        if(WebDocumentParser.isMcmod(source.location())&&path.matches("/class/[1-9][0-9]*\\.html")){
            String mod=knownMod(context,source.location());prewarm(source,mod==null?"mcmod:class:"+path.replaceAll("[^0-9]",""):mod,context);
        }
    }
    private void prewarm(BuildRequest.Source source,String catalogKey,Context context)throws Exception {
        if(prewarmPages==0)return;
        var details=new LinkedHashMap<String,WebCatalogStore.Entry>();
        for(var entry:new WebCatalogStore(context.database()).entries(catalogKey,4))if(entry.role().equals("entry"))details.putIfAbsent(entry.url(),entry);
        for(var category:WikiScraperService.McmodCategory.values())for(var entry:new WebCatalogStore(context.database()).entries(catalogKey,category.getId()))if(entry.role().equals("entry"))details.putIfAbsent(entry.url(),entry);
        int warmed=0;for(var detail:details.values()){if(warmed>=prewarmPages)break;context.checkCancelled();processDocument(source,detail.url(),context);report(context,"prewarm",++warmed,Math.min(prewarmPages,details.size()),"预取详情及正文图片："+detail.label());}
    }
    public void processDocument(BuildRequest.Source source,String location,Context context)throws Exception {
        processDocument(source,location,context,knownMod(context,source.location()),"网页资料");
    }
    void processDocument(BuildRequest.Source source,String location,Context context,String mod,String name)throws Exception {
        var page=scraper.fetchBuildResource(location,source.location(),false);
        if(page.status()!=200||page.bytes()==null)throw new IllegalStateException("网页文档获取失败 HTTP "+page.status());
        String type=Objects.toString(page.contentType(),"").toLowerCase(Locale.ROOT);
        if(!type.contains("html")) {
            String format=documentFormat(type,page.url());if(documents==null||format==null)throw new IllegalArgumentException("未支持的网页文档格式");
            documents.importBytes(location,location,format,page.bytes(),source,context,new HashMap<>());return;
        }
        if(page.bytes().length>4*1024*1024)throw new IllegalArgumentException("HTML 页面超过 4 MiB");
        ingest(source,location,WebDocumentParser.parse(page.document(),page.url()),name,mod,context);
    }

    @Override public void process(BuildRequest.Source source, Context context) throws Exception {
        String url = WebUrlPolicy.normalize(source.location());
        WikiScraperService.Resource resource;
        try(var scope=RetrievalTelemetry.resourceCause("BUILD_SOURCE_DOCUMENT")){resource=scraper.fetchBuildResource(url,null,true);}
        if (resource.status() != 200 || resource.bytes() == null) throw new IllegalStateException("网址资源获取失败 HTTP " + resource.status());
        String contentType = resource.contentType() == null ? "" : resource.contentType().toLowerCase(Locale.ROOT);
        if (!(contentType.contains("text/html") || contentType.contains("application/xhtml+xml"))) {
            String format = documentFormat(contentType, resource.url());
            if (documents == null || format == null) throw new IllegalArgumentException("不支持的网址资源格式");
            String path = URI.create(resource.url()).getPath();
            String title = path.substring(path.lastIndexOf('/') + 1);
            if (title.isBlank()) title = "网页文档." + format;
            context.content().setSourceScope(source.id(), resource.url(), "WEB_DOCUMENT", title, null);
            documents.importBytes(resource.url(), title, format, resource.bytes(), source, context, new HashMap<>());
            return;
        }
        if (resource.bytes().length > 4 * 1024 * 1024) throw new IllegalArgumentException("HTML 页面超过 4 MiB");
        Document main = resource.document();
        String path = URI.create(resource.url()).getPath();
        var parsed = WebDocumentParser.parse(main, resource.url());
        String name = parsed.title();
        if (WebDocumentParser.isMcmod(resource.url()) && path.matches("/modpack/[1-9][0-9]*\\.html")) {
            String id = path.replaceAll("[^0-9]", "");
            context.content().setSourceScope(source.id(), "mcmod:modpack:" + id, "MODPACK", name, null);
            ingest(source, resource.url(), parsed, name, null, context);
            var tutorials = new LinkedHashMap<>(McmodLinkDiscovery.tutorials(main, resource.url()));
            var visited = new LinkedHashSet<String>(); visited.add(resource.url());
            var pages = new ArrayDeque<>(McmodLinkDiscovery.tutorialPagination(main, resource.url()));
            while (!pages.isEmpty()) {
                context.checkCancelled();
                String next = pages.removeFirst();
                if (!visited.add(next)) continue;
                if (visited.size() > 100) throw new IllegalStateException("教程列表分页超过 100 页，拒绝发布不完整目录");
                var page = requirePage(next, resource.url());
                var document = page.document();
                McmodLinkDiscovery.tutorials(document, page.url()).forEach(tutorials::putIfAbsent);
                pages.addAll(McmodLinkDiscovery.tutorialPagination(document, page.url()));
            }
            context.result().tutorials += tutorials.size();
            int count = 0;
            for (var tutorial : tutorials.entrySet()) {
                context.checkCancelled();
                var page = requirePage(tutorial.getKey(), resource.url());
                ingest(source, page.url(), WebDocumentParser.parse(page.document(), page.url()), name, null, context);
                report(context, "tutorials", ++count, tutorials.size(), name + "：" + tutorial.getValue());
            }
            if (tutorials.isEmpty()) context.result().warnings.add(name + "：主页未发现关联教程");
        } else if (WebDocumentParser.isMcmod(resource.url()) && path.matches("/class/[1-9][0-9]*\\.html")) {
            String id = path.replaceAll("[^0-9]", "");
            String modId = knownMod(context, resource.url());
            String catalogKey = modId == null ? "mcmod:class:" + id : modId;
            if(context.refresh()) {
                var lifecycle=new SourceLifecycleStore(context.database());
                lifecycle.execute("DELETE FROM rag_web_category_member WHERE category_id IN (SELECT category_id FROM rag_web_category WHERE mod_id=?)",catalogKey);
                lifecycle.execute("DELETE FROM rag_web_category WHERE mod_id=?",catalogKey);
                lifecycle.execute("DELETE FROM rag_web_cache WHERE mod_id=?",catalogKey);
            }
            context.content().setSourceScope(source.id(), "mcmod:class:" + id, "MOD", name, modId);
            var result = scraper.scrapeModCatalog(catalogKey, id,
                    (current, total, category, item, items, status) -> report(context, "categories", current, total, category + "：" + status));
            context.database().saveWebPages(catalogKey, result.pages());
            new WebCatalogStore(context.database()).save(result.catalog());
            ingest(source, resource.url(), parsed, name, modId, context);
            var categories = McmodLinkDiscovery.categories(main, id, resource.url());
            if (result.pages().size() < categories.size() + 1)
                throw new IllegalStateException("模组分类页未完整获取，原基础库保留");
            for (var page : result.pages()) {
                if (page.url().equals(resource.url())) continue;
                var summary = new WebDocumentParser.Parsed(page.title(), "", "",
                        List.of(new WebDocumentParser.Block(page.content(), "分类目录", List.of())));
                ingest(source, page.url(), summary, name, modId, context);
            }
            prewarm(source,catalogKey,context);
        } else {
            context.content().setSourceScope(source.id(), resource.url(), "WEB", name, null);
            ingest(source, resource.url(), parsed, name, null, context);
        }
    }

    private WikiScraperService.Resource requirePage(String url, String referer) {
        var page = scraper.fetchBuildResource(url, referer, false);
        String type = page.contentType() == null ? "" : page.contentType().toLowerCase(Locale.ROOT);
        if (page.status() != 200 || page.bytes() == null)
            throw new IllegalStateException("网页获取失败 HTTP " + page.status() + "：" + url);
        if (!(type.contains("text/html") || type.contains("application/xhtml+xml")))
            throw new IllegalArgumentException("关联教程不是 HTML 页面：" + url);
        return page;
    }

    void ingest(BuildRequest.Source source, String url, WebDocumentParser.Parsed parsed,
                        String sourceName, String modId, Context context) throws Exception {
        try(var timingScope=BuildTrace.phase("web.document",url)) {

        context.checkCancelled();
        String documentId = BuildRequest.fingerprint(url);
        context.visitedDocuments().add(documentId);
        if (parsed.blocks().isEmpty()) throw new IllegalStateException("未识别到有效正文：" + url);
        String fullText = parsed.blocks().stream().map(b -> b.headingPath() + "\n" + b.text() + "\n" + b.images())
                .collect(java.util.stream.Collectors.joining("\n"));
        if (context.content().hasDocument(documentId)) {
            if(new SourceLifecycleStore(context.database()).strings("SELECT parser_version FROM rag_document_parser WHERE id=?",documentId).isEmpty() || context.refresh()&&(context.reparse()||!BuildRequest.fingerprint(fullText).equals(context.content().documentHash(documentId)))) {
                new SourceLifecycleStore(context.database()).clearDocumentContent(documentId);
            } else {
            context.result().reusedDocuments++;
            context.content().linkDocumentSource(documentId, source.id());
            if(!context.refresh())if(downloadImages)new WebImageCompletion(scraper,imageEnricher).complete(List.of(documentId),context);
            return;
            }
        }
        if(parsed.blocks().size()>20000) throw new IllegalArgumentException("网页原文块超过 20000");
        context.content().saveDocument(new KnowledgeContent.Document(documentId, source.id(), parsed.title(), url,
                BuildRequest.fingerprint(fullText), "text/html"));
        context.content().linkDocumentSource(documentId, source.id());
        context.content().setDocumentMetadata(documentId, parsed.author(), parsed.publishedAt());
        // 清理文本旧索引必须先于图片 OCR 入库，否则会一并删除同 URL 的新 OCR 向量。
        try(var c=context.database().getConnection();var p=c.prepareStatement("DELETE FROM rag_vectors WHERE source_path=?")){p.setString(1,url);p.executeUpdate();}
        var vectors = new ArrayList<TextChunker.TextChunk>();
        var rawBlocks=new ArrayList<KnowledgeContent.Block>();
        for(int ordinal=0;ordinal<parsed.blocks().size();ordinal++) {
            var block=parsed.blocks().get(ordinal);String text=block.text();
            if(text.isBlank() && !block.images().isEmpty()) {
                String previous=ordinal>0 && parsed.blocks().get(ordinal-1).lineage().equals(block.lineage())?parsed.blocks().get(ordinal-1).text():"";
                text=previous.isBlank()?"章节插图："+block.headingPath():previous;
            }
            var raw=new KnowledgeContent.Block(documentId+"/block/"+ordinal,documentId,block.headingPath(),block.lineage(),ordinal,null,
                    block.kind(),block.tableId(),text,block.text().isBlank()?"ADJACENT":"NATIVE");
            rawBlocks.add(raw);context.content().saveBlock(raw);
        }
        int chunkOrdinal=0;
        for(var indexed:StructuredChunker.split(rawBlocks,parsed.title(),modId,sourceName,"web",url,context.embedder())) {
            context.checkCancelled();var chunk=indexed.chunk();
            context.content().saveChunk(new KnowledgeContent.Chunk(chunk.id(),documentId,chunk.text(),indexed.heading(),indexed.page(),chunkOrdinal++));
            context.content().setChunkMethod(chunk.id(),indexed.method());vectors.add(chunk);
            for(String blockId:indexed.blockIds()) {
                context.content().linkBlock(chunk.id(),blockId);
            }
        }
        int imageOrdinal=0;
        // 先持久化完整引用清单，取消或失败后能够继续处理剩余图片。
        for(int ordinal=0;ordinal<parsed.blocks().size();ordinal++)for(var image:parsed.blocks().get(ordinal).images()){
            String occurrence=documentId+"/image/"+imageOrdinal++;
            context.content().saveImageReference(occurrence,documentId,image.url(),image.caption(),parsed.blocks().get(ordinal).headingPath(),ordinal,"DEFERRED","等待缓存正文图片");
            context.content().setImageStep(occurrence,image.stepText());
        }
        if(downloadImages)new WebImageCompletion(scraper,imageEnricher).complete(List.of(documentId),context);
        // 同一网页从 JAR 和网址进入时，用带图文定位的新块替换暂存库中的旧文本块。
        // 保持向量 chunk_id 与图文内容块一致，后续才能从命中块追溯图片。
        context.vectors().storeBatch(vectors, context.embedder());
        new SourceLifecycleStore(context.database()).parsedDocument(documentId,source.kind());
        context.result().embeddings += vectors.size();
        context.result().documents++;
        context.result().textChunks += vectors.size();
        report(context, "web", context.result().documents, 0, "已导入网页：" + parsed.title());
        }
    }

    private static String documentFormat(String type, String url) {
        if (type.contains("application/pdf")) return "pdf";
        if (type.contains("wordprocessingml")) return "docx";
        if (type.contains("application/msword")) return "doc";
        if (type.startsWith("text/plain")) return "txt";
        for (String format : List.of("png", "jpeg", "jpg", "gif", "webp"))
            if (type.startsWith("image/" + format)) return format;
        String path = URI.create(url).getPath().toLowerCase(Locale.ROOT);
        if (type.contains("application/octet-stream"))
            for (String format : List.of("pdf", "docx", "doc", "txt", "png", "jpg", "jpeg", "gif", "webp"))
                if (path.endsWith("." + format)) return format;
        return null;
    }

    private static String knownMod(Context context, String url) throws java.sql.SQLException {
        try (var c = context.database().getConnection(); var p = c.prepareStatement("SELECT w.mod_id FROM rag_web_cache w JOIN rag_mod_info m ON w.mod_id=m.mod_id WHERE w.url=? ORDER BY w.id")) {
            p.setString(1, url); try (var r = p.executeQuery()) { return r.next() ? r.getString(1) : null; }
        }
    }

    private static void report(Context c, String phase, int current, int total, String text) {
        if (c.progress() != null) c.progress().onProgress(phase, current, total, text);
    }
}
