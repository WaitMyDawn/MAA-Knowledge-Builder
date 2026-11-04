package yagen.waitmydawn.kb.dto;

import java.util.*;

/** 分段值是实际请求文本的本地估算；不拆分或替代供应商返回的真实 Token 用量。 */
public record PromptCost(String method,long requests,Map<String,Long> characters,Map<String,Long> estimatedTokens,long nonTextParts) {
    public PromptCost {characters=Map.copyOf(characters);estimatedTokens=Map.copyOf(estimatedTokens);}
    public static PromptCost empty(){return new PromptCost("mixed-codepoint-v1",0,Map.of(),Map.of(),0);}
    public PromptCost plus(PromptCost other){
        var chars=new TreeMap<>(characters);other.characters.forEach((key,value)->chars.merge(key,value,Long::sum));
        var tokens=new TreeMap<>(estimatedTokens);other.estimatedTokens.forEach((key,value)->tokens.merge(key,value,Long::sum));
        return new PromptCost(method,requests+other.requests,chars,tokens,nonTextParts+other.nonTextParts);
    }
    public long estimatedTotal(){return estimatedTokens.values().stream().mapToLong(Long::longValue).sum();}
}
