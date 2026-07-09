package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 实体名解析器 — 中文名 → 英文注册名映射。
 * 从 JAR lang 文件 + 手工映射表构建双向索引。
 * 配方 Agent 的关键前置步骤。
 */
public class EntityResolver {

    private static final Logger log = LoggerFactory.getLogger(EntityResolver.class);

    // cnName → {enRegistryName}
    private final Map<String, String> cnToEn = new HashMap<>();
    // enRegistryName → cnName
    private final Map<String, String> enToCn = new HashMap<>();

    private static final Pattern REGISTRY_PATTERN = Pattern.compile("([a-z_]+):([a-z_]+)");

    /** Rebuild index from vector DB's lang_items chunks */
    public void rebuildFromDB(yagen.waitmydawn.kb.model.DatabaseBuilder db) {
        try (var c = db.getConnection();
             var s = c.createStatement();
             var r = s.executeQuery("SELECT chunk_text FROM rag_vectors WHERE chunk_type = 'lang_items' LIMIT 1000")) {
            int count = 0;
            while (r.next()) {
                String text = r.getString("chunk_text");
                if (text != null) {
                    for (String line : text.split("\n")) {
                        if (line.startsWith("  - ") && line.contains(": ")) {
                            String rest = line.substring(4);
                            int ci = rest.indexOf(": ");
                            if (ci > 1 && ci < 60) {
                                String en = rest.substring(0, ci).trim();
                                String cn = rest.substring(ci + 2).trim();
                                if (!en.isEmpty() && !cn.isEmpty() && !en.equals(cn)) {
                                    indexLangEntry(en, cn);
                                    count++;
                                }
                            }
                        }
                    }
                }
            }
            log.info("EntityResolver rebuilt: {} mappings", count);
        } catch (Exception e) { log.warn("Entity rebuild failed: {}", e.getMessage()); }
    }

    /** From a lang JSON key-value pair, try to extract display→registry mapping */
    public void indexLangEntry(String key, String displayName) {
        if (displayName == null || displayName.isBlank()) return;
        if (displayName.equals(key)) return;

        String registry = key;
        // Normalize: "item.minecraft.diamond_pickaxe" → "minecraft:diamond_pickaxe"
        // "block.iceandfire.dragon_skull" → "iceandfire:dragon_skull"
        if (registry.contains(".")) {
            String[] parts = registry.split("\\.");
            // Try common patterns: type.modid.itemname
            if (parts.length >= 3) {
                registry = parts[parts.length - 2] + ":" + parts[parts.length - 1];
            }
        }

        registry = registry.replace('.', ':');
        // Only keep if it looks like a registry name
        if (registry.matches("[a-z_]+:[a-z_]+")) {
            cnToEn.put(displayName, registry);
            enToCn.put(registry, displayName);
        }
    }

    /** Resolve Chinese name to English registry name */
    public String resolve(String chineseName) {
        if (chineseName == null || chineseName.isBlank()) return null;
        String cleaned = chineseName.trim().toLowerCase();

        // 1. Exact match
        String exact = cnToEn.get(cleaned);
        if (exact != null) return exact;
        for (var e : cnToEn.entrySet()) {
            if (e.getKey().equalsIgnoreCase(cleaned)) return e.getValue();
        }

        // 2. Contains match (e.g. "探险家指南针" contains "指南针" → compass)
        for (Map.Entry<String, String> e : cnToEn.entrySet()) {
            if (cleaned.contains(e.getKey()) || e.getKey().contains(cleaned)) {
                return e.getValue();
            }
        }

        // 3. Longest common substring (Chinese character overlap)
        String best = null; int bestLen = 0;
        for (String cn : cnToEn.keySet()) {
            int overlap = longestCommonSubstring(cleaned, cn);
            if (overlap > bestLen && overlap >= 2) { bestLen = overlap; best = cnToEn.get(cn); }
        }
        if (best != null) return best;

        // 4. Fuzzy English match (Levenshtein)
        if (cleaned.matches("[a-z_]+")) {
            return fuzzyEnglishMatch(cleaned);
        }
        return null;
    }

    /** Fuzzy match English names using Levenshtein distance */
    private String fuzzyEnglishMatch(String input) {
        String best = null; int bestDist = 99;
        for (String en : cnToEn.values()) {
            String shortEn = en.contains(":") ? en.substring(en.indexOf(':') + 1) : en;
            int d = levenshtein(input, shortEn);
            if (d < bestDist && d <= 2) { bestDist = d; best = en; }
        }
        if (best != null) return best;

        // Try matching against all enToCn keys
        for (String en : enToCn.keySet()) {
            String shortEn = en.contains(":") ? en.substring(en.indexOf(':') + 1) : en;
            int d = levenshtein(input, shortEn);
            if (d < bestDist && d <= 2) { bestDist = d; best = en; }
        }
        return best;
    }

    private int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++)
            for (int j = 1; j <= b.length(); j++)
                dp[i][j] = Math.min(dp[i-1][j-1] + (a.charAt(i-1) == b.charAt(j-1) ? 0 : 1),
                        Math.min(dp[i-1][j] + 1, dp[i][j-1] + 1));
        return dp[a.length()][b.length()];
    }

    /** Resolve with LLM fallback */
    public String resolveWithLLM(String chineseName, RagAgentService llm) {
        String direct = resolve(chineseName);
        if (direct != null) return direct;

        // Try LLM
        if (llm != null) {
            try {
                String prompt = "What is the Minecraft item registry name (format: modid:item_name) for \"" + chineseName + "\"? Reply with ONLY the registry name, nothing else. If unsure, reply with UNKNOWN.";
                String resp = llm.rawAsk(prompt);
                if (resp != null && resp.contains(":")) {
                    resp = resp.trim().replaceAll("[^a-z0-9_:]", "");
                    if (resp.matches("[a-z_]+:[a-z_]+")) {
                        cnToEn.put(chineseName, resp); // cache for future
                        return resp;
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** Get Chinese name for registry name */
    public String toChinese(String registry) { return enToCn.getOrDefault(registry, registry); }

    /** Index from lang file JSON */
    public void indexLangJson(String json) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> map = mapper.readValue(json, Map.class);
            for (Map.Entry<String, Object> e : map.entrySet()) {
                if (e.getValue() instanceof String val) {
                    indexLangEntry(e.getKey(), val);
                }
            }
        } catch (Exception ignored) {}
    }

    public int size() { return cnToEn.size(); }

    private int longestCommonSubstring(String a, String b) {
        int max = 0;
        for (int i = 0; i < a.length(); i++) {
            for (int j = 0; j < b.length(); j++) {
                int k = 0;
                while (i + k < a.length() && j + k < b.length() && a.charAt(i + k) == b.charAt(j + k)) k++;
                if (k > max) max = k;
            }
        }
        return max;
    }
}
