package yagen.waitmydawn.kb.service;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yagen.waitmydawn.kb.config.AppConfig;
import yagen.waitmydawn.kb.dto.ClassificationResult;
import yagen.waitmydawn.kb.dto.RetrievalResult;

import java.time.Duration;

/**
 * LangChain4j Agent 工厂 — 负责创建问答 Agent，组合检索数据生成 Markdown 回答。
 */
public class RagAgentService {

    private static final Logger log = LoggerFactory.getLogger(RagAgentService.class);

    private final AppConfig config;
    private volatile ChatModel chatModel;
    /** 评测裁判专用模型：temperature=0，尽量稳定复现判分。 */
    private volatile ChatModel judgeModel;
    private final ThreadLocal<UsageTrace> trace = ThreadLocal.withInitial(UsageTrace::new);

    public static final class UsageTrace {
        public int calls, inputTokens, outputTokens, unknownUsageCalls;
        public long latencyMs;
        UsageTrace copy() {
            var copy = new UsageTrace(); copy.calls = calls; copy.inputTokens = inputTokens;
            copy.outputTokens = outputTokens; copy.unknownUsageCalls = unknownUsageCalls; copy.latencyMs = latencyMs;
            return copy;
        }
    }

    public void beginTrace() { trace.set(new UsageTrace()); }
    public UsageTrace usageTrace() { return trace.get().copy(); }
    public void endTrace() { trace.remove(); }

    private final dev.langchain4j.model.chat.listener.ChatModelListener usageListener = new dev.langchain4j.model.chat.listener.ChatModelListener() {
        @Override public void onRequest(dev.langchain4j.model.chat.listener.ChatModelRequestContext context) {
            trace.get().calls++;
            context.attributes().put("mkbStart", System.nanoTime());
        }
        @Override public void onResponse(dev.langchain4j.model.chat.listener.ChatModelResponseContext context) {
            var usage = context.chatResponse().tokenUsage();
            var stats = trace.get();
            if (usage == null || usage.inputTokenCount() == null || usage.outputTokenCount() == null) stats.unknownUsageCalls++;
            else { stats.inputTokens += usage.inputTokenCount(); stats.outputTokens += usage.outputTokenCount(); }
            complete(context.attributes());
        }
        @Override public void onError(dev.langchain4j.model.chat.listener.ChatModelErrorContext context) {
            trace.get().unknownUsageCalls++;
            complete(context.attributes());
        }
        private void complete(java.util.Map<Object, Object> attributes) {
            Object start = attributes.get("mkbStart");
            if (start instanceof Long nanos) trace.get().latencyMs += (System.nanoTime() - nanos) / 1_000_000;
        }
    };

    private static final String SYSTEM_PROMPT = """
            You are an expert Minecraft knowledge assistant.

            You are given two kinds of retrieval data:
            - [Recipes]: structured JSON containing crafting/furnace/smithing recipes
            - [Vector Chunks]: text snippets from mod lang files, descriptions, recipe summaries

            Rules:
            1. Read ALL provided data carefully. Pick the parts MOST relevant to the user's question.
            2. For recipe JSON: extract the output item, required ingredients with amounts,
               and describe the crafting pattern in a clear Markdown table. Use Chinese.
            3. For vector chunks: extract English item names and their Chinese translations,
               mod names, and any relevant descriptions. Map English names to Chinese when possible.
            4. Format answer as:
               ## {物品中文名} ({英文名})
               **合成方式**: {有序/无序合成 或 烧炼/锻造}
               **材料**: markdown table or bullet list
               **来源模组**: {mod name}
            5. If NONE of the data matches the user's question, reply:
               "相关知识不在当前知识库中。建议：① 扩展模组覆盖范围；② 抓取 MC百科/Wiki。"
            6. NEVER invent recipes or use your training data. Only use the provided retrieval data.
            7. Answer in Chinese. Be concise.
            """;

    /** Ask LLM a raw question, return text. For helper callers. */
    public String rawAsk(String prompt) {
        return rawAsk("generic", prompt);
    }

