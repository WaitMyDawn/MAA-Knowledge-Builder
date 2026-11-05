package yagen.waitmydawn.kb.service;

import java.util.*;
import java.util.regex.Pattern;

/** 已选择但未绑定运行时命名空间的来源，不能出现在当前环境声明中。 */
final class SourceEnvironmentBoundary {
    static String constrain(String answer,String sourceLabel,String namespace,RuntimeEvidencePolicy.Snapshot snapshot){
        if(answer==null||namespace!=null||sourceLabel==null||sourceLabel.isBlank()||snapshot==null)return answer;
        var protectedBlocks=Pattern.compile("(?s)```.*?(?:```|\\z)|~~~.*?(?:~~~|\\z)").matcher(answer);
        var text=new StringBuilder();int end=0;
        while(protectedBlocks.find()){text.append(plain(answer.substring(end,protectedBlocks.start()),sourceLabel,snapshot)).append(protectedBlocks.group());end=protectedBlocks.end();}
        return text.append(plain(answer.substring(end),sourceLabel,snapshot)).toString();
    }
    private static String plain(String answer,String label,RuntimeEvidencePolicy.Snapshot snapshot){
        var headers=Pattern.compile("((?:当前|本次)(?:快照|环境|游戏))\\s*[（(]([^）)\\n]{1,350})[）)]").matcher(answer);
        var result=new StringBuilder();
        while(headers.find()){
            String replacement=headers.group();
            if(KnowledgeSearchText.normalize(headers.group(2)).contains(KnowledgeSearchText.normalize(label)))
                replacement=headers.group(1)+"（Minecraft "+snapshot.minecraftVersion()+" / "+snapshot.loader()+"）";
            headers.appendReplacement(result,java.util.regex.Matcher.quoteReplacement(replacement));
        }
        headers.appendTail(result);
        String token=Pattern.quote(label);
        return result.toString().replaceAll("(?:已安装|安装了)\\s*[`\"']?"+token+"[`\"']?",java.util.regex.Matcher.quoteReplacement("尚未核实“"+label+"”与当前快照的安装对应关系"))
                .replaceAll(token+"\\s*(?:已经|已)安装",java.util.regex.Matcher.quoteReplacement("“"+label+"”尚未与当前快照建立安装对应关系"));
    }
}
