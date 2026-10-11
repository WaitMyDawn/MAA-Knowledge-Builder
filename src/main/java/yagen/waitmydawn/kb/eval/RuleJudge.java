package yagen.waitmydawn.kb.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 规则判分器（主判，确定性）。
 *
 * <p>对每道题的 assertions 逐条判定，输出：单条结果 + 加权得分 + 是否通过。
 * 通过条件：硬性 required 断言通过；超过五项的内容覆盖允许 80% 命中，且加权得分 >= {@link #PASS_SCORE}。
 *
 * <p>支持的断言 kind：
 * <ul>
 *   <li>answer_contains_any / answer_contains_all / answer_not_contains（对回答文本）</li>
 *   <li>registry_any / registry_all（答案中出现的注册名，本质同 contains，便于报告区分）</li>
 *   <li>entity_resolved_any / entity_resolved_all（QaResult.resolvedEntities）</li>
 *   <li>recipe_output_any（配方检索命中的 output_item）</li>
 *   <li>render_expected（是否应产出配方图，expected 默认 true）</li>
 *   <li>tier_at_least（value 形如 "TIER B"，A 最优）</li>
 *   <li>refusal（对抗题：命中拒答/免责标记；values 为「禁止出现」的编造标记）</li>
 *   <li>length_bounds（min/max 字符数）</li>
 *   <li>latency_under_ms（value 为毫秒上限）</li>
 *   <li>no_error（本轮是否无异常）</li>
 * </ul>
 */
public final class RuleJudge {

    /** 通过阈值：required 全过 + 加权分 >= 0.8。 */
    public static final double PASS_SCORE = 0.8;

    /** 一次问答的可观测结果（由 EvalRunner 从 QaResult/QaMetrics 提取）。 */
    /** 一张已渲染的配方图（type 取 crafting/furnace/smithing，valid=文件存在且可解码）。 */
    public record RenderedImage(String outputItem, String type, String path, long bytes, boolean valid) {}

    public record Observation(String answer, boolean error, String errorMessage,
                              List<String> recipeOutputs, List<String> resolvedRegistries,
                              int renderCount, List<RenderedImage> renderedImages,
                              List<String> subPageLabels, List<String> subPageUrls,
                              List<String> webCacheHitLabels,
                              String tier, long totalTimeMs,
                              int tokenIn, int tokenOut, boolean retrievalHit, java.util.Map<String,String> catalogMembers) {
        public Observation(String answer,boolean error,String errorMessage,List<String> recipeOutputs,List<String> resolvedRegistries,
                           int renderCount,List<RenderedImage> renderedImages,List<String> subPageLabels,List<String> subPageUrls,
                           List<String> webCacheHitLabels,String tier,long totalTimeMs,int tokenIn,int tokenOut,boolean retrievalHit) {
            this(answer,error,errorMessage,recipeOutputs,resolvedRegistries,renderCount,renderedImages,subPageLabels,subPageUrls,
                    webCacheHitLabels,tier,totalTimeMs,tokenIn,tokenOut,retrievalHit,java.util.Map.of());
        }
        public static Observation error(String message) {
            return new Observation("", true, message, List.of(), List.of(),
                    0, List.of(), List.of(), List.of(), List.of(),
                    null, 0, 0, 0, false);
        }
    }

    public record Outcome(String kind, boolean passed, double weight, boolean required, String detail) {}

    public record Verdict(double score, boolean passed, boolean allRequiredPassed,
                          boolean hasAssertions, List<Outcome> outcomes) {
        public List<Outcome> failedOutcomes() {
            return outcomes.stream().filter(o -> !o.passed).toList();
        }
    }

    /** 拒答/免责标记：对抗题命中任一即视为「正确地没有硬答」。 */
    public static final List<String> REFUSAL_MARKERS = List.of(
            "不在当前知识库", "知识库中没有", "知识库未包含", "未包含相关", "没有找到", "未找到",
            "无法回答", "无法确定", "无法提供", "未安装", "没有安装", "没有收录",
            "抱歉", "不确定", "⚠️", "通用知识", "可能不准确", "建议",
            // 纠正提问前提也视为正确的"拒答/纠偏"（如"鞘翅不能合成"）
            "不能合成", "无法合成", "不能通过合成", "无法通过合成",
            "不存在", "并不存在", "没有这个", "没有该", "不具备", "没有名为", "未找到名为", "未查到名为");

    private RuleJudge() {}

    public static Verdict judge(GoldenSet.Turn turn, Observation obs) {
        List<Outcome> outcomes = new ArrayList<>();
        if (turn.assertions().isEmpty()) {
            // 无断言的题（例如 v1 兼容题）：只要能正常跑完就算通过，仅作冒烟
            boolean passed = !obs.error();
            outcomes.add(new Outcome("no_assertions", passed, 1.0, true,
                    obs.error() ? "error=" + obs.errorMessage() : "ran without assertions"));
            return new Verdict(passed ? 1.0 : 0.0, passed, passed, false, outcomes);
        }

        double totalWeight = 0;
        double passedWeight = 0;
        boolean allRequiredPassed = true;
        for (GoldenSet.Assertion a : turn.assertions()) {
            Outcome outcome = evaluate(a, obs);
            outcomes.add(outcome);
            double w = a.weight() > 0 ? a.weight() : 1.0;
            totalWeight += w;
            if (outcome.passed()) passedWeight += w;
            if (a.required() && !outcome.passed()) allRequiredPassed = false;
        }
        double score = totalWeight > 0 ? passedWeight / totalWeight : 0;
        var coverage = outcomes.stream().filter(o -> isCoverage(o.kind()) && o.required()).toList();
        boolean coveragePassed = coverage.size() > 5
                && coverage.stream().filter(Outcome::passed).count() >= Math.ceil(coverage.size() * PASS_SCORE);
        boolean hardPassed = outcomes.stream().filter(o -> o.required() && !isCoverage(o.kind())).allMatch(Outcome::passed);
        boolean passed = hardPassed && (allRequiredPassed || coveragePassed) && score >= PASS_SCORE;
        return new Verdict(score, passed, allRequiredPassed, true, outcomes);
    }

    private static boolean isCoverage(String kind) {
        return kind.equals("answer_contains_any") || kind.equals("answer_contains_all")
                || kind.equals("answer_matches") || kind.equals("answer_contains_at_least");
    }

    private static Outcome evaluate(GoldenSet.Assertion a, Observation obs) {
        String answer = obs.answer() == null ? "" : obs.answer().replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1").replace("**", "").replace("`", "");
        double weight = a.weight() > 0 ? a.weight() : 1.0;
        List<String> values = a.values() == null ? List.of() : a.values();
        boolean ok;
        String detail;

        switch (a.kind()) {
            case "answer_matches_at_least" -> {
                int need = a.min() == null ? values.size() : a.min();
                long hits = values.stream().filter(v -> java.util.regex.Pattern.compile(v,
                        java.util.regex.Pattern.CASE_INSENSITIVE).matcher(answer).find()).count();
                ok = hits >= need; detail = "matchedAspects=" + hits + "/" + need;
            }
            case "answer_matches", "answer_core_matches" -> {
                ok = a.value() != null && java.util.regex.Pattern.compile(a.value(),
                        java.util.regex.Pattern.CASE_INSENSITIVE).matcher(answer).find();
                detail = "relationPattern=" + a.value();
            }
            case "answer_lists_at_least", "answer_catalog_lists_at_least" -> {
                int need = a.min() == null ? values.size() : a.min();
                var names="answer_catalog_lists_at_least".equals(a.kind())?new ArrayList<>(obs.catalogMembers().keySet()):values;
                List<String> hits = names.stream().filter(v -> v != null && !v.isBlank())
                        .distinct().filter(v -> java.util.regex.Pattern.compile(
                                "(?m)^\\s*(?:#{1,6}\\s*)?(?:[-*+]|\\d+[.、)）]|[①②③④⑤⑥⑦⑧⑨⑩]|\\|)?\\s*\\**"
                                        + java.util.regex.Pattern.quote(v) + "(?=[\\s（(：:|*—-]|$)")
                                .matcher(answer).find()).toList();
                long count="answer_catalog_lists_at_least".equals(a.kind())?hits.stream().map(obs.catalogMembers()::get).distinct().count():hits.size();
                ok = count >= need;
                detail = "listedMembers=" + hits.size() + "/" + need + " " + hits;
            }
            case "answer_contains_any", "registry_any" -> {
                ok = containsAny(answer, values);
                detail = describe("命中任一", values, firstHit(answer, values));
            }
            case "answer_contains_all", "registry_all" -> {
                ok = containsAll(answer, values);
                detail = describe("全部命中", values, missing(answer, values));
            }
            case "answer_contains_at_least" -> {
                // 举例/枚举类题：命中 values 中至少 min 个（min 缺省取 values.size()）
                int need = a.min() == null ? values.size() : a.min();
                List<String> hits = new ArrayList<>();
                for (String v : values) {
                    if (v != null && !v.isBlank() && firstHit(answer, List.of(v)) != null) hits.add(v);
                }
                ok = hits.size() >= need;
                detail = "hits=" + hits.size() + "/" + need + " " + hits;
            }
            case "answer_not_contains" -> {
                String hit = firstHit(answer, values);
                ok = hit == null;
                detail = hit == null ? "未出现禁止项" : "出现禁止项: " + hit;
            }
            case "entity_resolved_any" -> {
                List<String> regs = obs.resolvedRegistries() == null ? List.of() : obs.resolvedRegistries();
                ok = containsAny(String.join(" ", regs), values);
                detail = "resolved=" + regs + " expectAny=" + values;
            }
            case "entity_resolved_all" -> {
                List<String> regs = obs.resolvedRegistries() == null ? List.of() : obs.resolvedRegistries();
                ok = containsAll(String.join(" ", regs), values);
                detail = "resolved=" + regs + " expectAll=" + values;
            }
            case "recipe_output_any" -> {
                List<String> outs = obs.recipeOutputs() == null ? List.of() : obs.recipeOutputs();
                String hit = firstHit(String.join(" ", outs), values);
                ok = hit != null;
                detail = "recipeOutputs=" + outs + " hit=" + hit;
            }
            case "render_expected" -> {
                boolean expect = a.expected() == null || a.expected();
                ok = (obs.renderCount() > 0) == expect;
                detail = "renderCount=" + obs.renderCount() + " expected=" + expect;
            }
            case "render_image_valid" -> {
                // 校验真实渲染产物：文件存在、可解码；value/values 可选，用于限定类型（crafting/furnace/smithing）
                List<RenderedImage> images = obs.renderedImages() == null ? List.of() : obs.renderedImages();
                List<String> wantTypes = new ArrayList<>();
                if (a.value() != null && !a.value().isBlank()) wantTypes.add(a.value());
                if (values != null) wantTypes.addAll(values);
                List<RenderedImage> matched = images.stream()
                        .filter(RenderedImage::valid)
                        .filter(i -> wantTypes.isEmpty() || wantTypes.stream()
                                .anyMatch(t -> t.equalsIgnoreCase(i.type())))
                        .toList();
                ok = !matched.isEmpty();
                detail = "validImages=" + images.stream().filter(RenderedImage::valid)
                        .map(i -> i.type() + ":" + i.outputItem()).toList()
                        + " wantTypes=" + wantTypes;
            }
            case "web_cache_has_label" -> {
                // 过程层节点：rag_web_cache 的 subWebPage 映射里是否存在期望的「中文名(英文名)」条目
                List<String> hits = obs.webCacheHitLabels() == null ? List.of() : obs.webCacheHitLabels();
                ok = !hits.isEmpty();
                detail = "webCacheHits=" + hits + " wantAny=" + values;
            }
            case "subpage_url_any" -> {
                // 过程层节点：管道是否真的拿到了 实体→网页URL 映射（QaResult.subPageUrls）
                List<String> urls = obs.subPageUrls() == null ? List.of() : obs.subPageUrls();
                boolean anyUrl = !urls.isEmpty();
                boolean hostOk = values.isEmpty() || urls.stream()
                        .anyMatch(u -> values.stream().anyMatch(v -> u.toLowerCase(Locale.ROOT)
                                .contains(v.toLowerCase(Locale.ROOT))));
                ok = anyUrl && hostOk;
                detail = "subPageUrls=" + urls.size() + " (labels=" + obs.subPageLabels()
                        + ") hostFilter=" + values;
            }
            case "tier_at_least" -> {
                Integer got = tierRank(obs.tier());
                Integer want = tierRank(a.value());
                ok = got != null && want != null && got <= want;
                detail = "tier=" + obs.tier() + " atLeast=" + a.value();
            }
            case "refusal" -> {
                // Markdown 强调标记不应打断“知识库中没有”等语义相邻的词。
                String plainAnswer=answer.replace("*", "").replace("`", "");
                boolean refused = containsAny(plainAnswer, REFUSAL_MARKERS);
                String fabricated = values.isEmpty() ? null : firstHit(plainAnswer, values);
                ok = refused && fabricated == null;
                detail = "refused=" + refused + (fabricated == null ? "" : ", fabricated=" + fabricated);
            }
            case "length_bounds" -> {
                int len = answer.length();
                boolean minOk = a.min() == null || len >= a.min();
                boolean maxOk = a.max() == null || len <= a.max();
                ok = minOk && maxOk;
                detail = "length=" + len + " min=" + a.min() + " max=" + a.max();
            }
            case "latency_under_ms" -> {
                long limit = parseLong(a.value(), Long.MAX_VALUE);
                ok = obs.totalTimeMs() > 0 && obs.totalTimeMs() <= limit;
                detail = "totalTimeMs=" + obs.totalTimeMs() + " limit=" + limit;
            }
            case "no_error" -> {
                ok = !obs.error();
                detail = obs.error() ? "error=" + obs.errorMessage() : "no error";
            }
            default -> {
                ok = false;
                detail = "unknown assertion kind: " + a.kind();
            }
        }
        return new Outcome(a.kind(), ok, weight, a.required(), detail);
    }

    private static String describe(String prefix, List<String> expect, String evidence) {
        return prefix + " " + expect + (evidence == null ? "" : " -> " + evidence);
    }

    private static String firstHit(String text, List<String> values) {
        if (text == null || text.isEmpty() || values == null) return null;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String v : values) {
            if (v != null && !v.isBlank() && lower.contains(v.toLowerCase(Locale.ROOT))) return v;
        }
        return null;
    }

    private static String missing(String text, List<String> values) {
        List<String> miss = new ArrayList<>();
        for (String v : values) {
            if (v != null && !v.isBlank() && firstHit(text, List.of(v)) == null) miss.add(v);
        }
        return miss.isEmpty() ? "all present" : "missing=" + miss;
    }

    private static boolean containsAny(String text, List<String> values) {
        return firstHit(text, values) != null;
    }

    private static boolean containsAll(String text, List<String> values) {
        if (values == null || values.isEmpty()) return true;
        for (String v : values) {
            if (v != null && !v.isBlank() && firstHit(text, List.of(v)) == null) return false;
        }
        return true;
    }

    private static Integer tierRank(String tier) {
        if (tier == null) return null;
        return switch (tier.trim().toUpperCase(Locale.ROOT)) {
            case "TIER A", "A" -> 0;
            case "TIER B", "B" -> 1;
            case "TIER C", "C" -> 2;
            case "TIER D", "D" -> 3;
            default -> null;
        };
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
