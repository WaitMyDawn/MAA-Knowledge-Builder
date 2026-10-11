package yagen.waitmydawn.kb.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * LLM 裁判（复核路线，不参与主判 pass/fail）。
 *
 * <p>按题目给定的标准答案要点 + 评分细则打分，输出固定 JSON 维度分；
 * 完整 prompt 与原始响应由 {@link RunStore} 落盘，便于人工复核与面试演示。
 */
public final class LlmJudge {

    private static final Logger log = LoggerFactory.getLogger(LlmJudge.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 裁判硬超时（秒）：防止底层 HTTP 卡死拖住整个评测（2026-09-15 实测发生过 >6min 挂起）。 */
    private static final long JUDGE_TIMEOUT_SECONDS = 120;
    /** 裁判专用线程池（daemon）：超时后可放弃等待，不阻塞主流程。 */
    private static final ExecutorService JUDGE_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "llm-judge");
        t.setDaemon(true);
        return t;
    });

    private static final String JUDGE_PROMPT = """
            你是严格的中文 Minecraft 知识问答评测裁判。
            根据参考答案、核心要点、评分细则和本轮检索证据判断，不使用自身知识补充事实。
            参考答案不是封闭事实全集：证据支持的额外获取途径、修复配方等属于有效补充，不算幻觉。
            但补充不能替代核心获取方式；缺失核心方式仍应扣完整性并判 partial/fail。
            开放枚举允许证据支持的其他有效例子。分类目录只能证明名称和归属，驯服/骑乘等属性必须有详情证据。
            明确区分证据不足、与证据矛盾及已证实错误；不能因未写在参考答案里就判编造。
            核心要求以标准答案要点和评分细则为准；参考答案中“也可”“补充”“可能提到”的路线不是必需项，缺少它们不得单独导致 partial/fail。
            命名不准确时，明确说明名称差异并有条件地介绍有证据支持的真实候选，是有效的澄清；不得把候选当作已确认同义对象。

            [问题]
            %s

            [参考答案]
            %s

            [标准答案要点]
            %s

            [评分细则]
            %s

            [待评回答]
            %s

            [本轮实际检索证据，可能不完整]
            %s

            输出要求：只输出一个 JSON 对象，不要任何多余文字：
            {"verdict":"pass|partial|fail","faithfulness":1,"completeness":1,"correctness":1,"citation_ok":0,"hallucination":0,"refusal_correct":null,"reasons":"一句话理由"}
            字段说明：
            - faithfulness/completeness/correctness：1-5 分整数（5 最好）。
            - citation_ok：回答中给出的注册名/来源是否与要点一致（1/0）。
            - hallucination：是否出现与提供证据矛盾或有明确依据判为编造的关键事实（1=有，0=无）；仅证据不足须在 reasons 说明，不直接等同编造。
            - refusal_correct：仅当题目要求拒答时填 1/0，否则 null。
            """;

    /** 裁判结果；解析失败时只有 error 有值，rawResponse 仍保留原文。 */
    public record Result(String verdict, Double faithfulness, Double completeness,
                         Double correctness, Integer citationOk, Integer hallucination,
                         Integer refusalCorrect, String reasons, String rawResponse, String error) {}

    /** 裁判产出：结果 + 实际使用的 prompt。 */
    public record Outcome(Result result, String prompt) {}

    private LlmJudge() {}

    public static Outcome judge(ChatModel model, GoldenSet.Turn turn, String answer) {
        return judge(model, turn, answer, "未提供检索证据");
    }

    public static Outcome judge(ChatModel model, GoldenSet.Turn turn, String answer, String evidence) {
        String points = turn.answerPoints().isEmpty() ? "(未提供要点，按问题常识与评分细则判断)"
                : String.join("；", turn.answerPoints());
        String rubric = turn.judgeRubric().isEmpty() ? "(未提供细则，按事实正确性/完整性/是否幻觉三个维度判断)"
                : String.join("；", turn.judgeRubric());
        String reference = turn.referenceAnswer() == null || turn.referenceAnswer().isBlank()
                ? "(未提供参考答案)" : turn.referenceAnswer();
        if (reference.length() > 1500) reference = reference.substring(0, 1500) + "...(truncated)";
        String safeAnswer = answer == null ? "" : answer;
        if (safeAnswer.length() > 12000) safeAnswer = safeAnswer.substring(0, 12000) + "...(回答截断，不能据此断言末尾内容缺失)";

        String prompt = JUDGE_PROMPT.formatted(turn.question(), reference, points, rubric, safeAnswer, evidence);
        if (model == null) {
            return new Outcome(new Result(null, null, null, null, null, null, null, null, "", "judge model unavailable"), prompt);
        }
        long t0 = System.nanoTime();
        try {
            Future<ChatResponse> future = JUDGE_POOL.submit(() -> model.chat(ChatRequest.builder()
                    .messages(UserMessage.from(prompt))
                    .build()));
            ChatResponse response;
            try {
                response = future.get(JUDGE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException te) {
                future.cancel(true);
                log.warn("LLM[Judge] timeout after {}s for turn={} (abandoned)", JUDGE_TIMEOUT_SECONDS, turn.id());
                return new Outcome(new Result(null, null, null, null, null, null, null, null, "",
                        "judge timeout after " + JUDGE_TIMEOUT_SECONDS + "s"), prompt);
            }
            String raw = response.aiMessage().text();
            log.info("LLM[Judge] {}ms turn={} chars={}", (System.nanoTime() - t0) / 1_000_000,
                    turn.id(), raw == null ? 0 : raw.length());
            return new Outcome(parse(raw), prompt);
        } catch (Exception e) {
            log.warn("LLM[Judge] failed for turn={} after {}ms: {}", turn.id(),
                    (System.nanoTime() - t0) / 1_000_000, e.getMessage());
            return new Outcome(new Result(null, null, null, null, null, null, null, null, "", e.getMessage()), prompt);
        }
    }

    private static Result parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Result(null, null, null, null, null, null, null, null, "", "empty judge response");
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return new Result(null, null, null, null, null, null, null, null, raw, "no JSON object in judge response");
        }
        try {
            JsonNode node = MAPPER.readTree(raw.substring(start, end + 1));
            return new Result(
                    text(node, "verdict"),
                    number(node, "faithfulness"),
                    number(node, "completeness"),
                    number(node, "correctness"),
                    integer(node, "citation_ok"),
                    integer(node, "hallucination"),
                    integer(node, "refusal_correct"),
                    text(node, "reasons"),
                    raw,
                    null);
        } catch (Exception e) {
            return new Result(null, null, null, null, null, null, null, null, raw, "judge JSON parse failed: " + e.getMessage());
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Double number(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || !v.isNumber() ? null : v.asDouble();
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        return v.isNumber() ? v.asInt() : null;
    }
}
