package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * 文本分块器 — 从 JAR 提取的各类数据生成适合向量化的文本块。
 * 块大小: 500-1000 字符, 重叠 50 字符。
 */
public class TextChunker {

    private static final int TARGET_CHUNK = 500;
    private static final int MIN_TEXT_LEN = 20;
    private static final ObjectMapper mapper = new ObjectMapper();

    /** lang 文件黑名单前缀：纯 UI / 技术性 key 不进入知识库。 */
    private static final Set<String> LANG_BLACKLIST = Set.of(
            "accessibility.", "addServer.", "addresourcepack.", "argument.",
            "attribute.", "biome.", "block.", "chat.", "commands.", "config.",
            "connect.", "controls.", "createWorld.", "dataPack.", "debug.",
            "disconnect.", "editGamerule.", "filled_map.", "flat_world_preset.",
            "gameMode.", "generator.", "gpu_warnlist.", "gui.", "inventory.",
            "itemGroup.", "jigsaw_block.", "key.", "lanServer.", "language.",
            "mco.", "menu.", "merchant.", "mirror.", "mount.", "multiplayer.",
            "narrator.", "options.", "pack.", "painting.", "particle.",
            "selectServer.", "selectWorld.", "sleep.", "soundCategory.",
            "spectatorMenu.", "subtitles.", "team.", "telemetry.", "title.",
            "tutorial.", "vibration.", "world.");

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
            Iterator<String> fields = root.fieldNames();
            while (fields.hasNext()) {
                String key = fields.next();
                String val = root.path(key).asText("");
                if (val.isBlank() || val.equals(key)) continue;
                if (isLangBlacklisted(key)) continue;

                String itemName = key;
                if (itemName.startsWith("item.") || itemName.startsWith("block.")) {
                    itemName = itemName.substring(itemName.lastIndexOf('.') + 1);
                }
                if (itemName.startsWith(modId + ".")) {
                    itemName = itemName.substring(modId.length() + 1);
                }
                desc.append(itemName).append(": ").append(val).append("\n");
            }
            if (desc.length() > 0) {
                chunks.addAll(split(desc.toString(), modId, modName, "lang_items", sourcePath));
            }
        } catch (Exception ignored) {}
        return chunks;
    }

    private static boolean isLangBlacklisted(String key) {
        String lower = key.toLowerCase();
        for (String prefix : LANG_BLACKLIST) {
            if (lower.startsWith(prefix)) return true;
        }
        return false;
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

    /** 网页正文分块：metadata 使用真实 modId/modName/sourcePath。 */
    public List<TextChunk> fromWebPage(String text, String modId, String modName,
                                        String source, String sourcePath) {
        List<TextChunk> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) return chunks;
        chunks.addAll(split(text, modId, modName, source, sourcePath));
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
     * 通用文本分块：按段落填入，块目标长度约 500 字符。
     * 若当前块尚未达到目标长度，则即使下一段会超过目标也整段补入，避免截断。
     */
    private List<TextChunk> split(String text, String modId, String modName, String type, String source) {
        List<TextChunk> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) return chunks;

        if (text.length() <= TARGET_CHUNK) {
            String t = text.trim();
            if (t.length() > MIN_TEXT_LEN) {
                chunks.add(new TextChunk(modId + "/" + type + "/0",
                        t, modId, modName != null ? modName : modId, type, source));
            }
            return chunks;
        }

        String[] paragraphs = text.split("\\n\\s*\\n");
        if (paragraphs.length <= 1) paragraphs = text.split("\\n");

        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String raw : paragraphs) {
            String paragraph = raw.trim();
            if (paragraph.isEmpty()) continue;
            if (current.length() > 0
                    && current.length() + paragraph.length() + 1 > TARGET_CHUNK
                    && current.length() >= TARGET_CHUNK) {
                blocks.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) current.append("\n");
            current.append(paragraph);
        }
        if (current.length() > 0) blocks.add(current.toString());

        for (int i = 0; i < blocks.size(); i++) {
            String block = blocks.get(i).trim();
            if (block.length() <= MIN_TEXT_LEN) continue;
            chunks.add(new TextChunk(modId + "/" + type + "/" + i,
                    block, modId, modName != null ? modName : modId, type, source));
        }
        return chunks;
    }
}
