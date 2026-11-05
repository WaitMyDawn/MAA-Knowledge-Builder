package yagen.waitmydawn.kb.service;

import java.util.*;
import java.util.regex.Pattern;

/** 取证完成检查是确定性下限；不能把属性表或缺口说明当作操作步骤。 */
public final class TaskEvidence {
    private TaskEvidence() {}
    public static String positiveText(String text){
        var result=new StringBuilder();boolean uncertaintySection=false;
        for(String line:Objects.toString(text,"").replace("**","").replace("__","").split("\\R")){
            if(line.stripLeading().matches("#{1,6}\\s+.*"))uncertaintySection=line.matches(".*(?:无法确认|未确认|未知|缺口|未找到).*");
            if(uncertaintySection||line.matches(".*(?:未找到|未查到|没有找到|没有取得|未取得|未确认|无法确认|无法提供|无法给出|尚不能|尚未|未覆盖|缺少|没有明确|没有直接|未提供|未包含|不包含|没有.*(?:包含|说明|证据)|请确认具体对象|如需更详细|需要你.*(?:明确|指定)).*"))continue;
            result.append(line).append('\n');
        }
        return result.toString();
    }
    public static Map<String,String> requirements(String question){
        var facts=new LinkedHashMap<String,String>();
        if(question.matches("(?s).*(驯服|驯养).*"))facts.put("交互或驯服方法","(?:右键|喂食|喂给|喂养|投喂|使用|手持|不能驯服|不可驯服|无法驯服|不可被驯服)");
        if(question.matches("(?s).*(建造|搭建|摆放|多方块).*"))facts.put("搭建布局与操作","(?:摆放|放置|搭建|底层|顶层|中层|形状|朝向|结构布局)");
        if(question.matches("(?s).*(召唤|召出).*"))facts.put("出现或召唤流程","(?:放置|右键|使用|击败|激活|投入|召唤物|自然生成|无需召唤)");
        if(question.matches("(?s).*(怎么打|如何打|战斗流程|战斗特点).*"))facts.put("战斗行动","(?:躲避|躲开|闪避|摧毁|抵挡|破盾|格挡|走位|远离|拉开|弱点|破坏.{0,10}水晶|阶段.{0,30}(?:攻击|远离))");
        if(question.matches("(?s).*(交互|(?:采集|剥取|操作).{0,12}具体步骤).*"))facts.put("交互操作","(?:右键|右击|左键|左击|空手|手持.{0,30}(?:点击|采集|拾取|使用)|投喂|点击|激活|触发)");
        if(question.matches("(?s).*(如何升级|怎么升级|如何.*提升|怎么.*提升).*"))facts.put("升级操作与条件","(?:合并|放入|投入|消耗|右键|右击|等级上限|使用[^。\\n]{0,30}(?:升级|提升|强化))");
        if(question.matches("(?s).*(?:召唤物|前置材料|祭品).{0,10}(?:获取|获得|制作|合成).*"))facts.put("前置物品的获取细节","(?:配方|合成材料|工作台|材料清单|掉落自|击杀[^。\\n]{0,20}掉落|宝箱|战利品箱|交易价格)");
        if(question.matches("(?s).*如何.{0,12}使用.*"))facts.put("使用操作","(?:右键|按下|快捷键|放入|点击|选择.{0,15}施法)");
        if(question.matches("(?s).*(用途|有什么用|特点|作用).*"))facts.put("用途或机制","(?:用于|用来|可以|可合成|可制作|能够|允许|需要|必须|装入|右键|自然生成|无法驯服|不能驯服|没有用途)");
        if(question.matches("(?s).*(怎么获取|如何获取|获取方式|获取途径|获取、|如何获得|怎么获得|制作和获取|获取和使用).*"))facts.put("获取途径","(?:合成|酿造|交易|掉落|宝箱|展示框|挖掘|开采|搜刮|击杀|购买|采集)");
        return facts;
    }
    public static List<String> missing(String question,String evidence){
        String affirmative=positiveText(evidence),plain=Objects.toString(evidence,"").replace("**","");
        return requirements(question).entrySet().stream().filter(entry->{
            if(entry.getKey().equals("前置物品的获取细节")&&Pattern.compile("(?:无需|不需要|不消耗).{0,8}(?:召唤物|祭品|材料)").matcher(affirmative).find())return false;
            String cue=switch(entry.getKey()){
                case "交互或驯服方法"->"(?:如何驯服|驯服方式|驯服方法|驯服步骤|驯服野生|喂食方式)";
                case "搭建布局与操作"->"(?:搭建图|搭建步骤|摆放顺序|结构尺寸|方块布局)";
                case "出现或召唤流程"->"(?:召唤方法|召唤步骤|召唤流程)";
                case "战斗行动"->"(?:战斗机制|战斗流程|打法细节)";
                case "交互操作"->"(?:交互方式|交互细节|使用方法)";
                case "使用操作"->"(?:具体施法操作|具体操作|具体用法|使用流程)";
                case "用途或机制"->"(?:具体用途|用途说明|特点说明|交互方式)";
                case "获取途径"->"(?:获取方式|获取途径|获取方法|获得方法)";
                case "前置物品的获取细节"->"(?:合成配方|制作材料|召唤物的获取|祭品的获取)";
                default->"(?:升级方法|升级步骤|升级流程)";
            };
            String gap="(?:未找到|未包含|没有.{0,8}(?:包含|说明)|无法(?:确认|给出|提供))[^。！？\\n]{0,160}"+cue;
            return Pattern.compile(gap).matcher(plain).find()||!Pattern.compile(entry.getValue()).matcher(affirmative).find();
        }).map(Map.Entry::getKey).toList();
    }
}
