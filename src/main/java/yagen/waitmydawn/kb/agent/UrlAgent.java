package yagen.waitmydawn.kb.agent;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yagen.waitmydawn.kb.model.DatabaseBuilder;
import yagen.waitmydawn.kb.service.RagAgentService;

import java.util.*;

/**
 * Agent 2.5 — UrlAgent: 基于 EntityAgent 解析出的实体名 + modId，
 * 从 rag_web_cache 的 subWebPage 映射（cn(en)→URL）中利用 LLM 匹配最佳子网页 URL。
 *
 * 为什么需要这个 Agent：
 *   subWebPage 的键是 "秘银矿石(Mithril Ore)" 这种中文名(英文名)格式，
 *   用户问题中的实体描述不一定精确匹配（比如用户说"秘银矿"而非"秘银矿石"），
 *   因此需要 LLM 从候选键中推理选出最匹配的 URL。
 *
 * 输入: 实体中文名 + modId + 该 modId 下所有 subWebPage 键
 * 输出: 匹配到的 URL (可为 null)
 */
public class UrlAgent {

    private static final Logger log = LoggerFactory.getLogger(UrlAgent.class);

    private static final String MATCH_SYSTEM_PROMPT = """
            You are matching a user's entity description to the most likely MC百科 item page.

            [Rules]
            1. Pick the item(s) that BEST match the entity description.
               Consider: Chinese name, English name, partial matches, synonyms.
               E.g. "秘银矿" matches "秘银矿石(Mithril Ore)".
               "火龙" matches "火龙(Fire Dragon)".
            2. Reply with ONLY a JSON array, no extra text. Copy the matched keys EXACTLY from the candidate list:
               ["秘银矿石(Mithril Ore)","火龙(Fire Dragon)"]
            3. If nothing matches, reply: []
            """;

    private static final String MATCH_PROMPT = """
            You are matching a user's entity description to the most likely MC百科 item page.

            [Entity to find]
            %s

            [Candidate items for mod: %s]
            %s

            [Rules]
            1. Pick the item(s) that BEST match the entity description.
               Consider: Chinese name, English name, partial matches, synonyms.
               E.g. "秘银矿" matches "秘银矿石(Mithril Ore)".
               "火龙" matches "火龙(Fire Dragon)".
            2. Reply with EXACTLY ONE item key per line. Only copy from the candidate list.
            3. If nothing matches, reply: NONE
            4. NO extra text, NO explanations.
            """;

    /** AiServices 结构化匹配接口：LLM 直接返回 JSON 数组（包一层 record 以保证反序列化稳定）。 */
    public interface UrlMatcher {
        @SystemMessage(MATCH_SYSTEM_PROMPT)
        @UserMessage("{{it}}")
        Result<UrlMatches> match(String prompt);
    }

    /** 匹配到的候选键列表。 */
    public record UrlMatches(List<String> keys) {}

    private final RagAgentService llm;
    private final DatabaseBuilder db;
    private final UrlMatcher matcher;
    private volatile TokenUsage lastUsage;

    public UrlAgent(RagAgentService llm, DatabaseBuilder db) {
        this.llm = llm;
        this.db = db;
        ChatModel model = llm.getChatModel();
        UrlMatcher m = null;
        if (model != null) {
            try {
                m = AiServices.builder(UrlMatcher.class).chatModel(model).build();
            } catch (Exception e) {
                log.warn("AiServices init failed, url match falls back to text parsing: {}", e.getMessage());
            }
        }
        this.matcher = m;
    }

