package yagen.waitmydawn.kb.service;

import yagen.waitmydawn.kb.model.DatabaseBuilder;
import yagen.waitmydawn.kb.model.KnowledgeContent.ImageAsset;
import java.time.Duration;
import java.util.*;

/** URL 到不可变图片资产的下载索引不参与构建发布，取消后仍可复用。 */
final class WebImageDownloadCache {
    static final long TTL=Duration.ofDays(7).toMillis();
    private final DatabaseBuilder db;
    private final ImageAssetStore assets;
    private record Stamp(long size,java.nio.file.attribute.FileTime modified,Object key){}
    private final Map<String,Stamp> verified=new java.util.concurrent.ConcurrentHashMap<>();
    WebImageDownloadCache(DatabaseBuilder db,ImageAssetStore assets) {
        this.db=db;this.assets=assets;
        try(var c=db.getConnection();var s=c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS rag_web_image_download(url VARCHAR(4000) NOT NULL,asset_root VARCHAR(4000) NOT NULL,asset_id VARCHAR(64) NOT NULL,relative_path VARCHAR(1000) NOT NULL,media_type VARCHAR(200) NOT NULL,width INT NOT NULL,height INT NOT NULL,fetched_at BIGINT NOT NULL,PRIMARY KEY(url,asset_root))");
            s.execute("CREATE INDEX IF NOT EXISTS idx_web_image_download_origin ON rag_web_image_reference(url)");
        }catch(java.sql.SQLException failure){throw new IllegalStateException("图片下载索引初始化失败",failure);}
    }
    ImageAsset find(String url,boolean cacheOnly) {
        try(var c=db.getConnection();var p=c.prepareStatement("SELECT * FROM rag_web_image_download WHERE url=? AND asset_root=?")) {
            p.setString(1,WebUrlPolicy.normalize(url));p.setString(2,assets.directory().toString());try(var rows=p.executeQuery()) {
                if(!rows.next())return legacy(url,cacheOnly);
                if(!cacheOnly&&System.currentTimeMillis()-rows.getLong("fetched_at")>=TTL)return null;
                var asset=new ImageAsset(rows.getString("asset_id"),rows.getString("relative_path"),rows.getString("media_type"),rows.getInt("width"),rows.getInt("height"),null,null);
                String key=asset.id()+"/"+asset.relativePath();
                try {
                    var attributes=java.nio.file.Files.readAttributes(assets.resolve(asset.id(),asset.relativePath()),java.nio.file.attribute.BasicFileAttributes.class,java.nio.file.LinkOption.NOFOLLOW_LINKS);
                    var stamp=new Stamp(attributes.size(),attributes.lastModifiedTime(),attributes.fileKey());
                    if(!attributes.isRegularFile()||!stamp.equals(verified.get(key))&&!assets.available(asset.id(),asset.relativePath()))return null;
                    verified.put(key,stamp);
                }catch(java.io.IOException invalid){verified.remove(key);return null;}
                return asset;
            }
        }catch(java.sql.SQLException failure){throw new IllegalStateException("图片下载索引读取失败",failure);}
    }
    void save(String url,ImageAsset image) {
        save(url,image,System.currentTimeMillis());
    }
    private ImageAsset legacy(String url,boolean cacheOnly) {
        try(var c=db.getConnection();var p=c.prepareStatement("SELECT a.* FROM rag_web_image_reference r JOIN rag_image_occurrence o ON o.id=r.id JOIN rag_image_asset a ON a.id=o.image_id WHERE r.url=? AND r.status='AVAILABLE' LIMIT 16")) {
            p.setString(1,WebUrlPolicy.normalize(url));try(var rows=p.executeQuery()){while(rows.next()) {
                var asset=new ImageAsset(rows.getString("id"),rows.getString("relative_path"),rows.getString("media_type"),rows.getInt("width"),rows.getInt("height"),null,null);
                try {
                    long fetched=Math.min(System.currentTimeMillis(),java.nio.file.Files.getLastModifiedTime(assets.resolve(asset.id(),asset.relativePath()),java.nio.file.LinkOption.NOFOLLOW_LINKS).toMillis());
                    if((cacheOnly||System.currentTimeMillis()-fetched<TTL)&&assets.available(asset.id(),asset.relativePath())){save(url,asset,fetched);return asset;}
                }catch(java.io.IOException invalid){/* 已发布的引用仍由图片维护检查，下载索引不接受不可用文件。 */}
            }}return null;
        }catch(java.sql.SQLException failure){throw new IllegalStateException("旧图片下载索引迁移失败",failure);}
    }
    private void save(String url,ImageAsset image,long fetchedAt) {
        try(var c=db.getConnection()) {
            c.setAutoCommit(false);
            try {
                try(var p=c.prepareStatement("MERGE INTO rag_web_image_download KEY(url,asset_root) VALUES(?,?,?,?,?,?,?,?)")) {
                    p.setString(1,WebUrlPolicy.normalize(url));p.setString(2,assets.directory().toString());p.setString(3,image.id());p.setString(4,image.relativePath());p.setString(5,image.mediaType());p.setInt(6,image.width());p.setInt(7,image.height());p.setLong(8,fetchedAt);p.executeUpdate();
                }
                // 文件资产已完整落盘，原始响应中的重复二进制可以释放，保留 HTML 缓存预算。
                try(var tables=c.getMetaData().getTables(null,null,"rag_public_resource",null)) {
                    if(tables.next())try(var p=c.prepareStatement("DELETE FROM rag_public_resource WHERE url=? AND LOWER(content_type) LIKE 'image/%'")){p.setString(1,WebUrlPolicy.normalize(url));p.executeUpdate();}
                }
                c.commit();
            }catch(Exception failure){c.rollback();throw failure;}
        }catch(java.sql.SQLException failure){throw new IllegalStateException("图片下载索引保存失败",failure);}
    }
}
