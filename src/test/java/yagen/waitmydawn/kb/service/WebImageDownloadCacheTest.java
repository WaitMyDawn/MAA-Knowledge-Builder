package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class WebImageDownloadCacheTest {
    @TempDir Path temp;
    @Test void publishedImagesFromOlderLibrariesCanSeedTheDownloadIndex()throws Exception {
        var db=DatabaseBuilder.atPath(temp.resolve("legacy"));db.initDatabase();
        try {
            var store=new KnowledgeContentStore(db);var source=yagen.waitmydawn.kb.model.BuildRequest.Source.of(yagen.waitmydawn.kb.model.BuildRequest.Kind.URL,"https://example.com/guide");store.saveSource(source);
            store.saveDocument(new yagen.waitmydawn.kb.model.KnowledgeContent.Document("doc",source.id(),"指南",source.location(),"hash","text/html"));
            var assets=new ImageAssetStore(temp);var image=assets.put(FullWebMediaTest.image(5,8,8));store.saveImage(image);
            store.saveOccurrence(new yagen.waitmydawn.kb.model.KnowledgeContent.ImageOccurrence("image","doc",image.id(),"图片","https://example.com/image.png",null,0));
            store.saveImageReference("image","doc","https://example.com/image.png","图片","正文",0,"AVAILABLE",null);
            var cache=new WebImageDownloadCache(db,assets);assertEquals(image.id(),cache.find("https://example.com/image.png",false).id());
            assertEquals(1,FullWebMediaTest.count(db,"SELECT COUNT(*) FROM rag_web_image_download"));
        }finally{db.shutdown();}
    }
    @Test void cacheRejectsChangedFilesAndKeepsAssetRootsSeparate()throws Exception {
        var db=DatabaseBuilder.atPath(temp.resolve("cache"));db.initDatabase();
        try {
            var assets=new ImageAssetStore(temp.resolve("first"));var first=assets.put(FullWebMediaTest.image(1,8,8));var cache=new WebImageDownloadCache(db,assets);
            cache.save("https://example.com/image.png",first);assertEquals(first.id(),cache.find("https://example.com/image.png",false).id());
            var otherAssets=new ImageAssetStore(temp.resolve("second"));var other=otherAssets.put(FullWebMediaTest.image(2,8,8));var second=new WebImageDownloadCache(db,otherAssets);
            assertNull(second.find("https://example.com/image.png",false));second.save("https://example.com/image.png",other);
            assertEquals(other.id(),second.find("https://example.com/image.png",false).id());assertEquals(first.id(),cache.find("https://example.com/image.png",false).id());
            Files.write(assets.resolve(first.id(),first.relativePath()),new byte[]{1,2,3});assertNull(cache.find("https://example.com/image.png",false));
        }finally{db.shutdown();}
    }
    @Test void recentDownloadsProtectUnpublishedAssetsUntilRetentionExpires()throws Exception {
        var db=DatabaseBuilder.atPath(temp.resolve("cache"));db.initDatabase();new VectorStore(db,384);
        try {
            var assets=new ImageAssetStore(temp);var image=assets.put(FullWebMediaTest.image(3,8,8));var cache=new WebImageDownloadCache(db,assets);cache.save("https://example.com/image.png",image);
            long now=System.currentTimeMillis();Files.setLastModifiedTime(assets.resolve(image.id(),image.relativePath()),FileTime.fromMillis(now-Duration.ofDays(9).toMillis()));
            var maintenance=new ImageCacheMaintenance(db,temp,Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC));
            assertEquals(0,maintenance.inspect().removable());assertEquals(0,maintenance.cleanUnreferenced().removed());
            try(var c=db.getConnection();var p=c.prepareStatement("UPDATE rag_web_image_download SET fetched_at=?")){p.setLong(1,now-Duration.ofDays(8).toMillis());p.executeUpdate();}
            assertNull(cache.find("https://example.com/image.png",false));assertNotNull(cache.find("https://example.com/image.png",true));
            assertEquals(1,maintenance.cleanUnreferenced().removed());assertFalse(assets.available(image.id(),image.relativePath()));
        }finally{db.shutdown();}
    }
}