    /**
     * Match entity names to subWebPage URLs.
     *
     * @param entityDescriptions entity descriptions from user question (Chinese names)
     * @param modId              the mod this entity belongs to
     * @return map of entity description → URL (may be empty if no match)
     */
    public Map<String, String> matchUrls(List<String> entityDescriptions, String modId) {
        Map<String, String> results = new LinkedHashMap<>();
        if (llm == null || entityDescriptions.isEmpty()) return results;

        // Collect all subWebPage keys for this modId from rag_web_cache
        Map<String, String> allSubPages = getSubPagesForMod(modId);
        if (allSubPages.isEmpty()) {
            log.debug("UrlAgent: no subWebPage entries for modId={}", modId);
            return results;
        }

        // Build the list of candidate keys
        StringBuilder candidateList = new StringBuilder();
        int count = 0;
        for (String key : allSubPages.keySet()) {
            if (count++ >= 200) { candidateList.append("  ... and more\n"); break; }
            candidateList.append("  ").append(key).append("\n");
        }

        // Send to LLM for matching — one call with all entities
        String entitiesStr = String.join(", ", entityDescriptions);
        try {
            // 优先使用结构化输出（AiServices）
            if (matcher != null) {
                try {
                    // 结构化路径的用户消息只提供上下文，输出格式完全交给 @SystemMessage
                    String structuredPrompt = "[Entity to find]\n" + entitiesStr
                            + "\n\n[Candidate items for mod: " + modId + "]\n"
                            + candidateList;
                    Result<UrlMatches> result = matcher.match(structuredPrompt);
                    lastUsage = accumulateUsage(lastUsage, result.tokenUsage());
                    UrlMatches matches = result.content();
                    if (matches != null && matches.keys() != null && !matches.keys().isEmpty()) {
                        for (String key : matches.keys()) {
                            String url = allSubPages.get(key);
                            if (url == null) url = fuzzyMatch(key, allSubPages);
                            if (url != null) {
                                results.put(key, url);
                                log.info("UrlAgent: '{}' → {} → {}", entitiesStr, key, url);
                            }
                        }
                        return results;
                    }
                    // 模型明确返回 []：无匹配，避免重复调用 LLM
                    if (matches != null) {
                        log.info("UrlAgent: no match for '{}' in mod {} (structured)", entitiesStr, modId);
                        return results;
                    }
                } catch (Exception e) {
                    log.warn("UrlAgent structured call failed ({}), falling back to text parse: {}",
                            e.getClass().getSimpleName(), e.getMessage());
                    log.debug("UrlAgent structured failure detail", e);
                }
            }

            // 回退：旧版逐行文本解析
            String prompt = MATCH_PROMPT.formatted(entitiesStr, modId, candidateList.toString());
            String response = llm.rawAsk(prompt);
            if (response == null || response.isBlank() || response.trim().equals("NONE")) {
                log.info("UrlAgent: no match for '{}' in mod {}", entitiesStr, modId);
                return results;
            }

            // Parse response: each line is a matched key
            for (String line : response.split("\n")) {
                line = line.trim();
                if (line.isBlank() || line.equals("NONE")) continue;

                // Try exact key match first, then fuzzy
                String url = allSubPages.get(line);
                if (url == null) {
                    // Fuzzy: find the closest key
                    url = fuzzyMatch(line, allSubPages);
                }
                if (url != null) {
                    results.put(line, url);
                    log.info("UrlAgent: '{}' → {} → {}", entitiesStr, line, url);
                }
            }
        } catch (Exception e) {
            log.warn("UrlAgent failed: {}", e.getMessage());
        }

        return results;
    }

    /** 最近一次匹配调用的真实 Token 用量；回退路径或未配置 Key 时为 null。 */
    public TokenUsage lastUsage() {
        return lastUsage;
    }

    /** 清空累计用量（QaPipeline 在每个问题处理开始时调用）。 */
    public void resetUsage() {
        lastUsage = null;
    }

    private static TokenUsage accumulateUsage(TokenUsage existing, TokenUsage next) {
        if (next == null) return existing;
        if (existing == null) return next;
        try {
            return existing.add(next);
        } catch (Exception e) {
            return next;
        }
    }

    /** Get all subWebPage keys→URLs for a given modId from rag_web_cache */
    private Map<String, String> getSubPagesForMod(String modId) {
        Map<String, String> all = new LinkedHashMap<>();
        try {
            List<DatabaseBuilder.WebCacheEntry> entries = db.loadWebPages(modId);
            for (var entry : entries) {
                if (entry.subWebPage() != null) {
                    all.putAll(entry.subWebPage());
                }
            }
        } catch (Exception e) {
            log.debug("UrlAgent: load subPages failed for {}: {}", modId, e.getMessage());
        }
        return all;
    }

    /** Fuzzy key match: find the key in the map that is closest to the LLM's output */
    private String fuzzyMatch(String target, Map<String, String> allSubPages) {
        String bestKey = null;
        int bestScore = 0;
        String lower = target.toLowerCase();

        for (String key : allSubPages.keySet()) {
            int score = 0;
            String keyLower = key.toLowerCase();
            if (keyLower.equals(lower)) score = 100;
            else if (keyLower.contains(lower) || lower.contains(keyLower)) score = 80;
            else {
                // Character overlap
                int overlap = 0;
                for (int i = 0; i < Math.min(lower.length(), keyLower.length()); i++) {
                    if (lower.charAt(i) == keyLower.charAt(i)) overlap++;
                }
                score = overlap;
            }
            if (score > bestScore) { bestScore = score; bestKey = key; }
        }

        return bestScore > 0 ? allSubPages.get(bestKey) : null;
    }
}
