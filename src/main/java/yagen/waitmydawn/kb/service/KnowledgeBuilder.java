package yagen.waitmydawn.kb.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yagen.waitmydawn.kb.config.AppConfig;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import yagen.waitmydawn.kb.model.ModEntry;

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
    private final WikiScraperService wikiScraper = new WikiScraperService();
    private final Map<String, String> allEntityMappings = new LinkedHashMap<>();
    private Map<String, ModEntry> previousMods = Map.of();
    private Path diagnosticDir;
    private PublicKnowledgeCache publicCache;

    public KnowledgeBuilder(AppConfig config, DatabaseBuilder db) {
        this(config, db, initializedEmbedder(config));
    }

    public KnowledgeBuilder(AppConfig config, DatabaseBuilder db, EmbeddingService embedder) {
        this.config = config;
        this.db = db;
        this.scanner = new JarScannerService();
        this.parser = new ModMetadataParser();
        this.textureExtractor = new TextureExtractor(config);
        this.recipeExtractor = new RecipeExtractor();
        this.chunker = new TextChunker();
        this.embedder = embedder;
        this.vectorStore = new VectorStore(db, embedder.getDimension());
        this.publicCache = new PublicKnowledgeCache(db);
        wikiScraper.setPublicCache(publicCache);
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

    public BuildResult build(Path modFolder, ProgressCallback progressCallback) {
        if (!java.nio.file.Files.isDirectory(modFolder) || scanner.scan(modFolder).isEmpty())
            throw new IllegalArgumentException("No JAR files found; previous knowledge base retained");
        String generation = "build-" + UUID.randomUUID();
        Path stagingPath = config.getDataDir().resolve("staging-" + UUID.randomUUID());
        DatabaseBuilder staging = DatabaseBuilder.atPath(stagingPath);
        try {
            staging.initDatabase();
            var candidate = new KnowledgeBuilder(config, staging, embedder);
            candidate.publicCache=publicCache;
            candidate.wikiScraper.setPublicCache(publicCache);
            var oldMods = new LinkedHashMap<String, ModEntry>();
            for (var mod : db.findAllModEntries()) oldMods.put(mod.getModId(), mod);
            candidate.previousMods = oldMods;
            candidate.textureExtractor.setGeneration(generation);
            candidate.diagnosticDir = config.getTexturesDir().resolve(generation);
            java.nio.file.Files.createDirectories(candidate.diagnosticDir);
            staging.setBuildGeneration(generation);
            BuildResult result = candidate.buildInto(modFolder, (phase, cur, total, message) -> {
                if (!"done".equals(phase)) report(progressCallback, phase, cur, total, message);
            });
            if (result.parsed == 0) throw new IllegalStateException("No valid mods found; previous knowledge base retained");
            checkCancelled();
            staging.healthCheck();
            checkCancelled();
            db.replaceBuildDataFrom(staging);
            report(progressCallback, "done", result.parsed, result.parsed, "Build published: " + result.embeddings + " vectors");
            return result;
        } catch (java.sql.SQLException | java.io.IOException e) {
            throw new IllegalStateException("Staging database validation failed", e);
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

    private BuildResult buildInto(Path modFolder, ProgressCallback progressCallback) {
        final int warmLimit=Math.max(0,Math.min(30,Integer.getInteger("maa.build.prewarmPages",config.getPrewarmPages())));
        BuildResult result = new BuildResult();
        long timeStamp0 = System.currentTimeMillis();

        // ===== Overwrite mode: clear previous build data =====
        db.clearBuildData();
        log.info("Overwrite mode: previous build data cleared");

        // ===== Phase A-E: JAR extraction =====
        report(progressCallback, "scan", 0, 1, "Scanning JAR files...");
        List<Path> jars = scanner.scan(modFolder);
        result.totalJars = jars.size();

        for (int i = 0; i < jars.size(); i++) {
            checkCancelled();
            Path jar = jars.get(i);
            ModEntry entry = parser.parse(jar);
            if (entry == null) { result.skipped++; continue; }
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
            db.saveModEntry(entry);
            saveTextures(allTex, modId, slug);
            saveRecipes(recipes, entry.getLoader(), entry.getVersion());
        }

        // ===== Phase F: Baseline MC Wiki =====
        if (!jars.isEmpty()) {
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
        List<ModEntry> mods = db.findAllModEntries();
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
        public int textures, entities, recipes, textChunks, embeddings;
        public long durationMs;
    }
}
