package yagen.waitmydawn.kb.agent;

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

    private final RagAgentService llm;

    public ClassifyAgent(RagAgentService llm) {
        this.llm = llm;
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

        String prompt = CLASSIFY_PROMPT.formatted(catList.toString(), fullQuestion);

        try {
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
