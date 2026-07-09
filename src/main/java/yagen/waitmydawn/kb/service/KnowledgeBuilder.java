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

    public KnowledgeBuilder(AppConfig config, DatabaseBuilder db) {
        this.config = config;
        this.db = db;
        this.scanner = new JarScannerService();
        this.parser = new ModMetadataParser();
        this.textureExtractor = new TextureExtractor(config);
        this.recipeExtractor = new RecipeExtractor();
        this.chunker = new TextChunker();
        this.embedder = new EmbeddingService(config);
        this.vectorStore = new VectorStore(db, embedder.getDimension());
    }

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(String phase, int cur, int total, String message);
    }

    public VectorStore getVectorStore() { return vectorStore; }

    public BuildResult build(Path modFolder, ProgressCallback progressCallback) {
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
            TextureExtractor.dumpMapping(allTex, config.getDataDir());

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
                var rc = chunker.fromRecipe(r.recipeJson(), modId, modName, r.outputItem());
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
                ModEntry existing = db.findModEntry(entry.getModId());
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
                    wikiChunks.addAll(chunker.fromDescription(wp.content(), "minecraft", "Minecraft"));
                }
                try { Thread.sleep(200); } catch (Exception ignored) {} // Be polite to wiki
            }
            if (!wikiChunks.isEmpty()) {
                vectorStore.storeBatch(wikiChunks, embedder);
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
            List<WikiScraperService.WebPage> pages =
                    wikiScraper.scrapeModDeep(modEntry.getModId(),
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
            if (!pages.isEmpty()) {
                // 持久化网页 (含 subWebPage 映射), 供 Q&A 阶段增量抓取
                db.saveWebPages(modEntry.getModId(), pages);

                List<TextChunker.TextChunk> modChunks = new ArrayList<>();
                for (var p : pages) modChunks.addAll(chunker.fromDescription(p.content(), modEntry.getModId(),
                        modEntry.getDisplayName() != null ? modEntry.getDisplayName() : modEntry.getModId()));
                if (!modChunks.isEmpty()) {
                    vectorStore.storeBatch(modChunks, embedder);
                    result.embeddings += modChunks.size();
                    log.info("mcmod: {} chunks for {}", modChunks.size(), modEntry.getModId());
                }
            }
            try { Thread.sleep(500); } catch (Exception ignored) {}
        }

        result.durationMs = System.currentTimeMillis() - timeStamp0;

        // Export entity_map.json for debugging
        if (!allEntityMappings.isEmpty()) {
            try {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                        .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
                mapper.writeValue(config.getDataDir().resolve("entity_map.json").toFile(), allEntityMappings);
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
                    return chunker.fromLangJson(new String(is.readAllBytes()), modId, modName, p);
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
        } catch (Exception ex) { log.error("Save textures failed", ex); }
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
        } catch (Exception ex) { log.error("Save entity registries failed", ex); }
    }

    private void saveRecipes(List<RecipeExtractor.ParsedRecipe> recipes, String loader, String v) {
        try (var c = db.getConnection(); var ps = c.prepareStatement(
                "MERGE INTO rag_recipe (recipe_type, output_item, output_count, recipe_data, source_mod, loader, mod_version, source_type) KEY (output_item, recipe_type, source_mod) VALUES (?, ?, ?, ?, ?, ?, ?, 'JAR_PARSE')")) {
            for (var r : recipes) { ps.setString(1, r.recipeType()); ps.setString(2, r.outputItem()); ps.setInt(3, r.outputCount()); ps.setString(4, r.recipeJson()); ps.setString(5, r.sourceMod()); ps.setString(6, loader); ps.setString(7, v); ps.addBatch(); }
            ps.executeBatch();
        } catch (Exception ex) { log.error("Save recipes failed", ex); }
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
