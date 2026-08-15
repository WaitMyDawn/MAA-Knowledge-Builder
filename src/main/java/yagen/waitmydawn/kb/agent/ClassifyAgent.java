package yagen.waitmydawn.kb.agent;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yagen.waitmydawn.kb.service.RagAgentService;
import yagen.waitmydawn.kb.service.WikiScraperService.McmodCategory;

/**
 * Agent 1 — ClassifyAgent: 使用 LLM 将用户问题分类为 McmodCategory 枚举类型。
 * 替代原有的基于正则/关键词匹配的 ClassifierService。
 *
 * 职责: 理解用户意图 → 映射到 MC百科分类体系
 */
public class ClassifyAgent {

    private static final Logger log = LoggerFactory.getLogger(ClassifyAgent.class);

    private static final String CLASSIFY_SYSTEM_PROMPT = """
            You are a Minecraft mod knowledge classifier. Classify the user's question into exactly ONE McmodCategory.

            Reply with ONLY a JSON object, no extra text, in this exact shape:
            {"category":"ENTITY","confidence":0.9}

            category MUST be one of (name = id):
              ITEM=1 (item, block, tool, weapon, armor)
              BIOME=2 (biome)
              DIM=3 (dimension/world)
              ENTITY=4 (creature, monster, animal, boss)
              ENCHANT=5 (enchantment)
              EFFECT=6 (status effect, buff, debuff)
              MUL_BLOCK=7 (multi-block structure)
              STRUCTURE=8 (naturally generated structure)
              HOTKEYS=9 (key bindings, hotkeys)
              GAME_SETTINGS=10 (game settings, configuration)
              SPELL=229 (spell/magic)

            Rules:
            1. If unsure or the question is about general mechanics, reply {"category":"GENERAL","confidence":0}
            2. Use the [Previous Conversation] section only to resolve pronouns and follow-ups.
            3. category must be the exact enum name from the list above (or GENERAL).
            """;

    private static final String CLASSIFY_PROMPT = """
            You are a Minecraft mod knowledge classifier. Classify the user's question into exactly ONE of the following categories:

            %s

            Rules:
            1. Reply with ONLY the category NUMBER (e.g. "4" for ENTITY).
            2. If the question is about an item, block, tool, weapon, armor → ITEM (1)
            3. If the question is about a creature, monster, animal, boss → ENTITY (4)
            4. If the question is about a multi-block structure → MUL_BLOCK (7)
            5. If the question is about a naturally generated structure → STRUCTURE (8)
            6. If the question is about an enchantment → ENCHANT (5)
            7. If the question is about a status effect, buff, debuff → EFFECT (6)
            8. If the question is about a biome → BIOME (2)
            9. If the question is about a dimension/world → DIM (3)
            10. If the question is about game settings, configuration → GAME_SETTINGS (10)
            11. If the question is about key bindings, hotkeys → HOTKEYS (9)
            12. If the question is about a spell/magic → SPELL (229)
            13. If unsure or general mechanics question → reply with 0

            Question: %s
            Category number:""";

    /** AiServices 结构化分类接口：LLM 直接返回 JSON，由框架反序列化为中间 record。 */
    public interface Classifier {
        @SystemMessage(CLASSIFY_SYSTEM_PROMPT)
        @UserMessage("{{it}}")
        Result<StructuredClassification> classify(String prompt);
    }

    /** 结构化返回的中间形态：category 用字符串以便容纳 GENERAL/未知值，避免枚举反序列化失败。 */
    public record StructuredClassification(String category, float confidence) {}

    private final RagAgentService llm;
    private final Classifier classifier;
    private volatile TokenUsage lastUsage;

    public ClassifyAgent(RagAgentService llm) {
        this.llm = llm;
        ChatModel model = llm.getChatModel();
        Classifier c = null;
        if (model != null) {
            try {
                c = AiServices.builder(Classifier.class).chatModel(model).build();
            } catch (Exception e) {
                log.warn("AiServices init failed, classify falls back to text parsing: {}", e.getMessage());
            }
        }
        this.classifier = c;
    }

