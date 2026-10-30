package yagen.waitmydawn.kb.service;

import yagen.waitmydawn.kb.model.AnswerArtifacts;
import java.util.*;
import java.util.regex.Pattern;

/** 为复合操作预留插图名额；覆盖表示图注和步骤关联命中，不证明图片像素语义。 */
public final class GuideIllustrationCoverage {
    private record Facet(String name,String pattern){}
    private static final List<Facet> FACETS=List.of(new Facet("组装","组装|装配|搭建|拼装|assemble|assembly"),new Facet("升级","升级|强化|升阶|扩容|upgrade"),new Facet("更换","替换|更换|replace"));
    public record Audit(List<String> requested,List<String> covered,List<String> missing){}
    private GuideIllustrationCoverage(){}
    private static List<Facet> facets(String question){return FACETS.stream().filter(f->matches(question,f.pattern())).toList();}
    private static boolean matches(String text,String regex){return Pattern.compile(regex,Pattern.CASE_INSENSITIVE).matcher(Objects.toString(text,"")).find();}
    private static boolean supports(AnswerArtifacts.Picture image,Facet facet) {
        if(image.grounding()==null||image.grounding().blockId()==null)return false;
        String step=Objects.toString(image.grounding().stepText(),"");int newline=step.indexOf('\n');if(newline>=0)step=step.substring(newline+1);
        String body=step;
        if(FACETS.stream().anyMatch(item->matches(body,item.pattern())))return matches(body,facet.pattern());
        return FACETS.stream().filter(item->matches(image.caption(),item.pattern())).count()==1&&matches(image.caption(),facet.pattern());
    }
    public static List<AnswerArtifacts.Picture> select(String question,List<AnswerArtifacts.Picture> ranked,int limit) {
        var selected=new ArrayList<AnswerArtifacts.Picture>();var assets=new HashSet<String>();var counts=new HashMap<String,Integer>();
        var facets=facets(question);
        if(facets.size()>1)for(var facet:facets) {
            if(selected.stream().anyMatch(image->supports(image,facet)))continue;
            ranked.stream().filter(image->!assets.contains(image.assetId())&&supports(image,facet)).findFirst().ifPresent(image->{if(selected.size()<limit){selected.add(image);assets.add(image.assetId());counts.merge(image.sourceId(),1,Integer::sum);}});
        }
        for(var image:ranked)if(selected.size()<limit&&!assets.contains(image.assetId())&&counts.getOrDefault(image.sourceId(),0)<2){selected.add(image);assets.add(image.assetId());counts.merge(image.sourceId(),1,Integer::sum);}
        return List.copyOf(selected);
    }
    public static Audit audit(String question,String answer,AnswerArtifacts artifacts) {
        var displayed=new HashSet<String>();var refs=Pattern.compile("!\\[[^\\]\\r\\n]*]\\(kb-image:([a-zA-Z0-9]+)\\)").matcher(AnswerMedia.referenceText(answer));while(refs.find())displayed.add(refs.group(1));
        var requested=facets(question);var covered=new ArrayList<String>();var missing=new ArrayList<String>();
        for(var facet:requested) {
            boolean found=artifacts!=null&&artifacts.pictures().stream().filter(image->displayed.contains(image.id())&&artifacts.sources().stream().anyMatch(source->source.id().equals(image.sourceId()))).anyMatch(image->supports(image,facet));
            (found?covered:missing).add(facet.name());
        }
        return new Audit(requested.stream().map(Facet::name).toList(),List.copyOf(covered),List.copyOf(missing));
    }
    static Audit finish(QaPipeline.QaResult result,String question) {
        var audit=audit(question,result.answer,result.artifacts);
        if(audit.requested().size()>1&&!audit.missing().isEmpty())result.answer+="\n\n配图说明：本轮未选到“"+String.join("、",audit.missing())+"”对应的插图；其他步骤的图片不能替代这些步骤，也不能据此判断原资料没有相关内容。";
        result.answer=ModpackSources.format(result.answer,result.artifacts);
        return audit;
    }
}
