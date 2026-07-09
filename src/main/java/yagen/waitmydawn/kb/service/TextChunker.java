package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * 文本分块器 — 从 JAR 提取的各类数据生成适合向量化的文本块。
 * 块大小: 500-1000 字符, 重叠 50 字符。
 */
public class TextChunker {

    private static final int MAX_CHUNK = 1000;
    private static final int MIN_CHUNK = 500;
    private static final int OVERLAP = 50;
    private static final ObjectMapper mapper = new ObjectMapper();

    /** 单个文本块 */
    public record TextChunk(String id, String text, String modId, String modName, String type, String sourcePath) {}

    /**
     * 从 lang JSON (如 zh_cn.json) 提取物品名和描述。
     */
    public List<TextChunk> fromLangJson(String json, String modId, String modName, String sourcePath) {
        List<TextChunk> chunks = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(json);
            StringBuilder desc = new StringBuilder();
            desc.append("Mod: ").append(modName != null ? modName : modId).append("\n\n");
            desc.append("Items in ").append(modId).append(":\n");

            int count = 0;
            Iterator<String> fields = root.fieldNames();
            while (fields.hasNext()) {
                String key = fields.next();
                String val = root.path(key).asText("");
                if (val.isBlank() || val.equals(key)) continue;

                String itemName = key;
                if (itemName.startsWith("item.") || itemName.startsWith("block.")) {
                    itemName = itemName.substring(itemName.lastIndexOf('.') + 1);
                }
                if (itemName.startsWith(modId + ".")) {
                    itemName = itemName.substring(modId.length() + 1);
                }

                desc.append("  - ").append(itemName).append(": ").append(val).append("\n");
                count++;

                // Split into chunks when hitting limit
                if (desc.length() >= MAX_CHUNK) {
                    chunks.add(new TextChunk(modId + "/lang/" + chunks.size(),
                            desc.toString(), modId, modName,
                            "lang_items", sourcePath));
                    desc.setLength(Math.max(0, desc.length() - OVERLAP));
                }
            }
            if (desc.length() > 100 && count > 0) {
                chunks.add(new TextChunk(modId + "/lang/" + chunks.size(),
                        desc.toString(), modId, modName,
                        "lang_items", sourcePath));
            }
        } catch (Exception ignored) {}
        return chunks;
    }

    /**
     * 从 mods.toml description / 配方文本 生成块。
     */
    public List<TextChunk> fromDescription(String text, String modId, String modName) {
        List<TextChunk> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) return chunks;
        chunks.addAll(split(text, modId, modName, "description", "mods.toml"));
        return chunks;
    }

    /**
     * 将配方 JSON 转为可读英文描述，适合分块和向量化。
     * 配方 JSON 本身不直接分块 —— 我们生成描述文本再分块。
     */
    public TextChunk fromRecipe(String recipeJson, String modId, String modName, String outputItem) {
        try {
            JsonNode r = mapper.readTree(recipeJson);
            String type = r.path("type").asText("crafting");
            StringBuilder sb = new StringBuilder();
            sb.append("Recipe for ").append(outputItem).append(" (").append(type).append(")");

            if (r.has("result")) {
                JsonNode result = r.get("result");
                String id = result.path("id").asText(result.path("item").asText(null));
                int count = result.path("count").asInt(1);
                if (id != null) sb.append(" produces ").append(count).append("x ").append(id);
            }

            // Ingredients from key
            if (r.has("key")) {
                sb.append(". Ingredients: ");
                JsonNode key = r.get("key");
                key.fieldNames().forEachRemaining(k -> {
                    JsonNode v = key.get(k);
                    String mid = v.path("id").asText(v.path("item").asText(null));
                    if (mid != null) sb.append(k).append("=").append(mid).append(", ");
                });
            }

            // Ingredients from ingredient list
            if (r.has("ingredient")) {
                sb.append(". Input: ");
                JsonNode ing = r.get("ingredient");
                if (ing.isArray()) {
                    for (JsonNode i : ing) {
                        String mid = i.path("id").asText(i.path("item").asText(null));
                        if (mid != null) sb.append(mid).append(", ");
                    }
                } else {
                    String mid = ing.path("id").asText(ing.path("item").asText(null));
                    if (mid != null) sb.append(mid);
                }
            }

            return new TextChunk(modId + "/recipe/" + outputItem.replace(':', '_'),
                    sb.toString(), modId, modName, "recipe", outputItem);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 通用文本分块: 按句号/换行在 500-1000 字符处截断, 50 字符重叠。
     */
    private List<TextChunk> split(String text, String modId, String modName, String type, String source) {
        List<TextChunk> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) return chunks;

        // 短文本直接作为一整块, 避免重叠循环
        if (text.length() <= MAX_CHUNK) {
            String t = text.trim();
            if (t.length() > 20) {
                chunks.add(new TextChunk(modId + "/" + type + "/0",
                        t, modId, modName != null ? modName : modId, type, source));
            }
            return chunks;
        }

        int start = 0;
        int idx = 0;
        int prevStart = -1;
        while (start < text.length()) {
            // 防止死循环 — 确保每次迭代 start 都在前进
            if (start == prevStart) break;
            prevStart = start;

            int end = Math.min(start + MAX_CHUNK, text.length());
            if (end < text.length()) {
                // 在句号/换行处截断
                for (int i = end; i > start + MIN_CHUNK; i--) {
                    char c = text.charAt(i - 1);
                    if (c == '.' || c == '\n' || c == '。' || c == '！' || c == '？') {
                        end = i; break;
                    }
                }
            }
            String chunkText = text.substring(start, end).trim();
            if (!chunkText.isEmpty()) {
                chunks.add(new TextChunk(modId + "/" + type + "/" + idx,
                        chunkText, modId, modName != null ? modName : modId, type, source));
                idx++;
            }
            int next = end - OVERLAP;
            // 确保前进: 如果 next 不超过 start, 直接跳到 end
            start = (next > start) ? next : end;
        }
        return chunks;
    }
}
