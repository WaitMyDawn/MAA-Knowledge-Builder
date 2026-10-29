package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.config.*;
import yagen.waitmydawn.kb.model.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SourceLifecycleTest {
    @TempDir Path temp;
    AppConfig config;DatabaseBuilder db;EmbeddingService embedding;Path docs;BuildRequest.Source source;
    final AtomicInteger embedded=new AtomicInteger();
    @BeforeEach void init()throws Exception {
        config=AppConfig.forIsolatedRun(false);config.setDataDir(temp.resolve("data"));db=new DatabaseBuilder(config);db.initDatabase();
        embedding=new EmbeddingService(null){@Override public List<float[]> embedBatch(List<String> texts){embedded.addAndGet(texts.size());return super.embedBatch(texts);}};
        docs=Files.createDirectory(temp.resolve("docs"));Files.writeString(docs.resolve("one.txt"),"傀儡的旧组装步骤：需要铜锭。");Files.writeString(docs.resolve("two.txt"),"女仆可以钓鱼，这是保持不变的资料。");
        source=BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,docs.toString());new KnowledgeBuilder(config,db,embedding).build(RuntimeBuildFixtures.withSnapshot(new BuildRequest(List.of(source)),config.getDataDir()),null);
    }
    @AfterEach void close(){embedding.close();db.shutdown();LogSetup.shutdown();}
    SourceLifecycleService service(){return new SourceLifecycleService(config,db,embedding);}
    long count(String sql)throws Exception {try(var c=db.getConnection();var s=c.createStatement();var r=s.executeQuery(sql)){r.next();return r.getLong(1);}}
    @Test void refreshChangesOnlyModifiedFileAndRemovesMissingFile()throws Exception {
        int before=embedded.get();Files.writeString(docs.resolve("one.txt"),"傀儡的新组装步骤：改为需要铁锭。");
        var manager=service();var plan=manager.preview(source.id(),null,SourceLifecycleService.Operation.REFRESH,null);manager.apply(plan,null);
        assertEquals(before+1,embedded.get());assertEquals(0,count("SELECT COUNT(*) FROM rag_vectors WHERE chunk_text LIKE '%需要铜锭%'"));assertEquals(1,count("SELECT COUNT(*) FROM rag_vectors WHERE chunk_text LIKE '%需要铁锭%'"));
        before=embedded.get();manager.apply(manager.preview(source.id(),null,SourceLifecycleService.Operation.REFRESH,null),null);assertEquals(before,embedded.get());
        Files.delete(docs.resolve("two.txt"));manager.apply(manager.preview(source.id(),null,SourceLifecycleService.Operation.REFRESH,null),null);assertEquals(1,count("SELECT COUNT(*) FROM rag_document"));
        assertFalse(manager.entries().stream().filter(e->e.source().id().equals(source.id())).findFirst().orElseThrow().needsMigration());new KnowledgeContentStore(db).validate();
    }
    @Test void singleDocumentRefreshDoesNotImportUnselectedNewFile()throws Exception {
        String id=BuildRequest.fingerprint(docs.resolve("one.txt").toString());Files.writeString(docs.resolve("one.txt"),"傀儡改为使用金锭。");Files.writeString(docs.resolve("new.txt"),"未选择的新文件不应导入。");
        var manager=service();manager.apply(manager.preview(source.id(),id,SourceLifecycleService.Operation.REFRESH,null),null);assertEquals(2,count("SELECT COUNT(*) FROM rag_document"));assertEquals(0,count("SELECT COUNT(*) FROM rag_vectors WHERE chunk_text LIKE '%未选择的新文件%'"));
    }
    @Test void sharedDocumentSurvivesDeletingOneSourceThenLastReferenceIsRemoved()throws Exception {
        var nested=BuildRequest.Source.of(BuildRequest.Kind.DOCUMENT_DIRECTORY,docs.getParent().toString());new KnowledgeBuilder(config,db,embedding).build(RuntimeBuildFixtures.withSnapshot(new BuildRequest(List.of(nested),BuildRequest.Mode.APPEND),config.getDataDir()),null);
        var manager=service();assertEquals(2,manager.preview(source.id(),null,SourceLifecycleService.Operation.DELETE,null).sharedDocuments());
        manager.apply(manager.preview(source.id(),null,SourceLifecycleService.Operation.DELETE,null),null);assertEquals(2,count("SELECT COUNT(*) FROM rag_document"));new KnowledgeContentStore(db).validate();
        manager.apply(manager.preview(nested.id(),null,SourceLifecycleService.Operation.DELETE,null),null);assertEquals(0,count("SELECT COUNT(*) FROM rag_document"));assertEquals(0,new VectorStore(db,384).count());assertEquals(1,manager.entries().size());
    }
    @Test void replaceCarriesLogicalIdentityAndDoesNotLeaveOldVectors()throws Exception {
        var old=new SourceLifecycleStore(db).state(source.id());Path folder=Files.createDirectory(temp.resolve("replacement"));Files.writeString(folder.resolve("next.txt"),"替换来源只保留新的资料。");
        var next=BuildRequest.Source.of(source.kind(),folder.toString());var manager=service();manager.apply(manager.preview(source.id(),null,SourceLifecycleService.Operation.REPLACE,next),null);
        assertEquals(2,manager.entries().size());assertTrue(manager.entries().stream().anyMatch(e->e.source().id().equals(next.id())));assertEquals(old.logicalId(),new SourceLifecycleStore(db).state(next.id()).logicalId());assertEquals(1,new VectorStore(db,384).count());assertEquals(0,count("SELECT COUNT(*) FROM rag_vectors WHERE chunk_text LIKE '%旧组装步骤%'"));
    }
    @Test void failedReplacementAndCancellationRetainExactOriginalData()throws Exception {
        var manager=service();String before=db.contentFingerprint();var missing=BuildRequest.Source.of(source.kind(),temp.resolve("missing").toString());
        assertThrows(IllegalStateException.class,()->manager.apply(manager.preview(source.id(),null,SourceLifecycleService.Operation.REPLACE,missing),null));assertEquals(before,db.contentFingerprint());
        Thread.currentThread().interrupt();try{assertThrows(java.util.concurrent.CancellationException.class,()->manager.apply(manager.preview(source.id(),null,SourceLifecycleService.Operation.DELETE,null),null));}finally{Thread.interrupted();}assertEquals(before,db.contentFingerprint());
    }
    @Test void stalePreviewCannotPublishAndLegacyParserCanBeExplicitlyRebuilt()throws Exception {
        var manager=service();var stale=manager.preview(source.id(),null,SourceLifecycleService.Operation.DELETE,null);db.setBuildGeneration("concurrent-change");String before=db.contentFingerprint();assertThrows(IllegalStateException.class,()->manager.apply(stale,null));assertEquals(before,db.contentFingerprint());
        new SourceLifecycleStore(db).execute("UPDATE rag_source_state SET parser_version='old' WHERE id=?",source.id());assertTrue(manager.entries().stream().filter(e->e.source().id().equals(source.id())).findFirst().orElseThrow().needsMigration());int calls=embedded.get();
        manager.apply(manager.preview(source.id(),null,SourceLifecycleService.Operation.REPARSE,null),null);assertEquals(calls+2,embedded.get());assertFalse(manager.entries().stream().filter(e->e.source().id().equals(source.id())).findFirst().orElseThrow().needsMigration());
    }
    @Test void appendDoesNotClaimLegacyDocumentsWereReparsed() {
        var store=new SourceLifecycleStore(db);store.execute("DELETE FROM rag_document_parser");store.execute("DELETE FROM rag_source_state");
        new KnowledgeBuilder(config,db,embedding).build(RuntimeBuildFixtures.withSnapshot(new BuildRequest(List.of(source),BuildRequest.Mode.APPEND),config.getDataDir()),null);
        assertTrue(service().entries().stream().filter(e->e.source().id().equals(source.id())).findFirst().orElseThrow().needsMigration());assertEquals("legacy/mixed",store.state(source.id()).parserVersion());
    }
    @Test void deletingIconSourceClearsSelectionButPreservesHistoryImage()throws Exception {
        Path pack=temp.resolve("pack");new NativeIconPackTest().fixture(pack);var icons=BuildRequest.Source.of(source.kind(),pack.toString());new RuntimeBuildFixtures.IconArchiveImporter(config,db,embedding).build(new BuildRequest(List.of(icons),BuildRequest.Mode.APPEND),null);
        var store=new ItemIconStore(db);String profile=store.profiles().stream().filter(p->p.location().equals(pack.toString())).findFirst().orElseThrow().id();store.selectProfile(profile);var icon=store.findDefault(profile,"demo:widget").orElseThrow();
        var picture=new AnswerArtifacts.Picture("i1",icon.assetId(),icon.relativePath(),"旧图片",null,"RECIPE",List.of(),"");var history=new ChatHistoryService(config.getDataDir());var session=history.newSession("旧快照",config.getDataDir());history.addMessage(session,"maa","![旧图片](kb-image:i1)",new AnswerArtifacts(null,null,List.of(),List.of(picture)));history.save(session);
        var manager=service();manager.apply(manager.preview(icons.id(),null,SourceLifecycleService.Operation.DELETE,null),null);assertNull(store.activeProfileId());assertEquals(1,store.profiles().size());
        assertEquals(1,AnswerMedia.assess(session.messages.getFirst().content(),history.load(session.sessionId).messages.getFirst().artifacts(),config.getDataDir()).valid());assertEquals(2,new VectorStore(db,384).count());
    }
}
