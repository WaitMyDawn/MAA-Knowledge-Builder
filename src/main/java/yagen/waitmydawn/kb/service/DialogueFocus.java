package yagen.waitmydawn.kb.service;

import yagen.waitmydawn.kb.model.AnswerArtifacts;
import java.util.*;

/** 对话焦点只继承最近任务对象；状态补丁按轴覆盖，不拼接互相矛盾的选择条件。 */
final class DialogueFocus {
    private DialogueFocus() {}
    static boolean followup(String question){return question.matches("(?s).*(它们|它的|它吗|介绍下它|介绍它|刚才|其他条件不变|只看|改成|再展示|组装好后|所需材料).*");}
    static String expand(String question,AnswerArtifacts.TurnState state,String profile,String scope) {
        if(state==null||!Objects.equals(profile,state.profile())||scope!=null&&!Objects.equals(scope,state.scopeId())||!followup(question))return question;
        if(state.objects().isEmpty())return question;
        var targets=state.objects();
        var selected=targets.stream().filter(object->question.contains(object.label())||question.contains(object.registry())).toList();
        if(!selected.isEmpty())targets=selected;
        var parts=new ArrayList<String>();
        for(var object:targets){var selectors=new LinkedHashMap<>(object.selectors());selectors.putAll(MediaRequestPlanner.selectors(question));parts.add(object.registry()+" "+String.join(" ",selectors.values()));}
        return String.join(" 与 ",parts)+" "+question;
    }
    static AnswerArtifacts.TurnState capture(String profile,String scope,MediaRequestPlanner.Plan plan,List<yagen.waitmydawn.kb.agent.EntityAgent.ResolvedEntity> objects) {
        var focus=new LinkedHashMap<String,AnswerArtifacts.FocusedObject>();
        if(plan!=null)for(var request:plan.requests())focus.put(request.subject().registry()+"|"+request.query(),new AnswerArtifacts.FocusedObject(request.subject().registry(),request.kind(),request.subject().names().stream().filter(name->!name.contains(":")).findFirst().orElse(request.subject().registry()),MediaRequestPlanner.selectors(request.query())));
        if(focus.isEmpty())for(var object:objects)focus.putIfAbsent(object.registry(),new AnswerArtifacts.FocusedObject(object.registry(),"GENERAL",object.registry(),Map.of()));
        return new AnswerArtifacts.TurnState(profile,scope,List.copyOf(focus.values()));
    }
}
