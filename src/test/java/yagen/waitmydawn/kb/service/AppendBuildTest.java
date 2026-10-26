package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.config.*;
import yagen.waitmydawn.kb.model.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class AppendBuildTest {
    @TempDir Path temp;
    @org.junit.jupiter.api.AfterEach void logging() {LogSetup.shutdown();}
    private AppConfig config() throws Exception {
        var config=AppConfig.forIsolatedRun(false);config.setDataDir(temp.resolve("data"));return config;
    }
    private DatabaseBuilder database(AppConfig config) {
        var db=new DatabaseBuilder(config);db.initDatabase();return db;
    }
    private BuildRequest documents(Path path,BuildRequest.Mode mode) {
        return new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,path.toString())),mode);
    }
    private long count(DatabaseBuilder db,String table) throws Exception {
        try(var c=db.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table)) {r.next();return r.getLong(1);}
    }
    @Test void appendRetainsOldVectorsSkipsUnchangedFilesAndPreservesHistory() throws Exception {
        var config=config();var db=database(config);var calls=new AtomicInteger();
        try(var embedder=new EmbeddingService(null) {
            @Override public List<float[]> embedBatch(List<String> texts) {calls.incrementAndGet();return super.embedBatch(texts);}
        }) {
            Path docs=Files.createDirectory(temp.resolve("docs"));Files.writeString(docs.resolve("one.txt"),"旧资料：傀儡核心需要在装配台组装。");
            var builder=new KnowledgeBuilder(config,db,embedder);
            builder.build(documents(docs,BuildRequest.Mode.REBUILD),null);
            var history=new ChatHistoryService(config.getDataDir());var session=history.newSession("原有会话",config.getDataDir());
            history.addMessage(session,"maa","旧回答");history.save(session);
            var oldHits=new VectorStore(db,384).search(embedder.embed("傀儡核心"),10);
            var oldVector=new VectorStore(db,384).loadEmbeddings(oldHits,embedder.profile());
            int before=calls.get();
            Files.writeString(docs.resolve("two.txt"),"新增资料：女仆能够进行钓鱼工作。");
            var result=builder.build(documents(docs,BuildRequest.Mode.APPEND),null);
            assertEquals(1,result.reusedDocuments);assertEquals(1,result.addedEmbeddings);assertEquals(before+1,calls.get());
            assertEquals(2,count(db,"rag_document"));
            var retained=new VectorStore(db,384).loadEmbeddings(oldHits,embedder.profile());
            oldVector.forEach((id,vector) -> assertArrayEquals(vector,retained.get(id)));
            assertEquals("旧回答",history.load(session.sessionId).messages.getFirst().content());
            String fingerprint=db.contentFingerprint();int after=calls.get();
            var repeated=builder.build(documents(docs,BuildRequest.Mode.APPEND),null);
            assertEquals(0,repeated.addedEmbeddings);assertEquals(after,calls.get());assertEquals(fingerprint,db.contentFingerprint());
            Files.writeString(docs.resolve("one.txt"),"已修改但不应覆盖旧内容。");
            var changed=builder.build(documents(docs,BuildRequest.Mode.APPEND),null);
            assertEquals(1,changed.conflicts);assertEquals(0,changed.addedEmbeddings);assertEquals(fingerprint,db.contentFingerprint());
        } finally {db.shutdown();}
    }

    @Test void existingUrlIsSkippedBeforeNetworkAndUnselectedSourcesRemain() throws Exception {
        var config=config();var db=database(config);var calls=new AtomicInteger();
        var web=new WikiScraperService() {
            @Override public Resource fetchBuildResource(String url,String referer,boolean image) {
                calls.incrementAndGet();
                return new Resource(200,"<article><h1>傀儡教程</h1><p>傀儡核心的详细组装步骤与操作说明。</p></article>".getBytes(java.nio.charset.StandardCharsets.UTF_8),"text/html",url,null,null);
            }
        };
        var one=BuildRequest.Source.of(BuildRequest.Kind.URL,"https://example.com/one");
        var two=BuildRequest.Source.of(BuildRequest.Kind.URL,"https://example.com/two");
        try(var embedder=new EmbeddingService(null)) {
            var builder=new KnowledgeBuilder(config,db,embedder,List.of(new UrlBuildProcessor(web)));
            builder.build(new BuildRequest(List.of(one)),null);
            builder.build(new BuildRequest(List.of(two),BuildRequest.Mode.APPEND),null);
            assertEquals(2,calls.get());assertEquals(2,count(db,"rag_document"));assertEquals(2,count(db,"rag_knowledge_source"));
            String fingerprint=db.contentFingerprint();
            var repeated=builder.build(new BuildRequest(List.of(one,two),BuildRequest.Mode.APPEND),null);
            assertEquals(2,repeated.reusedSources);assertEquals(2,calls.get());assertEquals(fingerprint,db.contentFingerprint());
        } finally {db.shutdown();}
    }

    @Test void failedOrCancelledAppendDoesNotPublishPartialData() throws Exception {
        var config=config();var db=database(config);
        try(var embedder=new EmbeddingService(null)) {
            Path docs=Files.createDirectory(temp.resolve("docs"));Files.writeString(docs.resolve("one.txt"),"旧资料有完整的傀儡组装步骤。");
            var builder=new KnowledgeBuilder(config,db,embedder);builder.build(documents(docs,BuildRequest.Mode.REBUILD),null);
            String fingerprint=db.contentFingerprint();
            BuildSourceProcessor failing=new BuildSourceProcessor() {
                @Override public boolean supports(BuildRequest.Kind kind) {return kind==BuildRequest.Kind.URL;}
                @Override public void process(BuildRequest.Source source,Context context) {
                    context.vectors().storeBatch(List.of(new TextChunker.TextChunk("partial","不应发布的向量",null,"x","web",source.location())),context.embedder());
                    throw new IllegalStateException("解析失败");
                }
            };
            var request=new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.URL,"https://example.com/fail")),BuildRequest.Mode.APPEND);
            assertThrows(IllegalStateException.class,()->new KnowledgeBuilder(config,db,embedder,List.of(failing)).build(request,null));
            assertEquals(fingerprint,db.contentFingerprint());
            Files.writeString(docs.resolve("two.txt"),"新增资料应在取消后保持未发布。");
            try {
                assertThrows(java.util.concurrent.CancellationException.class,()->builder.build(documents(docs,BuildRequest.Mode.APPEND),
                        (phase,current,total,message) -> {if(phase.equals("documents")) Thread.currentThread().interrupt();}));
            } finally {Thread.interrupted();}
            assertEquals(fingerprint,db.contentFingerprint());
        } finally {db.shutdown();}
    }

    @Test void appendRejectsDifferentEmbeddingProfileBeforeProcessing() throws Exception {
        var config=config();var db=database(config);Path docs=Files.createDirectory(temp.resolve("docs"));
        Files.writeString(docs.resolve("one.txt"),"傀儡组装资料。");
        try(var embedder=new EmbeddingService(null)) {new KnowledgeBuilder(config,db,embedder).build(documents(docs,BuildRequest.Mode.REBUILD),null);}
        String fingerprint=db.contentFingerprint();
        try(var other=new EmbeddingService(null) {@Override public String profile(){return "different-model:384";}}) {
            assertThrows(IllegalStateException.class,()->new KnowledgeBuilder(config,db,other).build(documents(docs,BuildRequest.Mode.APPEND),null));
            assertEquals(fingerprint,db.contentFingerprint());
        } finally {db.shutdown();}
    }

    @Test void jarAppendExtractsOnlyNewModsAndDoesNotRescrapeExistingMods() throws Exception {
        var config=config();var db=database(config);var scraped=new ArrayList<String>();var baseline=new AtomicInteger();
        var web=new WikiScraperService() {
            @Override public WebPage fetchMcWiki(String page) {baseline.incrementAndGet();return new WebPage(page,"https://example.com/wiki/"+page,"基础合成知识说明。","mcwiki",Map.of());}
            @Override public ModScrapeResult scrapeModCatalog(String modId,ScrapeProgress progress) {
                scraped.add(modId);return new ModScrapeResult(List.of(),new WebCatalog(List.of(),List.of()));
            }
        };
        Path first=Files.createDirectory(temp.resolve("first")),second=Files.createDirectory(temp.resolve("second"));
        jar(first.resolve("old.jar"),"sample","1");jar(second.resolve("copy.jar"),"sample","1");jar(second.resolve("new.jar"),"another","1");
        try(var embedder=new EmbeddingService(null)) {
            var builder=new KnowledgeBuilder(config,db,embedder,null,web);
            builder.build(BuildRequest.jars(first),null);int initialBaseline=baseline.get();
            var result=builder.build(new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.JAR_DIRECTORY,second.toString())),BuildRequest.Mode.APPEND),null);
            assertEquals(1,result.parsed);assertEquals(1,result.skipped);assertEquals(List.of("sample","another"),scraped);
            assertEquals(initialBaseline,baseline.get());assertEquals(2,db.findAllModEntries().size());
            jar(second.resolve("copy.jar"),"sample","2");
            result=builder.build(new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.JAR_DIRECTORY,second.toString())),BuildRequest.Mode.APPEND),null);
            assertEquals(0,result.parsed);assertEquals(1,result.conflicts);assertEquals("1",db.findModEntry("sample").getVersion());
        } finally {db.shutdown();}
    }

    @Test void externalTargetIsLockedAndDoesNotSwitchCurrentConfiguration() throws Exception {
        var config=config();var current=database(config);Path external=Files.createDirectory(temp.resolve("external"));
        var externalConfig=config.forBuildTarget(external);var outside=database(externalConfig);outside.shutdown();
        try {
            try(var target=BuildTarget.open(external,config,current)) {
                assertTrue(target.external());assertEquals(temp.resolve("data"),config.getDataDir());
                assertThrows(java.io.IOException.class,()->DataDirLock.acquire(external));
            }
            try(var lock=DataDirLock.acquire(external)) {assertNotNull(lock);}
        } finally {current.shutdown();}
    }

    @Test void oldScanMetadataDoesNotCountAsAnImportedJar() throws Exception {
        var config=config();var db=database(config);
        var entry=new ModEntry();entry.setModId("sample");entry.setVersion("1");entry.setLoader("fabric");db.saveModEntry(entry);
        try(var embedder=new EmbeddingService(null)) {
            var index=new JarBuildIndex(db);
            assertEquals(JarBuildIndex.Status.NEW,index.inspect(entry,"a".repeat(64)));
            var vectors=new VectorStore(db,384);
            vectors.storeBatch(List.of(new TextChunker.TextChunk("old","旧版本JAR正文","sample","示例","description","mods.toml")),embedder);
            assertEquals(JarBuildIndex.Status.LEGACY,index.inspect(entry,"a".repeat(64)));
            entry.setVersion("2");assertEquals(JarBuildIndex.Status.CONFLICT,index.inspect(entry,"b".repeat(64)));
        } finally {db.shutdown();}
    }

    @Test void duplicateJarBackfillsRecipeResourcesWithoutChangingTextCorpus() throws Exception {
        var config=config();var db=database(config);
        Path directory=Files.createDirectory(temp.resolve("mods")),file=directory.resolve("sample.jar");
        try(var out=new JarOutputStream(Files.newOutputStream(file))) {
            for(var entry:Map.of("fabric.mod.json","{\"schemaVersion\":1,\"id\":\"sample\",\"version\":\"1\",\"name\":\"Sample\"}",
                    "data/sample/tags/items/materials.json","{\"values\":[\"sample:ingot\"]}",
                    "assets/sample/lang/zh_cn.json","{\"item.sample.ingot\":\"示例锭\"}").entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));out.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));out.closeEntry();
            }
        }
        try(var embedder=new EmbeddingService(null)) {
            var entry=new ModMetadataParser().parse(file);db.saveModEntry(entry);new JarBuildIndex(db).save(entry,JarBuildIndex.hash(file));
            var vectors=new VectorStore(db,384);
            vectors.storeBatch(List.of(new TextChunker.TextChunk("retained","既有配方说明","sample","示例","recipe","old")),embedder);
            db.setBuildGeneration("existing-corpus");
            try(var forbidden=new EmbeddingService(null) {
                @Override public float[] embed(String text){throw new AssertionError("Resource backfill must not embed");}
                @Override public List<float[]> embedBatch(List<String> texts){throw new AssertionError("Resource backfill must not embed");}
            }) {
                var request=new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.JAR_DIRECTORY,directory.toString())),BuildRequest.Mode.APPEND);
                var builder=new KnowledgeBuilder(config,db,forbidden);
                var result=builder.build(request,null);
                assertEquals(0,result.parsed);assertEquals(1,result.skipped);assertEquals(1,result.itemTagFiles);assertEquals(1,result.itemNames);
                assertEquals(1,vectors.count());assertEquals("existing-corpus",db.buildGeneration());
                String fingerprint=db.contentFingerprint();
                result=builder.build(request,null);
                assertEquals(0,result.itemTagFiles);assertEquals(0,result.itemNames);assertEquals(fingerprint,db.contentFingerprint());
            }
        } finally {db.shutdown();}
    }

    private void jar(Path path,String id,String version) throws Exception {
        try(var jar=new JarOutputStream(Files.newOutputStream(path))) {
            var entry=new JarEntry("fabric.mod.json");entry.setTime(0);jar.putNextEntry(entry);
            jar.write(("{\"schemaVersion\":1,\"id\":\""+id+"\",\"version\":\""+version+"\",\"name\":\""+id+"\",\"description\":\"傀儡组装与核心升级资料\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));jar.closeEntry();
        }
    }
}
