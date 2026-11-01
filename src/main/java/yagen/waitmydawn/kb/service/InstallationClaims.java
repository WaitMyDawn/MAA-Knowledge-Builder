package yagen.waitmydawn.kb.service;

import java.util.*;
import java.util.regex.*;

/**
 * 最终答案里的安装结论必须与当前快照成员一致。
 *
 * <p>网页来源可以证明某个模组有资料，但不能证明它已安装；快照成员清单才是安装事实。
 * 这里只做确定性检查与最小改写，不猜测模型意图：显式写了“已安装/未安装 + 命名空间”的
 * 句子才参与判定，无法识别的表述保持原样。
 */
public final class InstallationClaims {
    /** 一条与快照冲突的安装结论。 */
    public record Conflict(String token, String claim, String truth) {}

    private static final Set<String> STOP=new HashSet<>(List.of(
            "java","windows","macos","linux","jar","json","png","html","http","https","url","api","mod","mods",
            "mcmod","modrinth","curseforge","forge","neoforge","fabric","quilt","minecraft","deepseek","onnx",
            "sql","tts","ocr","gpu","cpu","dlc","net","org","com","version","registry","spawn","loot"));
    private static final Pattern CLAIM=Pattern.compile(
            "(已安装|未安装|没有安装|尚未安装|没装|未装|安装了|安装有)([\\s`\"']*)([a-z][a-z0-9_]{2,})(?![a-z0-9_:/])",
            Pattern.CASE_INSENSITIVE);

    private InstallationClaims(){}

    /** 返回与快照成员事实冲突的安装结论；installed 为命名空间集合（大小写不敏感）。 */
    public static List<Conflict> conflicts(String answer,Collection<String> installed){
        if(answer==null||answer.isBlank()||installed==null||installed.isEmpty())return List.of();
        var known=new HashSet<String>();for(String id:installed)known.add(id.toLowerCase(Locale.ROOT));
        var seen=new LinkedHashSet<String>();var result=new ArrayList<Conflict>();
        var matcher=CLAIM.matcher(answer);
        while(matcher.find()){
            String verb=matcher.group(1),token=matcher.group(3).toLowerCase(Locale.ROOT);
            if(STOP.contains(token))continue;
            boolean negated=verb.startsWith("未")||verb.startsWith("没")||verb.startsWith("没有")||verb.startsWith("尚未");
            boolean present=known.contains(token);
            if(negated!=present)continue;
            if(seen.add(verb+"|"+token))result.add(new Conflict(token,verb+" "+token,present?"已安装":"未安装"));
        }
        return result;
    }

    /** 按快照事实改写冲突句，并追加更正说明；只改动词与命名空间，不改其余内容。 */
    public static String repair(String answer,List<Conflict> conflicts){
        if(answer==null||conflicts.isEmpty())return answer;
        String fixed=answer;
        for(var conflict:conflicts){
            var pattern=Pattern.compile("(已安装|未安装|没有安装|尚未安装|没装|未装|安装了|安装有)([\\s`\"']*)"+
                    Pattern.quote(conflict.token())+"(?![a-z0-9_:/])",Pattern.CASE_INSENSITIVE);
            fixed=pattern.matcher(fixed).replaceAll(conflict.truth()+"$2"+Matcher.quoteReplacement(conflict.token()));
        }
        var notes=new LinkedHashSet<String>();
        for(var conflict:conflicts)notes.add(conflict.truth()+" `"+conflict.token()+"`");
        return fixed+"\n\n> 安装事实按当前快照校正："+String.join("、",notes)+"。网页资料只说明该模组有外部文档，不代表当前环境已安装。";
    }
}