    /** 带调用方标签的原始 LLM 调用：记录耗时/token/结束原因，便于自动化评测定位。 */
    public String rawAsk(String caller, String prompt) {
        ChatModel model = getModel();
        if (model == null) return null;
        long t0 = System.nanoTime();
        try {
            ChatResponse response = model.chat(ChatRequest.builder()
                            .messages(UserMessage.from(prompt))
                            .build());
            logLlmCall(caller, t0, response);
            return response.aiMessage().text();
        } catch (Exception e) {
            log.warn("LLM[{}] rawAsk failed after {}ms: {}", caller,
                    (System.nanoTime() - t0) / 1_000_000, e.getMessage());
            return null;
        }
    }

    /**
     * Ask LLM and return text together with real token usage / finish reason.
     * 供 QaMetrics 使用真实 Token 数据替换估算值。
     */
    public LlmResult askWithUsage(String prompt) {
        return askWithUsage("generic", prompt);
    }

    /** 带调用方标签的 LLM 调用：返回文本 + 真实用量，并输出单次调用日志。 */
    public LlmResult askWithUsage(String caller, String prompt) {
        ChatModel model = getModel();
        if (model == null) return null;
        long t0 = System.nanoTime();
        try {
            ChatResponse response = model.chat(ChatRequest.builder()
                    .messages(UserMessage.from(prompt))
                    .build());
            logLlmCall(caller, t0, response);
            return new LlmResult(response.aiMessage().text(), response.tokenUsage(),
                    response.finishReason() != null ? response.finishReason().name() : null);
        } catch (Exception e) {
            log.warn("LLM[{}] askWithUsage failed after {}ms: {}", caller,
                    (System.nanoTime() - t0) / 1_000_000, e.getMessage());
            return null;
        }
    }

