package yagen.waitmydawn.kb.service;

import yagen.waitmydawn.kb.model.*;
import java.util.*;
import java.util.regex.Pattern;

/** 用途来自可追溯的图注、OCR 或位置推断，不把分类结果当作像素或游戏机制事实。 */
public final class GuideImageSemantics {
    public static final String VERSION="guide-image-v1";
    public record Grounding(String purpose,String basis,String blockId,String lineage,String stepText,String relation,String ocrStatus) {}
    private record Rule(String purpose,Pattern pattern) {}
    private static final List<Rule> RULES=List.of(
            rule("DECORATIVE","头像|站点标志|网站标志|广告|赞助商|avatar|banner ad|site logo"),
            rule("RECIPE","合成表|合成图|合成配方|配方图|crafting recipe|recipe grid"),
            rule("UI","界面|菜单|按钮|插槽|物品栏|任务栏|技能树|修复和命名|切石机|附魔花费|inventory|interface|menu|upgrade slot"),
            rule("STRUCTURE","多方块|装配|组装|结构|搭建|assembly|multiblock|structure"),
            rule("MAP","地图|坐标|地形|路线图|world map|coordinates"),
            rule("COMBAT","战斗|攻击|走位|弹幕|击败|boss fight|combat"),
            rule("ENTITY","外观|立绘|生物图|appearance|portrait"));
    private GuideImageSemantics() {}
    private static Rule rule(String purpose,String regex){return new Rule(purpose,Pattern.compile(regex,Pattern.CASE_INSENSITIVE));}
    public static Grounding describe(String caption,String ocr,String status,String blockId,String lineage,String stepText,String relation) {
        String cap=Objects.toString(caption,"");if(KnowledgeImageSearch.generatedCaption(cap))cap="";
        String purpose=purpose(cap),basis="CAPTION";
        if(purpose.equals("UNKNOWN")){purpose=purpose(ocr);basis="OCR";}
        if(purpose.equals("UNKNOWN")){
            String context=Objects.toString(stepText,"");
            purpose=context.matches("(?s).*(界面|菜单|按钮).*" )?"UI":context.matches("(?s).*(合成|制作).*" )?"RECIPE":purpose(context);basis="CONTEXT_INFERRED";
        }
        if(purpose.equals("UNKNOWN"))basis="UNKNOWN";
        return new Grounding(purpose,basis,blockId,lineage,limit(stepText,1400),relation,Objects.toString(status,"NOT_ANALYZED"));
    }
    private static String purpose(String text){for(var rule:RULES)if(rule.pattern().matcher(Objects.toString(text,"")).find())return rule.purpose();return "UNKNOWN";}
    static String exclusion(String query,Grounding grounding) {
        if(grounding==null)return null;
        if(grounding.purpose().equals("DECORATIVE")&&!query.matches("(?is).*(头像|标志|logo|广告).*"))return "DECORATIVE_IMAGE";
        String requested=stepNumber(query),actual=stepNumber(grounding.stepText());
        if(requested!=null&&actual!=null&&!requested.equals(actual))return "STEP_NUMBER_MISMATCH";
        String operation=operation(grounding.stepText());var requestedOperations=requestedOperations(query);
        if(operation!=null&&!requestedOperations.isEmpty()&&!requestedOperations.contains(operation)
                &&!(requestedOperations.contains("ASSEMBLY")&&Set.of("CRAFTING","CUTTING","ANVIL","LAYOUT").contains(operation)))return "STEP_ACTION_MISMATCH";
        var wanted=actions(query);String step=Objects.toString(grounding.stepText(),"");int newline=step.indexOf('\n');
        var depicted=actions(newline>=0?step.substring(newline+1):step);if(depicted.isEmpty())depicted=actions(step);
        if(!wanted.isEmpty()&&!depicted.isEmpty()&&Collections.disjoint(wanted,depicted))return "STEP_ACTION_MISMATCH";
        if(!grounding.basis().equals("CONTEXT_INFERRED")&&query.matches("(?is).*(配方|合成表|合成图).*" )&&Set.of("MAP","COMBAT","ENTITY").contains(grounding.purpose()))return "IMAGE_PURPOSE_MISMATCH";
        return null;
    }
    private static Set<String> requestedOperations(String query) {
        String text=Objects.toString(query,"").toLowerCase(Locale.ROOT);var result=new HashSet<String>();
        if(text.matches("(?s).*(切石机|切成|切为|stonecut).*"))result.add("CUTTING");
        if(text.matches("(?s).*(铁砧|修复和命名|anvil).*"))result.add("ANVIL");
        if(text.matches("(?s).*(摆放|摆成|排列|组装图|assembly layout).*"))result.add("LAYOUT");
        if(text.matches("(?s).*(替换|更换|replace).*"))result.add("REPLACE");
        if(text.matches("(?s).*(升级|升阶|扩容|强化|upgrade).*"))result.add("UPGRADE");
        if(text.matches("(?s).*(组装|装配|搭建|拼装|assemble).*" )&&(result.isEmpty()||text.matches("(?s).*(组装|装配)(并|和|与|后|再|以及|升级).*")))result.add("ASSEMBLY");return result;
    }
    private static String operation(String step) {
        String text=Objects.toString(step,"");int newline=text.indexOf('\n');if(newline>=0)text=text.substring(newline+1);
        if(text.matches("(?is).*(铁砧上|放[在入到].{0,6}铁砧|铁砧加工|anvil).*"))return "ANVIL";
        if(text.matches("(?is).*(切为|切成|切石机.{0,20}(切|部件)|stonecut).*"))return "CUTTING";
        if(text.matches("(?is).*(替换|更换|replace).*"))return "REPLACE";
        if(text.matches("(?is).*(升级|升阶|扩容|upgrade).*"))return "UPGRADE";
        if(text.matches("(?is).*(各.{0,3}部件.{0,30}(图|合成|摆放)|部件.{0,12}(摆放|排列)|assembly layout).*"))return "LAYOUT";
        if(text.matches("(?is).*(收集|准备|制作).*"))return "CRAFTING";
        if(text.matches("(?is).*(组装|装配|搭建|拼装|assemble).*"))return "ASSEMBLY";return null;
    }
    private static Set<String> actions(String text) {
        text=Objects.toString(text,"").toLowerCase(Locale.ROOT);var actions=new HashSet<String>();
        if(text.matches("(?s).*(组装|装配|搭建|拼装|assembly|assemble).*"))actions.add("ASSEMBLY");
        if(text.matches("(?s).*(升级|升阶|扩容|强化|upgrade).*"))actions.add("UPGRADE");
        if(text.matches("(?s).*(替换|更换|replace).*"))actions.add("REPLACE");return actions;
    }
    private static String stepNumber(String text) {
        var matcher=Pattern.compile("(?:第\\s*([0-9]{1,2}|[一二三四五六七八九十])\\s*步|(?i:step)\\s*([0-9]{1,2}))").matcher(Objects.toString(text,""));
        if(!matcher.find())return null;String number=matcher.group(1)==null?matcher.group(2):matcher.group(1);
        int chinese="一二三四五六七八九十".indexOf(number);return chinese>=0?Integer.toString(chinese+1):Integer.toString(Integer.parseInt(number));
    }
    public static String label(String purpose){return switch(Objects.toString(purpose,"UNKNOWN")){case "RECIPE"->"配方示意";case "UI"->"界面操作";case "STRUCTURE"->"结构装配";case "MAP"->"地图位置";case "COMBAT"->"战斗示意";case "ENTITY"->"外观参考";case "DECORATIVE"->"装饰图片";default->"资料插图";};}
    static boolean usableOcr(String text){return Pattern.compile("[\\p{IsHan}]{2}|[a-zA-Z]{3}").matcher(Objects.toString(text,"")).find();}
    static String limit(String text,int max){text=Objects.toString(text,"");return text.substring(0,Math.min(text.length(),max));}
}
