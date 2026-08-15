package yagen.waitmydawn.kb.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yagen.waitmydawn.kb.service.RagAgentService;
import yagen.waitmydawn.kb.service.VectorStore;
import yagen.waitmydawn.kb.service.WikiScraperService.McmodCategory;

import java.util.List;

/**
 * Agent 3 — AnswerAgent: 使用 LLM 组合检索结果生成最终 Markdown 回答。
 *
 * 职责:
 *   1. 接收分类结果、实体解析结果、配方搜索结果、向量搜索结果
 *   2. 组织上下文 → LLM 生成格式化的中文 Markdown 回答
 *   3. 绝不编造数据，仅基于提供的检索内容
 *
 * 后续可扩展:
 *   - 图片生成调度 ("这个配方帮我画出来")
 *   - 外部工具调用联动
 */
public class AnswerAgent {

    private static final Logger log = LoggerFactory.getLogger(AnswerAgent.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String ANSWER_SYSTEM_PROMPT = """
            You are an expert Minecraft knowledge assistant. Answer the user's question using the provided retrieval data.

            [Rules]
            1. Answer in Chinese. Be thorough but concise.
            2. For items/entities: give the Chinese name, English registry name in backticks, and which mod it's from.
            3. For recipes: describe crafting pattern, list ingredients with quantities.
            4. Format using Markdown: ## for sections, **bold** for key terms, bullet lists for ingredients/traits.
            5. If the data contains registry names like "iceandfire:ghost", include them for clarity.

            [Data Quality Tiers — choose your approach based on what's available]
            - TIER A (rich data): Recipes + detailed vector chunks available → answer fully from data.
            - TIER B (sparse data): Only names/registry entries, no details → use the registry names as anchors,
              supplement with your Minecraft knowledge to describe the entity's general traits.
              Mark knowledge-based parts with: > 💡 此部分基于模组通用知识，具体内容可能因模组版本而异。
            - TIER C (minimal data): Only mod/entity names → describe what you know about them,
              include the disclaimer, and suggest: "如需更详细的信息，请尝试更具体地描述你的问题，
              例如指定模组名称或询问特定方面（驯服、繁殖、掉落物等）。"
            - TIER D (ZERO data): No recipes, no relevant vectors, no entities →
              answer from your knowledge with a clear disclaimer at the TOP:
              > ⚠️ 本地知识库未包含相关数据，以下回答基于 AI 通用知识，可能不完全准确。
              建议构建知识库以获得准确的模组特定信息。
              Then suggest: "尝试更具体地描述问题，例如提及模组名称或物品英文名。"
            """;

    /** AiServices 答案合成接口：自由文本 Markdown 输出 + 真实 Token 用量。 */
    public interface AnswerComposer {
        @SystemMessage(ANSWER_SYSTEM_PROMPT)
        @UserMessage("{{it}}")
        Result<String> compose(String prompt);
    }

    private final RagAgentService llm;
    private final AnswerComposer composer;
    private final MessageWindowChatMemory chatMemory;
    private volatile TokenUsage lastUsage;

    public AnswerAgent(RagAgentService llm) {
        this.llm = llm;
        this.chatMemory = MessageWindowChatMemory.withMaxMessages(20);
        ChatModel model = llm.getChatModel();
        AnswerComposer c = null;
        if (model != null) {
            try {
                c = AiServices.builder(AnswerComposer.class)
                        .chatModel(model)
                        .chatMemory(chatMemory)
                        .build();
            } catch (Exception e) {
                log.warn("AiServices init failed, answer compose falls back to offline mode: {}", e.getMessage());
            }
        }
        this.composer = c;
    }

    /** Compose a final answer from all retrieval results. */
    public String compose(AnswerContext ctx) {
        if (llm == null) return composeFallback(ctx);

        // Assess data tier
        boolean hasRecipes = ctx.recipeMatches() != null && !ctx.recipeMatches().isEmpty();
        boolean hasRelevantVectors = ctx.vectorResults() != null
                && ctx.vectorResults().stream().anyMatch(v -> v.score() > 0.3);
        boolean hasEntities = ctx.resolvedEntities() != null && !ctx.resolvedEntities().isEmpty();
        boolean hasAnyVectors = ctx.vectorResults() != null && !ctx.vectorResults().isEmpty();

        String dataTier;
        if (hasRecipes && hasRelevantVectors) dataTier = "TIER A";
        else if (hasEntities && (hasAnyVectors || hasRecipes)) dataTier = "TIER B";
        else if (hasEntities || hasAnyVectors) dataTier = "TIER C";
        else dataTier = "TIER D";

        StringBuilder context = new StringBuilder();
        context.append("[Data Tier: ").append(dataTier).append("]\n\n");

        if (ctx.category() != null) {
            context.append(String.format("[Category: %s (%s)]\n\n", ctx.category().name(), ctx.category().getName()));
        }

        // Always include ALL entities, even without details
        if (hasEntities) {
            context.append("=== [Resolved Entities] ===\n");
            for (EntityAgent.ResolvedEntity e : ctx.resolvedEntities()) {
                context.append(String.format("- `%s` (mod: %s, confidence: %.2f)\n",
                        e.registry(), e.modId(), e.confidence()));
            }
            context.append("\n");
        }

        // Always include recipes even if few
        if (hasRecipes) {
            context.append("=== [Recipes Found] ===\n");
            for (int i = 0; i < Math.min(ctx.recipeMatches().size(), 8); i++) {
                RecipeMatch rm = ctx.recipeMatches().get(i);
                context.append(String.format("Recipe #%d: output=%s, source=%s\n",
                        i + 1, rm.outputItem(), rm.sourceMod()));
                context.append(rm.recipeJson()).append("\n\n");
            }
            context.append("=== [Recipe Analysis] ===\n");
            for (int i = 0; i < Math.min(ctx.recipeMatches().size(), 8); i++) {
                analyzeRecipe(ctx.recipeMatches().get(i), context, i + 1);
            }
        }

        // Always include ALL vector results (lower threshold to 0.1)
        if (hasAnyVectors) {
            context.append("=== [Knowledge Base Context] ===\n");
            int included = 0;
            for (var sr : ctx.vectorResults()) {
                if (sr.score() > 0.1 && included < 8) {
                    String snippet = sr.chunkText();
                    if (snippet.length() > 1500) snippet = snippet.substring(0, 1500);
                    context.append(String.format("[%s/%s score=%.3f]\n%s\n\n",
                            sr.modName(), sr.type(), sr.score(), snippet));
                    included++;
                }
            }
            if (included == 0) context.append("(no chunks above 0.1 threshold — data is very sparse)\n\n");
        }

        if (ctx.incrementalInfo() != null && !ctx.incrementalInfo().isBlank()) {
            context.append("=== [Incremental Fetch] ===\n").append(ctx.incrementalInfo()).append("\n\n");
        }

        String prompt = "[Retrieval Context]\n" + context + "\n\n[User Question]\n" + ctx.question();

        try {
            if (composer != null) {
                Result<String> result = composer.compose(prompt);
                lastUsage = result.tokenUsage();
                String answer = result.content();
                if (answer != null && !answer.isBlank()) return answer;
            }
        } catch (Exception e) {
            log.error("AnswerAgent LLM call failed", e);
        }
        return composeFallback(ctx);
    }

    /** 清空多轮对话记忆（切换会话/清空历史时调用）。 */
    public void clearMemory() {
        chatMemory.clear();
        lastUsage = null;
    }

    /** 最近一次合成的真实 Token 用量；离线/回退路径时为 null。 */
    public TokenUsage lastUsage() {
        return lastUsage;
    }

    private String composeFallback(AnswerContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Retrieval Results (Offline)\n\n");

        if (ctx.category() != null) {
            sb.append("**Category**: ").append(ctx.category().getName()).append("\n\n");
        }
        if (ctx.resolvedEntities() != null && !ctx.resolvedEntities().isEmpty()) {
            sb.append("### Resolved Entities\n");
            for (var e : ctx.resolvedEntities()) {
                sb.append("- `").append(e.registry()).append("` (").append(e.modId()).append(")\n");
            }
            sb.append("\n");
        }
        if (ctx.recipeMatches() != null && !ctx.recipeMatches().isEmpty()) {
            sb.append("### Recipes (").append(ctx.recipeMatches().size()).append(")\n");
            for (var rm : ctx.recipeMatches()) {
                sb.append("- ").append(rm.outputItem()).append(" [").append(rm.sourceMod()).append("]\n");
            }
            sb.append("\n");
        }
        if (ctx.vectorResults() != null && !ctx.vectorResults().isEmpty()) {
            sb.append("### Knowledge Snippets\n");
            for (int i = 0; i < Math.min(5, ctx.vectorResults().size()); i++) {
                var sr = ctx.vectorResults().get(i);
                String snippet = sr.chunkText();
                if (snippet.length() > 300) snippet = snippet.substring(0, 300) + "...";
                sb.append("- [").append(sr.modName()).append(" score=")
                        .append(String.format("%.3f", sr.score())).append("] ").append(snippet).append("\n");
            }
            sb.append("\n");
        }
        sb.append("> Configure API Key for AI-generated synthesis of the above data.\n");
        sb.append("> Try being more specific, e.g. mention the mod name or ask about a specific aspect.\n");
        return sb.toString();
    }

    private void analyzeRecipe(RecipeMatch rm, StringBuilder ctx, int idx) {
        try {
            JsonNode root = mapper.readTree(rm.recipeJson());
            String type = root.path("type").asText("crafting");
            ctx.append("Recipe ").append(idx).append(" type: ").append(type).append("\n");

            JsonNode result = root.path("result");
            String outId = result.path("id").asText(null);
            if (outId == null) outId = result.path("item").asText(result.asText(null));
            int outCount = result.path("count").asInt(1);
            if (outId != null) ctx.append("  Output: ").append(outCount).append("x ").append(outId).append("\n");

            if (root.has("key")) {
                JsonNode key = root.get("key");
                ctx.append("  Key:\n");
                key.fieldNames().forEachRemaining(k -> {
                    JsonNode v = key.get(k);
                    String id = v.path("id").asText(v.path("item").asText(null));
                    ctx.append("    ").append(k).append(" = ").append(id != null ? id : "?").append("\n");
                });
            }
            if (root.has("pattern")) {
                ctx.append("  Pattern:\n");
                for (JsonNode row : root.get("pattern")) {
                    ctx.append("    `").append(row.asText()).append("`\n");
                }
            }
            if (root.has("ingredient")) {
                JsonNode ing = root.get("ingredient");
                ctx.append("  Ingredients:\n");
                if (ing.isArray()) {
                    for (JsonNode i : ing) {
                        String id = i.path("id").asText(i.path("item").asText("?"));
                        ctx.append("    - ").append(id).append("\n");
                    }
                } else {
                    String id = ing.path("id").asText(ing.path("item").asText("?"));
                    ctx.append("    - ").append(id).append("\n");
                }
            }
            ctx.append("\n");
        } catch (Exception e) {
            ctx.append("  (parse error)\n\n");
        }
    }

    /** A matched recipe from DB search */
    public record RecipeMatch(String recipeJson, String outputItem, String sourceMod) {}

    /** Context object bundling all information for answer composition */
    public record AnswerContext(
            String question,
            McmodCategory category,
            List<EntityAgent.ResolvedEntity> resolvedEntities,
            List<RecipeMatch> recipeMatches,
            List<VectorStore.SearchResult> vectorResults,
            String incrementalInfo
    ) {}
}
