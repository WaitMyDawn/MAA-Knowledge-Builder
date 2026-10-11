package yagen.waitmydawn.kb.agent;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yagen.waitmydawn.kb.dto.QuestionType;
import yagen.waitmydawn.kb.service.RagAgentService;

/**
 * Agent 1 — ClassifyAgent: 判断问题属于哪一类问答意图。
 *
     * <p>输出 {@link QuestionType} 意图 + source 标记：
 * <ul>
 *   <li>llm     — LLM 直接推理</li>
 *   <li>last    — 问题过短/无法归类且像追问时，沿用上一次成功分类</li>
 *   <li>offline — 未配置 API Key 的硬编码离线结果</li>
 * </ul>
 */
public class ClassifyAgent {

    private static final Logger log = LoggerFactory.getLogger(ClassifyAgent.class);
    /** debug 日志中完整 prompt 的最大长度，超出截断，避免每问写数万字符日志。 */
    private static final int MAX_PROMPT_LOG_CHARS = 1500;

    private static final String CLASSIFY_SYSTEM_PROMPT = """
            You are a Minecraft question intent classifier.
            Classify the user's question into EXACTLY ONE intent.

            Intents:
              ITEM(物品)          - items, blocks, tools, armor; crafting/recipes, how to obtain
              ENTITY(生物)        - creatures, mobs, bosses, animals
              STRUCTURE(结构)     - multi-block structures, naturally generated structures
              BIOME(群系)         - biomes
              DIM(维度)           - dimensions / worlds
              MECHANISM(游戏机制) - redstone, commands, machines, game mechanics
              PLAYING(游戏玩法)   - taming, breeding, survival tips, gameplay guides
              ENUMERATE(枚举)     - list members of a class, examples, which creatures/items meet conditions
              GENERAL(其他)       - anything else, unclear, or too short to classify

            Rules:
            0. Listing several members or asking which members satisfy a condition → ENUMERATE,
               even when the condition concerns taming/riding. A specific entity's variants remain ENTITY/ITEM.
            1. Crafting / recipe / how-to-obtain questions → ITEM
            2. Redstone, commands, machines, mechanism principles → MECHANISM
            3. Taming, breeding, survival flow → PLAYING
            4. Multi-block or generated structures → STRUCTURE
            5. If unsure or the question is too short → GENERAL

            Reply with ONLY a JSON object, no extra text:
            {"intent":"ENTITY"}
            """;

    /** 文本回退：只要求返回枚举名，不再维护第二份分类清单。 */
    private static final String CLASSIFY_FALLBACK_PROMPT = """
            Reply with only ONE of: ITEM, ENTITY, STRUCTURE, BIOME, DIM, MECHANISM, PLAYING, ENUMERATE, GENERAL
            Question: %s
            """;

    /** AiServices 结构化分类接口：LLM 直接返回 JSON，由框架反序列化为中间 record。 */
    public interface Classifier {
        @SystemMessage(CLASSIFY_SYSTEM_PROMPT)
        @UserMessage("{{it}}")
        Result<StructuredClassification> classify(String prompt);
    }

    /** 结构化返回的中间形态：只保留意图名。 */
    public record StructuredClassification(String intent) {}

    /** 分类结果：意图 + 来源（llm / last / offline）。 */
    public record Classification(QuestionType intent, String source) {}

    private final RagAgentService llm;
    private final Classifier classifier;
    private volatile TokenUsage lastUsage;
    /** 上一次成功分类的意图（会话级），用于过短追问的兜底。 */
    private volatile QuestionType lastCategory;

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
     * Classify a question into QuestionType.
     * 不再依赖对话上下文：分类只看问题本身；对过短/无法归类且像追问的问题，
     * 沿用上一次成功分类（source=last）。
     */
    public Classification classify(String question) {
        if (llm == null || llm.getChatModel() == null) return new Classification(null, "offline");

        try {
            if (classifier != null) {
                try {
                    long t0 = System.nanoTime();
                    Result<StructuredClassification> result = classifier.classify(question);
                    lastUsage = result.tokenUsage();
                    log.info("LLM[ClassifyAgent] {}ms in={} out={}",
                            (System.nanoTime() - t0) / 1_000_000,
                            tokenOf(lastUsage, true), tokenOf(lastUsage, false));
                    StructuredClassification structured = result.content();
                    if (structured != null) {
                        return resolve(parseIntent(structured.intent()), question);
                    }
                } catch (Exception e) {
                    log.warn("ClassifyAgent structured call failed, falling back to text parse: {}", e.getMessage());
                }
            }

            String prompt = CLASSIFY_FALLBACK_PROMPT.formatted(question);
            log.debug("ClassifyAgent fallback prompt ({} chars):\n{}", prompt.length(), truncatePrompt(prompt));
            String response = llm.rawAsk("ClassifyAgent", prompt);
            if (response == null || response.isBlank()) return new Classification(null, "llm");
            return resolve(parseIntent(response), question);
        } catch (Exception e) {
            log.warn("ClassifyAgent failed: {}", e.getMessage());
            return new Classification(null, "llm");
        }
    }

