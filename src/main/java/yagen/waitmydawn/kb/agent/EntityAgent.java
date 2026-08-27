package yagen.waitmydawn.kb.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import yagen.waitmydawn.kb.model.ModEntry;
import yagen.waitmydawn.kb.service.EmbeddingService;
import yagen.waitmydawn.kb.service.RagAgentService;
import yagen.waitmydawn.kb.service.VectorStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

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

    private static final String ENTITY_SYSTEM_PROMPT = """
            You are a Minecraft: Java Edition expert assistant specialized in identifying items, blocks, entities, and structures across vanilla Minecraft AND all installed mods.

            IMPORTANT: Always consider vanilla Minecraft entities alongside modded ones.
            For example, "龙" (dragon) includes both `minecraft:ender_dragon` (vanilla) AND modded dragons like `iceandfire:fire_dragon`.
            Always list ALL possibilities — vanilla first, then modded.

            Your task: given a Chinese-language user question, identify the EXACT registry name(s) (format: modid:item_name) of EVERY item, block, entity, or structure mentioned or implied.

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
            Reply with ONLY a JSON array, no extra text, in this exact shape:
            [{"registry":"minecraft:compass","modId":"minecraft","confidence":0.95,"reason":"Vanilla compass item"}]
            - If the entity could belong to multiple mods, list ALL possibilities.
            - confidence is 0.0 to 1.0.
            - If you genuinely cannot identify any entity, reply: []
            """;

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

    /** AiServices 结构化实体解析接口：LLM 直接返回 JSON 数组。 */
    public interface EntityResolver {
        @SystemMessage(ENTITY_SYSTEM_PROMPT)
        @UserMessage("{{it}}")
        Result<ResolvedEntities> resolve(String prompt);
    }

    /**
     * 结构化返回包装：1.18.1 的 AiServices 对顶层 List&lt;POJO&gt; 返回类型存在
     * formatInstructions 未实现的限制（IllegalStateException），因此包一层单 POJO。
     */
    public record ResolvedEntities(List<ResolvedEntity> items) {}

    /** 从别名向量库命中的中英对照条目。 */
    public record AliasHit(String subQuery, String label, String url, String modId, double score) {}

    /** EntityAgent 新工作流的结果：别名命中 + 原版 Wiki 主题 + 原版注册名回退 + 拆分后的子问题。 */
    public record EntityResolutionResult(
            List<AliasHit> aliasHits,
            List<String> wikiTopics,
            List<ResolvedEntity> inferredEntities,
            List<String> subQueries) {}

    /** LLM 对问题的分析结果：拆分出的主体 + 目标模组 ID。 */
    public record QuestionAnalysis(List<String> subjects, String targetModId) {}

    private final RagAgentService llm;
    private final DatabaseBuilder db;
    private final EmbeddingService embedder;
    private final VectorStore baseVectorStore;
    private final EntityResolver resolver;
    private volatile TokenUsage lastUsage;

    private static final int ALIAS_TARGET_K = 3;
    private static final int ALIAS_CANDIDATE_LIMIT = 50;
    private static final double ALIAS_MIN_SCORE = 0.18;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public EntityAgent(RagAgentService llm, DatabaseBuilder db,
                       EmbeddingService embedder, VectorStore baseVectorStore) {
        this.llm = llm;
        this.db = db;
        this.embedder = embedder;
        this.baseVectorStore = baseVectorStore;
        ChatModel model = llm.getChatModel();
        EntityResolver r = null;
        if (model != null) {
            try {
                r = AiServices.builder(EntityResolver.class).chatModel(model).build();
            } catch (Exception e) {
                log.warn("AiServices init failed, entity resolve falls back to text parsing: {}", e.getMessage());
            }
        }
        this.resolver = r;
    }

    /**
     * 新工作流：先拆主体并识别目标模组，然后按主体多线路检索。
     * 线路 1：每个主体都走 MC Wiki（路径 1，Wiki 自带重定向）。
     * 线路 2：优先按字面包含匹配所有相关别名（可过滤模组），否则向量检索兜底（路径 3）。
     */
    public EntityResolutionResult resolveEntities(String question, String conversationContext) {
        boolean vanillaOnly = question != null
                && (question.contains("原版") || question.toLowerCase().contains("vanilla"));
        QuestionAnalysis analysis = analyzeQuestion(question, conversationContext);
        List<String> subjects = analysis.subjects().isEmpty() ? List.of(question) : analysis.subjects();
        String targetModId = vanillaOnly ? null : analysis.targetModId();
        log.info("EntityAgent: question split into {} subjects: {}, targetMod={}, vanillaOnly={}",
                subjects.size(), subjects, targetModId, vanillaOnly);

        List<AliasHit> hits = new ArrayList<>();
        List<String> wikiTopics = new ArrayList<>();
        List<String> vanillaSubjects = new ArrayList<>();

        for (String subject : subjects) {
            // 路径 1：始终保留 MC Wiki 主题，作为原版内容兜底
            if (subject != null && !subject.isBlank()) wikiTopics.add(subject);

            if (vanillaOnly) {
                vanillaSubjects.add(subject);
                continue; // 原版问题只走 Wiki，不再匹配模组别名
            }

            // 路径 2：先按字面包含匹配所有别名，例如"龙"命中火龙/冰龙/电龙
            List<VectorStore.AliasResult> aliases =
                    baseVectorStore.searchAliasesByText(subject, ALIAS_CANDIDATE_LIMIT, targetModId);
            aliases = selectAliases(aliases, subject);
            if (aliases.isEmpty()) {
                // 路径 3：文字匹配不到时，再做语义向量检索
                aliases = baseVectorStore.searchAliases(
                        embedder.embed(subject), ALIAS_TARGET_K, ALIAS_MIN_SCORE, targetModId);
            }
            log.info("EntityAgent: subject '{}' -> {} alias hits: {}", subject, aliases.size(),
                    aliases.stream().map(a -> a.label() + "(" + String.format("%.2f", a.score()) + ")")
                            .toList());
            if (aliases.isEmpty()) {
                vanillaSubjects.add(subject);
            }
            for (VectorStore.AliasResult alias : aliases) {
                hits.add(new AliasHit(subject, alias.label(), alias.url(), alias.modId(), alias.score()));
            }
        }

        List<ResolvedEntity> inferred = vanillaSubjects.isEmpty()
                ? List.of()
                : inferVanillaRegistries(vanillaSubjects);
        return new EntityResolutionResult(hits, wikiTopics, inferred, subjects);
    }

    /**
     * 对文字匹配出的别名做离散等级筛选。
     * 等级 0：中文或英文完全一致；1：只多一个字；2：多两个字；以此类推。
     * Top-K 取默认 3 个，但同等级的条目会整体保留，避免把同字数的火龙/冰龙/电龙截断。
     */
    private static List<VectorStore.AliasResult> selectAliases(
            List<VectorStore.AliasResult> candidates, String subject) {
        if (candidates == null || candidates.size() <= ALIAS_TARGET_K) return candidates;

        Map<Integer, List<VectorStore.AliasResult>> byRank = new TreeMap<>();
        for (VectorStore.AliasResult candidate : candidates) {
            int rank = aliasRank(candidate.label(), subject);
            byRank.computeIfAbsent(rank, k -> new ArrayList<>()).add(candidate);
        }

        List<VectorStore.AliasResult> selected = new ArrayList<>();
        for (List<VectorStore.AliasResult> group : byRank.values()) {
            group.sort(Comparator.comparing(VectorStore.AliasResult::label));
            selected.addAll(group);
            if (selected.size() >= ALIAS_TARGET_K) break;
        }
        return selected;
    }

    /** 计算别名与主体的离散等级：0=完全匹配，后续按中文字数差递增。 */
    private static int aliasRank(String label, String subject) {
        if (label == null || subject == null || subject.isBlank()) return Integer.MAX_VALUE;
        String target = subject.trim();

        int leftParen = label.indexOf('(');
        String zh = (leftParen > 0 ? label.substring(0, leftParen) : label).trim();
        String en = "";
        if (leftParen > 0 && label.endsWith(")")) {
            en = label.substring(leftParen + 1, label.length() - 1).trim();
        }

        String part = containsCjk(target) ? zh : en;
        if (part.equalsIgnoreCase(target)) return 0;
        if (part.toLowerCase().contains(target.toLowerCase())) {
            return part.length() - target.length();
        }
        return Math.max(0, label.length() - target.length());
    }

    private static boolean containsCjk(String text) {
        for (char c : text.toCharArray()) {
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) {
                return true;
            }
        }
        return false;
    }

    /**
     * 一次 LLM 调用完成两件事：
     * 1) 拆分问题主体；2) 从模组清单里识别目标模组（支持"冰火传说/冰与火之歌/ice and fire"等变体表达）。
     */
    private QuestionAnalysis analyzeQuestion(String question, String conversationContext) {
        if (llm == null || question == null || question.isBlank()) {
            return new QuestionAnalysis(List.of(question), null);
        }
        try {
            String prompt = """
                    You are analyzing a Chinese Minecraft question.
                    Reply with ONLY one JSON object, no extra text:
                    {"subjects":["龙"],"modId":"iceandfire"}

                    - subjects: short search subjects from the question. For "龙有哪几种？法术书有哪几种？" -> ["龙","法术书"].
                    - modId: if the question explicitly mentions a mod, choose the EXACT modId from [Installed Mods].
                      Mod names may be expressed in Chinese or English variants, e.g. "冰火传说", "冰与火之歌", "ice and fire"
                      should all map to the matching installed modId. If no mod is mentioned, use null.

                    [Installed Mods]
                    %s

                    Examples:
                    - "冰火传说的龙有哪几种" -> {"subjects":["龙"],"modId":"iceandfire"}
                    - "信标怎么做" -> {"subjects":["信标"],"modId":null}

                    [Previous Conversation]
                    %s

                    [Question]
                    %s
                    """.formatted(buildModContext(),
                            conversationContext == null ? "(none)" : conversationContext,
                            question);

            log.debug("EntityAgent analysis prompt ({} chars):\n{}", prompt.length(), prompt);
            RagAgentService.LlmResult r = llm.askWithUsage(prompt);
            lastUsage = r != null ? r.tokenUsage() : null;
            String response = r != null ? r.text() : null;
            log.debug("EntityAgent analysis response: {}", response);
            return parseAnalysis(response, question);
        } catch (Exception e) {
            log.warn("EntityAgent question analysis failed: {}", e.getMessage());
            return new QuestionAnalysis(List.of(question), null);
        }
    }

    /** 解析 {"subjects":[...], "modId":"xxx"} 形式的分析结果。 */
    private static QuestionAnalysis parseAnalysis(String response, String fallbackQuestion) {
        List<String> subjects = new ArrayList<>();
        String modId = null;
        if (response != null && !response.isBlank()) {
            try {
                JsonNode node = MAPPER.readTree(response);
                if (node.isObject()) {
                    JsonNode subjectsNode = node.get("subjects");
                    if (subjectsNode != null && subjectsNode.isArray()) {
                        for (JsonNode item : subjectsNode) {
                            if (item.isTextual() && !item.asText().isBlank()) {
                                subjects.add(item.asText().trim());
                            }
                        }
                    }
                    JsonNode modNode = node.get("modId");
                    if (modNode != null && modNode.isTextual() && !modNode.asText().isBlank()) {
                        modId = modNode.asText().trim();
                    }
                }
            } catch (Exception ignored) {}
        }
        if (subjects.isEmpty()) subjects.add(fallbackQuestion);
        return new QuestionAnalysis(subjects, modId);
    }

    /** 仅在别名检索失败时推断 minecraft 原版注册名。 */
    private List<ResolvedEntity> inferVanillaRegistries(List<String> subjects) {
        if (llm == null || subjects.isEmpty()) return List.of();
        try {
            String prompt = """
                    Convert each Minecraft subject below into its most likely VANILLA registry name.
                    Reply with ONLY a JSON array of strings, e.g. ["minecraft:beacon","minecraft:ender_dragon"].
                    Only use the "minecraft:" namespace.
                    Subjects: %s
                    """.formatted(String.join(", ", subjects));

            log.debug("EntityAgent vanilla inference prompt ({} chars):\n{}", prompt.length(), prompt);
            RagAgentService.LlmResult r = llm.askWithUsage(prompt);
            lastUsage = r != null ? r.tokenUsage() : null;
            String response = r != null ? r.text() : null;
            log.debug("EntityAgent vanilla inference response: {}", response);
            List<String> names = parseStringArray(response);

            List<ResolvedEntity> result = new ArrayList<>();
            for (String name : names) {
                if (name != null && name.startsWith("minecraft:")
                        && name.matches("[a-z_][a-z0-9_]*:[a-z_][a-z0-9_]*")) {
                    result.add(new ResolvedEntity(name, "minecraft", 0.7f, "vanilla fallback"));
                }
            }
            log.info("EntityAgent: inferred vanilla registries: {}",
                    result.stream().map(ResolvedEntity::registry).toList());
            return result;
        } catch (Exception e) {
            log.warn("EntityAgent vanilla registry inference failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** 尽量宽容地解析 LLM 返回的 JSON 字符串数组。 */
    private static List<String> parseStringArray(String response) {
        if (response == null || response.isBlank()) return List.of();
        String text = response.trim();
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start >= 0 && end > start) {
            text = text.substring(start, end + 1);
        }
        List<String> result = new ArrayList<>();
        try {
            JsonNode node = MAPPER.readTree(text);
            if (node.isArray()) {
                for (JsonNode item : node) {
                    if (item.isTextual() && !item.asText().isBlank()) {
                        result.add(item.asText().trim());
                    }
                }
            }
        } catch (Exception ignored) {}
        return result;
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

        try {
            // 优先使用结构化输出（AiServices）
            if (resolver != null) {
                try {
                    // 结构化路径的用户消息只提供上下文，输出格式完全交给 @SystemMessage
                    String structuredPrompt = "[Installed Mods]\n" + modContext
                            + "\n[Conversation Context]\n"
                            + (conversationContext == null || conversationContext.isBlank()
                                ? "(none)" : conversationContext)
                            + "\n" + hintsSection
                            + "\n[Question]\n" + question;
                    log.debug("EntityAgent structured prompt ({} chars):\n{}",
                            structuredPrompt.length(), structuredPrompt);
                    Result<ResolvedEntities> result = resolver.resolve(structuredPrompt);
                    lastUsage = result.tokenUsage();
                    ResolvedEntities wrapper = result.content();
                    List<ResolvedEntity> structured = wrapper == null ? null : wrapper.items();
                    if (structured != null) {
                        if (!structured.isEmpty()) {
                            log.info("EntityAgent: '{}' → {} entities (structured)", question, structured.size());
                            return new ArrayList<>(structured);
                        }
                        // 模型明确返回 []：视为无实体，避免重复调用 LLM
                        log.info("EntityAgent: no entity identified for '{}' (structured)", question);
                        return new ArrayList<>();
                    }
                } catch (Exception e) {
                    log.warn("EntityAgent structured call failed ({}), falling back to text parse: {}",
                            e.getClass().getSimpleName(), e.getMessage());
                }
            }

            // 回退：旧版竖线文本解析
            String prompt = ENTITY_PROMPT.formatted(modContext, conversationContext, hintsSection, question);
            log.debug("EntityAgent fallback prompt ({} chars):\n{}", prompt.length(), prompt);
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

    /** 最近一次结构化调用的真实 Token 用量；回退路径或未配置 Key 时为 null。 */
    public TokenUsage lastUsage() {
        return lastUsage;
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
