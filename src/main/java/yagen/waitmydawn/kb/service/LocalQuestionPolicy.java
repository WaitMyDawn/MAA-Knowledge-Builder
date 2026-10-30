package yagen.waitmydawn.kb.service;

import java.util.regex.Pattern;

/** 快照可直接证明的查询在网页检索前终止；材料来源和玩法属于独立需求。 */
final class LocalQuestionPolicy {
    private static final Pattern RECIPE=Pattern.compile("合成|配方|制作|烧炼|熔炼|冶炼|锻造|加工|怎么做|如何做|\\bcraft(?:ing)?\\b|\\brecipe\\b",Pattern.CASE_INSENSITIVE);
    private static final Pattern APPEARANCE=Pattern.compile("展示|图片|配图|图标|外观|长什么样|长啥样|渲染图|品种|变种|(?:成年|幼年|一阶|五阶).*(?:对比|比较)|\\b(?:show|image|icon|appearance|picture|variant)\\b",Pattern.CASE_INSENSITIVE);
    private static final Pattern KNOWLEDGE=Pattern.compile("获取|获得|哪里|在哪|何处|来源|掉落|用途|作用|有什么用|机制|攻略|教程|流程|组装|升级|多方块|打法|怎么打|如何打|怎么玩|如何玩|怎么用|如何用|怎么使用|如何使用|用法|使用方法|为什么|为何|失败|条件|前置|解锁|伤害|属性|效果|推荐|如何驯服|怎么驯服|如何饲养|怎么饲养|如何修改|怎么修改|脚本|\\b(?:obtain|acquire|find|drop|fight|damage|mechanic|tame|tutorial)\\b",Pattern.CASE_INSENSITIVE);
    private static final Pattern WEB=Pattern.compile("https?://|联网|上网|百科|网页|网址|网站|\\bwiki\\b|\\b(?:search|browse)\\b",Pattern.CASE_INSENSITIVE);
    record Decision(boolean recipe,boolean appearance,boolean externalKnowledge,boolean explicitWeb) {
        boolean localOnly(){return (recipe||appearance)&&!externalKnowledge&&!explicitWeb;}
        String reason(){return localOnly()?"快照配方／外观查询：仅使用本地证据；缺失状态不触发网页补充":explicitWeb?"用户明确请求网页资料":"需要检查本地正文及外部知识缺口";}
    }
    static Decision decide(String question) {
        String q=question==null?"":question;
        String webRequest=q.replaceAll("不(?:需要|要|用|必)?(?:联网|上网)|禁止联网|仅(?:查|用|使用)?本地|不要查百科","");
        String facts=q.replaceAll("(?:标注|注明|显示|给出|附上)(?:图片|图像|配图)?来源","");
        return new Decision(RECIPE.matcher(q).find(),APPEARANCE.matcher(q).find(),KNOWLEDGE.matcher(facts).find(),WEB.matcher(webRequest).find());
    }
}
