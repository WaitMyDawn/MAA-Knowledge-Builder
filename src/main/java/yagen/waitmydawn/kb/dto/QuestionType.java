package yagen.waitmydawn.kb.dto;

/**
 * 问答意图分类（ClassifyAgent 输出）。
 *
 * 与 WikiScraperService.McmodCategory（MC百科站点分类，仅供抓取使用）相互独立：
 * 本枚举描述“用户问题属于哪一类问答意图”，供管道路由、渲染开关与观测使用。
 */
public enum QuestionType {
    ITEM("物品"),
    ENTITY("生物"),
    STRUCTURE("结构"),
    BIOME("群系"),
    DIM("维度"),
    MECHANISM("游戏机制"),
    PLAYING("游戏玩法"),
    ENUMERATE("枚举"),
    GENERAL("其他");

    private final String name;

    QuestionType(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
