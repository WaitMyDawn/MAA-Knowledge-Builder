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
import yagen.waitmydawn.kb.dto.RetrievalPlan;
import yagen.waitmydawn.kb.service.CatalogEnumeration;

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
    /** debug 日志中完整 prompt 的最大长度，超出截断，避免每问写数万字符日志。 */
    private static final int MAX_PROMPT_LOG_CHARS = 1500;

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
    public record AliasHit(String subQuery, String label, String url, String modId, double score, String role) {
        public AliasHit(String query,String label,String url,String modId,double score) {
            this(query,label,url,modId,score,"primary");
        }
    }

    /** EntityAgent 新工作流的结果：别名命中 + 原版 Wiki 主题 + 原版注册名回退 + 拆分后的子问题。 */
    public record EntityResolutionResult(
            List<AliasHit> aliasHits,
            List<String> wikiTopics,
            List<ResolvedEntity> inferredEntities,
            List<String> subQueries, RetrievalPlan plan, String catalogEvidence) {
        public EntityResolutionResult(List<AliasHit> hits,List<String> topics,List<ResolvedEntity> entities,List<String> queries) {
            this(hits,topics,entities,queries,null,null);
        }
    }

    /** LLM 对问题的分析结果：拆分出的主体 + 目标模组 ID。 */
    public record QuestionAnalysis(List<String> subjects, String targetModId, RetrievalPlan plan,List<String> vanillaTopics) {
        public QuestionAnalysis(List<String> subjects,String modId) { this(subjects,modId,null,List.of()); }
        public QuestionAnalysis(List<String> subjects,String modId,RetrievalPlan plan) { this(subjects,modId,plan,List.of()); }
    }

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
     * 先确定范围和对象，再选择真实目录页面；操作问题可以追加同模组的机制支撑页。
     * 原版对象优先使用 Wiki，语义近邻不能直接充当已确认实体。
     */
    public EntityResolutionResult resolveEntities(String question, String conversationContext) {
        baseVectorStore.verifyProfile(embedder.profile(), false);
        boolean vanillaOnly = question != null
                && (question.contains("原版") || question.toLowerCase().contains("vanilla"));
        QuestionAnalysis analysis = analyzeQuestion(question, conversationContext);
        List<String> subjects = analysis.subjects().isEmpty() ? List.of(question) : analysis.subjects();
        String targetModId = vanillaOnly ? "minecraft" : analysis.targetModId();
        if (targetModId != null && !targetModId.equals("minecraft") && db.findModEntry(targetModId)==null) targetModId=null;
        List<ResolvedEntity> knownVanilla=List.of();
        if(targetModId==null && (analysis.plan()==null || !analysis.plan().enumerate())) {
            knownVanilla=inferVanillaRegistries(subjects).stream().filter(e -> hasVanillaRecipe(e.registry())).toList();
            if(!knownVanilla.isEmpty()) targetModId="minecraft";
        }
        RetrievalPlan plan = analysis.plan()==null ? RetrievalPlan.lookup(question,targetModId)
                : new RetrievalPlan(question,targetModId,analysis.plan().enumerate(),analysis.plan().objectType(),
                    analysis.plan().filters(),analysis.plan().requestedCount(),analysis.plan().exhaustive(),analysis.plan().intent());
        if (plan.enumerate()) {
            if("minecraft".equals(plan.modId())) {
                var topics=new ArrayList<>(subjects);
                if("ENTITY".equals(plan.objectType()) && !topics.contains("生物")) topics.add("生物");
                return new EntityResolutionResult(List.of(),topics,List.of(),topics,plan,null);
            }
            var enumeration = new CatalogEnumeration(db,llm).select(plan,subjects);
            return new EntityResolutionResult(enumeration.hits(),List.of(),List.of(),List.of(question),plan,enumeration.evidence());
        }
        log.info("EntityAgent: question split into {} subjects: {}, targetMod={}, vanillaOnly={}",
                subjects.size(), subjects, targetModId, vanillaOnly);

        List<AliasHit> hits = new ArrayList<>();
        List<String> wikiTopics = new ArrayList<>();
        wikiTopics.addAll(analysis.vanillaTopics());
        List<String> vanillaSubjects = new ArrayList<>();
        var mechanismHints=yagen.waitmydawn.kb.service.KnowledgeHints.match(db,targetModId,question,"ambiguous_mechanism");
        if(!mechanismHints.isEmpty()) {
            String branch="歧义解释：卷轴等级与法术强度";
            for(var hint:mechanismHints) hits.add(new AliasHit(branch,hint.label(),hint.url(),targetModId,1,"support"));
            return new EntityResolutionResult(hits,List.of(),List.of(),List.of(branch),plan,
                    "术语存在歧义：分别说明卷轴等级的升级与法术强度的强化；不能断言不存在法术等级。抄写台用于装卸法术，槽位扩容不等于卷轴升级。具体步骤以两个机制正文来源为准。");
        }

        for (String subject : subjects) {
            if(targetModId!=null && !"minecraft".equals(targetModId) && analysis.vanillaTopics().contains(subject)) continue;
            // Wiki 与模组目录遵循同一范围，避免原版对象被模组同名条目覆盖。
            if ((targetModId==null || "minecraft".equals(targetModId)) && subject != null && !subject.isBlank()) wikiTopics.add(subject);

            if (vanillaOnly) {
                vanillaSubjects.add(subject);
                continue; // 原版问题只走 Wiki，不再匹配模组别名
            }

            // 路径 2：先按字面包含匹配所有别名，例如"龙"命中火龙/冰龙/电龙
            var relations=yagen.waitmydawn.kb.service.KnowledgeHints.match(db,targetModId,subject,"prototype");
            if(!relations.isEmpty()) {
                for(var relation:relations) hits.add(new AliasHit(subject,relation.label(),relation.url(),targetModId,.75,"name_candidate"));
                continue;
            }
            List<VectorStore.AliasResult> aliases = typedAliases(subject,plan);
            if(aliases.isEmpty()) aliases=baseVectorStore.searchAliasesByText(subject, ALIAS_CANDIDATE_LIMIT, targetModId);
            aliases = selectAliases(aliases, subject);
            if (aliases.isEmpty() && targetModId != null && !"minecraft".equals(targetModId)) {
                // 路径 4（2026-09-15）：问题点名的模组过滤后一无所获 → 放开模组过滤再试一次。
                // 典型场景："Alex's Mobs 里的九头蛇怎么打"——九头蛇实际属于 iceandfire，
                // 模组过滤会挡住正确页面，导致回退成原版实体并诱发幻觉。
                aliases = baseVectorStore.searchAliasesByText(subject, ALIAS_CANDIDATE_LIMIT, null);
                aliases = selectAliases(aliases, subject);
                if (!aliases.isEmpty()) {
                    log.info("EntityAgent: subject '{}' had no hit under mod filter '{}'; "
                            + "cross-mod fallback found {} aliases", subject, targetModId, aliases.size());
                }
            }
            if (aliases.isEmpty()) {
                // 其他模组的字面命中优先于当前模组的原型猜测，避免相近生物阻断归属纠偏。
                aliases = matchKnownAliases(subject,targetModId,null);
            }
            log.info("EntityAgent: subject '{}' -> {} alias hits: {}", subject, aliases.size(),
                    aliases.stream().map(a -> a.label() + "(" + String.format("%.2f", a.score()) + ")")
                            .toList());
            if (aliases.isEmpty()) {
                if (targetModId==null || "minecraft".equals(targetModId)) vanillaSubjects.add(subject);
            }
            for (VectorStore.AliasResult alias : aliases) {
                hits.add(new AliasHit(subject, alias.label(), alias.url(), alias.modId(), alias.score(),alias.score()<0.9?"name_candidate":"primary"));
            }
        }

        List<ResolvedEntity> inferred = vanillaSubjects.isEmpty()
                ? knownVanilla
                : knownVanilla.isEmpty()?inferVanillaRegistries(vanillaSubjects):knownVanilla;
        String primaryScope=targetModId;
        // 机制概念未必有同名条目；仅在没有对象候选时使用支撑页，不能覆盖跨模组纠偏。
        boolean mechanismConcept=hits.stream().noneMatch(h->"primary".equals(h.role())) && Set.of("SPELL","MECHANISM").contains(plan.objectType());
        if(targetModId!=null && !"minecraft".equals(targetModId) && plan.needsDetails()
                && (mechanismConcept || hits.stream().anyMatch(h -> primaryScope.equals(h.modId()) && "primary".equals(h.role())))) {
            for(var alias:matchKnownAliases(String.join("、",subjects),targetModId,question)) {
                if(hits.stream().noneMatch(h -> h.url().equals(alias.url())))
                    hits.add(new AliasHit(subjects.getFirst(),alias.label(),alias.url(),alias.modId(),alias.score(),"support"));
            }
        }
        String names=hits.stream().anyMatch(h->"name_candidate".equals(h.role())) && "ENTITY".equals(plan.objectType())
                ?"名称核验：当前目录没有按用户字面名称匹配到正式条目。相近名称或原型关系候选必须有条件地介绍；只有正文能确认关联与机制，不能编造用户名称的注册名。":null;
        var queries=new LinkedHashSet<>(subjects); queries.addAll(wikiTopics);
        return new EntityResolutionResult(hits, wikiTopics, inferred, List.copyOf(queries),plan,names);
    }

    private boolean hasVanillaRecipe(String registry) {
        try(var c=db.getConnection(); var p=c.prepareStatement("SELECT 1 FROM rag_recipe WHERE output_item=? AND source_mod='minecraft' LIMIT 1")) {
            p.setString(1,registry);try(var r=p.executeQuery()) { return r.next(); }
        } catch(Exception e) { return false; }
    }

    /** 生物与同名掉落物分别有页面；先按查询对象类型选择，避免扁平映射抢占名称。 */
    private List<VectorStore.AliasResult> typedAliases(String subject,RetrievalPlan plan) {
        if(plan.modId()==null || subject==null || subject.isBlank() || plan.objectType().isBlank()) return List.of();
        String term=subject.toLowerCase(java.util.Locale.ROOT).strip();
        return new yagen.waitmydawn.kb.service.WebCatalogStore(db).entries(plan.modId(),plan.topCategory()).stream()
                .filter(e->e.role().equals("entry"))
                .filter(e->e.chineseName().toLowerCase(java.util.Locale.ROOT).contains(term) || e.englishName().toLowerCase(java.util.Locale.ROOT).contains(term))
                .map(e->new VectorStore.AliasResult(e.label(),plan.modId(),plan.modId(),e.url(),1))
                .distinct().limit(ALIAS_CANDIDATE_LIMIT).toList();
    }

    private List<VectorStore.AliasResult> matchKnownAliases(String subject,String modId,String question) {
        var candidates = new LinkedHashMap<String,String>();
        for(var page : db.loadWebPages(modId)) candidates.putAll(page.subWebPage());
        if(candidates.isEmpty() || llm==null) return List.of();
        var spellingMatches=candidates.keySet().stream().filter(label -> matchesExpandedName(subject,label)).toList();
        String task=question==null
                ?"Match this subject, well-established synonyms or its named variants. If the literal name is absent, you may suggest at most ONE plausible real candidate inspired by that object. Such a prototype/lookalike is only a hypothesis for fetching and verification, not a confirmed synonym. Do NOT return unrelated animals, introductions or workstations. Return [] if there is no plausible candidate. "
                :"Select existing primary and mechanism/support pages needed for the question. A material's acquisition may need its source creature, and upgrading may need a workstation. Do not return unrelated objects. ";
        int limit=question==null?8:4;
        String prompt=task+"Return at most "+limit+" exact candidate labels as JSON array. Choose a minimal set covering the requested mechanisms, not every related page. Subject: "+subject
                +"\nQuestion: "+(question==null?subject:question)+"\nCandidates:\n"+String.join("\n",candidates.keySet());
        var response=llm.askWithUsage("EntityAgent.alias",prompt);
        var labels=new ArrayList<>(spellingMatches);
        labels.addAll(parseStringArray(response==null?null:response.text()));
        return labels.stream().filter(candidates::containsKey).distinct().limit(limit)
                .map(label -> new VectorStore.AliasResult(label,modId,modId,candidates.get(label),spellingMatches.contains(label)?1:0.75)).toList();
    }

    /** 限定在原有字符间插入少量变体字，不把任意相似词当成同义对象。 */
    static boolean matchesExpandedName(String subject,String label) {
        if(subject==null || subject.length()<3 || !containsCjk(subject)) return false;
        String name=label.split("\\(",2)[0];
        if(name.length()<subject.length() || name.length()-subject.length()>2) return false;
        int cursor=0;
        for(char character:name.toCharArray()) if(cursor<subject.length() && subject.charAt(cursor)==character) cursor++;
        return cursor==subject.length();
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
                    {"subjects":["龙"],"modId":"iceandfire","enumerate":false,"objectType":"ENTITY","filters":[],"count":3,"exhaustive":false,"intent":"ENTITY","vanillaTopics":[]}

                    - subjects: short search subjects from the question. For "龙有哪几种？法术书有哪几种？" -> ["龙","法术书"].
                    - modId: if the question explicitly mentions a mod, choose the EXACT modId from [Installed Mods].
                      Mod names may be expressed in Chinese or English variants, e.g. "冰火传说", "冰与火之歌", "ice and fire"
                      should all map to the matching installed modId. If no mod is mentioned, use null.
                      Use "minecraft" for an ordinary vanilla object unless explicitly referring to a mod.
                    - enumerate: true for listing members of a category or examples, false for a specific object or family such as dragon variants.
                    - objectType: ENTITY, ITEM, BIOME, DIM, STRUCTURE (natural), MUL_BLOCK, MECHANISM, SPELL, ENCHANT, EFFECT.
                    - filters: category/habitat/behavior conditions such as terrestrial, tameable, rideable. These need page evidence.
                    - count: requested example count, default 3; exhaustive: true only if a complete list is explicitly required.
                    - Resolve pronouns from the previous conversation, but preserve the current action (taming, obtaining, etc.).
                    - intent: ITEM (crafting/obtaining), ENTITY, STRUCTURE, BIOME, DIM, MECHANISM, PLAYING (taming/breeding), ENUMERATE, GENERAL.
                    - An ambiguous mechanism term may have several useful interpretations. Retrieve subjects for each plausible interpretation rather than silently choosing one or denying it exists. Spell level may mean scroll level or spell power: choose upgrading and strengthening support subjects.
                    - For broad combat/playing questions, retrieve practical guide subjects covering preparation, stage-specific actions and hazards, not only entity statistics. For vanilla combat, a valid Wiki tutorial title may be added to vanillaTopics (for example 教程/打败末影龙); do not invent a mod URL.
                    - vanillaTopics: at most 3 explicitly mentioned vanilla objects whose mechanisms are needed alongside mod knowledge. For a mod spell killing the Ender Dragon, use ["末影龙"] to retrieve the boss's health/damage constraints as well as the spell. Do not add vanilla dragons when the question is only about a mod's own dragons.

                    [Installed Mods]
                    %s

                    Examples:
                    - "冰火传说的龙有哪几种" -> {"subjects":["龙"],"modId":"iceandfire"}
                    - "信标怎么做" or "桶怎么合成" -> ordinary vanilla ITEM, modId "minecraft".

                    [Previous Conversation]
                    %s

                    [Question]
                    %s
                    """.formatted(buildModContext(),
                            (conversationContext == null || conversationContext.isBlank())
                                    ? "(none)" : conversationContext,
                            question);

            log.debug("EntityAgent analysis prompt ({} chars):\n{}",
                    prompt.length(), truncatePrompt(prompt));
            RagAgentService.LlmResult r = llm.askWithUsage("EntityAgent.analyze", prompt);
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
        RetrievalPlan plan = null;
        List<String> vanillaTopics=new ArrayList<>();
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
                    var filters = new ArrayList<String>();
                    for(var topic:node.path("vanillaTopics")) if(topic.isTextual() && !topic.asText().isBlank() && topic.asText().length()<=80 && vanillaTopics.size()<3) vanillaTopics.add(topic.asText());
                    for(var filter:node.path("filters")) if(filter.isTextual()) filters.add(filter.asText());
                    String kind=node.path("objectType").asText("ITEM").toUpperCase();
                    if(!Set.of("ENTITY","ITEM","BIOME","DIM","STRUCTURE","MUL_BLOCK","MECHANISM","SPELL","ENCHANT","EFFECT").contains(kind)) kind="ITEM";
                    yagen.waitmydawn.kb.dto.QuestionType intent;
                    try { intent=yagen.waitmydawn.kb.dto.QuestionType.valueOf(node.path("intent").asText("GENERAL")); }
                    catch(IllegalArgumentException e) { intent=yagen.waitmydawn.kb.dto.QuestionType.GENERAL; }
                    plan=new RetrievalPlan(fallbackQuestion,modId,node.path("enumerate").asBoolean(false),kind,
                            List.copyOf(filters),Math.max(1,Math.min(30,node.path("count").asInt(3))),node.path("exhaustive").asBoolean(false),intent);
                }
            } catch (Exception ignored) {}
        }
        if (subjects.isEmpty()) subjects.add(fallbackQuestion);
        return new QuestionAnalysis(subjects, modId,plan,List.copyOf(vanillaTopics));
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

            log.debug("EntityAgent vanilla inference prompt ({} chars):\n{}",
                    prompt.length(), truncatePrompt(prompt));
            RagAgentService.LlmResult r = llm.askWithUsage("EntityAgent.vanilla", prompt);
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
                            structuredPrompt.length(), truncatePrompt(structuredPrompt));
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
            log.debug("EntityAgent fallback prompt ({} chars):\n{}",
                    prompt.length(), truncatePrompt(prompt));
            String response = llm.rawAsk("EntityAgent.resolve", prompt);
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

    /** 截断完整 prompt 日志：超长部分省略并注明被截断字符数。 */
    private static String truncatePrompt(String prompt) {
        if (prompt == null || prompt.length() <= MAX_PROMPT_LOG_CHARS) return prompt;
        return prompt.substring(0, MAX_PROMPT_LOG_CHARS)
                + "\n...[truncated " + (prompt.length() - MAX_PROMPT_LOG_CHARS) + " chars]";
    }

    /** Build a list of all mods in the DB for LLM context */
    private String buildModContext() {
        StringBuilder sb = new StringBuilder();
        try {
            List<ModEntry> mods = db.findAllModEntries();
            if (mods.isEmpty()) {
                sb.append("  [No mods installed — assume vanilla Minecraft only]\n");
            }
            // rag_mod_info 允许 (mod_id, loader, mc_version) 并存，同一 mod 可能有多行；
            // 这里按 modId 去重，避免 prompt 中出现重复模组误导 LLM 并浪费 token。
            Set<String> seenModIds = new LinkedHashSet<>();
            for (ModEntry m : mods) {
                if (!seenModIds.add(m.getModId())) continue;
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
