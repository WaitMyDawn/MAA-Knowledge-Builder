package yagen.waitmydawn.kb.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import yagen.waitmydawn.kb.model.ModEntry;
import yagen.waitmydawn.kb.service.RagAgentService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 2 — EntityAgent: 使用 LLM 推断问题中涉及的实体注册名 (registry name)。
 *
 * 工作流:
 *   1. 获取知识库中所有模组列表 (modId + displayName) 作为前置上下文
 *   2. LLM 分析问题 → 推断出 modid:item_name 格式的注册名
 *   3. 可能返回多个候选 (如 iceandfire:ghost, twilightforest:ghost)
 *   4. 返回结果供后续从 rag_web_cache.subWebPage 查询增量网页
 *
 * 替代原有的基于正则/编辑距离的 EntityResolver。
 */
public class EntityAgent {

    private static final Logger log = LoggerFactory.getLogger(EntityAgent.class);

    private static final String ENTITY_PROMPT = """
            You are a Minecraft: Java Edition expert assistant specialized in identifying items, blocks, entities, and structures across vanilla Minecraft AND all installed mods.

            IMPORTANT: Always consider vanilla Minecraft entities alongside modded ones.
            For example, "龙" (dragon) includes both `minecraft:ender_dragon` (vanilla) AND modded dragons like `iceandfire:fire_dragon`.
            Always list ALL possibilities — vanilla first, then modded.

            Your task: given a Chinese-language user question, identify the EXACT registry name(s) (format: modid:item_name) of EVERY item, block, entity, or structure mentioned or implied.

            [Installed Mods]
            %s
            %s
            %s

            [How to translate Chinese → registry names]
            1. You know Minecraft deeply. Map the Chinese name to the correct English registry name.
               E.g.: 指南针 → compass, 钻石剑 → diamond_sword, 工作台 → crafting_table,
               冰龙 → ice_dragon, 火龙 → fire_dragon, 幽灵 → ghost, 龙 → dragon.
            2. For vanilla Minecraft, always use "minecraft:" prefix.
            3. For mod items, use the mod's modId as shown in [Installed Mods].
               If the modId is "iceandfire" and the entity is "dragon", the registry is "iceandfire:dragon".
            4. Consider multiple word forms: "冰龙" could be ice_dragon, frostdragon, etc.
            5. If the user mentions a mod by name (e.g. "冰火传说"), all entities likely belong to that mod.
            6. If the question mentions "它们" (them), "这些" (these), "它" (it) — these are pronouns referring to
               previously discussed entities. Use the conversation context to resolve them.

            [Output Format]
            Reply STRICTLY one line per entity:
            REGISTRY|MODID|CONFIDENCE|REASON
            - If the entity could belong to multiple mods, list ALL possibilities.
            - CONFIDENCE is 0.0 to 1.0.
            - If you genuinely cannot identify any entity, reply: UNKNOWN
            - NO explanations, NO extra text. ONLY the format lines or UNKNOWN.

            [Examples]
            Q: 指南针怎么做
            A: minecraft:compass|minecraft|0.95|Vanilla compass item

            Q: 幽灵是什么
            A: iceandfire:ghost|iceandfire|0.9|Ice and Fire ghost entity
            twilightforest:wraith|twilightforest|0.4|Twilight Forest wraith

            Q: 冰火传说如何养龙
            A: iceandfire:dragon|iceandfire|0.85|Ice and Fire dragon entity
            iceandfire:fire_dragon|iceandfire|0.85|Ice and Fire fire dragon
            iceandfire:ice_dragon|iceandfire|0.85|Ice and Fire ice dragon

            Q: 龙有哪几种
            A: minecraft:ender_dragon|minecraft|0.95|Vanilla ender dragon
            iceandfire:fire_dragon|iceandfire|0.9|Ice and Fire fire dragon variant
            iceandfire:ice_dragon|iceandfire|0.9|Ice and Fire ice dragon variant
            iceandfire:lightning_dragon|iceandfire|0.85|Ice and Fire lightning dragon variant

            Q: %s
            A:""";

