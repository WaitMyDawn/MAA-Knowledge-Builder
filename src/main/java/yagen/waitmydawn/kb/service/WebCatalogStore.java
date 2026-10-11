package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import yagen.waitmydawn.kb.model.WebCatalog;
import java.sql.*;
import java.util.*;

/** 分类索引与正文共享基础库构建代次；空索引表示旧库未补抓分类。 */
public final class WebCatalogStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final DatabaseBuilder db;
    public WebCatalogStore(DatabaseBuilder db) { this.db = db; }

    public static void init(Connection c) throws SQLException {
        try (var s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS rag_web_category(category_id VARCHAR(64) PRIMARY KEY, parent_id VARCHAR(64), mod_id VARCHAR(255) NOT NULL, category_url VARCHAR(1000), top_category INT, name VARCHAR(500), path_json CLOB)");
            s.execute("CREATE TABLE IF NOT EXISTS rag_web_category_member(category_id VARCHAR(64), item_url VARCHAR(1000), label VARCHAR(1000), chinese_name VARCHAR(500), english_name VARCHAR(500), role VARCHAR(50), PRIMARY KEY(category_id,item_url))");
            s.execute("CREATE INDEX IF NOT EXISTS idx_catalog_mod ON rag_web_category(mod_id,top_category)");
        }
    }

    public void save(WebCatalog catalog) {
        try (var c = db.getConnection()) {
            c.setAutoCommit(false);
            try (var nodes = c.prepareStatement("MERGE INTO rag_web_category KEY(category_id) VALUES (?,?,?,?,?,?,?)");
                 var members = c.prepareStatement("MERGE INTO rag_web_category_member KEY(category_id,item_url) VALUES (?,?,?,?,?,?)")) {
                for (var n : catalog.nodes()) {
                    nodes.setString(1,n.id()); nodes.setString(2,n.parentId()); nodes.setString(3,n.modId());
                    nodes.setString(4,n.categoryUrl()); nodes.setInt(5,n.topCategory()); nodes.setString(6,n.name());
                    nodes.setString(7,JSON.writeValueAsString(n.path())); nodes.addBatch();
                }
                nodes.executeBatch();
                for (var m : catalog.members()) {
                    members.setString(1,m.categoryId()); members.setString(2,m.url()); members.setString(3,m.label());
                    members.setString(4,m.chineseName()); members.setString(5,m.englishName()); members.setString(6,m.role()); members.addBatch();
                }
                members.executeBatch(); c.commit();
            } catch (Exception e) { c.rollback(); throw e; }
        } catch (Exception e) { throw new IllegalStateException("Catalog persistence failed",e); }
    }

    public record Entry(String categoryId, String categoryUrl, int topCategory, List<String> path,
                        String url, String label, String chineseName, String englishName, String role) {}
    public List<Entry> entries(String modId, int topCategory) {
        var result = new ArrayList<Entry>();
        String sql = "SELECT c.*,m.* FROM rag_web_category c JOIN rag_web_category_member m ON c.category_id=m.category_id WHERE c.mod_id=? AND c.top_category=? ORDER BY c.path_json,m.label,m.item_url";
        try (var c=db.getConnection(); var p=c.prepareStatement(sql)) {
            p.setString(1,modId); p.setInt(2,topCategory);
            try (var r=p.executeQuery()) {
                while(r.next()) result.add(new Entry(r.getString("category_id"),r.getString("category_url"),r.getInt("top_category"),
                        List.of(JSON.readValue(r.getString("path_json"),String[].class)),r.getString("item_url"),r.getString("label"),
                        r.getString("chinese_name"),r.getString("english_name"),r.getString("role")));
            }
        } catch (SQLException e) {
            if(e.getErrorCode()!=42102) throw new IllegalStateException("Catalog query failed",e);
        } catch (Exception e) { throw new IllegalStateException("Invalid catalog path",e); }
        return result;
    }
}
