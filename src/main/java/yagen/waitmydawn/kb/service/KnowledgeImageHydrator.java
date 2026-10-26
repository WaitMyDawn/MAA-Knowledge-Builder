package yagen.waitmydawn.kb.service;

import yagen.waitmydawn.kb.model.*;
import java.nio.file.Path;
import java.util.*;

/** 为命中段落及同分支的相关图注补取构建记录的插图，不改写已发布知识。 */
public final class KnowledgeImageHydrator {
    public record Audit(int budget,int attempts,int resolved,int failures,String status) {}
    private Audit audit=new Audit(0,0,0,0,"NOT_RUN");
    Audit audit() {return audit;}
    boolean available(AnswerArtifacts.Picture picture) {return assets.available(picture.assetId(),picture.relativePath());}
    private final DatabaseBuilder db;
    private final ImageAssetStore assets;
    private final WikiScraperService scraper;
    KnowledgeImageHydrator(DatabaseBuilder db,Path dataDir,WikiScraperService scraper) {
        this.db=db;assets=new ImageAssetStore(dataDir);this.scraper=scraper; scraper.setResourceDatabase(db);
    }

    List<AnswerArtifacts.Picture> hydrate(String question,List<VectorStore.SearchResult> hits,
                                          List<AnswerArtifacts.Picture> present,Map<String,String> sourceIds) {
        int budget=Math.max(0,Math.min(4,Integer.getInteger("maa.qa.imageDownloads",2)));
        audit=new Audit(budget,0,0,0,budget==0?"DISABLED":"NO_RECORDED_CANDIDATE");
        if(budget==0) return List.of();
        var existing=new HashMap<String,AnswerArtifacts.Picture>();
        present.forEach(p -> existing.put(p.id(),p));
        var candidates=new LinkedHashMap<String,Candidate>();
        String sql="SELECT r.*,CASE WHEN NOT EXISTS(SELECT 1 FROM rag_chunk_block l WHERE l.chunk_id=c.id) OR EXISTS(SELECT 1 FROM rag_chunk_block l JOIN rag_content_block b ON b.id=l.block_id WHERE l.chunk_id=c.id AND ABS(r.block_ordinal-b.ordinal)<=1) THEN 1 ELSE 0 END AS adjacent FROM rag_web_image_reference r JOIN rag_content_chunk c ON c.document_id=r.document_id WHERE c.id=? AND "
                +"((NOT EXISTS(SELECT 1 FROM rag_chunk_block l WHERE l.chunk_id=c.id) AND ABS(r.block_ordinal-FLOOR(c.ordinal/10000.0))<=1) "
                +"OR EXISTS(SELECT 1 FROM rag_chunk_block l JOIN rag_content_block b ON b.id=l.block_id JOIN rag_content_block image_block ON image_block.document_id=b.document_id AND image_block.ordinal=r.block_ordinal "
                +"WHERE l.chunk_id=c.id AND b.document_id=r.document_id AND image_block.lineage=b.lineage))";
        try(var c=db.getConnection();var p=c.prepareStatement(sql)) {
            for(var hit:hits) {
                p.setString(1,hit.chunkId());try(var rows=p.executeQuery()) {
                    while(rows.next()) {
                        String id="i"+BuildRequest.fingerprint(rows.getString("id")).substring(0,20);
                        var current=existing.get(id);
                        if(current!=null && assets.available(current.assetId(),current.relativePath())) continue;
                        String caption=Objects.toString(rows.getString("caption"),"");
                        boolean generated=KnowledgeImageSearch.generatedCaption(caption);
                        double captionScore=generated?0:KnowledgeSearchText.overlap(question,caption);
                        String topic=Objects.toString(rows.getString("heading"),"");
                        if(topic.isBlank() || topic.equals("正文")) topic=generated?caption.replaceFirst("-第[0-9]+张图片$",""):Objects.toString(hit.modName(),"");
                        if(captionScore<KnowledgeImageSearch.MIN_IMAGE_TEXT_RELEVANCE && (KnowledgeSearchText.overlap(question,hit.chunkText()+" "+rows.getString("heading"))<KnowledgeImageSearch.MIN_CONTEXT_RELEVANCE || KnowledgeSearchText.overlap(question,topic)==0)) continue;
                        if(captionScore==0 && (rows.getInt("adjacent")==0 || !generated && !caption.isBlank() && !caption.matches("(?s).*(示意|插图|步骤).*"))) continue;
                        double score=hit.score()+KnowledgeSearchText.overlap(question,caption+" "+hit.chunkText());
                        var candidate=new Candidate(id,rows.getString("url"),caption,hit,score,current);
                        if(!candidates.containsKey(id) || candidates.get(id).score()<score) candidates.put(id,candidate);
                    }
                }
            }
        } catch(java.sql.SQLException e) {throw new IllegalStateException("待缓存插图查询失败",e);}
        var result=new ArrayList<AnswerArtifacts.Picture>();var seen=new HashSet<String>();present.forEach(p -> seen.add(p.assetId()));
        var sourceCounts=new HashMap<String,Integer>();present.stream().filter(this::available).forEach(p -> sourceCounts.merge(p.sourceId(),1,Integer::sum));
        int attempts=0,resolved=0,failures=0;
        for(var candidate:candidates.values().stream().sorted(Comparator.<Candidate>comparingInt(c -> c.current()==null?1:0)
                .thenComparing(Comparator.comparingDouble(Candidate::score).reversed())).toList()) {
            if(attempts>=budget) break;
            if(candidate.current()==null && result.size()+present.size()>=4) continue;
            String sourceId=sourceIds.get(candidate.hit().chunkId());
            if(sourceId==null || sourceCounts.getOrDefault(sourceId,0)>=2) continue;
            attempts++;
            if(Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Image retrieval cancelled");
            try {
                var response=scraper.fetchBuildResource(candidate.url(),candidate.hit().sourcePath(),true);
                if(response.status()!=200 || response.bytes()==null) {failures++;continue;}
                if(candidate.current()!=null) {
                    assets.restore(candidate.current().assetId(),response.bytes());
                    resolved++;sourceCounts.merge(sourceId,1,Integer::sum);
                    continue;
                }
                var asset=assets.put(response.bytes());resolved++; if(!seen.add(asset.id())) continue;
                sourceCounts.merge(sourceId,1,Integer::sum);
                result.add(new AnswerArtifacts.Picture(candidate.id(),asset.id(),asset.relativePath(),candidate.caption(),
                        sourceIds.get(candidate.hit().chunkId()),"ILLUSTRATION",List.of(candidate.hit().chunkId()),candidate.hit().chunkText(),
                        candidate.url(),candidate.hit().sourcePath()));
            } catch(java.util.concurrent.CancellationException e) {throw e;}
            catch(java.io.IOException | IllegalArgumentException ignored) {failures++;}
        }
        audit=new Audit(budget,attempts,resolved,failures,attempts==0?"NO_FETCH_NEEDED":failures>0?"FETCH_INCOMPLETE":attempts>=budget?"BUDGET_REACHED":"RESOLVED");
        return result;
    }
    private record Candidate(String id,String url,String caption,VectorStore.SearchResult hit,double score,AnswerArtifacts.Picture current) {}
}