    /**
     * Classify a question into McmodCategory.
     * @param question user question
     * @param conversationContext previous Q&A pairs for context (pronouns, follow-ups)
     * @return the matched category, or null if classification fails / is GENERAL
     */
    public Classification classify(String question, String conversationContext) {
        if (llm == null) return new Classification(null, 0, "offline: no LLM available");

        // If we have conversation context and the question looks like a follow-up,
        // prepend it to help classification
        String fullQuestion = question;
        if (conversationContext != null && !conversationContext.isBlank()
                && isFollowUp(question)) {
            fullQuestion = "[Conversation Context]\n" + conversationContext
                    + "\n\n[Current Question]\n" + question;
        }

        // Build category list for prompt
        StringBuilder catList = new StringBuilder();
        for (McmodCategory cat : McmodCategory.values()) {
            catList.append(String.format("  %d = %s\n", cat.getId(), cat.getName()));
        }

        try {
            // 优先使用结构化输出（AiServices）
            if (classifier != null) {
                try {
                    // 结构化路径的用户消息只提供上下文，输出格式完全交给 @SystemMessage
                    String structuredPrompt = "[Categories]\n" + catList
                            + "\n[Question]\n" + fullQuestion;
                    Result<StructuredClassification> result = classifier.classify(structuredPrompt);
                    lastUsage = result.tokenUsage();
                    StructuredClassification structured = result.content();
                    if (structured != null) {
                        McmodCategory cat = parseCategory(structured.category());
                        Classification cls = new Classification(cat, structured.confidence(),
                                cat == null ? "general or unknown category" : null);
                        if (cat != null) {
                            log.info("ClassifyAgent: '{}' → {} ({})", question,
                                    cat.name(), cat.getName());
                        }
                        return cls;
                    }
                } catch (Exception e) {
                    log.warn("ClassifyAgent structured call failed, falling back to text parse: {}",
                            e.getMessage());
                }
            }

            // 回退：旧版文本解析（DeepSeek 不支持 json_schema 等场景）
            String prompt = CLASSIFY_PROMPT.formatted(catList.toString(), fullQuestion);
            String response = llm.rawAsk(prompt);
            if (response == null || response.isBlank()) {
                return new Classification(null, 0, "no response");
            }

            // Parse category number
            String cleaned = response.trim().replaceAll("[^0-9]", "");
            if (cleaned.isEmpty()) return new Classification(null, 0, "unparseable: " + response);

            int catId = Integer.parseInt(cleaned);
            if (catId == 0) return new Classification(null, 0, "general question");

            McmodCategory cat = McmodCategory.fromId(catId);
            if (cat != null) {
                log.info("ClassifyAgent: '{}' → {} ({})", question, cat.name(), cat.getName());
                return new Classification(cat, 1.0f, null);
            }
            return new Classification(null, 0, "unknown category id: " + catId);

        } catch (Exception e) {
            log.warn("ClassifyAgent failed: {}", e.getMessage());
            return new Classification(null, 0, "error: " + e.getMessage());
        }
    }

    /** 将 LLM 返回的分类名映射为枚举；GENERAL/未知值返回 null。 */
    private static McmodCategory parseCategory(String name) {
        if (name == null || name.isBlank()) return null;
        String cleaned = name.trim().toUpperCase();
        if ("GENERAL".equals(cleaned) || "UNSURE".equals(cleaned) || "NONE".equals(cleaned)) {
            return null;
        }
        try {
            return McmodCategory.valueOf(cleaned);
        } catch (IllegalArgumentException e) {
            // 兼容模型返回中文名或编号
            for (McmodCategory cat : McmodCategory.values()) {
                if (cat.getName().equals(name.trim())) return cat;
                if (String.valueOf(cat.getId()).equals(name.trim())) return cat;
            }
            return null;
        }
    }

    /** 最近一次结构化调用的真实 Token 用量；回退路径或未配置 Key 时为 null。 */
    public TokenUsage lastUsage() {
        return lastUsage;
    }

    /** Detect if the question is a follow-up that needs conversation context */
    private static boolean isFollowUp(String question) {
        if (question == null) return false;
        String q = question.strip();
        // Pronouns, references, short questions
        return q.contains("它们") || q.contains("他们") || q.contains("她们")
                || q.contains("这些") || q.contains("那些") || q.contains("这个") || q.contains("那个")
                || q.contains("它") || q.contains("他") || q.contains("她")
                || q.startsWith("介绍") || q.startsWith("说说")
                || q.length() <= 10;
    }

    /** Classification result */
    public record Classification(McmodCategory category, float confidence, String debug) {
        public boolean isClassified() { return category != null; }
    }
}
