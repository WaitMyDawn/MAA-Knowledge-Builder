package yagen.waitmydawn.kb.service;

import java.util.*;
import yagen.waitmydawn.kb.model.KnowledgeContent.ImageAsset;

/** 正文图片的元数据与关联按小批事务写入；同一段落的锚点只解析一次。 */
final class WebImageBindings {
    record Ready(WebImageCompletion.Image image,ImageAsset asset,String failure){}
    private final BuildSourceProcessor.Context context;
    private final Map<String,GuideImageIndex.Anchor> anchors=new HashMap<>();
    WebImageBindings(BuildSourceProcessor.Context context){this.context=context;}
    void store(List<Ready> batch)throws java.sql.SQLException {
        if(batch.isEmpty())return;context.checkCancelled();
        var index=new GuideImageIndex(context.database());var groundings=new HashMap<String,GuideImageSemantics.Grounding>();
        for(var ready:batch)if(ready.failure()==null) {
            var image=ready.image();String key=image.document()+"/"+image.block();
            if(!anchors.containsKey(key))anchors.put(key,index.anchor(image.document(),image.block()));
            var anchor=anchors.get(key);boolean inline=image.step()!=null&&image.step().strip().length()>=6;
            groundings.put(image.id(),GuideImageSemantics.describe(image.caption(),"","CONTEXT_ONLY",anchor==null?null:anchor.id(),anchor==null?null:anchor.lineage(),
                    anchor==null?"":anchor.heading()+"\n"+(inline?image.step():anchor.text()),inline?"INLINE_STEP":anchor==null?"UNBOUND":anchor.ordinal()==image.block()?"EXACT_BLOCK":"ADJACENT_BLOCK"));
        }
        try(var c=context.database().getConnection()) {
            c.setAutoCommit(false);
            try(var assets=c.prepareStatement("""
                    MERGE INTO rag_image_asset t USING (VALUES(?,?,?,?,?)) s(id,path,type,w,h) ON t.id=s.id
                    WHEN MATCHED THEN UPDATE SET relative_path=s.path,media_type=s.type,width=s.w,height=s.h
                    WHEN NOT MATCHED THEN INSERT(id,relative_path,media_type,width,height) VALUES(s.id,s.path,s.type,s.w,s.h)
                    """);
                var occurrences=c.prepareStatement("MERGE INTO rag_image_occurrence(id,document_id,image_id,caption,original_location,page_number,ordinal,role,position_text,analysis_status) KEY(id) VALUES(?,?,?,?,?,NULL,?,'ILLUSTRATION',?,'CONTEXT_ONLY')");
                var links=c.prepareStatement("MERGE INTO rag_chunk_image(chunk_id,occurrence_id,relation) KEY(chunk_id,occurrence_id) SELECT DISTINCT l.chunk_id,?,'same_block' FROM rag_chunk_block l JOIN rag_content_block b ON b.id=l.block_id WHERE b.document_id=? AND b.ordinal=? AND NOT EXISTS(SELECT 1 FROM rag_chunk_image previous WHERE previous.chunk_id=l.chunk_id AND previous.occurrence_id=?)");
                var grounding=c.prepareStatement("MERGE INTO rag_image_grounding KEY(id) VALUES(?,?,?,?,?,?,?,?,?)");
                var states=c.prepareStatement("UPDATE rag_web_image_reference SET status=?,error_text=? WHERE id=?")) {
                var saved=new HashSet<String>();
                for(var ready:batch) {
                    context.checkCancelled();var image=ready.image();var asset=ready.asset();
                    states.setString(1,ready.failure()==null?"AVAILABLE":ready.failure().contains("SMALL:")?"EXCLUDED_SMALL":"FAILED");states.setString(2,ready.failure());states.setString(3,image.id());states.addBatch();
                    if(ready.failure()!=null)continue;
                    if(saved.add(asset.id())){assets.setString(1,asset.id());assets.setString(2,asset.relativePath());assets.setString(3,asset.mediaType());assets.setInt(4,asset.width());assets.setInt(5,asset.height());assets.addBatch();}
                    occurrences.setString(1,image.id());occurrences.setString(2,image.document());occurrences.setString(3,asset.id());occurrences.setString(4,image.caption());occurrences.setString(5,image.url());occurrences.setInt(6,image.ordinal());occurrences.setString(7,"DOM block "+image.block());occurrences.addBatch();
                    links.setString(1,image.id());links.setString(2,image.document());links.setInt(3,image.block());links.setString(4,image.id());links.addBatch();
                    var g=groundings.get(image.id());grounding.setString(1,image.id());grounding.setString(2,asset.id());grounding.setString(3,GuideImageSemantics.VERSION);grounding.setString(4,g.purpose());grounding.setString(5,g.basis());grounding.setString(6,g.blockId());grounding.setString(7,g.lineage());grounding.setString(8,g.stepText());grounding.setString(9,g.relation());grounding.addBatch();
                }
                assets.executeBatch();occurrences.executeBatch();links.executeBatch();grounding.executeBatch();states.executeBatch();context.checkCancelled();c.commit();
            }catch(java.sql.SQLException|RuntimeException failure){c.rollback();throw failure;}
        }
        int bound=(int)batch.stream().filter(item->item.failure()==null).count();context.result().webImageBindings+=bound;context.result().enrichedWebImages+=bound;
    }
}
