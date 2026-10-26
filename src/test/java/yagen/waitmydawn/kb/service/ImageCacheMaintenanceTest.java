package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.model.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ImageCacheMaintenanceTest {
    @TempDir Path temp;
    private final Clock clock=Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"),ZoneOffset.UTC);
    private DatabaseBuilder db() {var db=DatabaseBuilder.atPath(temp.resolve("base"));db.initDatabase();new VectorStore(db,384);return db;}
    private byte[] png(int color) throws Exception {
        var image=new java.awt.image.BufferedImage(20,12,java.awt.image.BufferedImage.TYPE_INT_RGB);image.setRGB(2,2,color);
        var output=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",output);return output.toByteArray();
    }
    private AnswerArtifacts.Picture picture(KnowledgeContent.ImageAsset asset) {
        return new AnswerArtifacts.Picture("i"+asset.id().substring(0,20),asset.id(),asset.relativePath(),"说明图",null,"ILLUSTRATION",List.of(),"");
    }
    private void session(Path directory,KnowledgeContent.ImageAsset asset) throws Exception {
        var history=new ChatHistoryService(directory);var session=history.newSession("图文记录",temp);
        history.addMessage(session,"maa","图文回答",new AnswerArtifacts(null,null,List.of(),List.of(picture(asset))));history.save(session);
    }
    @Test void cleanupProtectsBaseChatsAndEvalSnapshotsAndKeepsRecentOrphans() throws Exception {
        var db=db();var store=new ImageAssetStore(temp);var images=new ArrayList<KnowledgeContent.ImageAsset>();
        for(int i=1;i<=7;i++) {
            var asset=store.put(png(i));images.add(asset);
            Files.setLastModifiedTime(store.resolve(asset.id(),asset.relativePath()),FileTime.from(clock.instant().minus(Duration.ofDays(8))));
        }
        new KnowledgeContentStore(db).saveImage(images.get(0));
        session(temp,images.get(1));session(temp.resolve("eval-runs/run-test"),images.get(2));
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(temp.resolve("sessions/legacy.json").toFile(),
                Map.of("messages",List.of(Map.of("role","maa","content","![旧图](D:/old/assets/images/"+images.get(6).relativePath()+")"))));
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(temp.resolve("eval-runs/run-test/turns.jsonl").toFile(),
                Map.of("observation",Map.of("artifacts",new AnswerArtifacts(null,null,List.of(),List.of(picture(images.get(3)))))));
        Files.setLastModifiedTime(store.resolve(images.get(5).id(),images.get(5).relativePath()),FileTime.from(clock.instant()));
        try {
            var service=new ImageCacheMaintenance(db,temp,clock);var report=service.inspect();
            assertTrue(report.cleanupSafe());assertEquals(5,report.references());assertEquals(2,report.orphans());assertEquals(1,report.removable());
            String fingerprint=db.contentFingerprint();var result=service.cleanUnreferenced();
            assertEquals(1,result.removed());assertEquals(fingerprint,db.contentFingerprint());
            for(int i:List.of(0,1,2,3,5,6)) assertTrue(store.available(images.get(i).id(),images.get(i).relativePath()));
            assertFalse(Files.exists(store.resolve(images.get(4).id(),images.get(4).relativePath())));
        } finally {db.shutdown();}
    }

    @Test void unreadableOrUnsafeHistoryBlocksAllCleanup() throws Exception {
        var db=db();var store=new ImageAssetStore(temp);var asset=store.put(png(123));Path path=store.resolve(asset.id(),asset.relativePath());
        Files.setLastModifiedTime(path,FileTime.from(clock.instant().minus(Duration.ofDays(9))));
        Path sessions=Files.createDirectories(temp.resolve("sessions"));Path bad=sessions.resolve("bad.json");
        Files.writeString(bad,"{\"messages\":[");
        try {
            var service=new ImageCacheMaintenance(db,temp,clock);
            assertFalse(service.inspect().cleanupSafe());assertThrows(java.io.IOException.class,service::cleanUnreferenced);assertTrue(Files.exists(path));
            Files.writeString(bad,"null");
            assertFalse(service.inspect().cleanupSafe());assertThrows(java.io.IOException.class,service::cleanUnreferenced);assertTrue(Files.exists(path));
            Files.writeString(bad,"{\"assetId\":\""+asset.id()+"\",\"relativePath\":\"../../outside.png\"}");
            assertFalse(service.inspect().cleanupSafe());assertThrows(java.io.IOException.class,service::cleanUnreferenced);assertTrue(Files.exists(path));
        } finally {db.shutdown();}
    }

    @Test void knownBytesAtomicallyRepairCorruptionAndRejectChangedSource() throws Exception {
        var store=new ImageAssetStore(temp);byte[] bytes=Files.readAllBytes(Path.of("src/test/resources/web/tiny.webp"));
        var asset=store.put(bytes);Path path=store.resolve(asset.id(),asset.relativePath());
        Files.writeString(path,"broken");assertFalse(store.available(asset.id(),asset.relativePath()));
        assertEquals(asset,store.restore(asset.id(),bytes));assertTrue(store.available(asset.id(),asset.relativePath()));
        Files.delete(path);store.put(bytes);assertTrue(store.available(asset.id(),asset.relativePath()));
        assertThrows(java.io.IOException.class,()->store.restore(asset.id(),png(12)));
        assertTrue(store.available(asset.id(),asset.relativePath()));
    }

    @Test void danglingBaseReferencesBlockCleanup() throws Exception {
        var db=db();var store=new ImageAssetStore(temp);var asset=store.put(png(321));var path=store.resolve(asset.id(),asset.relativePath());
        Files.setLastModifiedTime(path,FileTime.from(clock.instant().minus(Duration.ofDays(9))));
        new KnowledgeContentStore(db).saveOccurrence(new KnowledgeContent.ImageOccurrence("broken","missing-document",asset.id(),"图",null,null,0));
        try {
            var service=new ImageCacheMaintenance(db,temp,clock);
            assertThrows(java.io.IOException.class,service::inspect);assertThrows(java.io.IOException.class,service::cleanUnreferenced);
            assertTrue(Files.isRegularFile(path));
        } finally {db.shutdown();}
    }

    @Test void webRepairRestoresOriginalSnapshotFromPersistentCacheAfterBaseRebuild() throws Exception {
        var db=db();var store=new ImageAssetStore(temp);byte[] bytes=png(100);var asset=store.put(bytes);var calls=new AtomicInteger();
        var image=new AnswerArtifacts.Picture("i1",asset.id(),asset.relativePath(),"示意图",null,"ILLUSTRATION",List.of(),"","https://images.example/one.png","https://example.com/guide");
        var history=new ChatHistoryService(temp);var session=history.newSession("旧会话",temp);
        history.addMessage(session,"maa","回答",new AnswerArtifacts(null,null,List.of(),List.of(image)));history.save(session);
        var scraper=new WikiScraperService() {
            @Override public Resource fetchResource(String url,String referer,boolean isImage,Map<String,String> headers) {
                assertEquals("https://images.example/one.png",url);assertEquals("https://example.com/guide",referer);assertTrue(isImage);
                calls.incrementAndGet();return new Resource(200,bytes,"image/png",url,null,null);
            }
        };
        try {
            db.clearBuildData();String fingerprint=db.contentFingerprint();
            Files.delete(store.resolve(asset.id(),asset.relativePath()));
            var service=new ImageCacheMaintenance(db,temp,clock);
            assertEquals(1,service.repairMissing(scraper,1).restored());assertEquals(1,calls.get());
            Files.writeString(store.resolve(asset.id(),asset.relativePath()),"bad");
            assertEquals(1,service.repairMissing(scraper,1).restored());assertEquals(1,calls.get());
            assertEquals(fingerprint,db.contentFingerprint());assertEquals(0,service.inspect().missing());assertEquals(0,service.inspect().corrupt());
        } finally {db.shutdown();}
    }

    @Test void repairBudgetAndChangedImagesDoNotRewriteKnowledgeOrSnapshotIdentity() throws Exception {
        var db=db();var store=new ImageAssetStore(temp);var content=new KnowledgeContentStore(db);var calls=new AtomicInteger();
        var source=BuildRequest.Source.of(BuildRequest.Kind.URL,"https://example.com/guide");content.saveSource(source);
        content.saveDocument(new KnowledgeContent.Document("d",source.id(),"教程",source.location(),BuildRequest.fingerprint("body"),"text/html"));
        for(int i=1;i<=3;i++) {
            var asset=store.put(png(i));content.saveImage(asset);
            content.saveOccurrence(new KnowledgeContent.ImageOccurrence("o"+i,"d",asset.id(),"图","https://images.example/"+i+".png",null,i));
            Files.delete(store.resolve(asset.id(),asset.relativePath()));
        }
        byte[] changed=png(500);
        var web=new WikiScraperService() {@Override public Resource fetchResource(String url,String referer,boolean image,Map<String,String> headers) {
            calls.incrementAndGet();return new Resource(200,changed,"image/png",url,null,null);
        }};
        try {
            String fingerprint=db.contentFingerprint();var result=new ImageCacheMaintenance(db,temp,clock).repairMissing(web,2);
            assertEquals(2,result.attempted());assertEquals(2,calls.get());assertEquals(0,result.restored());assertEquals(3,result.after().missing());
            assertEquals(0,result.after().files());assertEquals(fingerprint,db.contentFingerprint());
        } finally {db.shutdown();}
    }
}
