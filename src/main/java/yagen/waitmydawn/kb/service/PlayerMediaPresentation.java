package yagen.waitmydawn.kb.service;

import yagen.waitmydawn.kb.model.AnswerArtifacts;
import java.util.*;

/** 纯外观任务按最终可用图片生成展示块，介绍文字与图片共用同一选择结果。 */
final class PlayerMediaPresentation {
    /**
     * 题面要求解释、比较或说明缺失状态时，答案必须经过组织模型；
     * 纯外观展示只适用于“只要图”的请求。
     */
    private static final java.util.regex.Pattern EXPLAIN=java.util.regex.Pattern.compile("说明|解释|为何|为什么|是否存在|不存在|可用状态|没有对应|原因|区别|差异|对比|比较|\\bexplain\\b",java.util.regex.Pattern.CASE_INSENSITIVE);
    static boolean requested(String question){return LocalQuestionPolicy.decide(question).appearance()||Objects.toString(question,"").matches("(?s).*(插图|步骤图|结构图|示意图|截图).*");}
    static boolean explains(String question){return question!=null&&EXPLAIN.matcher(question).find();}
    static boolean pure(String question){var policy=LocalQuestionPolicy.decide(question);return requested(question)&&!policy.externalKnowledge()&&!policy.recipe()&&!explains(question);}
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
            // 直接消费 prepare 阶段的请求→图片映射：正文与图片出自同一次选择，避免“无图”与配图并存。
            String pictureId=result.mediaRequestPictures.get(request.query());
            if(pictureId==null)pictureId=result.artifacts.pictures().stream()
                    .filter(value->Set.of("ENTITY","ITEM").contains(value.role()))
                    .filter(value->value.caption()!=null&&value.caption().startsWith(request.label()+"（"))
                    .map(AnswerArtifacts.Picture::id).findFirst().orElse(null);
            if(pictureId!=null&&used.add(pictureId))text.add("[[image:"+pictureId+"]]");
            else if(pictureId==null)text.add("本次快照没有与“"+request.label()+"”匹配的可用图片。");
        }
        if(text.isEmpty())text.add("本次未取得符合指定对象和状态的可用图片。");
        return String.join("\n\n",new LinkedHashSet<>(text));
    }
}
