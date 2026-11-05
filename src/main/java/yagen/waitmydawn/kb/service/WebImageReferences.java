package yagen.waitmydawn.kb.service;

import yagen.waitmydawn.kb.model.DatabaseBuilder;

import java.util.*;

/** 文本 chunk 与网页图片引用的确定性绑定；不按问题或图片语义猜测相关性。 */
final class WebImageReferences {
    private WebImageReferences() {}

    static List<Map<String, Object>> forChunk(DatabaseBuilder db, String chunkId) {
        if (db == null || chunkId == null || chunkId.isBlank()) return List.of();
        var result = new ArrayList<Map<String, Object>>();
        String sql = """
                SELECT r.id, r.url, r.caption, r.block_ordinal, r.status
                FROM rag_web_image_reference r
                JOIN rag_content_chunk c ON c.document_id=r.document_id
                JOIN rag_chunk_block l ON l.chunk_id=c.id
                JOIN rag_content_block b ON b.id=l.block_id AND b.document_id=r.document_id AND b.ordinal=r.block_ordinal
                WHERE c.id=? AND r.status<>'EXCLUDED_SMALL'
                ORDER BY r.block_ordinal,r.id
                """;
        try (var c = db.getConnection(); var p = c.prepareStatement(sql)) {
            p.setString(1, chunkId);
            try (var rows = p.executeQuery()) {
                while (rows.next()) {
                    var row = new LinkedHashMap<String, Object>();
                    row.put("imageId", rows.getString("id"));
                    row.put("url", rows.getString("url"));
                    row.put("caption", Objects.toString(rows.getString("caption"), ""));
                    row.put("blockOrdinal", rows.getInt("block_ordinal"));
                    row.put("status", rows.getString("status"));
                    result.add(row);
                }
            }
        } catch (java.sql.SQLException e) {
            if (e.getErrorCode() != 42102) throw new IllegalStateException("网页图片引用读取失败", e);
        }
        return List.copyOf(result);
    }
}
