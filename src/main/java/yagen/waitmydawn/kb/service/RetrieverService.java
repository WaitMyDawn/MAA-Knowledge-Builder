package yagen.waitmydawn.kb.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yagen.waitmydawn.kb.dto.ClassificationResult;
import yagen.waitmydawn.kb.dto.RetrievalResult;
import yagen.waitmydawn.kb.model.DatabaseBuilder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * 混合检索器: 向量语义搜索 + 结构化配方精确查询。
 */
public class RetrieverService {

    private static final Logger log = LoggerFactory.getLogger(RetrieverService.class);
    private final DatabaseBuilder db;
    private final VectorStore vectorStore;
    private final EmbeddingService embedder;

    public RetrieverService(DatabaseBuilder db, VectorStore vectorStore, EmbeddingService embedder) {
        this.db = db;
        this.vectorStore = vectorStore;
        this.embedder = embedder;
    }

    public RetrievalResult retrieve(ClassificationResult classification, String rawQuestion) {
        RetrievalResult result = new RetrievalResult();

        // 1: Vector semantic search FIRST (for Chinese-English mapping)
        retrieveVectorSemantic(rawQuestion, result);

        // 2: Extract possible English item names from vector results
        List<String> searchTerms = new java.util.ArrayList<>(classification.getEntities());
        for (String snippet : result.getWikiSnippets()) {
            // Extract patterns like "modid:item_name" from vector chunks
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("([a-z_]+):([a-z_]+)").matcher(snippet);
            while (m.find()) { String term = m.group(2); if (term.length() > 2) searchTerms.add(term); }
        }

        // 3: Recipe DB search with enhanced terms
        retrieveRecipes(searchTerms, result);

        // 4: Multiblock
        retrieveMultiblocks(result);

        // 5: Fallback — broad search
        if (!result.isFound()) {
            retrieveAllRecipes(result);
            result.setFound(!result.getRecipeJsons().isEmpty());
            result.setSourceDescription("broad recipe search");
        }

        return result;
    }

    private void retrieveRecipes(List<String> searchTerms, RetrievalResult result) {
        StringBuilder sql = new StringBuilder(
                "SELECT recipe_data, output_item FROM rag_recipe WHERE 1=1");

        if (!searchTerms.isEmpty()) {
            sql.append(" AND (");
            for (int i = 0; i < searchTerms.size(); i++) {
                if (i > 0) sql.append(" OR ");
                sql.append("output_item LIKE ? OR recipe_data LIKE ?");
            }
            sql.append(")");
        }
        sql.append(" LIMIT 30");

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            if (!searchTerms.isEmpty()) {
                for (String term : searchTerms) {
                    String p = "%" + term + "%";
                    ps.setString(idx++, p); ps.setString(idx++, p);
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.getRecipeJsons().add(rs.getString("recipe_data"));
                    lookupTexture(rs.getString("output_item"), result);
                }
            }
        } catch (SQLException e) { log.error("Recipe retrieval failed", e); }

        if (!result.getRecipeJsons().isEmpty()) {
            result.setFound(true);
            result.setSourceDescription("recipe DB (" + result.getRecipeJsons().size() + " recipes)");
        }
    }

    /** 向量语义搜索 */
    private void retrieveVectorSemantic(String question, RetrievalResult result) {
        if (vectorStore.count() == 0) return;

        float[] qVec = embedder.embed(question);
        List<VectorStore.SearchResult> hits = vectorStore.search(qVec, 10);

        StringBuilder snippets = new StringBuilder();
        for (VectorStore.SearchResult hit : hits) {
            if (hit.score() > 0.3) {  // Relevance threshold
                result.getWikiSnippets().add(
                        "[" + hit.modName() + "/" + hit.type() + "] " + hit.chunkText());
            }
        }

        if (!result.getWikiSnippets().isEmpty()) {
            result.setFound(true);
            result.setSourceDescription("vector search (" + result.getWikiSnippets().size() + " chunks, top score="
                    + String.format("%.3f", hits.isEmpty() ? 0 : hits.get(0).score()) + ")");
        }
    }

    private void retrieveMultiblocks(RetrievalResult result) {
        String sql = "SELECT layers_json FROM rag_multiblock LIMIT 10";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.getMultiblockJsons().add(rs.getString("layers_json"));
            if (!result.getMultiblockJsons().isEmpty()) result.setFound(true);
        } catch (SQLException e) { log.error("Multiblock retrieval failed", e); }
    }

    private void retrieveAllRecipes(RetrievalResult result) {
        String sql = "SELECT recipe_data, output_item FROM rag_recipe LIMIT 40";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.getRecipeJsons().add(rs.getString("recipe_data"));
                lookupTexture(rs.getString("output_item"), result);
            }
        } catch (SQLException e) { log.error("Fallback retrieval failed", e); }
    }

    private void lookupTexture(String outputItem, RetrievalResult result) {
        if (outputItem == null) return;
        String sql = "SELECT local_path FROM rag_texture_cache WHERE registry_name = ? LIMIT 1";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, outputItem);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) result.getTexturePaths().put(outputItem, rs.getString("local_path"));
            }
        } catch (SQLException ignored) {}
    }
}
