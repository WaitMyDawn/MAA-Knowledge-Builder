package yagen.waitmydawn.kb.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yagen.waitmydawn.kb.config.AppConfig;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import yagen.waitmydawn.kb.model.ModEntry;
import yagen.waitmydawn.kb.model.BuildRequest;

import java.nio.file.Path;
import java.util.*;

/** 游戏快照提供全部本地资源；JAR 输入只使用已校验的 Scan 身份，百科保持显式绑定。 */
public class KnowledgeBuilder {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBuilder.class);

    private final AppConfig config;
    private final DatabaseBuilder db;
    private final TextChunker chunker;
    private final EmbeddingService embedder;
    private final VectorStore vectorStore;
    private final WikiScraperService wikiScraper;
    private Map<String, ModEntry> previousMods = Map.of();
    private Map<String, ModEntry> scannedMods = Map.of();
    private PublicKnowledgeCache publicCache;
    private final List<BuildSourceProcessor> sourceProcessors;
    private Set<String> selectedJarMods=Set.of();
    private List<BuildRequest.Source> jarSources=List.of();
    private ModBindingStore bindingDrafts;
    void setBindingStore(ModBindingStore store){bindingDrafts=store;}
    private ScanEnvironmentStore environments;
    void setEnvironmentStore(ScanEnvironmentStore store){environments=store;}
    private RuntimeBuildContract.Prepared gameSnapshot;
    private final Map<Path,JarScanCache.Snapshot> scanPreparations=new HashMap<>();
    public void clearScanPreparations(){scanPreparations.clear();}
    public void setScanPreparation(Path folder,JarScanCache.Snapshot snapshot){if(snapshot!=null)scanPreparations.put(folder.toAbsolutePath().normalize(),snapshot);}
    private boolean requireBoundWiki;
    void requireBoundWiki(){requireBoundWiki=true;}
    private Map<String,ModEntry> preparations=Map.of();
    private void prepareMod(ModEntry entry){
        var old=previousMods.get(entry.getModId());bindingDrafts.apply(entry,old);
        var selected=preparations.get(entry.getModId());
        if(selected!=null&&Objects.equals(selected.getVersion(),entry.getVersion())) {
            if(selected.getSlug()!=null){entry.setSlug(selected.getSlug());entry.setModrinthUrl(selected.getModrinthUrl());entry.setSource(selected.getSource());}
            if(!requireBoundWiki&&selected.getMcmodBinding()!=null)entry.setMcmodBinding(selected.getMcmodBinding());
        }
    }
    private void recordJarOwners(Path jar,String mod,String hash){
        var store=new SourceLifecycleStore(db);
        for(var source:jarSources)if(jar.toAbsolutePath().normalize().startsWith(Path.of(source.location())))
            store.execute("MERGE INTO rag_jar_source KEY(id) VALUES(?,?,?,?,?)",BuildRequest.fingerprint(source.id()+"\n"+mod),source.id(),mod,jar.toAbsolutePath().normalize().toString(),hash);
    }
    void selectJarMods(Set<String> mods){selectedJarMods=Set.copyOf(mods);}

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
        this.bindingDrafts=new ModBindingStore(db);this.environments=new ScanEnvironmentStore(db);
        this.wikiScraper=scraper;

        this.chunker = new TextChunker();
        this.embedder = embedder;
        this.vectorStore = new VectorStore(db, embedder.getDimension());
        this.publicCache = new PublicKnowledgeCache(db);
        wikiScraper.setPublicCache(publicCache);
        wikiScraper.setResourceDatabase(db);
        var documents = new DocumentDirectoryProcessor(config);
        this.sourceProcessors = sourceProcessors == null
                ? List.of(documents, new UrlBuildProcessor(wikiScraper, documents).withPrewarmPages(config.getPrewarmPages()))
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
        final BuildTaskJournal task;
        try{task=BuildTaskJournal.begin(config.getDataDir());}catch(java.io.IOException failure){throw new IllegalStateException("无法创建构建任务记录",failure);}
        Path stagingPath=task.staging();DatabaseBuilder staging=DatabaseBuilder.atPath(stagingPath);
        String outcome="FAILED";boolean validated=false;
        try {
        final RuntimeBuildContract.Prepared prepared;
        try{prepared=RuntimeBuildContract.prepare(request,db,scanPreparations,environments);}
        catch(java.io.IOException failure){throw new IllegalArgumentException("MKB-Export 预检查失败，原库保留："+failure.getMessage(),failure);}
        report(progressCallback,"preflight",1,1,"完整游戏快照校验通过；JAR 不参与资源解析。");
        var iconPacks=Map.of(prepared.source().id(),prepared.pack());
        boolean append=request.mode()==BuildRequest.Mode.APPEND;
        if(append)VectorStore.verifyProfile(db,embedder.profile(),false);
        String targetFingerprint=append?db.contentFingerprint():null;
        for(var source:request.sources())if(source.kind()!=BuildRequest.Kind.JAR_DIRECTORY&&!iconPacks.containsKey(source.id())
                &&sourceProcessors.stream().noneMatch(processor->processor.supports(source.kind())))throw new IllegalArgumentException("来源类型未接通："+source.kind());
        long started = System.currentTimeMillis();
        String generation = "build-" + UUID.randomUUID();
            checkCancelled();validated=true;
            staging.initDatabase();
            new VectorStore(staging,embedder.getDimension());
            if(append) staging.replaceBuildDataFrom(db);
            String originalFingerprint=append?staging.contentFingerprint():null;
            int originalVectors=append?new VectorStore(staging,embedder.getDimension()).count():0;
            var candidate = new KnowledgeBuilder(config, staging, embedder, sourceProcessors,wikiScraper);
            candidate.selectedJarMods=selectedJarMods;candidate.gameSnapshot=prepared;
            candidate.environments=environments;candidate.bindingDrafts=bindingDrafts;candidate.preparations=scannedMods;candidate.requireBoundWiki=requireBoundWiki;
            candidate.jarSources=request.sources().stream().filter(s->s.kind()==BuildRequest.Kind.JAR_DIRECTORY).toList();
            candidate.publicCache=publicCache;
            candidate.wikiScraper.setPublicCache(publicCache);
            candidate.wikiScraper.setResourceDatabase(db);
            sourceProcessors.stream().filter(UrlBuildProcessor.class::isInstance).map(UrlBuildProcessor.class::cast).forEach(processor->processor.useResourceDatabase(db));
            var oldMods = new LinkedHashMap<String, ModEntry>();
            for (var mod : db.findAllModEntries()) oldMods.put(mod.getModId(), mod);
            scannedMods.forEach((id,entry) -> {
                if(!append || !oldMods.containsKey(id)) oldMods.put(id,entry);
            });
            candidate.previousMods = oldMods;
            if(append)for(var old:new ArrayList<>(new SourceLifecycleStore(staging).sources())) {
                if(old.id().equals(prepared.source().id()))continue;
                if(!new SourceLifecycleStore(staging).strings("SELECT profile_id FROM rag_runtime_snapshot WHERE source_id=?",old.id()).isEmpty())new SourceLifecycleStore(staging).deleteSource(old);
            }
            RuntimeBuildContract.removeLegacyResources(staging);
            BuildResult result = candidate.buildInto(append, (phase, cur, total, message) -> {
                if (!"done".equals(phase)) report(progressCallback, phase, cur, total, message);
            });
            var content = new KnowledgeContentStore(staging);
            var images = new ImageAssetStore(config.getDataDir());
            int completedSources=0;report(progressCallback,"progress:来源",0,request.sources().size(),"开始处理来源");
            for (var source : request.sources()) {
                checkCancelled();
                try{
                if(append && source.kind()==BuildRequest.Kind.URL && content.publishedRequest().stream()
                        .flatMap(r -> r.sources().stream()).anyMatch(s -> s.id().equals(source.id()))) {
                    var enrichmentContext=new BuildSourceProcessor.Context(staging,content,images,candidate.vectorStore,embedder,progressCallback,result);
                    var urlProcessor=sourceProcessors.stream().filter(p->p instanceof UrlBuildProcessor).map(p->(UrlBuildProcessor)p).findFirst();
                    if(urlProcessor.isPresent())urlProcessor.get().enrichExisting(source,enrichmentContext);else new WebImageEnricher().backfill(source,enrichmentContext);
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
                }finally{report(progressCallback,"progress:来源",++completedSources,request.sources().size(),source.location());}
            }
            if (candidate.vectorStore.count() == 0 && result.parsed == 0 && !new ItemIconStore(staging).hasUsableIcons())
                throw new IllegalStateException("没有可发布的有效内容；原基础库保留");
            RuntimeBuildContract.mark(staging,prepared.source().id());
            if(result.runtimeSnapshots>0||!append)new ItemIconStore(staging).selectProfile(RuntimeKnowledgeStore.active(staging));
            var lifecycle=new SourceLifecycleStore(staging);
            for(var source:request.sources()){lifecycle.captureJarOwners(source);lifecycle.indexed(source,null,0);}
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
            RuntimeBuildContract.verifyUnchanged(prepared,request,db,scanPreparations,environments);
            if(append && originalFingerprint.equals(staging.contentFingerprint())) {
                outcome="UNCHANGED";
                result.durationMs=System.currentTimeMillis()-started;
                report(progressCallback,"done",1,1,"没有新增内容，基础库保持不变");
                return result;
            }
            if(!append || result.runtimeSnapshots>0 || (result.parsed>0 || result.addedEmbeddings!=0 || result.documents>0 || result.tutorials>0))staging.setBuildGeneration(generation);
            if(append && !targetFingerprint.equals(db.contentFingerprint()))
                throw new IllegalStateException("构建期间目标基础库发生变化，请重新执行补充构建");
            checkCancelled();task.state("PUBLISHING");
            report(progressCallback,"publish",0,1,"正在原子发布构建结果，完成前不再取消。");checkCancelled();
            db.replaceBuildDataFrom(staging);outcome="PUBLISHED";
            result.durationMs = System.currentTimeMillis() - started;
            try {
                report(progressCallback, "done", request.sources().size(), request.sources().size(), "Build published: " + result.embeddings + " vectors, "+result.nativeIcons+" imported icons");
            } catch (RuntimeException e) { log.warn("Build published, but progress listener failed", e); }
            return result;
        } catch (java.util.concurrent.CancellationException e) {
            outcome="CANCELLED";
            throw e;
        } catch (Exception e) {
            if(task.cancelRequested()){outcome="CANCELLED";var cancelled=new java.util.concurrent.CancellationException("Knowledge build cancelled");cancelled.initCause(e);throw cancelled;}
            if(!validated&&e instanceof IllegalArgumentException invalid)throw invalid;
            throw new IllegalStateException("构建失败，原基础库保留：" + e.getMessage(), e);
        } finally {
            boolean interrupted=Thread.interrupted();
            try {
                try{if(java.nio.file.Files.exists(Path.of(stagingPath+".mv.db")))staging.shutdown();}catch(Exception failure){log.warn("Staging shutdown failed",failure);}
                try{report(progressCallback,"cleanup",1,1,task.finish(outcome)+retainedCacheSummary());}
                catch(Exception failure){log.warn("Build cleanup incomplete",failure);try{report(progressCallback,"cleanup",0,1,"暂存清理未完成，下次打开数据目录时重试；图片缓存保留。");}catch(RuntimeException ignored){}}
            }finally{try{task.close();}catch(java.io.IOException failure){log.warn("Task lock release failed",failure);}if(interrupted)Thread.currentThread().interrupt();}
        }
    }

    private static void checkCancelled() {
        BuildCancellation.check();
    }

    private String retainedCacheSummary() {
        try(var c=db.getConnection();var s=c.createStatement()) {
            long resources=0,bytes=0,urls=0;
            try(var tables=c.getMetaData().getTables(null,null,"rag_public_resource",null)) {
                if(tables.next())try(var rows=s.executeQuery("SELECT COUNT(*),COALESCE(SUM(OCTET_LENGTH(body)),0) FROM rag_public_resource")){rows.next();resources=rows.getLong(1);bytes=rows.getLong(2);}
            }
            try(var tables=c.getMetaData().getTables(null,null,"rag_web_image_download",null)) {
                if(tables.next())try(var p=c.prepareStatement("SELECT COUNT(*) FROM rag_web_image_download WHERE asset_root=? AND fetched_at>=?")){p.setString(1,config.getDataDir().toAbsolutePath().normalize().toString());p.setLong(2,System.currentTimeMillis()-WebImageDownloadCache.TTL);try(var rows=p.executeQuery()){rows.next();urls=rows.getLong(1);}}
            }
            return " 当前原始资源缓存 "+resources+" 项（"+String.format(Locale.ROOT,"%.2f",bytes/1048576.0)+" MiB），近期图片下载地址索引 "+urls+" 条。";
        }catch(java.sql.SQLException failure){log.warn("Cache retention statistics unavailable",failure);return " 资源缓存统计暂不可用。";}
    }

    private BuildResult buildInto(boolean append, ProgressCallback progressCallback)throws Exception {
        final int warmLimit=Math.max(0,Math.min(30,Integer.getInteger("maa.build.prewarmPages",config.getPrewarmPages())));
        BuildResult result = new BuildResult();
        long timeStamp0 = System.currentTimeMillis();

        // ===== Overwrite mode: clear previous build data =====
        if(!append) db.clearBuildData();

        var importedMods=new ArrayList<ModEntry>();
        var incoming=RuntimeGameIdentity.entries(gameSnapshot);
        var identities=new HashMap<String,RuntimeBuildContract.Identity>();gameSnapshot.identities().forEach(i->identities.put(i.mod().getModId(),i));
        result.totalJars=gameSnapshot.identities().size();
        var ids=incoming.stream().map(ModEntry::getModId).collect(java.util.stream.Collectors.toSet());
        for(var old:db.findAllModEntries())if(!ids.contains(old.getModId()))new SourceLifecycleStore(db).execute("DELETE FROM rag_mod_info WHERE mod_id=?",old.getModId());
        report(progressCallback,"progress:身份",0,incoming.size(),"导入快照模组身份");int done=0;
        for(var entry:incoming) {
            checkCancelled();prepareMod(entry);var previous=previousMods.get(entry.getModId());
            if(append&&previous!=null&&!requireBoundWiki&&!ModBindingStore.signature(entry.getMcmodBinding()).equals(ModBindingStore.signature(previous.getMcmodBinding()))) {
                entry.setMcmodBinding(previous.getMcmodBinding());result.warnings.add(entry.getModId()+"：百科绑定已修改，请通过来源管理刷新对应模组后发布。");
            }
            boolean changed=previous==null||!Objects.equals(entry.getVersion(),previous.getVersion())||!Objects.equals(entry.getMcVersion(),previous.getMcVersion())
                    ||!ModBindingStore.signature(entry.getMcmodBinding()).equals(ModBindingStore.signature(previous.getMcmodBinding()));
            if(previous!=null&&entry.getJarPath()==null&&!changed){entry.setJarPath(previous.getJarPath());entry.setJarFileName(previous.getJarFileName());}
            var json=new com.fasterxml.jackson.databind.ObjectMapper();
            if(previous==null||!json.valueToTree(entry).equals(json.valueToTree(previous)))db.saveModEntry(entry);
            var identity=identities.get(entry.getModId());if(identity!=null){new JarBuildIndex(db).save(entry,identity.hash());recordJarOwners(Path.of(entry.getJarPath()),entry.getModId(),identity.hash());}
            if((!append||changed||requireBoundWiki)&& (selectedJarMods.isEmpty()||selectedJarMods.contains(entry.getModId())))importedMods.add(entry);
            if(requireBoundWiki&&(selectedJarMods.isEmpty()||selectedJarMods.contains(entry.getModId())))new SourceLifecycleStore(db).clearWiki(entry.getModId());
            if(identity!=null){if(changed)result.parsed++;else result.skipped++;}
            report(progressCallback,"progress:身份",++done,incoming.size(),entry.getModId());
        }
        try{RuntimeGameIdentity.entities(db,gameSnapshot.pack());}catch(Exception failure){throw new IllegalStateException("快照实体注册表导入失败",failure);}
        // ===== Phase F: Baseline MC Wiki =====
        if (!jarSources.isEmpty() && !importedMods.isEmpty() && (!append || !hasBaselineWiki())) {
            int total = WikiScraperService.BASELINE_PAGES.length;
            List<TextChunker.TextChunk> wikiChunks = new ArrayList<>();
            int idx = 0;
            report(progressCallback,"progress:Wiki",0,total,"开始处理 Wiki");
            for (String page : WikiScraperService.BASELINE_PAGES) {
                idx++;
                report(progressCallback, "wiki", idx, total, "MC Wiki: " + page);
                WikiScraperService.WebPage wp = wikiScraper.fetchMcWiki(page);
                if (wp != null) {
                    wikiChunks.addAll(chunker.fromWebPage(wp.content(), "minecraft", "Minecraft", "mcwiki", wp.url()));
                }
                report(progressCallback,"progress:Wiki",idx,total,"已处理 Wiki 页面");
                try { Thread.sleep(200); } catch (InterruptedException cancelled) {
                    Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException("Knowledge build cancelled");
                }
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
        report(progressCallback,"progress:百科",0,deduped.size(),"开始处理模组百科");
        for (ModEntry modEntry : deduped) {
            sIdx++;
            try{
            if ("minecraft".equals(modEntry.getModId()) || "vanilla".equals(modEntry.getLoader())) continue;
            var binding=modEntry.getMcmodBinding();
            if(binding==null||binding.url()==null||binding.status().equals("DISABLED")) {
                result.warnings.add(modEntry.getModId()+"：未绑定百科主页，仅构建本地内容");continue;
            }
            report(progressCallback, "scrape", sIdx, deduped.size(), "mcmod: " + modEntry.getModId());
            final int cur = sIdx;
            final int total = deduped.size();
            final String mid = modEntry.getModId();
            var scrape = wikiScraper.scrapeModCatalog(modEntry.getModId(),binding.classId(),
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
            if(pages.isEmpty()) {
                String message=modEntry.getModId()+"：绑定百科未返回有效正文："+binding.url();
                if(requireBoundWiki)throw new IllegalStateException(message);
                result.warnings.add(message);
            }
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
                var owner=jarSources.stream().filter(source->java.nio.file.Path.of(modEntry.getJarPath()).toAbsolutePath().normalize().startsWith(java.nio.file.Path.of(source.location()).toAbsolutePath().normalize())).findFirst().orElse(null);
                if(owner!=null){var context=new BuildSourceProcessor.Context(db,new KnowledgeContentStore(db),new ImageAssetStore(config.getDataDir()),vectorStore,embedder,progressCallback,result);new UrlBuildProcessor(wikiScraper).processDocument(owner,entry.url(),context,modEntry.getModId(),modEntry.getDisplayName());}
            }
            report(progressCallback,"prewarm",warmed.size(),warmed.size(),warmLimit==0?"已关闭常用详情预取":"详情预取完成：成功 "+prepared+" 页，跳过/失败 "+skipped+" 页（站点冷却期间不继续访问）");
            try { Thread.sleep(500); } catch (InterruptedException cancelled) {Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException("Knowledge build cancelled");}
            }finally{report(progressCallback,"progress:百科",sIdx,deduped.size(),"已处理模组百科");}
        }

        result.durationMs = System.currentTimeMillis() - timeStamp0;

        report(progressCallback, "done", result.totalJars, result.totalJars,
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

    private void report(ProgressCallback cb, String phase, int cur, int total, String msg) {
        if (cb != null) cb.onProgress(phase, cur, total, msg);
    }

    public static class BuildResult {
        public int totalJars, parsed, skipped;
        public int reusedSources, reusedDocuments, conflicts, addedEmbeddings;
        public int documents, tutorials, webImages, webImageAttempts, deferredWebImages;
        public int reusedWebImages,webImageBindings,webImageQueuePeak,webImageDecodes,permanentImageFailures,temporaryImageFailures,cooldownImageFailures;
        public long webImageFetchMs,webImageProcessMs,webImageStoreMs;
        public int localImages, ocrAttempts, ocrImages, visionAttempts, visionImages;
        public int webOcrAttempts,webOcrImages,webOcrChunks,enrichedWebImages,reusedImageAnalyses,deferredImageAnalyses,imageStepBackfills;
        public int nativeIconPacks,nativeIcons,transparentNativeIcons,failedNativeIcons,reusedIconPacks;
        public int entityVisible,entityTransparent,entityFailed,entityExcluded,entityUncertain;
        public int entityUniqueImages,entityImageAliases;
        public int entityCompositionComplete,entityCompositionPartial,entityCompositionUnverified,entityUnexpectedEmpty;
        public int itemTagFiles,itemNames,staticItemIcons;
        public final List<String> warnings = new ArrayList<>();
        public int textures, entities, recipes, textChunks, embeddings, runtimeSnapshots;
        public long durationMs;
    }
}