    private final RagAgentService llm;
    private final DatabaseBuilder db;

    public EntityAgent(RagAgentService llm, DatabaseBuilder db) {
        this.llm = llm;
        this.db = db;
    }

    /**
     * Resolve entities from the user's question.
     * @param question user question
     * @param conversationContext previous Q&A context for pronoun resolution
     * @param entityRegistryHints optional list of known entity registries for the suspected mod
     */
    public List<ResolvedEntity> resolve(String question, String conversationContext,
                                        List<String> entityRegistryHints) {
        List<ResolvedEntity> results = new ArrayList<>();
        if (llm == null) return results;

        String modContext = buildModContext();
        String hintsSection = buildHintsSection(entityRegistryHints);

        String prompt = ENTITY_PROMPT.formatted(modContext, conversationContext, hintsSection, question);

        try {
            String response = llm.rawAsk(prompt);
            if (response == null || response.isBlank() || response.trim().equals("UNKNOWN")) {
                log.info("EntityAgent: no entity identified for '{}'", question);
                return results;
            }

            for (String line : response.split("\n")) {
                line = line.trim();
                if (line.isBlank() || line.startsWith("#") || line.startsWith("Q:") || line.startsWith("A:"))
                    continue;

                String[] parts = line.split("\\|");
                if (parts.length < 2) continue;

                String registry = parts[0].trim();
                String modId = parts.length > 1 ? parts[1].trim() : "unknown";
                float confidence = 0.5f;
                if (parts.length > 2) {
                    try { confidence = Float.parseFloat(parts[2].trim()); } catch (NumberFormatException ignored) {}
                }
                String reason = parts.length > 3 ? parts[3].trim() : "";

                if (registry.matches("[a-z_]+:[a-z_]+")) {
                    results.add(new ResolvedEntity(registry, modId, confidence, reason));
                }
            }

            log.info("EntityAgent: '{}' → {} entities", question, results.size());

        } catch (Exception e) {
            log.warn("EntityAgent failed: {}", e.getMessage());
        }

        return results;
    }

    /** Build a list of all mods in the DB for LLM context */
    private String buildModContext() {
        StringBuilder sb = new StringBuilder();
        try {
            List<ModEntry> mods = db.findAllModEntries();
            if (mods.isEmpty()) {
                sb.append("  [No mods installed — assume vanilla Minecraft only]\n");
            }
            for (ModEntry m : mods) {
                String name = m.getDisplayName() != null ? m.getDisplayName() : m.getModId();
                sb.append(String.format("  %s | %s\n", m.getModId(), name));
            }
        } catch (Exception e) {
            sb.append("  minecraft | Minecraft (vanilla)\n");
        }
        return sb.toString();
    }

    /** Build entity registry hints section if available */
    private String buildHintsSection(List<String> entityRegistryHints) {
        if (entityRegistryHints == null || entityRegistryHints.isEmpty()) {
            return "[Entity Registry Hints]\n  (none available)";
        }
        StringBuilder sb = new StringBuilder("[Entity Registry Hints — verified entities from textures]\n");
        // Limit to 100 to avoid overflowing the prompt
        int count = 0;
        for (String reg : entityRegistryHints) {
            if (count++ >= 100) { sb.append("  ... and more\n"); break; }
            sb.append("  ").append(reg).append("\n");
        }
        return sb.toString();
    }

    /**
     * Look up sub-page URLs for resolved entities from rag_web_cache.
     * Falls back to MC百科 search when cache misses.
     * Returns a map: registryName → subPageUrl
     */
    public Map<String, String> lookupSubPages(List<ResolvedEntity> entities) {
        Map<String, String> urls = new LinkedHashMap<>();
        for (ResolvedEntity e : entities) {
            String url = null;
            // Strategy 1: exact registry name
            url = db.findSubPageUrl(e.registry());
            // Strategy 2: just the item name part
            if (url == null) {
                String itemPart = e.registry().contains(":")
                        ? e.registry().substring(e.registry().indexOf(':') + 1) : e.registry();
                url = db.findSubPageUrl(itemPart);
            }
            // Strategy 3: try with underscores replaced by spaces
            if (url == null) {
                String spaced = e.registry().replace('_', ' ');
                String itemPart = spaced.contains(":") ? spaced.substring(spaced.indexOf(':') + 1) : spaced;
                url = db.findSubPageUrl(itemPart);
            }
            // Strategy 4: partial match — query all cached sub-page maps for this modId
            if (url == null) {
                url = findSubPageUrlPartial(e);
            }
            // Strategy 5: dynamic search on MC百科
            if (url == null) {
                url = searchMcmodForEntity(e);
            }

            if (url != null) {
                urls.put(e.registry(), url);
            } else {
                log.debug("EntityAgent: no URL found for {}", e.registry());
            }
        }
        return urls;
    }

