package yagen.waitmydawn.kb.service;

import yagen.waitmydawn.kb.model.AnswerArtifacts;
import java.util.*;
import java.util.regex.Pattern;

/** 对实际展示的图片检查证据绑定；该指标不等价于人工核对图片像素语义。 */
public final class ImageGroundingAssessment {
    public record Check(String imageId,String purpose,String state,String reason) {}
    public record Result(int displayed,int bound,int mismatched,int unbound,int unknownPurpose,List<Check> checks) {}
    private ImageGroundingAssessment() {}
    public static Result assess(String question,String answer,AnswerArtifacts artifacts) {
        if(artifacts==null)return new Result(0,0,0,0,0,List.of());
        var used=new LinkedHashSet<String>();var matcher=Pattern.compile("!\\[[^\\]\\r\\n]*]\\(kb-image:([a-zA-Z0-9]+)\\)").matcher(AnswerMedia.referenceText(answer));while(matcher.find())used.add(matcher.group(1));
        var sourceIds=new HashSet<String>();artifacts.sources().forEach(s->sourceIds.add(s.id()));
        var checks=new ArrayList<Check>();int bound=0,mismatch=0,unbound=0,unknown=0;
        for(var picture:artifacts.pictures()) {
            if(!used.contains(picture.id())||"RECIPE".equals(picture.role()))continue;
            var g=picture.grounding();if(g==null){unbound++;unknown++;checks.add(new Check(picture.id(),"UNKNOWN","UNBOUND","MISSING_GROUNDING"));continue;}
            if(g.purpose().equals("UNKNOWN"))unknown++;
            var evidence=new GuideImageSemantics.Grounding(g.purpose(),g.basis(),g.blockId(),null,g.stepText(),g.relation(),g.analysisStatus());
            String exclusion=GuideImageSemantics.exclusion(question,evidence);
            if(exclusion!=null){mismatch++;checks.add(new Check(picture.id(),g.purpose(),"MISMATCH",exclusion));}
            else if(g.blockId()==null||g.stepText()==null||g.stepText().isBlank()||!sourceIds.contains(picture.sourceId())){unbound++;checks.add(new Check(picture.id(),g.purpose(),"UNBOUND","MISSING_STEP_OR_SOURCE"));}
            else{bound++;checks.add(new Check(picture.id(),g.purpose(),"BOUND","PROVIDED_SOURCE_AND_STEP"));}
        }
        return new Result(checks.size(),bound,mismatch,unbound,unknown,List.copyOf(checks));
    }
}
