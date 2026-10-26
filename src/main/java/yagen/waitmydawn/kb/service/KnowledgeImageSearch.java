package yagen.waitmydawn.kb.service;

import yagen.waitmydawn.kb.model.*;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;

/** 图片文字独立评分，但图片必须能绑定本轮实际提供的章节或段落来源。 */
public final class KnowledgeImageSearch {
    static final double MIN_CONTEXT_RELEVANCE=0.3;
    static final double MIN_IMAGE_TEXT_RELEVANCE=0.3;
    public record Decision(String occurrenceId,String sourceId,String relation,double captionScore,double ocrScore,
                           String state,String reason) {}
    public record Audit(int inspected,int pending,int selected,String status,boolean truncated,List<Decision> candidates,KnowledgeImageHydrator.Audit hydration) {
        Audit(int inspected,int pending,int selected,String status,boolean truncated,List<Decision> candidates) {this(inspected,pending,selected,status,truncated,candidates,null);}
    }
    record Result(List<AnswerArtifacts.Picture> pictures,List<AnswerArtifacts.Picture> repairs,Audit audit) {}
    private record Candidate(String occurrence,AnswerArtifacts.Picture picture,double score,double caption,double ocr,String relation,String state) {}
    private final DatabaseBuilder db;
    private final ImageAssetStore assets;
    KnowledgeImageSearch(DatabaseBuilder db,Path dataDir) {this.db=db;assets=dataDir==null?null:new ImageAssetStore(dataDir);}
    static boolean generatedCaption(String caption) {return caption.matches("(?s).*-第[0-9]+张图片$");}

