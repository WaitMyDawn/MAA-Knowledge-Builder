package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * 文本分块器 — 从 JAR 提取的各类数据生成适合向量化的文本块。
 * 块大小最多 180 个 UTF-16 单元，重叠 20 个单元，避免短文本模型截断。
 */
public class TextChunker {

    private static final int TARGET_CHUNK = 180;
    private static final int MIN_TEXT_LEN = 20;
    private static final ObjectMapper mapper = new ObjectMapper();

    /** lang 文件黑名单前缀：纯 UI / 技术性 key 不进入知识库。 */
    private static final Set<String> LANG_BLACKLIST = Set.of(
            "accessibility.", "addServer.", "addresourcepack.", "argument.",
            "attribute.", "biome.", "chat.", "commands.", "config.",
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
        String lower = key.toLowerCase(Locale.ROOT);
        for (String prefix : LANG_BLACKLIST) {
            if (lower.startsWith(prefix.toLowerCase(Locale.ROOT))) return true;
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
        String title="", heading="";
        StringBuilder section=new StringBuilder();
        for(String line:text.split("\\R")) {
            if(title.isEmpty() && (line.startsWith("标题：") || line.startsWith("名称："))) { title=line.trim(); continue; }
            if(line.stripLeading().startsWith("## ")) {
                chunks.addAll(webSection(section.toString(),title,heading,modId,modName,source,sourcePath));
                section.setLength(0); heading=line.replaceFirst("^\\s*#+\\s*","").trim();
            } else section.append(line).append('\n');
        }
        chunks.addAll(webSection(section.toString(),title,heading,modId,modName,source,sourcePath));
        return chunks;
    }

    private List<TextChunk> webSection(String body,String title,String heading,String modId,String modName,String type,String url) {
        if(body.isBlank()) return List.of();
        String prefix=(title.isEmpty()?"":shorten(title,40)+"\n")+(heading.isEmpty()?"":"章节："+shorten(heading,35)+"\n");
        int budget=TARGET_CHUNK-prefix.length();
        var result=new ArrayList<TextChunk>();
        for(int start=0;start<body.length();) {
            int end=Math.min(body.length(),start+budget);
            if(end<body.length()) {
                int newline=body.lastIndexOf('\n',end);
                if(newline>start+budget/2) end=newline;
                if(Character.isHighSurrogate(body.charAt(end-1))) end--;
            }
            String part=body.substring(start,end).trim();
            if(!part.isEmpty()) {
                String block=prefix+part;
                result.add(new TextChunk(modId+"/"+type+"/"+fingerprint(url+"\n"+type+"\n"+block),block,modId,modName==null?modId:modName,type,url));
            }
            if(end==body.length()) break;
            start=Math.max(start+1,end-20);
            if(Character.isLowSurrogate(body.charAt(start))) start++;
        }
        return result;
    }

    private static String shorten(String text,int limit) {
        if(text.length()<=limit) return text;
        int end=Character.isHighSurrogate(text.charAt(limit-1))?limit-1:limit;
        return text.substring(0,end);
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
     * 通用文本分块：固定上限与重叠，来源及内容共同确定块身份。
     */
    private static String fingerprint(String text) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private List<TextChunk> split(String text, String modId, String modName, String type, String source) {
        List<TextChunk> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) return chunks;

        for (int start = 0, index = 0; start < text.length(); index++) {
            int end = Math.min(text.length(), start + TARGET_CHUNK);
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            String block = text.substring(start, end).trim();
            if (block.length() > MIN_TEXT_LEN) {
                String identity = source + "\n" + type + "\n" + block;
                chunks.add(new TextChunk(modId + "/" + type + "/" + fingerprint(identity), block,
                        modId, modName != null ? modName : modId, type, source));
            }
            if (end == text.length()) break;
            start = Math.max(start + 1, end - 20);
            if (Character.isLowSurrogate(text.charAt(start))) start++;
        }
        return chunks;
    }
}
