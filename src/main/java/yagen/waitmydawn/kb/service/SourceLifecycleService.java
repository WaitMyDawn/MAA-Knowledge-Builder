package yagen.waitmydawn.kb.service;

import yagen.waitmydawn.kb.config.AppConfig;
import yagen.waitmydawn.kb.model.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** 所有维护操作在独立暂存库执行；预览代次过期或任何处理失败均不发布。 */
public final class SourceLifecycleService {
    public enum Operation {
        REFRESH("刷新变化内容"), REPARSE("按当前解析器重建"), REPLACE("替换来源位置"), DELETE("删除索引");
        private final String label;Operation(String label){this.label=label;}@Override public String toString(){return label;}
    }
    public record Entry(BuildRequest.Source source,String name,int documents,int mods,int icons,String parserVersion,String currentVersion,String hash,String indexedAt,long revision){
        public boolean needsMigration(){return !Objects.equals(parserVersion,currentVersion);}
        public String description(){
            String type=source.kind()==BuildRequest.Kind.DOCUMENT_DIRECTORY?(icons>0?"游戏快照目录":"资料目录"):kindLabel(source.kind());
            String title=Objects.toString(name,"").strip();
            return title.isBlank()||title.replace('\\','/').equalsIgnoreCase(source.location().replace('\\','/'))?type:title+" · "+type;
        }
        @Override public String toString(){return description()+"\n"+source.location();}
    }
    public static String kindLabel(BuildRequest.Kind kind){return switch(kind){case JAR_DIRECTORY->"模组目录";case URL->"网址";case DOCUMENT_DIRECTORY->"资料或游戏快照目录";};}
    public record Item(String id,String label,String location){@Override public String toString(){return label+" · "+location;}}
    public record Plan(String databaseFingerprint,BuildRequest.Source source,String itemId,Operation operation,BuildRequest.Source replacement,int affectedDocuments,int sharedDocuments,int affectedMods,int affectedIcons,String summary) {}
    public record Result(int documents,int vectors,int parsedMods,String generation,String message) {}
    private final AppConfig config;private final DatabaseBuilder db;private final EmbeddingService embedding;
    private final List<BuildSourceProcessor> processors;private final WikiScraperService scraper;
    public SourceLifecycleService(AppConfig config,DatabaseBuilder db,EmbeddingService embedding){this(config,db,embedding,null,new WikiScraperService());}
    SourceLifecycleService(AppConfig config,DatabaseBuilder db,EmbeddingService embedding,List<BuildSourceProcessor> processors,WikiScraperService scraper){this.config=config;this.db=db;this.embedding=embedding;this.processors=processors;this.scraper=scraper;}
    public List<Entry> entries(){
        var store=new SourceLifecycleStore(db);var out=new ArrayList<Entry>();
        for(var source:store.sources()) {
            var names=store.strings("SELECT COALESCE(display_name,location) FROM rag_knowledge_source WHERE id=?",source.id());var state=store.state(source.id());
            int icons=store.strings("SELECT id FROM rag_item_icon_profile WHERE source_id=?",source.id()).size();
            out.add(new Entry(source,names.isEmpty()?source.location():names.getFirst(),store.documents(source.id()).size(),store.mods(source).size(),icons,state==null?"旧库：未知":state.parserVersion(),SourceLifecycleStore.parserVersion(source.kind()),state==null?store.contentHash(source):state.hash(),state==null?"未知":state.indexedAt(),state==null?0:state.revision()));
        }return List.copyOf(out);
    }
    public List<Item> items(String sourceId){
        var source=find(sourceId);var store=new SourceLifecycleStore(db);var out=new ArrayList<Item>();
        for(String id:store.documents(sourceId)) {
            var values=store.strings("SELECT title||CHAR(9)||location FROM rag_document WHERE id=?",id);if(!values.isEmpty()){var parts=values.getFirst().split("\t",2);out.add(new Item(id,parts[0],parts[1]));}
        }
        for(String mod:store.mods(source)){var entry=db.findModEntry(mod);out.add(new Item("mod:"+mod,mod,entry==null?source.location():Objects.toString(entry.getJarPath(),source.location())));}
        return List.copyOf(out);
    }
    private BuildRequest.Source find(String id){return new SourceLifecycleStore(db).sources().stream().filter(s->s.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("来源已不存在，请刷新列表"));}
    public Plan preview(String sourceId,String itemId,Operation operation,BuildRequest.Source replacement){
        Objects.requireNonNull(operation);var source=find(sourceId);var store=new SourceLifecycleStore(db);
        if(itemId!=null&&items(sourceId).stream().noneMatch(i->i.id().equals(itemId)))throw new IllegalArgumentException("子项不属于所选来源");
        if(operation==Operation.REPLACE&&(itemId!=null||replacement==null||replacement.kind()!=source.kind()))throw new IllegalArgumentException("替换需选择整个来源，并提供同类型的新位置");
        String snapshotProof="";
        boolean snapshotSource=!store.strings("SELECT profile_id FROM rag_runtime_snapshot WHERE source_id=?",sourceId).isEmpty();
        if(snapshotSource&&itemId!=null)throw new IllegalArgumentException("游戏快照只能整体刷新、重建、替换或删除，不能单独维护派生文档。");
        if(operation!=Operation.DELETE) {
            if(RuntimeBuildContract.migrationWarning(db)!=null)throw new IllegalStateException(RuntimeBuildContract.migrationWarning(db));
            var selected=snapshotSource?(operation==Operation.REPLACE?replacement:source):RuntimeBuildContract.associated(db);
            try{var prepared=RuntimeBuildContract.prepare(new BuildRequest(List.of(selected),BuildRequest.Mode.APPEND),db,Map.of());snapshotProof="\n关联快照："+selected.location()+"\n快照指纹："+prepared.pack().manifestHash();}
            catch(java.io.IOException failure){throw new IllegalStateException("关联游戏快照校验失败，请重新导出或选择正确目录："+failure.getMessage(),failure);}
        }
        if(operation==Operation.REPLACE&&!replacement.id().equals(sourceId)&&store.sources().stream().anyMatch(s->s.id().equals(replacement.id())))throw new IllegalArgumentException("新位置已是独立来源，请先处理该来源，避免覆盖");
        var docs=itemId==null?store.documents(sourceId):itemId.startsWith("mod:")?List.<String>of():List.of(itemId);int shared=0;
        for(String doc:docs)if(!store.strings("SELECT source_id FROM rag_document_source WHERE document_id=? AND source_id<>?",doc,sourceId).isEmpty())shared++;
        int mods=itemId==null?store.mods(source).size():itemId.startsWith("mod:")?1:0;
        if(source.kind()==BuildRequest.Kind.JAR_DIRECTORY&&store.sources().stream().filter(s->s.kind()==BuildRequest.Kind.JAR_DIRECTORY).count()>1) {
            for(String mod:store.mods(source))if((itemId==null||itemId.equals("mod:"+mod))&&store.strings("SELECT id FROM rag_jar_source WHERE source_id=? AND mod_id=?",sourceId,mod).isEmpty())
                throw new IllegalStateException("旧库有多个模组目录且缺少所有权记录，请先对相关目录执行补充 Build，再预览维护操作");
        }
        int icons=itemId==null?store.strings("SELECT id FROM rag_item_icon_profile WHERE source_id=?",sourceId).size():0;
        String summary=operation+"\n来源："+source.location()+"\n范围："+(itemId==null?"整个来源（包含发现的新增内容）":itemId)+"\n现有文档："+docs.size()+"；共享文档："+shared+"；模组："+mods+"；图标包版本："+icons
                +(replacement==null?"":"\n新位置："+replacement.location())+snapshotProof+"\n刷新共享文档会更新所有引用方的当前证据；删除仅解除所选来源的引用。\n保留历史会话、图片及原始文件。失败或取消不发布。";
        if(source.kind()==BuildRequest.Kind.JAR_DIRECTORY&&operation!=Operation.DELETE) {
            var environment=new ScanEnvironmentStore(db).forFolder(Path.of(source.location()));
            if(environment!=null)summary+="\n已确认游戏环境："+environment.summary();
            for(String mod:store.mods(source))if(itemId==null||itemId.equals("mod:"+mod)){
                var published=db.findModEntry(mod);if(published==null)continue;
                var desired=new ModEntry();desired.setModId(mod);desired.setLoader(published.getLoader());desired.setVersion(published.getVersion());new ModBindingStore(db).apply(desired,published);
                if(!ModBindingStore.signature(desired.getMcmodBinding()).equals(ModBindingStore.signature(published.getMcmodBinding())))
                    summary+="\n待发布百科绑定："+mod+" · "+bindingLocation(published)+" → "+bindingLocation(desired);
            }
        }
        return new Plan(db.contentFingerprint(),source,itemId,operation,replacement,docs.size(),shared,mods,icons,summary);
    }
    private static String bindingLocation(ModEntry mod){var b=mod.getMcmodBinding();return b==null||b.url()==null?"仅本地内容":b.url();}
    public Result apply(Plan plan,KnowledgeBuilder.ProgressCallback progress){
        Objects.requireNonNull(plan);check();
        if(!db.contentFingerprint().equals(plan.databaseFingerprint()))throw new IllegalStateException("基础库已变化，请重新预览");
        // 重验来源归属，不能把外部构造的子项标识作为删除授权。
        var checked=preview(plan.source().id(),plan.itemId(),plan.operation(),plan.replacement());
        if(!checked.databaseFingerprint().equals(plan.databaseFingerprint()))throw new IllegalStateException("基础库已变化，请重新预览");
        if(!checked.summary().equals(plan.summary()))throw new IllegalStateException("绑定草稿、游戏环境或快照已变化，请重新预览");
        Path path=config.getDataDir().resolve("maintenance-"+UUID.randomUUID());var stage=DatabaseBuilder.atPath(path);
        scraper.setBuildRefresh(plan.operation()==Operation.REFRESH||plan.operation()==Operation.REPLACE);
        scraper.setBuildCacheOnly(plan.operation()==Operation.REPARSE&&plan.source().kind()==BuildRequest.Kind.URL);
        try {
            stage.initDatabase();new VectorStore(stage,embedding.getDimension());stage.replaceBuildDataFrom(db);
            var store=new SourceLifecycleStore(stage);var old=store.state(plan.source().id());String logical=old==null?plan.source().id():old.logicalId();long revision=old==null?1:old.revision()+1;
            var content=new KnowledgeContentStore(stage);var images=new ImageAssetStore(config.getDataDir());var vectors=new VectorStore(stage,embedding.getDimension());
            var stats=new KnowledgeBuilder.BuildResult();var context=new BuildSourceProcessor.Context(stage,content,images,vectors,embedding,progress,stats,true,plan.operation()==Operation.REPARSE,new HashSet<>());
            var oldDocs=store.documents(plan.source().id());var source=plan.source();
            var invalidatedUrls=new HashSet<String>();
            for(String doc:oldDocs)if(plan.itemId()==null||plan.itemId().equals(doc))invalidatedUrls.addAll(store.strings("SELECT location FROM rag_document WHERE id=?",doc));
            if(source.kind()==BuildRequest.Kind.JAR_DIRECTORY)for(String mod:store.mods(source))if(plan.itemId()==null||plan.itemId().equals("mod:"+mod)) {
                invalidatedUrls.addAll(store.strings("SELECT url FROM rag_web_cache WHERE mod_id=?",mod));
                invalidatedUrls.addAll(store.strings("SELECT DISTINCT source_path FROM rag_vectors WHERE mod_id=? AND source_path LIKE 'http%'",mod));
            }
            if(plan.operation()==Operation.DELETE) {
                if(plan.itemId()==null)store.deleteSource(source);
                else if(plan.itemId().startsWith("mod:")){String mod=plan.itemId().substring(4);store.execute("DELETE FROM rag_jar_source WHERE source_id=? AND mod_id=?",source.id(),mod);store.releaseMod(mod);}
                else {store.unlinkDocument(source.id(),plan.itemId());store.execute("DELETE FROM rag_document_import WHERE source_id=? AND document_id=?",source.id(),plan.itemId());}
            } else {
                if(plan.operation()==Operation.REPLACE){store.deleteSource(source);source=plan.replacement();content.saveSource(source);}
                if(source.kind()==BuildRequest.Kind.JAR_DIRECTORY) {
                    maintainJars(plan,source,stage,store,stats,progress);
                } else {
                    boolean nativePack=source.kind()==BuildRequest.Kind.DOCUMENT_DIRECTORY&&NativeIconPack.detected(Path.of(source.location()));
                    if(!nativePack)VectorStore.verifyProfile(stage,embedding.profile(),false);
                    if(nativePack) {
                        if(plan.operation()==Operation.REPARSE)new RuntimeKnowledgeStore(stage).deleteSource(source.id());
                        var builder=new KnowledgeBuilder(config,stage,embedding,processors,scraper);
                        builder.setScannedMods(db.findAllModEntries());
                        var result=builder.build(new BuildRequest(List.of(source),BuildRequest.Mode.APPEND),progress);
                        stats.runtimeSnapshots=result.runtimeSnapshots;stats.nativeIcons=result.nativeIcons;stats.documents=result.documents;
                    }
                    else {
                        var documents=new DocumentDirectoryProcessor(config);var urls=new UrlBuildProcessor(scraper,documents);scraper.setResourceDatabase(db);
                        var available=processors==null?List.<BuildSourceProcessor>of(documents,urls):processors;
                        var kind=source.kind();var processor=available.stream().filter(p->p.supports(kind)).findFirst().orElseThrow(()->new IllegalStateException("来源类型未接通"));
                        if(plan.itemId()!=null){String location=store.strings("SELECT location FROM rag_document WHERE id=?",plan.itemId()).getFirst();if(source.kind()==BuildRequest.Kind.URL&&processor instanceof UrlBuildProcessor u)u.processDocument(source,location,context);else if(source.kind()==BuildRequest.Kind.DOCUMENT_DIRECTORY&&processor instanceof DocumentDirectoryProcessor d)d.processDocument(source,location,context);else throw new IllegalArgumentException("此处理器不支持文档级维护");}
                        else processor.process(source,context);
                        if(plan.itemId()==null&&plan.operation()!=Operation.REPLACE)for(String doc:oldDocs)if(!context.visitedDocuments().contains(doc))store.unlinkDocument(source.id(),doc);
                    }
                }
                if(stats.conflicts>0)throw new IllegalStateException("来源包含版本冲突，未发布，请先检查共享模组及输入版本");
            }
            check();
            var iconStore=new ItemIconStore(stage);String active=iconStore.activeProfileId();
            if(active!=null&&iconStore.profiles().stream().noneMatch(p->p.id().equals(active)&&!p.compatibility().equals("CONFLICT")))iconStore.selectProfile(null);
            if(plan.operation()!=Operation.DELETE||plan.itemId()!=null)store.indexed(source,logical,revision);
            for(var linked:store.sources())if(!linked.id().equals(source.id())) {
                var state=store.state(linked.id());String hash=store.contentHash(linked);
                if(state!=null&&!state.hash().equals(hash))store.execute("UPDATE rag_source_state SET content_hash=?,indexed_at=?,revision=revision+1 WHERE id=?",hash,java.time.Instant.now().toString(),linked.id());
            }
            store.event(logical,plan.operation().name(),plan.source().id(),plan.operation()==Operation.DELETE&&plan.itemId()==null?null:source.id(),plan.summary());
            images.validate(content,stage);stage.healthCheck();check();
            String generation=stage.buildGeneration();
            if(plan.affectedDocuments()>0||plan.affectedMods()>0||stats.documents>0||stats.parsed>0||stats.runtimeSnapshots>0){generation="maintenance-"+UUID.randomUUID();stage.setBuildGeneration(generation);}
            if(!db.contentFingerprint().equals(plan.databaseFingerprint()))throw new IllegalStateException("维护期间基础库发生变化，请重新预览");
            if(!preview(plan.source().id(),plan.itemId(),plan.operation(),plan.replacement()).summary().equals(plan.summary()))throw new IllegalStateException("维护期间绑定、环境或快照已变化，请重新预览");
            for(String doc:context.visitedDocuments())invalidatedUrls.addAll(store.strings("SELECT location FROM rag_document WHERE id=?",doc));
            db.replaceBuildDataFrom(stage,invalidatedUrls);
            return new Result(stats.documents,vectors.count(),stats.parsed,generation,"已发布："+plan.operation()+"；文档处理 "+stats.documents+"，复用 "+stats.reusedDocuments+"，当前向量 "+vectors.count()+"。历史资源已保留。");
        }catch(java.util.concurrent.CancellationException e){throw e;}
        catch(Exception e){throw new IllegalStateException("来源维护失败，原库保留："+e.getMessage(),e);}
        finally {
            scraper.setBuildRefresh(false);scraper.setBuildCacheOnly(false);try{stage.shutdown();}finally{try{Files.deleteIfExists(Path.of(path+".mv.db"));Files.deleteIfExists(Path.of(path+".trace.db"));}catch(java.io.IOException ignored){}}
        }
    }
    private void maintainJars(Plan plan,BuildRequest.Source source,DatabaseBuilder stage,SourceLifecycleStore store,KnowledgeBuilder.BuildResult stats,KnowledgeBuilder.ProgressCallback progress)throws Exception {
        var snapshot=RuntimeBuildContract.associated(db);
        var builder=new KnowledgeBuilder(config,stage,embedding,processors,scraper);
        builder.requireBoundWiki();builder.setEnvironmentStore(new ScanEnvironmentStore(db));builder.setBindingStore(new ModBindingStore(db));
        if(plan.itemId()!=null)builder.selectJarMods(Set.of(plan.itemId().substring(4)));
        builder.setScannedMods(db.findAllModEntries());
        var result=builder.build(new BuildRequest(List.of(source,snapshot),BuildRequest.Mode.APPEND),progress);
        stats.parsed=result.parsed;stats.conflicts=result.conflicts;stats.documents=result.documents;stats.runtimeSnapshots=result.runtimeSnapshots;
        store.captureJarOwners(source);
    }
    private static void check(){if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Source maintenance cancelled");}
}