    Result search(String query,AnswerArtifacts artifacts,KnowledgeRetrievalService.Diagnostics diagnostics) {
        var candidates=new LinkedHashMap<String,Candidate>();var decisions=new LinkedHashMap<String,Decision>();
        var availability=new HashMap<String,Boolean>();
        var lineages=new HashMap<String,Set<String>>();int inspected=0;boolean truncated=false;
        var context=diagnostics==null?null:diagnostics.context();
        try(var c=db.getConnection()) {
            if(context!=null) try(var p=c.prepareStatement("SELECT lineage FROM rag_content_block WHERE id=?")) {
                for(var entry:context.blockSources().entrySet()) {
                    p.setString(1,entry.getKey());try(var r=p.executeQuery()) {if(r.next()) lineages.computeIfAbsent(entry.getValue(),k -> new HashSet<>()).add(r.getString(1));}
                }
            }
            String sql="""
                    SELECT o.*,i.relative_path,LEFT(i.ocr_text,1800) AS ocr_text,LEFT(i.description,1000) AS description,
                           c.id AS chunk_id,c.heading,b.lineage,r.url AS web_url
                    FROM rag_image_occurrence o JOIN rag_image_asset i ON i.id=o.image_id
                    JOIN rag_chunk_image l ON l.occurrence_id=o.id JOIN rag_content_chunk c ON c.id=l.chunk_id
                    LEFT JOIN rag_chunk_block cb ON cb.chunk_id=c.id LEFT JOIN rag_content_block b ON b.id=cb.block_id
                    LEFT JOIN rag_web_image_reference r ON r.id=o.id
                    WHERE o.document_id=? ORDER BY o.id,c.id LIMIT 2001
                    """;
            for(String doc:artifacts.sources().stream().map(AnswerArtifacts.Source::documentId).distinct().toList()) {
                try(var p=c.prepareStatement(sql)) {
                    p.setString(1,doc);try(var r=p.executeQuery()) {
                        int rows=0;while(r.next()) {
                            if(++rows>2000) {truncated=true;break;} inspected++;
                            String occurrence=r.getString("id"),chunk=r.getString("chunk_id"),heading=r.getString("heading"),lineage=r.getString("lineage");
                            Integer page=(Integer)r.getObject("page_number");
                            String direct=context==null?"s"+BuildRequest.fingerprint(chunk).substring(0,20):context.chunkSources().get(chunk);
                            var source=artifacts.sources().stream().filter(s -> s.documentId().equals(doc) && Objects.equals(s.pageNumber(),page)
                                    && (s.id().equals(direct) || lineage!=null && lineages.getOrDefault(s.id(),Set.of()).contains(lineage)
                                    || lineage==null && Objects.equals(s.heading(),heading)))
                                    .sorted(Comparator.comparingInt(s -> s.id().equals(direct)?0:1)).findFirst().orElse(null);
                            if(source==null) {decisions.putIfAbsent(occurrence,new Decision(occurrence,null,"NONE",0,0,"EXCLUDED","OUTSIDE_PROVIDED_SECTION"));continue;}
                            String role=Objects.toString(r.getString("role"),"ILLUSTRATION");
                            if(role.equals("PAGE_PREVIEW") && !query.matches("(?s).*(整页|预览|布局|页图).*")) {
                                decisions.putIfAbsent(occurrence,new Decision(occurrence,source.id(),"NONE",0,0,"EXCLUDED","PAGE_PREVIEW"));continue;
                            }
                            String caption=Objects.toString(r.getString("caption"),""),ocr=Objects.toString(r.getString("ocr_text"),""),description=Objects.toString(r.getString("description"),"");
                            boolean generated=generatedCaption(caption);
                            double captionScore=generated?0:KnowledgeSearchText.overlap(query,caption),ocrScore=KnowledgeSearchText.overlap(query,ocr);
                            double contextScore=KnowledgeSearchText.overlap(query,source.heading()+"\n"+source.evidence());
                            String topic=Objects.toString(source.heading(),"");
                            if(topic.isBlank() || topic.equals("正文")) topic=source.title();
                            boolean topicMatch=KnowledgeSearchText.overlap(query,topic)>0;
                            if(Math.max(captionScore,ocrScore)<MIN_IMAGE_TEXT_RELEVANCE && (contextScore<MIN_CONTEXT_RELEVANCE || !topicMatch)) {
                                decisions.putIfAbsent(occurrence,new Decision(occurrence,source.id(),"NONE",captionScore,ocrScore,"EXCLUDED","WEAK_TOPIC_MATCH"));continue;
                            }
                            String relation=source.id().equals(direct)?"PARAGRAPH":"SECTION";
                            double score=Math.max(captionScore,ocrScore)+0.35*KnowledgeSearchText.overlap(query,description);
                            if(score==0 && (generated || caption.isBlank() || caption.matches("(?s).*(示意|插图|步骤).*")) && relation.equals("PARAGRAPH")) score=0.15*contextScore;
                            if(score<=0) {decisions.putIfAbsent(occurrence,new Decision(occurrence,source.id(),relation,captionScore,ocrScore,"EXCLUDED","NO_IMAGE_TEXT_MATCH"));continue;}
                            String id="i"+BuildRequest.fingerprint(occurrence).substring(0,20),asset=r.getString("image_id"),path=r.getString("relative_path");
                            String state=assets==null?"CACHE_UNCHECKED":availability.computeIfAbsent(asset+"/"+path,k -> assets.available(asset,path))?"AVAILABLE":"CACHE_MISSING";
                            String original=r.getString("web_url");if(original==null && Objects.toString(r.getString("original_location"),"").matches("(?i)^https?://.*")) original=r.getString("original_location");
                            var picture=new AnswerArtifacts.Picture(id,asset,path,caption,source.id(),role,List.of(chunk),source.evidence()
                                    +(ocr.isBlank()?"":"\nOCR识别："+ocr)+(description.isBlank()?"":"\n模型图像描述（推断）："+description),original,source.location());
                            var candidate=new Candidate(occurrence,picture,score+(relation.equals("PARAGRAPH")?0.1:0),captionScore,ocrScore,relation,state);
                            if(!candidates.containsKey(occurrence) || candidates.get(occurrence).score()<candidate.score()) candidates.put(occurrence,candidate);
                        }
                    }
                }
            }
            int pending=0;
            for(var source:artifacts.sources()) try(var p=c.prepareStatement("""
                    SELECT r.*,b.lineage FROM rag_web_image_reference r
                    LEFT JOIN rag_content_block b ON b.document_id=r.document_id AND b.ordinal=r.block_ordinal
                    WHERE r.document_id=? AND NOT EXISTS(SELECT 1 FROM rag_image_occurrence o WHERE o.id=r.id) ORDER BY r.id LIMIT 2001
                    """)) {
                p.setString(1,source.documentId());try(var r=p.executeQuery()) {
                    int rows=0;while(r.next()) {
                        if(++rows>2000) {truncated=true;break;}
                        String lineage=r.getString("lineage");
                        if((lineage!=null && lineages.getOrDefault(source.id(),Set.of()).contains(lineage) || lineage==null && Objects.equals(source.heading(),r.getString("heading")))
                                && Math.max(KnowledgeSearchText.overlap(query,source.heading()+" "+source.evidence()),
                                generatedCaption(Objects.toString(r.getString("caption"),""))?0:KnowledgeSearchText.overlap(query,Objects.toString(r.getString("caption"),"")))>=MIN_CONTEXT_RELEVANCE) {
                            String id=r.getString("id");
                            if(!decisions.containsKey(id)) {pending++;decisions.put(id,new Decision(id,source.id(),"SECTION",0,0,"NOT_DOWNLOADED",Objects.toString(r.getString("status"),"DEFERRED")));}
                        }
                    }
                }
            }
            var pictures=new ArrayList<AnswerArtifacts.Picture>();var seen=new HashSet<String>();var perSource=new HashMap<String,Integer>();
            for(var candidate:candidates.values().stream().sorted(Comparator.comparingDouble(Candidate::score).reversed().thenComparing(Candidate::occurrence)).toList()) {
                String reason="SELECTED";var picture=candidate.picture();
                if(candidate.state().equals("CACHE_MISSING")) reason="CACHE_MISSING";
                else if(seen.contains(picture.assetId())) reason="DUPLICATE_ASSET";
                else if(pictures.size()>=4 || perSource.getOrDefault(picture.sourceId(),0)>=2) reason="DISPLAY_BUDGET";
                else {pictures.add(picture);seen.add(picture.assetId());perSource.merge(picture.sourceId(),1,Integer::sum);}
                decisions.put(candidate.occurrence(),new Decision(candidate.occurrence(),picture.sourceId(),candidate.relation(),candidate.caption(),candidate.ocr(),candidate.state(),reason));
            }
            String status=!pictures.isEmpty()?"SELECTED":pending>0?"RELEVANT_NOT_DOWNLOADED":candidates.values().stream().anyMatch(i -> i.state().equals("CACHE_MISSING"))?"CACHE_MISSING":"NO_RELEVANT_IMAGE_IN_CONTEXT";
            var repairs=candidates.values().stream().filter(i -> i.state().equals("CACHE_MISSING")).sorted(Comparator.comparingDouble(Candidate::score).reversed())
                    .map(Candidate::picture).limit(4).toList();
            return new Result(List.copyOf(pictures),repairs,new Audit(inspected,pending,pictures.size(),status,truncated || decisions.size()>200,decisions.values().stream().limit(200).toList()));
        } catch(SQLException e) {throw new IllegalStateException("章节插图检索失败",e);}
    }
}
