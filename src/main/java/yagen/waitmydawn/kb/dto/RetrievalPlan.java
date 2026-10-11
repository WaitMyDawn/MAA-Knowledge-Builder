package yagen.waitmydawn.kb.dto;

import java.util.List;

/** 查询主体用于定位页面，原始诉求用于选择正文；枚举范围不等于详情覆盖范围。 */
public record RetrievalPlan(String question, String modId, boolean enumerate, String objectType,
                            List<String> filters, int requestedCount, boolean exhaustive, QuestionType intent) {
    public RetrievalPlan(String question,String modId,boolean enumerate,String objectType,List<String> filters,int requestedCount,boolean exhaustive) {
        this(question,modId,enumerate,objectType,filters,requestedCount,exhaustive,
                enumerate?QuestionType.ENUMERATE:QuestionType.GENERAL);
    }
    public static RetrievalPlan lookup(String question, String modId) {
        return new RetrievalPlan(question,modId,false,"",List.of(),3,false);
    }
    public int topCategory() {
        return switch(objectType) {
            case "ENTITY" -> 4; case "BIOME" -> 2; case "DIM" -> 3;
            case "MUL_BLOCK" -> 7; case "STRUCTURE" -> 8; case "MECHANISM" -> 10;
            case "SPELL" -> 229; case "ENCHANT" -> 5; case "EFFECT" -> 6; default -> 1;
        };
    }
    public boolean needsDetails() {
        return !filters.isEmpty() || question.matches("(?s).*(怎么|如何|方法|操作|驯服|骑乘|繁殖|获得|获取|使用).*" );
    }
    public String queryFor(String subject) { return subject + "：" + question; }
}