    /** 单次 LLM 调用的结构化摘要日志（评测/自动化依赖这一行做成本与失败定位）。 */
    private static void logLlmCall(String caller, long startNanos, ChatResponse response) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000;
        TokenUsage u = response.tokenUsage();
        int in = u != null && u.inputTokenCount() != null ? u.inputTokenCount() : -1;
        int out = u != null && u.outputTokenCount() != null ? u.outputTokenCount() : -1;
        String finish = response.finishReason() != null ? response.finishReason().name() : "?";
        log.info("LLM[{}] {}ms in={} out={} finish={}", caller, ms, in, out, finish);
    }

    /**
     * 评测裁判模型（temperature=0、较小 maxTokens）。未配置 Key 时返回 null。
     */
    public ChatModel getJudgeModel() {
        ChatModel model = judgeModel;
        if (model == null) {
            synchronized (this) {
                model = judgeModel;
                if (model == null) {
                    String apiKey = config.getApiKey();
                    if (apiKey == null || apiKey.isBlank()) return null;
                    model = OpenAiChatModel.builder()
                            .baseUrl("https://api.deepseek.com/v1")
                            .apiKey(apiKey)
                            .modelName("deepseek-chat")
                            .timeout(Duration.ofSeconds(90))
                            .maxTokens(1024)
                            .temperature(0.0)
                            .build();
                    judgeModel = model;
                    log.info("Judge model initialized (DeepSeek, temperature=0)");
                }
            }
        }
        return model;
    }

    /**
     * 暴露底层 ChatModel，供 AiServices 构建结构化 Agent 使用。
     * 未配置 API Key 时返回 null，调用方应走离线回退逻辑。
     */
    public ChatModel getChatModel() {
        return getModel();
    }

    public RagAgentService(AppConfig config) {
        this.config = config;
    }

    private ChatModel getModel() {
        ChatModel model = chatModel;
        if (model == null) {
            synchronized (this) {
                model = chatModel;
                if (model == null) {
                    String apiKey = config.getApiKey();
                    if (apiKey == null || apiKey.isBlank()) {
                        log.warn("API key not set, the agent will return a default response");
                        return null;
                    }
                    model = OpenAiChatModel.builder()
                            .listeners(java.util.List.of(usageListener))
                            .baseUrl("https://api.deepseek.com/v1")
                            .apiKey(apiKey)
                            .modelName("deepseek-chat")
                            .timeout(Duration.ofSeconds(60))
                            .maxTokens(2048)
                            .temperature(0.3)
                            .build();
                    chatModel = model;
                    log.info("RAG Agent has been initialized (DeepSeek)");
                }
            }
        }
        return model;
    }

    /**
     * 组织回答：系统提示词 + 检索数据 + 用户问题 → LLM 生成 Markdown。
     */
    public String composeAnswer(String userQuestion, ClassificationResult classification,
                                 RetrievalResult retrieval) {
        ChatModel model = getModel();
        if (model == null) {
            return generateFallbackAnswer(userQuestion, classification, retrieval);
        }

        // 构建检索数据摘要
        String context = buildContext(classification, retrieval);

        try {
            ChatResponse response = model.chat(ChatRequest.builder()
                    .messages(
                            SystemMessage.from(SYSTEM_PROMPT),
                            UserMessage.from("""
                                    [Retrieval Data]
                                    Question Type: %s
                                    %s

                                    [User Question]
                                    %s
                                    """.formatted(classification.getQuestionType(), context, userQuestion)))
                    .build());
            return response.aiMessage().text();
        } catch (Exception e) {
            log.error("LLM call failed", e);
            return generateFallbackAnswer(userQuestion, classification, retrieval);
        }
    }

    /** 一次 LLM 调用的结果：文本 + 真实 Token 用量 + 结束原因。 */
    public record LlmResult(String text, TokenUsage tokenUsage, String finishReason) {}

    /** 无 API Key 或 LLM 调用失败时的回退回答 */
    private String generateFallbackAnswer(String question, ClassificationResult cls, RetrievalResult retrieval) {
        if (!retrieval.isFound()) {
            return "该知识不在当前知识库中。请扩展知识库的模组覆盖范围或添加相关网站资源。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 检索结果 (本地知识库)\n\n");
        sb.append("**问题类型**: ").append(cls.getQuestionType()).append("\n\n");

        if (!retrieval.getRecipeJsons().isEmpty()) {
            sb.append("### 找到 ").append(retrieval.getRecipeJsons().size()).append(" 条配方\n\n");
            sb.append("(配置 API Key 后可获得 AI 生成的详细回答)\n\n");
        }
        if (!retrieval.getMultiblockJsons().isEmpty()) {
            sb.append("### 找到 ").append(retrieval.getMultiblockJsons().size()).append(" 个多方块结构\n\n");
            sb.append("(配置 API Key 后可获得 AI 生成的详细回答)\n\n");
        }
        if (!retrieval.getWikiSnippets().isEmpty()) {
            sb.append("### 相关知识片段\n\n");
            for (int i = 0; i < Math.min(3, retrieval.getWikiSnippets().size()); i++) {
                sb.append(retrieval.getWikiSnippets().get(i)).append("\n\n---\n\n");
            }
        }

        sb.append("📌 **来源**: ").append(retrieval.getSourceDescription()).append("\n");
        return sb.toString();
    }

    private String buildContext(ClassificationResult cls, RetrievalResult retrieval) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Recipes]\n");
        if (!retrieval.getRecipeJsons().isEmpty()) {
            for (int i = 0; i < Math.min(10, retrieval.getRecipeJsons().size()); i++) {
                String json = retrieval.getRecipeJsons().get(i);
                if (json.length() > 3000) json = json.substring(0, 3000);
                sb.append(json).append("\n---\n");
            }
        } else { sb.append("(none)\n"); }

        sb.append("\n[Vector Chunks]\n");
        if (!retrieval.getWikiSnippets().isEmpty()) {
            for (int i = 0; i < Math.min(8, retrieval.getWikiSnippets().size()); i++) {
                String s = retrieval.getWikiSnippets().get(i);
                if (s.length() > 2000) s = s.substring(0, 2000);
                sb.append(s).append("\n---\n");
            }
        } else { sb.append("(none)\n"); }

        return sb.toString();
    }
}
