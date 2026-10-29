package yagen.waitmydawn.kb.service;

import yagen.waitmydawn.kb.model.DatabaseBuilder;

/** 原始图文快照保留 DOM 与懒加载属性，独立于基础库发布且有总字节预算。 */
final class WebResourceCache {
    private static final long TTL = java.time.Duration.ofDays(7).toMillis();
    private static final long BYTE_BUDGET = 128L * 1024 * 1024;
    private final DatabaseBuilder db;
    private final WikiScraperService scraper;

    WebResourceCache(DatabaseBuilder db, WikiScraperService scraper) {
        this.db = db; this.scraper = scraper;
        try (var c = db.getConnection(); var s = c.createStatement()) {
            s.execute("""
                    CREATE TABLE IF NOT EXISTS rag_public_resource (
                      url VARCHAR(4000) PRIMARY KEY, final_url VARCHAR(4000) NOT NULL,
                      body BLOB NOT NULL, content_type VARCHAR(300), fetched_at BIGINT NOT NULL,
                      etag VARCHAR(2000), last_modified VARCHAR(2000))
                    """);
        } catch (java.sql.SQLException e) { throw new IllegalStateException("网页资源缓存初始化失败", e); }
    }

    synchronized WikiScraperService.Resource fetch(String input, String referer, boolean image) {
        return fetch(input,referer,image,false);
    }
    synchronized WikiScraperService.Resource fetch(String input, String referer, boolean image,boolean refresh) {
        String url = WebUrlPolicy.normalize(input);
        WikiScraperService.Resource cached = null;
        long fetchedAt = 0;
        try (var c = db.getConnection(); var p = c.prepareStatement("SELECT * FROM rag_public_resource WHERE url=?")) {
            p.setString(1, url);
            try (var r = p.executeQuery()) {
                if (r.next()) {
                    cached = new WikiScraperService.Resource(200, r.getBytes("body"), r.getString("content_type"),
                            r.getString("final_url"), r.getString("etag"), r.getString("last_modified"));
                    fetchedAt = r.getLong("fetched_at");
                }
            }
        } catch (java.sql.SQLException e) { throw new IllegalStateException("网页资源缓存读取失败", e); }
        if (!refresh && cached != null && System.currentTimeMillis() - fetchedAt < TTL) {
            RetrievalTelemetry.pageHit(true); return cached;
        }
        var headers = new java.util.LinkedHashMap<String, String>();
        if (cached != null) {
            if (cached.etag() != null) headers.put("If-None-Match", cached.etag());
            if (cached.lastModified() != null) headers.put("If-Modified-Since", cached.lastModified());
        }
        var fetched = scraper.fetchResource(url, referer, image, headers);
        if (fetched.status() == 304 && cached != null) fetched = new WikiScraperService.Resource(200, cached.bytes(),
                cached.contentType(), cached.url(), fetched.etag() == null ? cached.etag() : fetched.etag(),
                fetched.lastModified() == null ? cached.lastModified() : fetched.lastModified());
        if (fetched.status() == 200 && fetched.bytes() != null) put(url, fetched);
        return fetched;
    }

    WikiScraperService.Resource snapshot(String input) {
        String url=WebUrlPolicy.normalize(input);
        try(var c=db.getConnection();var p=c.prepareStatement("SELECT final_url,body,content_type,etag,last_modified FROM rag_public_resource WHERE url=?")) {
            p.setString(1,url);try(var r=p.executeQuery()){return r.next()?new WikiScraperService.Resource(200,r.getBytes(2),r.getString(3),r.getString(1),r.getString(4),r.getString(5)):new WikiScraperService.Resource(404,null,"",url,null,null);}
        }catch(java.sql.SQLException e){throw new IllegalStateException("原始网页缓存读取失败",e);}
    }
    void put(String url, WikiScraperService.Resource value) {
        try (var c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (var p = c.prepareStatement("MERGE INTO rag_public_resource KEY(url) VALUES(?,?,?,?,?,?,?)")) {
                    p.setString(1, url); p.setString(2, value.url()); p.setBytes(3, value.bytes());
                    p.setString(4, value.contentType()); p.setLong(5, System.currentTimeMillis());
                    p.setString(6, value.etag()); p.setString(7, value.lastModified()); p.executeUpdate();
                }
                long retained = 0; int count = 0;
                var evict = new java.util.ArrayList<String>();
                try (var s = c.createStatement(); var r = s.executeQuery("SELECT url,OCTET_LENGTH(body) FROM rag_public_resource ORDER BY fetched_at DESC,url")) {
                    while (r.next()) {
                        retained += r.getLong(2);
                        if (++count > 512 || retained > BYTE_BUDGET) evict.add(r.getString(1));
                    }
                }
                try (var p = c.prepareStatement("DELETE FROM rag_public_resource WHERE url=?")) {
                    for (String key : evict) { p.setString(1, key); p.addBatch(); } p.executeBatch();
                }
                c.commit();
            } catch (Exception e) { c.rollback(); throw e; }
        } catch (java.sql.SQLException e) { throw new IllegalStateException("网页资源缓存写入失败", e); }
    }
}
