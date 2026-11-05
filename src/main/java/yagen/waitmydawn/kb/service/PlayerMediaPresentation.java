package yagen.waitmydawn.kb.service;

import yagen.waitmydawn.kb.model.AnswerArtifacts;
import java.util.*;

/** 纯外观任务按最终可用图片生成展示块，介绍文字与图片共用同一选择结果。 */
final class PlayerMediaPresentation {
    static boolean requested(String question){return LocalQuestionPolicy.decide(question).appearance()||Objects.toString(question,"").matches("(?s).*(插图|步骤图|结构图|示意图|截图).*");}
    static boolean pure(String question){var policy=LocalQuestionPolicy.decide(question);return requested(question)&&!policy.externalKnowledge()&&!policy.recipe();}
    static void captions(QaPipeline.QaResult result){
        var pictures=new ArrayList<AnswerArtifacts.Picture>();
        var states=new HashSet<AnswerArtifacts.RuntimeIdentity>();
        for(var picture:result.artifacts.pictures()){
            if(picture.role().equals("ENTITY")&&picture.runtimeIdentity()!=null&&!states.add(picture.runtimeIdentity()))continue;
            String caption=picture.caption();
            if(picture.role().equals("ENTITY")&&picture.runtimeIdentity()!=null){
                String id=picture.runtimeIdentity().registry();
                var subject=result.mediaPlan==null?null:result.mediaPlan.requests().stream().map(MediaRequestPlanner.Request::subject).filter(value->value.registry().equals(id)).findFirst().orElse(null);
                caption=subject==null?id:subject.names().stream().filter(name->!name.contains(":" )).findFirst().orElse(id);
                var traits=picture.runtimeIdentity().attributes();var details=new ArrayList<String>();
                String age=traits.get("age");if("baby".equals(age))details.add("幼年");if("adult".equals(age))details.add("成年");
                String sex=traits.get("sex");if("female".equals(sex))details.add("雌性");if("male".equals(sex))details.add("雄性");
                String growth=traits.get("growth_stage");if(growth!=null&&growth.matches("[0-9]+"))details.add(growth+"阶");
                if(!details.isEmpty())caption+="（"+String.join("、",details)+"）";
                var row=result.runtimeMediaStates.get(picture.id());if(row!=null&&Set.of("POSSIBLY_CLIPPED","UNSTABLE_FRAMING").contains(row.path("framing").asText()))caption+="（取景待核对）";
            }
            pictures.add(new AnswerArtifacts.Picture(picture.id(),picture.assetId(),picture.relativePath(),caption,picture.sourceId(),picture.role(),picture.chunkIds(),picture.context(),picture.originalUrl(),picture.referer(),picture.grounding(),picture.runtimeIdentity()));
        }
        var old=result.artifacts;result.artifacts=new AnswerArtifacts(old.scopeId(),old.scopeLabel(),old.sources(),pictures,old.citedSourceIds(),old.turnState());
    }
    static String render(QaPipeline.QaResult result){
        var text=new ArrayList<String>();var used=new HashSet<String>();
        if(result.mediaPlan!=null)for(var request:result.mediaPlan.requests()){
            var picture=result.artifacts.pictures().stream().filter(value->{
                if(!Set.of("ENTITY","ITEM").contains(value.role()))return false;
                if(value.runtimeIdentity()!=null&&!request.subject().registry().equals(value.runtimeIdentity().registry()))return false;
                if(value.runtimeIdentity()==null&&!value.context().contains(request.subject().registry()))return false;
                var state=result.runtimeMediaStates.get(value.id());try{return state==null||EntityAppearanceState.matches(state,request.query());}catch(java.io.IOException invalid){return false;}
            }).findFirst();
            if(picture.isPresent()){
                var value=picture.get();if(used.add(value.id()))text.add("[[image:"+value.id()+"]]");
            }else text.add("本次快照没有与“"+request.label()+"”匹配的可用图片。");
        }
        if(text.isEmpty())text.add("本次未取得符合指定对象和状态的可用图片。");
        return String.join("\n\n",new LinkedHashSet<>(text));
    }
}
