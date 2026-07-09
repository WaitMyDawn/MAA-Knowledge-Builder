package yagen.waitmydawn.kb.service;

import yagen.waitmydawn.kb.dto.ClassificationResult;
import yagen.waitmydawn.kb.dto.ClassificationResult.QuestionType;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 问题分类器 — 基于规则引擎 (关键字匹配)。
 * 识别问题类型: RECIPE / MULTIBLOCK / MECHANICS / GENERAL。
 */
public class ClassifierService {

    // 配方相关关键词
    private static final Set<String> RECIPE_KEYWORDS = Set.of(
            "合成", "配方", "制作", "craft", "recipe", "烧炼", "锻造", "酿造",
            "怎么做", "如何做", "怎么合成", "怎样制作", "冶炼", "烧制", "烹饪"
    );

    // 多方块结构关键词
    private static final Set<String> MULTIBLOCK_KEYWORDS = Set.of(
            "多方块", "搭建", "建造", "结构", "multiblock", "怎么搭", "如何搭",
            "形状", "摆法", "摆放", "构造", "架构", "分层", "层次",
            "几层", "多少方块", "build", "structure"
    );

    // 机制/原理关键词
    private static final Set<String> MECHANICS_KEYWORDS = Set.of(
            "机制", "原理", "怎么用", "如何使用", "功能", "特性", "mechanic",
            "具体作用", "效果", "条件", "触发", "生成", "刷怪",
            "概率", "掉落", "伤害", "范围", "持续时间"
    );

    // 物品名提取模式 (中文引号内、英文引号内、"XX的XX"、纯英文单词)
    private static final Pattern QUOTED = Pattern.compile("[「『\"“]([^」』\"”]+)[」』\"”]");
    private static final Pattern ENGLISH_WORD = Pattern.compile("\\b([A-Z][a-z]+(?:\\s+[A-Z][a-z]+)*)\\b");

    /**
     * 分类用户问题。
     */
    public ClassificationResult classify(String question) {
        ClassificationResult result = new ClassificationResult();
        if (question == null || question.isBlank()) return result;

        String lower = question.toLowerCase();

        // 计算各类别的匹配分数
        int recipeScore = countMatches(lower, RECIPE_KEYWORDS);
        int multiScore = countMatches(lower, MULTIBLOCK_KEYWORDS);
        int mechScore = countMatches(lower, MECHANICS_KEYWORDS);

        // 判断类型
        if (multiScore > recipeScore && multiScore > mechScore && multiScore > 0) {
            result.setQuestionType(QuestionType.MULTIBLOCK);
        } else if (recipeScore > mechScore && recipeScore > 0) {
            result.setQuestionType(QuestionType.RECIPE);
        } else if (mechScore > 0) {
            result.setQuestionType(QuestionType.MECHANICS);
        } else {
            result.setQuestionType(QuestionType.GENERAL);
        }

        // 提取实体 (物品/模组名)
        result.setEntities(extractEntities(question));
        result.setScope(inferScope(question));

        return result;
    }

    private int countMatches(String text, Set<String> keywords) {
        int count = 0;
        for (String kw : keywords) {
            if (text.contains(kw)) count++;
        }
        return count;
    }

    // 中文问句后缀模式: 提取 "XX怎么做/如何制作/怎么合成" 中的 XX
    private static final Pattern CN_ITEM_PATTERN = Pattern.compile(
            "(.+?)(怎么|如何|怎样|怎么合成|如何制作|怎样制作|的配方|的制作|合成配方|怎么做|合成方式|的合成|打造|制造)" +
            "|(.+?)(在哪里|在哪|怎么去|怎么找|怎么打|如何获取|怎么获得)" +
            "|([「『\"“].+?[」』\"”])" +
            "|(\\b[A-Z][a-z]+(?:\\s+[A-Z][a-z]+)*\\b)"
    );

    private List<String> extractEntities(String question) {
        List<String> entities = new ArrayList<>();

        // Strategy 1: Use LLM for Chinese entity extraction (most reliable)
        // Don't call LLM here — entity extraction should be fast
        // Instead, use regex-based Chinese extraction

        // Remove common question words/postfixes to isolate the target
        String cleaned = question
                .replaceAll("[?？!！。，,、]", " ")
                .replaceAll("(怎么做|如何制作|怎么合成|合成配方|合成方法|合成方式|的配方|的制作|怎么搞|怎么弄|怎样做|如何做|在哪里|在哪|怎么去|怎么找|怎么打|如何获取|怎么获得)", " ")
                .replaceAll("(有谁|是谁|哪位|哪个|什么|哪些|多少|几个|怎么|如何|为何|为啥|为什么)", " ")
                .trim();

        // Split by spaces, take meaningful fragments
        for (String part : cleaned.split("[\\s]+")) {
            part = part.trim();
            if (part.length() >= 2 && !isStopWord(part)) {
                entities.add(part);
            }
        }

        // Also try direct pattern: "XXXX怎么做" → XXXX
        Matcher m = CN_ITEM_PATTERN.matcher(question);
        while (m.find()) {
            for (int i = 1; i <= m.groupCount(); i++) {
                String g = m.group(i);
                if (g != null && g.length() >= 2 && !isStopWord(g)) {
                    entities.add(g.trim());
                }
            }
        }

        // Quote content
        m = QUOTED.matcher(question);
        while (m.find()) entities.add(m.group(1).trim());

        // English words
        m = ENGLISH_WORD.matcher(question);
        while (m.find()) {
            String w = m.group(1);
            if (w.length() > 1 && !isCommonWord(w)) entities.add(w);
        }

        // Deduplicate
        return entities.stream().distinct().toList();
    }

    private boolean isStopWord(String s) {
        return Set.of("的", "和", "或", "在", "是", "有", "做", "用", "要", "可以", "这个",
                "那个", "一个", "使用", "什么", "怎么", "如何", "哪里", "哪个", "为什么",
                "我", "你", "他", "她", "它", "了", "吗", "吧", "呢", "啊").contains(s);
    }

    private String inferScope(String question) {
        String lower = question.toLowerCase();
        if (lower.contains("整合包") || lower.contains("modpack")) return "modpack";
        if (lower.contains("原版") || lower.contains("vanilla") || lower.contains("minecraft")) return "vanilla";
        return "mod";
    }

    private boolean isCommonWord(String w) {
        return Set.of("The", "And", "For", "With", "From", "This", "That", "What", "How",
                "When", "Where", "Which", "There", "Their").contains(w);
    }
}