    /** 结果裁决：GENERAL/无法解析 + 过短追问 → 沿用上次；否则记录并返回。 */
    private Classification resolve(QuestionType intent, String question) {
        if (intent == null || intent == QuestionType.GENERAL) {
            if (isTerseFollowUp(question) && lastCategory != null) {
                log.info("ClassifyAgent: '{}' → {} (fallback to last)", question, lastCategory);
                return new Classification(lastCategory, "last");
            }
            if (intent == QuestionType.GENERAL) {
                log.info("ClassifyAgent: '{}' → GENERAL", question);
                return new Classification(QuestionType.GENERAL, "llm");
            }
            return new Classification(null, "llm");
        }
        lastCategory = intent;
        log.info("ClassifyAgent: '{}' → {} ({})", question, intent, intent.getName());
        return new Classification(intent, "llm");
    }

    /** 将 LLM 返回的意图名解析为枚举；兼容别名/中文名，无法识别返回 null。 */
    private static QuestionType parseIntent(String name) {
        if (name == null || name.isBlank()) return null;
        String cleaned = name.trim().toUpperCase();
        switch (cleaned) {
            case "RECIPE" -> { return QuestionType.ITEM; }
            case "MULTIBLOCK", "MUL_BLOCK" -> { return QuestionType.STRUCTURE; }
            case "MECHANICS" -> { return QuestionType.MECHANISM; }
            case "OTHER", "UNSURE", "NONE" -> { return QuestionType.GENERAL; }
            default -> { }
        }
        try {
            return QuestionType.valueOf(cleaned);
        } catch (IllegalArgumentException ignored) {
            // 兼容中文名
            for (QuestionType t : QuestionType.values()) {
                if (t.getName().equals(name.trim())) return t;
            }
        }
        return null;
    }

    /** 判断是否为过短/代词类追问（决定是否启用 lastCategory 回退）。 */
    private static boolean isTerseFollowUp(String question) {
        if (question == null) return false;
        String q = question.strip();
        return q.contains("它们") || q.contains("他们") || q.contains("她们")
                || q.contains("这些") || q.contains("那些") || q.contains("这个") || q.contains("那个")
                || q.contains("它") || q.contains("他") || q.contains("她")
                || q.startsWith("介绍") || q.startsWith("说说")
                || q.length() <= 10;
    }

    /** 最近一次调用的真实 Token 用量；回退路径或未配置 Key 时为 null。 */
    public TokenUsage lastUsage() {
        return lastUsage;
    }

    /** 截断完整 prompt 日志：超长部分省略并注明被截断字符数。 */
    private static String truncatePrompt(String prompt) {
        if (prompt == null || prompt.length() <= MAX_PROMPT_LOG_CHARS) return prompt;
        return prompt.substring(0, MAX_PROMPT_LOG_CHARS)
                + "\n...[truncated " + (prompt.length() - MAX_PROMPT_LOG_CHARS) + " chars]";
    }

    /** 取 TokenUsage 的输入/输出计数，缺失时返回 -1。 */
    private static int tokenOf(TokenUsage usage, boolean input) {
        if (usage == null) return -1;
        Integer v = input ? usage.inputTokenCount() : usage.outputTokenCount();
        return v == null ? -1 : v;
    }

    /** 清空上次分类（切换会话时调用）。 */
    public void reset() {
        lastCategory = null;
    }
}
