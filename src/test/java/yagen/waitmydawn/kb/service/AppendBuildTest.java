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
            builder.build(RuntimeBuildFixtures.withSnapshot(documents(docs,BuildRequest.Mode.REBUILD),config.getDataDir()),null);
            var history=new ChatHistoryService(config.getDataDir());var session=history.newSession("原有会话",config.getDataDir());
            history.addMessage(session,"maa","旧回答");history.save(session);
            var oldHits=new VectorStore(db,384).search(embedder.embed("傀儡核心"),10);
            var oldVector=new VectorStore(db,384).loadEmbeddings(oldHits,embedder.profile());
            int before=calls.get();
            Files.writeString(docs.resolve("two.txt"),"新增资料：女仆能够进行钓鱼工作。");
            var result=builder.build(RuntimeBuildFixtures.withSnapshot(documents(docs,BuildRequest.Mode.APPEND),config.getDataDir()),null);
            assertEquals(1,result.reusedDocuments);assertEquals(1,result.addedEmbeddings);assertEquals(before+1,calls.get());
            assertEquals(2,count(db,"rag_document"));
            var retained=new VectorStore(db,384).loadEmbeddings(oldHits,embedder.profile());
            oldVector.forEach((id,vector) -> assertArrayEquals(vector,retained.get(id)));
            assertEquals("旧回答",history.load(session.sessionId).messages.getFirst().content());
            String fingerprint=db.contentFingerprint();int after=calls.get();
            var repeated=builder.build(RuntimeBuildFixtures.withSnapshot(documents(docs,BuildRequest.Mode.APPEND),config.getDataDir()),null);
            assertEquals(0,repeated.addedEmbeddings);assertEquals(after,calls.get());assertEquals(fingerprint,db.contentFingerprint());
            Files.writeString(docs.resolve("one.txt"),"已修改但不应覆盖旧内容。");
            var changed=builder.build(RuntimeBuildFixtures.withSnapshot(documents(docs,BuildRequest.Mode.APPEND),config.getDataDir()),null);
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
            builder.build(RuntimeBuildFixtures.withSnapshot(new BuildRequest(List.of(one)),config.getDataDir()),null);
            builder.build(RuntimeBuildFixtures.withSnapshot(new BuildRequest(List.of(two),BuildRequest.Mode.APPEND),config.getDataDir()),null);
            assertEquals(2,calls.get());assertEquals(2,count(db,"rag_document"));assertEquals(3,count(db,"rag_knowledge_source"));
            String fingerprint=db.contentFingerprint();
            var repeated=builder.build(RuntimeBuildFixtures.withSnapshot(new BuildRequest(List.of(one,two),BuildRequest.Mode.APPEND),config.getDataDir()),null);
            assertEquals(2,repeated.reusedSources);assertEquals(2,calls.get());assertEquals(fingerprint,db.contentFingerprint());
        } finally {db.shutdown();}
    }

    @Test void failedOrCancelledAppendDoesNotPublishPartialData() throws Exception {
        var config=config();var db=database(config);
        try(var embedder=new EmbeddingService(null)) {
            Path docs=Files.createDirectory(temp.resolve("docs"));Files.writeString(docs.resolve("one.txt"),"旧资料有完整的傀儡组装步骤。");
            var builder=new KnowledgeBuilder(config,db,embedder);builder.build(RuntimeBuildFixtures.withSnapshot(documents(docs,BuildRequest.Mode.REBUILD),config.getDataDir()),null);
            String fingerprint=db.contentFingerprint();
            BuildSourceProcessor failing=new BuildSourceProcessor() {
                @Override public boolean supports(BuildRequest.Kind kind) {return kind==BuildRequest.Kind.URL;}
                @Override public void process(BuildRequest.Source source,Context context) {
                    context.vectors().storeBatch(List.of(new TextChunker.TextChunk("partial","不应发布的向量",null,"x","web",source.location())),context.embedder());
                    throw new IllegalStateException("解析失败");
                }
            };
            var request=new BuildRequest(List.of(BuildRequest.Source.of(BuildRequest.Kind.URL,"https://example.com/fail")),BuildRequest.Mode.APPEND);
            assertThrows(IllegalStateException.class,()->new KnowledgeBuilder(config,db,embedder,List.of(failing)).build(RuntimeBuildFixtures.withSnapshot(request,config.getDataDir()),null));
            assertEquals(fingerprint,db.contentFingerprint());
            Files.writeString(docs.resolve("two.txt"),"新增资料应在取消后保持未发布。");
            try {
                assertThrows(java.util.concurrent.CancellationException.class,()->builder.build(RuntimeBuildFixtures.withSnapshot(documents(docs,BuildRequest.Mode.APPEND),config.getDataDir()),
                        (phase,current,total,message) -> {if(phase.equals("documents")) Thread.currentThread().interrupt();}));
            } finally {Thread.interrupted();}
            assertEquals(fingerprint,db.contentFingerprint());
        } finally {db.shutdown();}
    }

    @Test void appendRejectsDifferentEmbeddingProfileBeforeProcessing() throws Exception {
        var config=config();var db=database(config);Path docs=Files.createDirectory(temp.resolve("docs"));
        Files.writeString(docs.resolve("one.txt"),"傀儡组装资料。");
        try(var embedder=new EmbeddingService(null)) {new KnowledgeBuilder(config,db,embedder).build(RuntimeBuildFixtures.withSnapshot(documents(docs,BuildRequest.Mode.REBUILD),config.getDataDir()),null);}
        String fingerprint=db.contentFingerprint();
        try(var other=new EmbeddingService(null) {@Override public String profile(){return "different-model:384";}}) {
            assertThrows(IllegalStateException.class,()->new KnowledgeBuilder(config,db,other).build(RuntimeBuildFixtures.withSnapshot(documents(docs,BuildRequest.Mode.APPEND),config.getDataDir()),null));
            assertEquals(fingerprint,db.contentFingerprint());
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

}
