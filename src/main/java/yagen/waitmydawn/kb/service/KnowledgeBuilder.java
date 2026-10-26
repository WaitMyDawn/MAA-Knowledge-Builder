package yagen.waitmydawn.kb.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yagen.waitmydawn.kb.config.AppConfig;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import yagen.waitmydawn.kb.model.ModEntry;
import yagen.waitmydawn.kb.model.BuildRequest;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 知识库构建编排器:
 *   Phase A-E: JAR 提取 (纹理/配方/文本 → 向量)
 *   Phase F:   基线 MC Wiki 抓取 (Crafting/Smelting/... 约20页)
 *   Phase G:   模组 MC百科 抓取 (按 modId 搜索)
 */
public class KnowledgeBuilder {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBuilder.class);

    private final AppConfig config;
    private final DatabaseBuilder db;
    private final JarScannerService scanner;
    private final ModMetadataParser parser;
    private final TextureExtractor textureExtractor;
    private final RecipeExtractor recipeExtractor;
    private final TextChunker chunker;
    private final EmbeddingService embedder;
    private final VectorStore vectorStore;
    private final WikiScraperService wikiScraper;
    private final Map<String, String> allEntityMappings = new LinkedHashMap<>();
    private Map<String, ModEntry> previousMods = Map.of();
    private Map<String, ModEntry> scannedMods = Map.of();
    private Path diagnosticDir;
    private PublicKnowledgeCache publicCache;
    private final List<BuildSourceProcessor> sourceProcessors;

    public KnowledgeBuilder(AppConfig config, DatabaseBuilder db) {
        this(config, db, initializedEmbedder(config));
    }

    public KnowledgeBuilder(AppConfig config, DatabaseBuilder db, EmbeddingService embedder) {
        this(config, db, embedder, null);
    }

    public KnowledgeBuilder(AppConfig config, DatabaseBuilder db, EmbeddingService embedder,
                            List<BuildSourceProcessor> sourceProcessors) {
        this(config,db,embedder,sourceProcessors,new WikiScraperService());
    }

    KnowledgeBuilder(AppConfig config, DatabaseBuilder db, EmbeddingService embedder,
                     List<BuildSourceProcessor> sourceProcessors, WikiScraperService scraper) {
        this.config = config;
        this.db = db;
        this.wikiScraper=scraper;

        this.scanner = new JarScannerService();
        this.parser = new ModMetadataParser();
        this.textureExtractor = new TextureExtractor(config);
        this.recipeExtractor = new RecipeExtractor();
        this.chunker = new TextChunker();
        this.embedder = embedder;
        this.vectorStore = new VectorStore(db, embedder.getDimension());
        this.publicCache = new PublicKnowledgeCache(db);
        wikiScraper.setPublicCache(publicCache);
        wikiScraper.setResourceDatabase(db);
        var documents = new DocumentDirectoryProcessor(config);
        this.sourceProcessors = sourceProcessors == null
                ? List.of(documents, new UrlBuildProcessor(wikiScraper, documents))
                : List.copyOf(sourceProcessors);
    }

    private static EmbeddingService initializedEmbedder(AppConfig config) {
        var service = new EmbeddingService(config);
        service.initModel(config.getEmbeddingModelName());
        return service;
    }

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(String phase, int cur, int total, String message);
    }

    public VectorStore getVectorStore() { return vectorStore; }

    public void setScannedMods(List<ModEntry> entries) {
        var selected=new LinkedHashMap<String,ModEntry>();
        if(entries!=null) for(var entry:entries) selected.put(entry.getModId(),entry);
        scannedMods=Map.copyOf(selected);
    }

    public BuildResult build(Path modFolder, ProgressCallback progressCallback) {
        return build(BuildRequest.jars(modFolder), progressCallback);
    }

    public BuildResult build(BuildRequest request, ProgressCallback progressCallback) {
        Objects.requireNonNull(request);
        checkCancelled();
        var iconPacks=new LinkedHashMap<String,NativeIconPack.Pack>();
        for(var source:request.sources()) if(source.kind()==BuildRequest.Kind.DOCUMENT_DIRECTORY && NativeIconPack.detected(Path.of(source.location()))) {
            try {iconPacks.put(source.id(),NativeIconPack.read(Path.of(source.location())));}
            catch(java.io.IOException e){throw new IllegalArgumentException("MKB-Export 预检查失败，原库保留："+e.getMessage(),e);}
        }
        boolean append=request.mode()==BuildRequest.Mode.APPEND;
        boolean iconsOnly=iconPacks.size()==request.sources().size();
        if(append && !iconsOnly) VectorStore.verifyProfile(db,embedder.profile(),false);
        String targetFingerprint=append?db.contentFingerprint():null;
        for (var source : request.sources()) {
            if (source.kind() == BuildRequest.Kind.JAR_DIRECTORY) {
                if (!java.nio.file.Files.isDirectory(Path.of(source.location())) || scanner.scan(Path.of(source.location())).isEmpty())
                    throw new IllegalArgumentException("No JAR files found; previous knowledge base retained");
            } else if (sourceProcessors.stream().noneMatch(p -> p.supports(source.kind()))) {
                throw new IllegalArgumentException("尚未接通此来源的内容处理器：" + source.kind() + "；已保存来源，原库保留");
            }
        }
        long started = System.currentTimeMillis();
        String generation = "build-" + UUID.randomUUID();
        Path stagingPath = config.getDataDir().resolve("staging-" + UUID.randomUUID());
        DatabaseBuilder staging = DatabaseBuilder.atPath(stagingPath);
        try {
            staging.initDatabase();
            new VectorStore(staging,embedder.getDimension());
            if(append) staging.replaceBuildDataFrom(db);
            String originalFingerprint=append?staging.contentFingerprint():null;
            int originalVectors=append?new VectorStore(staging,embedder.getDimension()).count():0;
            var candidate = new KnowledgeBuilder(config, staging, embedder, sourceProcessors,wikiScraper);
            candidate.publicCache=publicCache;
            candidate.wikiScraper.setPublicCache(publicCache);
            candidate.wikiScraper.setResourceDatabase(db);
            var oldMods = new LinkedHashMap<String, ModEntry>();
            for (var mod : db.findAllModEntries()) oldMods.put(mod.getModId(), mod);
            scannedMods.forEach((id,entry) -> {
                if(!append || !oldMods.containsKey(id)) oldMods.put(id,entry);
            });
            candidate.previousMods = oldMods;
            candidate.textureExtractor.setGeneration(generation);
            candidate.diagnosticDir = config.getTexturesDir().resolve(generation);
            java.nio.file.Files.createDirectories(candidate.diagnosticDir);
            var jars = request.sources().stream().filter(s -> s.kind() == BuildRequest.Kind.JAR_DIRECTORY)
                    .map(s -> Path.of(s.location())).toList();
            BuildResult result = candidate.buildInto(jars, append, (phase, cur, total, message) -> {
                if (!"done".equals(phase)) report(progressCallback, phase, cur, total, message);
            });
            var content = new KnowledgeContentStore(staging);
            var images = new ImageAssetStore(config.getDataDir());
            for (var source : request.sources()) {
                checkCancelled();
                if(append && source.kind()==BuildRequest.Kind.URL && content.publishedRequest().stream()
                        .flatMap(r -> r.sources().stream()).anyMatch(s -> s.id().equals(source.id()))) {
                    result.reusedSources++;
                    report(progressCallback,"reuse",result.reusedSources,0,"跳过已导入网址："+source.location());
                    continue;
                }
                content.saveSource(source);
                if(iconPacks.containsKey(source.id())) {
                    new ItemIconStore(staging).importPack(source,iconPacks.get(source.id()),new BuildSourceProcessor.Context(staging,content,images,candidate.vectorStore,embedder,progressCallback,result));
                    continue;
                }
                if (source.kind() != BuildRequest.Kind.JAR_DIRECTORY) {
                    var processor = sourceProcessors.stream().filter(p -> p.supports(source.kind())).findFirst().orElseThrow();
                    processor.process(source, new BuildSourceProcessor.Context(staging, content, images,
                            candidate.vectorStore, embedder, progressCallback, result));
                }
            }
            if (candidate.vectorStore.count() == 0 && result.parsed == 0 && !new ItemIconStore(staging).hasUsableIcons())
                throw new IllegalStateException("没有可发布的有效内容；原基础库保留");
            try(var c=staging.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM rag_document d WHERE EXISTS(SELECT 1 FROM rag_content_chunk c WHERE c.document_id=d.id) AND NOT EXISTS(SELECT 1 FROM rag_content_block b WHERE b.document_id=d.id)")) {
                r.next();if(r.getInt(1)>0) result.warnings.add("复用了 "+r.getInt(1)+" 篇旧分块资料；完整标题与段落扩展需要对目标基础库执行完整重建。");
            }
            checkCancelled();
            images.validate(content, staging);
            if (result.deferredWebImages > 0) result.warnings.add("未缓存的网页图片：" + result.deferredWebImages + "；原始地址与处理状态已保留");
            checkCancelled();
            staging.healthCheck();
            checkCancelled();
            result.embeddings = candidate.vectorStore.count();
            result.addedEmbeddings=result.embeddings-originalVectors;
            if(append && originalFingerprint.equals(staging.contentFingerprint())) {
                result.durationMs=System.currentTimeMillis()-started;
                report(progressCallback,"done",1,1,"没有新增内容，基础库保持不变");
                return result;
            }
            if(!append || !iconsOnly && (result.parsed>0 || result.addedEmbeddings!=0 || result.documents>0 || result.tutorials>0))staging.setBuildGeneration(generation);
            if(append && !targetFingerprint.equals(db.contentFingerprint()))
                throw new IllegalStateException("构建期间目标基础库发生变化，请重新执行补充构建");
            db.replaceBuildDataFrom(staging);
            result.durationMs = System.currentTimeMillis() - started;
            try {
                report(progressCallback, "done", request.sources().size(), request.sources().size(), "Build published: " + result.embeddings + " vectors, "+result.nativeIcons+" imported icons");
            } catch (RuntimeException e) { log.warn("Build published, but progress listener failed", e); }
            return result;
        } catch (java.util.concurrent.CancellationException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("构建失败，原基础库保留：" + e.getMessage(), e);
        } finally {
            try { staging.shutdown(); } catch (Exception e) { log.warn("Staging shutdown failed", e); }
            try {
                java.nio.file.Files.deleteIfExists(Path.of(stagingPath + ".mv.db"));
                java.nio.file.Files.deleteIfExists(Path.of(stagingPath + ".trace.db"));
            } catch (Exception e) { log.warn("Staging cleanup failed", e); }
        }
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("Knowledge build cancelled");
    }

    private BuildResult buildInto(List<Path> modFolders, boolean append, ProgressCallback progressCallback) {
        final int warmLimit=Math.max(0,Math.min(30,Integer.getInteger("maa.build.prewarmPages",config.getPrewarmPages())));
        BuildResult result = new BuildResult();
        long timeStamp0 = System.currentTimeMillis();

        // ===== Overwrite mode: clear previous build data =====
        if(!append) db.clearBuildData();

        // ===== Phase A-E: JAR extraction =====
        report(progressCallback, "scan", 0, 1, "Scanning JAR files...");
        List<Path> jars = modFolders.stream().flatMap(folder -> scanner.scan(folder).stream()).distinct().toList();
        result.totalJars = jars.size();
        var importedMods=new ArrayList<ModEntry>();
        var jarIndex=new JarBuildIndex(db);

        for (int i = 0; i < jars.size(); i++) {
            checkCancelled();
            Path jar = jars.get(i);
            String jarHash;
            try { jarHash=JarBuildIndex.hash(jar); }
            catch(java.io.IOException e) {throw new IllegalStateException("JAR 内容读取失败："+jar,e);}
            ModEntry entry = parser.parse(jar);
            if (entry == null) { result.skipped++; continue; }
            var status=jarIndex.inspect(entry,jarHash);
            if(status!=JarBuildIndex.Status.NEW) {
                if(status==JarBuildIndex.Status.DUPLICATE) {
                    try {
                        var resources=new RecipeResourceStore(db).importJar(jar,entry.getModId(),config.getDataDir());
                        result.itemTagFiles+=resources.tags();result.itemNames+=resources.names();result.staticItemIcons+=resources.flatIcons();
                    }catch(Exception failure){throw new IllegalStateException("已有 JAR 的配方资源补建失败",failure);}
                }
                if(!append && status==JarBuildIndex.Status.CONFLICT)
                    throw new IllegalStateException("同一构建包含不同版本的模组："+entry.getModId());
                result.skipped++;
                if(status==JarBuildIndex.Status.CONFLICT) result.conflicts++;
                String message=entry.getModId()+"："+status;
                report(progressCallback,"reuse",i+1,jars.size(),message);
                if(status!=JarBuildIndex.Status.DUPLICATE) result.warnings.add(message);
                continue;
            }
            result.parsed++;

            String modId = entry.getModId();
            String modName = entry.getDisplayName() != null ? entry.getDisplayName() : modId;
            String slug = entry.getSlug() != null ? entry.getSlug() : modId;

            report(progressCallback, "parse", i + 1, jars.size(), modId);

            // Extract textures once, use for counting + saving
            Map<String, Map<String, String>> allTex = textureExtractor.extract(jar, modId, slug);
            result.textures += allTex.values().stream().mapToInt(Map::size).sum();
            TextureExtractor.dumpMapping(allTex, diagnosticDir);

            // Entity texture scanning — extract registry names from textures/entity/
            report(progressCallback, "entity", i + 1, jars.size(), "Entities: " + modId);
            Map<String, String> entityRegs = EntityTextureScanner.scan(jar, modId);
            result.entities += entityRegs.size();
            saveEntityRegistries(entityRegs, modId);
            // Append to global entity map for export
            allEntityMappings.putAll(entityRegs);

            report(progressCallback, "recipe", i + 1, jars.size(), "Recipes: " + modId);
            List<RecipeExtractor.ParsedRecipe> recipes = recipeExtractor.extract(jar, modId);
            result.recipes += recipes.size();
            try {
                var resources = new RecipeResourceStore(db).importJar(jar, modId, config.getDataDir());
                result.itemTagFiles += resources.tags(); result.itemNames += resources.names(); result.staticItemIcons += resources.flatIcons();
            } catch (Exception failure) { throw new IllegalStateException("配方标签与图标资源解析失败：" + modId, failure); }

            // Text chunks
            List<TextChunker.TextChunk> chunks = new ArrayList<>();
            report(progressCallback, "text", i + 1, jars.size(), "Lang: " + modId);
            chunks.addAll(extractLangText(jar, modId, modName));
            if (entry.getDescription() != null && !entry.getDescription().isBlank())
                chunks.addAll(chunker.fromDescription(entry.getDescription(), modId, modName));
            for (var r : recipes) {
                if (!"PARSED".equals(r.parseStatus())) continue;
                var rc = chunker.fromRecipe(r.recipeJson(), modId, modName, r.outputItem());
                if (rc != null) rc = new TextChunker.TextChunk(r.recipeId(), rc.text(), modId, modName, "recipe", r.recipeId());
                if (rc != null) chunks.add(rc);
            }
            result.textChunks += chunks.size();

            if (!chunks.isEmpty()) {
                report(progressCallback, "embed", i + 1, jars.size(), "Embedding " + chunks.size() + " chunks: " + modId);
                vectorStore.storeBatch(chunks, embedder);
                result.embeddings += chunks.size();
            }

            // Preserve slug from previous scan if not set during parse
            if (entry.getSlug() == null) {
                ModEntry existing = previousMods.get(entry.getModId());
                if (existing != null && existing.getSlug() != null) {
                    entry.setSlug(existing.getSlug());
                    entry.setModrinthUrl(existing.getModrinthUrl());
                    entry.setSource(existing.getSource());
                }
            }
            try {
                if(!jarHash.equals(JarBuildIndex.hash(jar))) throw new IllegalStateException("构建期间 JAR 内容发生变化："+jar);
            } catch(java.io.IOException e) {throw new IllegalStateException("JAR 内容校验失败："+jar,e);}
            db.saveModEntry(entry);
            saveTextures(allTex, modId, slug);
            saveRecipes(recipes, entry.getLoader(), entry.getVersion());
            jarIndex.save(entry,jarHash);
            importedMods.add(entry);
        }

        // ===== Phase F: Baseline MC Wiki =====
        if (!importedMods.isEmpty() && (!append || !hasBaselineWiki())) {
            int total = WikiScraperService.BASELINE_PAGES.length;
            List<TextChunker.TextChunk> wikiChunks = new ArrayList<>();
            int idx = 0;
            for (String page : WikiScraperService.BASELINE_PAGES) {
                idx++;
                report(progressCallback, "wiki", idx, total, "MC Wiki: " + page);
                WikiScraperService.WebPage wp = wikiScraper.fetchMcWiki(page);
                if (wp != null) {
                    wikiChunks.addAll(chunker.fromWebPage(wp.content(), "minecraft", "Minecraft", "mcwiki", wp.url()));
                }
                try { Thread.sleep(200); } catch (Exception ignored) {} // Be polite to wiki
            }
            if (!wikiChunks.isEmpty()) {
                vectorStore.storeBatch(wikiChunks, publicCache.embeddings(wikiChunks,embedder),embedder.profile());
                result.embeddings += wikiChunks.size();
                log.info("Baseline Wiki: {} chunks from {} pages", wikiChunks.size(), idx);
            }
        }

        // ===== Phase G: MC百科 mod scraping =====
        List<ModEntry> mods = importedMods;
        // Deduplicate by modId (prevent double-scrape from old entries with different KEY)
        Map<String, ModEntry> uniqueMods = new LinkedHashMap<>();
        for (ModEntry m : mods) {
            uniqueMods.putIfAbsent(m.getModId(), m);
        }
        List<ModEntry> deduped = new ArrayList<>(uniqueMods.values());

        report(progressCallback, "scrape", 0, deduped.size(), "mcmod.cn scraping...");
        int sIdx = 0;
        for (ModEntry modEntry : deduped) {
            sIdx++;
            if ("minecraft".equals(modEntry.getModId()) || "vanilla".equals(modEntry.getLoader())) continue;
            report(progressCallback, "scrape", sIdx, deduped.size(), "mcmod: " + modEntry.getModId());
            final int cur = sIdx;
            final int total = deduped.size();
            final String mid = modEntry.getModId();
            var scrape = wikiScraper.scrapeModCatalog(modEntry.getModId(),
                            (catCur, catTotal, catName, itemCur, itemTotal, status) -> {
                        String detail;
                        if (status.startsWith("error:")) {
                            detail = String.format("mcmod:%s [%d/%d] %s — %s",
                                    mid, catCur, catTotal, catName, status);
                        } else if (status.equals("blocked")) {
                            detail = String.format("mcmod:%s [%d/%d] %s (0 items) BLOCKED?",
                                    mid, catCur, catTotal, catName);
                        } else if (status.equals("404")) {
                            detail = String.format("mcmod:%s [%d/%d] %s (not found)",
                                    mid, catCur, catTotal, catName);
                        } else if (itemCur < 0) {
                            detail = String.format("mcmod:%s [%d/%d] %s (%d items) ✓",
                                    mid, catCur, catTotal, catName, itemTotal);
                        } else if (itemCur == 0) {
                            detail = String.format("mcmod:%s [%d/%d] %s (0/%d items)",
                                    mid, catCur, catTotal, catName, itemTotal);
                        } else {
                            detail = String.format("mcmod:%s [%d/%d] %s (%d/%d items)",
                                    mid, catCur, catTotal, catName, itemCur, itemTotal);
                        }
                        report(progressCallback, "scrape", cur, total, detail);
                    });
            List<WikiScraperService.WebPage> pages = scrape.pages();
            new WebCatalogStore(db).save(scrape.catalog());
            if (!pages.isEmpty()) {
                // 持久化网页 (含 subWebPage 映射), 供 Q&A 阶段增量抓取
                db.saveWebPages(modEntry.getModId(), pages);

                // 把 "中文名(英文名)" → URL 的映射建成 entity_alias 向量块。
                // 检索时以中文/英文主体问题命中别名块，从而直接拿到真实网页 URL。
                Map<String, String> aliasMap = pages.stream()
                        .map(WikiScraperService.WebPage::subWebPage)
                        .filter(m -> m != null && !m.isEmpty())
                        .findFirst()
                        .orElse(Map.of());
                List<TextChunker.TextChunk> aliasChunks = new ArrayList<>();
                for (var entry : aliasMap.entrySet()) {
                    String label = entry.getKey().trim();
                    String id = "alias/" + modEntry.getModId() + "/"
                            + label.replaceAll("[^\\p{L}\\p{N}]+", "_");
                    aliasChunks.add(new TextChunker.TextChunk(
                            id, label, modEntry.getModId(),
                            modEntry.getDisplayName() != null
                                    ? modEntry.getDisplayName() : modEntry.getModId(),
                            "entity_alias", entry.getValue()));
                }
                if (!aliasChunks.isEmpty()) {
                    vectorStore.storeBatch(aliasChunks, embedder);
                    result.embeddings += aliasChunks.size();
                    log.info("mcmod: {} alias chunks for {}", aliasChunks.size(), modEntry.getModId());
                }

                List<TextChunker.TextChunk> modChunks = new ArrayList<>();
                for (var p : pages) modChunks.addAll(chunker.fromWebPage(p.content(), modEntry.getModId(),
                        modEntry.getDisplayName() != null ? modEntry.getDisplayName() : modEntry.getModId(), p.source(), p.url()));
                if (!modChunks.isEmpty()) {
                    vectorStore.storeBatch(modChunks, publicCache.embeddings(modChunks,embedder),embedder.profile());
                    result.embeddings += modChunks.size();
                    log.info("mcmod: {} chunks for {}", modChunks.size(), modEntry.getModId());
                }
            }
            // 小预算预热覆盖不同子分类的生物；完整目录仍保留，不把预热范围当成全部。
            var warmed=new LinkedHashMap<String,WebCatalogStore.Entry>();
            var hintUrls=KnowledgeHints.forMod(modEntry.getModId()).stream().map(KnowledgeHints.Relation::url).toList();
            var paths=new HashSet<List<String>>();
            for(var entry:new WebCatalogStore(db).entries(modEntry.getModId(),4)) {
                if(entry.role().equals("entry") && paths.add(entry.path())) warmed.putIfAbsent(entry.url(),entry);
                if(warmed.size()>=warmLimit) break;
            }
            for(var hint:KnowledgeHints.forMod(modEntry.getModId())) {
                if(warmLimit==0) break;
                for(var category:WikiScraperService.McmodCategory.values()) for(var entry:new WebCatalogStore(db).entries(modEntry.getModId(),category.getId()))
                    if(hint.url().equals(entry.url())) warmed.putIfAbsent(entry.url(),entry);
            }
            int warm=0, prepared=0, skipped=0;
            if(warmLimit==0) warmed.clear();
            for(var entry:warmed.values()) {
                if(warm++>=warmLimit+hintUrls.size()) break;
                checkCancelled();
                report(progressCallback,"prewarm",warm,warmed.size(),"预取常用详情（"+warm+"/"+warmed.size()+"）："+entry.label());
                var fetched=wikiScraper.fetchPageWithRegistries(entry.url(),"mcmod");
                if(fetched==null) { skipped++; continue; }
                prepared++;
                var chunks=chunker.fromWebPage(fetched.page().content(),modEntry.getModId(),modEntry.getDisplayName(),"mcmod",entry.url());
                vectorStore.storeBatch(chunks,publicCache.embeddings(chunks,embedder),embedder.profile());
                result.embeddings+=chunks.size();
            }
            report(progressCallback,"prewarm",warmed.size(),warmed.size(),warmLimit==0?"已关闭常用详情预取":"详情预取完成：成功 "+prepared+" 页，跳过/失败 "+skipped+" 页（站点冷却期间不继续访问）");
            try { Thread.sleep(500); } catch (Exception ignored) {}
        }

        result.durationMs = System.currentTimeMillis() - timeStamp0;

        // Export entity_map.json for debugging
        if (!allEntityMappings.isEmpty()) {
            try {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                        .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
                mapper.writeValue(diagnosticDir.resolve("entity_map.json").toFile(), allEntityMappings);
            } catch (Exception ex) { log.warn("Entity map export failed", ex); }
        }

        report(progressCallback, "done", jars.size(), jars.size(),
                String.format("Done! %d mods, %d textures, %d entities, %d recipes, %d chunks, %d vectors (%.1fs)",
                        result.parsed, result.textures, result.entities, result.recipes, result.textChunks,
                        result.embeddings, result.durationMs / 1000.0));
        return result;
    }

    private boolean hasBaselineWiki() {
        try(var c=db.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT 1 FROM rag_vectors WHERE chunk_type='mcwiki' LIMIT 1")) {
            return r.next();
        } catch(java.sql.SQLException e) {throw new IllegalStateException("原版知识读取失败",e);}
    }

    private List<TextChunker.TextChunk> extractLangText(Path jarPath, String modId, String modName) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            for (String p : new String[]{
                    "assets/" + modId + "/lang/zh_cn.json",
                    "assets/" + modId + "/lang/en_us.json",
                    "assets/" + modId.toLowerCase() + "/lang/zh_cn.json",
                    "assets/" + modId.toLowerCase() + "/lang/en_us.json"}) {
                JarEntry je = jar.getJarEntry(p);
                if (je != null) try (InputStream is = jar.getInputStream(je)) {
                    byte[] bytes = is.readNBytes(2 * 1024 * 1024 + 1);
                    if (bytes.length > 2 * 1024 * 1024) throw new IllegalArgumentException("Language resource too large: " + p);
                    return chunker.fromLangJson(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), modId, modName, p);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) { log.debug("Lang: {} - {}", modId, e.getMessage()); }
        return Collections.emptyList();
    }

    private void saveTextures(Map<String, Map<String, String>> allTex, String modId, String slug) {
        try (var c = db.getConnection(); var ps = c.prepareStatement(
                "MERGE INTO rag_texture_cache (registry_name, local_path, mod_source) KEY (registry_name) VALUES (?, ?, ?)")) {
            for (var catMap : allTex.values()) {
                for (var e : catMap.entrySet()) {
                    ps.setString(1, e.getKey());  // full registry name like "minecraft:acacia_door"
                    ps.setString(2, e.getValue()); // absolute local file path
                    ps.setString(3, slug);
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        } catch (Exception ex) { throw new IllegalStateException("Save textures failed", ex); }
    }

    private void saveEntityRegistries(Map<String, String> entities, String modId) {
        if (entities.isEmpty()) return;
        try (var c = db.getConnection(); var ps = c.prepareStatement(
                "MERGE INTO rag_entity_registry (registry_name, mod_id, source_path) KEY (registry_name) VALUES (?, ?, ?)")) {
            for (var e : entities.entrySet()) {
                ps.setString(1, e.getKey());
                ps.setString(2, modId);
                ps.setString(3, e.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (Exception ex) { throw new IllegalStateException("Save entity registries failed", ex); }
    }

    private void saveRecipes(List<RecipeExtractor.ParsedRecipe> recipes, String loader, String v) {
        try (var c = db.getConnection(); var ps = c.prepareStatement(
                "MERGE INTO rag_recipe (recipe_type, output_item, output_count, recipe_data, source_mod, loader, mod_version, recipe_id, raw_type, parse_status, source_type) KEY (recipe_id, source_mod) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'JAR_PARSE')")) {
            for (var r : recipes) {
                ps.setString(1, r.recipeType()); ps.setString(2, r.outputItem()); ps.setInt(3, r.outputCount());
                ps.setString(4, r.recipeJson()); ps.setString(5, r.sourceMod()); ps.setString(6, loader); ps.setString(7, v);
                ps.setString(8, r.recipeId()); ps.setString(9, r.rawType()); ps.setString(10, r.parseStatus()); ps.addBatch();
            }
            ps.executeBatch();
        } catch (Exception ex) { throw new IllegalStateException("Save recipes failed", ex); }
    }

    private void report(ProgressCallback cb, String phase, int cur, int total, String msg) {
        if (cb != null) cb.onProgress(phase, cur, total, msg);
    }

    public static class BuildResult {
        public int totalJars, parsed, skipped;
        public int reusedSources, reusedDocuments, conflicts, addedEmbeddings;
        public int documents, tutorials, webImages, webImageAttempts, deferredWebImages;
        public int localImages, ocrAttempts, ocrImages, visionAttempts, visionImages;
        public int nativeIconPacks,nativeIcons,failedNativeIcons,reusedIconPacks;
        public int itemTagFiles,itemNames,staticItemIcons;
        public final List<String> warnings = new ArrayList<>();
        public int textures, entities, recipes, textChunks, embeddings;
        public long durationMs;
    }
}
