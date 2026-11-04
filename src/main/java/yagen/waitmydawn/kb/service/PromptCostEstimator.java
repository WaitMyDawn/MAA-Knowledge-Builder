package yagen.waitmydawn.kb.service;

import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import yagen.waitmydawn.kb.dto.PromptCost;
import java.util.*;
import java.util.regex.Pattern;

/** 按请求中的消息类型和显式分区计数；只保存计数，不保存提示词或工具正文。 */
public final class PromptCostEstimator {
    private static final Pattern SECTION=Pattern.compile("(?m)^(\\[Recent conversation[^\\n]*]|\\[Selected environment[^\\n]*]|\\[Imported evidence[^\\n]*]|\\[User Question]|\\[MKB_EVIDENCE]|\\[MKB_HISTORY]|\\[MKB_END_HISTORY]|=== \\[[^\\n]*] ===)");
    private PromptCostEstimator(){}
    public static PromptCost measure(ChatRequest request){
        var counts=new Counts();
        for(var message:request.messages()){
            if(message instanceof SystemMessage system)counts.sectioned(system.text(),"system");
            else if(message instanceof ToolExecutionResultMessage result)counts.add("tool_results",result.text());
            else if(message instanceof AiMessage ai){counts.add("history",ai.text());if(ai.toolExecutionRequests()!=null)for(var call:ai.toolExecutionRequests())counts.add("tool_calls",call.name()+call.arguments());}
            else if(message instanceof UserMessage user)for(var part:user.contents()){
                if(part instanceof TextContent text)counts.sectioned(text.text(),text.text().startsWith("Evidence is incomplete:")?"other":"question");
                else counts.nonText++;
            }
        }
        if(request.toolSpecifications()!=null)for(var specification:request.toolSpecifications())counts.add("tool_schema",specification.toString());
        return new PromptCost("mixed-codepoint-v1",1,counts.characters,counts.tokens,counts.nonText);
    }
    private static final class Counts {
        final Map<String,Long> characters=new TreeMap<>(),tokens=new TreeMap<>();long nonText;
        void sectioned(String text,String defaultKind){
            var matcher=SECTION.matcher(text);int offset=0;String kind=defaultKind;
            while(matcher.find()){
                add(kind,text.substring(offset,matcher.start()));String marker=matcher.group();
                kind=marker.startsWith("[Recent conversation")||marker.equals("[MKB_HISTORY]")?"history":marker.equals("[User Question]")?"question":"evidence";
                offset=matcher.start();
            }
            add(kind,text.substring(offset));
        }
        void add(String kind,String text){
            if(text==null||text.isEmpty())return;
            long ascii=0,other=0;for(var points=text.codePoints().iterator();points.hasNext();)if(points.nextInt()<128)ascii++;else other++;
            characters.merge(kind,(long)text.length(),Long::sum);
            tokens.merge(kind,other+(ascii+3)/4,Long::sum);
        }
    }
}
