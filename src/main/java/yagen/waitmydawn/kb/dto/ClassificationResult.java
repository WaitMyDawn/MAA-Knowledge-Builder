package yagen.waitmydawn.kb.dto;

import yagen.waitmydawn.kb.service.WikiScraperService.McmodCategory;

import java.util.ArrayList;
import java.util.List;

/** 问题分类结果 (由 ClassifyAgent 生成) */
public class ClassificationResult {

    /** 遗留问题类型 (保持向后兼容) */
    public enum QuestionType {
        RECIPE,       // 合成配方
        MULTIBLOCK,   // 多方块结构
        MECHANICS,    // 机制说明
        GENERAL       // 通用问答
    }

    private QuestionType questionType = QuestionType.GENERAL;
    private McmodCategory mcmodCategory;        // MC百科分类 (ClassifyAgent 输出)
    private List<String> entities = new ArrayList<>();
    private String scope = "general";
    private String targetMod;

    public QuestionType getQuestionType() { return questionType; }
    public void setQuestionType(QuestionType v) { this.questionType = v; }

    public McmodCategory getMcmodCategory() { return mcmodCategory; }
    public void setMcmodCategory(McmodCategory v) { this.mcmodCategory = v; }

    public List<String> getEntities() { return entities; }
    public void setEntities(List<String> v) { this.entities = v; }

    public String getScope() { return scope; }
    public void setScope(String v) { this.scope = v; }

    public String getTargetMod() { return targetMod; }
    public void setTargetMod(String v) { this.targetMod = v; }

    /** Map McmodCategory to legacy QuestionType */
    public static QuestionType toQuestionType(McmodCategory cat) {
        if (cat == null) return QuestionType.GENERAL;
        return switch (cat) {
            case ITEM -> QuestionType.RECIPE;
            case MUL_BLOCK -> QuestionType.MULTIBLOCK;
            case STRUCTURE -> QuestionType.MULTIBLOCK;
            case ENTITY, ENCHANT, EFFECT, SPELL, BIOME, DIM -> QuestionType.MECHANICS;
            default -> QuestionType.GENERAL;
        };
    }
}