    /** Partial match: query all WebCacheEntries for this modId, search sub-page maps */
    private String findSubPageUrlPartial(ResolvedEntity entity) {
        try {
            List<yagen.waitmydawn.kb.model.DatabaseBuilder.WebCacheEntry> entries =
                    db.loadWebPages(entity.modId());
            if (entries.isEmpty()) entries = db.loadAllWebPages();

            String itemName = entity.registry().contains(":")
                    ? entity.registry().substring(entity.registry().indexOf(':') + 1) : entity.registry();

            for (var entry : entries) {
                if (entry.subWebPage() == null) continue;
                for (var kv : entry.subWebPage().entrySet()) {
                    String key = kv.getKey().toLowerCase();
                    String val = kv.getValue();
                    // Match: key contains item name or vice versa
                    if (key.contains(itemName) || itemName.contains(key)
                            || key.contains(itemName.replace('_', ' '))) {
                        return val;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Partial subPage lookup failed: {}", e.getMessage());
        }
        return null;
    }

    /** Dynamic search on MC百科 for an entity item page */
    private String searchMcmodForEntity(ResolvedEntity entity) {
        try {
            String itemName = entity.registry().contains(":")
                    ? entity.registry().substring(entity.registry().indexOf(':') + 1) : entity.registry();
            String searchTerm = itemName.replace('_', ' ');

            // Try MC百科 search
            String searchUrl = "https://search.mcmod.cn/s?key="
                    + java.net.URLEncoder.encode(searchTerm, java.nio.charset.StandardCharsets.UTF_8);
            org.jsoup.nodes.Document doc = org.jsoup.Jsoup.connect(searchUrl)
                    .userAgent("MAA-Knowledge-Builder/1.0").timeout(8000).get();

            // Look for item links matching our modId
            var itemLinks = doc.select("a[href*=/item/]");
            for (var link : itemLinks) {
                String href = link.attr("href");
                String text = link.text().toLowerCase();
                // Prefer links whose text matches our search term
                if (text.contains(searchTerm.toLowerCase())
                        || searchTerm.toLowerCase().contains(text)) {
                    if (!href.startsWith("http")) href = "https://www.mcmod.cn" + href;
                    log.info("EntityAgent: MC百科 search found {} → {}", entity.registry(), href);
                    return href;
                }
            }
            // Fallback: return first item link
            if (!itemLinks.isEmpty()) {
                String href = itemLinks.first().attr("href");
                if (!href.startsWith("http")) href = "https://www.mcmod.cn" + href;
                return href;
            }
        } catch (Exception e) {
            log.debug("MC百科 search failed for {}: {}", entity.registry(), e.getMessage());
        }
        return null;
    }

    /** Query all entity registries for a given modId from rag_entity_registry */
    public List<String> getEntityRegistries(String modId) {
        List<String> list = new ArrayList<>();
        try (var c = db.getConnection();
             var ps = c.prepareStatement(
                     "SELECT registry_name FROM rag_entity_registry WHERE mod_id = ?")) {
            ps.setString(1, modId);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) list.add(rs.getString("registry_name"));
            }
        } catch (Exception e) {
            log.debug("Entity registry query failed: {}", e.getMessage());
        }
        return list;
    }

    /** A resolved entity with registry name and confidence */
    public record ResolvedEntity(String registry, String modId, float confidence, String reason) {}
}
