package yagen.waitmydawn.kb.service;

import java.util.*;
import yagen.waitmydawn.kb.model.*;

/** 完成已声明网页的全部正文图片；失败保留可重试状态，不按图片数量截断。 */
public final class WebImageCompletion {
    private final WikiScraperService scraper;
    private final WebImageEnricher enricher;
    public WebImageCompletion(WikiScraperService scraper,WebImageEnricher enricher){this.scraper=scraper;this.enricher=enricher;}
    public void completeSource(BuildRequest.Source source,BuildSourceProcessor.Context context)throws Exception {
        var documents=new ArrayList<String>();
        try(var c=context.database().getConnection();var p=c.prepareStatement("SELECT DISTINCT d.id FROM rag_document d WHERE d.source_id=? OR EXISTS(SELECT 1 FROM rag_document_source s WHERE s.document_id=d.id AND s.source_id=?)")) {
            p.setString(1,source.id());p.setString(2,source.id());try(var rows=p.executeQuery()){while(rows.next())documents.add(rows.getString(1));}
        }
        complete(documents,context);
    }
    public void complete(Collection<String> documents,BuildSourceProcessor.Context context)throws Exception {
        record Image(String id,String document,String url,String caption,int block,String location,String asset,String path,String type,int width,int height,String status,String step){}
        var images=new ArrayList<Image>();
        try(var c=context.database().getConnection();var p=c.prepareStatement("SELECT r.*,d.location,o.image_id,a.relative_path,a.media_type,a.width,a.height FROM rag_web_image_reference r JOIN rag_document d ON d.id=r.document_id LEFT JOIN rag_image_occurrence o ON o.id=r.id LEFT JOIN rag_image_asset a ON a.id=o.image_id WHERE r.document_id=? ORDER BY r.block_ordinal,r.id")) {
            for(String document:new LinkedHashSet<>(documents)){p.setString(1,document);try(var rows=p.executeQuery()){while(rows.next())images.add(new Image(rows.getString("id"),document,rows.getString("url"),rows.getString("caption"),rows.getInt("block_ordinal"),rows.getString("location"),rows.getString("image_id"),rows.getString("relative_path"),rows.getString("media_type"),rows.getInt("width"),rows.getInt("height"),rows.getString("status"),rows.getString("step_text")));}}
        }
        int completed=0;var failedUrls=new HashMap<String,String>();
        for(var image:images){context.checkCancelled();if(context.progress()!=null)context.progress().onProgress("web-images",completed,images.size(),"缓存正文图片："+image.location());
            try{
                KnowledgeContent.ImageAsset asset;
                if(image.asset()!=null&&context.images().available(image.asset(),image.path()))asset=new KnowledgeContent.ImageAsset(image.asset(),image.path(),image.type(),image.width(),image.height(),null,null);
                else{
                    if(failedUrls.containsKey(image.url()))throw new java.io.IOException(failedUrls.get(image.url()));
                    context.result().webImageAttempts++;
                    var resource=scraper.fetchBuildResource(image.url(),image.location(),true);
                    if(resource.status()!=200||resource.bytes()==null)throw new java.io.IOException("HTTP "+resource.status());
                    asset=context.images().put(resource.bytes());context.content().saveImage(asset);
                    int ordinal=image.block();int split=image.id().lastIndexOf("/image/");if(split>=0)try{ordinal=Integer.parseInt(image.id().substring(split+7));}catch(NumberFormatException ignored){}
                    context.content().saveOccurrence(new KnowledgeContent.ImageOccurrence(image.id(),image.document(),asset.id(),image.caption(),image.url(),null,ordinal));
                    context.result().webImages++;
                }
                try(var c=context.database().getConnection();var p=c.prepareStatement("SELECT DISTINCT l.chunk_id FROM rag_chunk_block l JOIN rag_content_block b ON b.id=l.block_id WHERE b.document_id=? AND b.ordinal=? AND NOT EXISTS(SELECT 1 FROM rag_chunk_image existing WHERE existing.chunk_id=l.chunk_id AND existing.occurrence_id=?)")){
                    p.setString(1,image.document());p.setInt(2,image.block());p.setString(3,image.id());try(var rows=p.executeQuery()){while(rows.next())context.content().saveLink(new KnowledgeContent.ChunkImageLink(rows.getString(1),image.id(),"same_block"));}
                }
                var index=new GuideImageIndex(context.database());var cached=index.cached(asset.id());var grounding=index.find(image.id());
                String analysisStatus=cached==null?"":Objects.toString(cached.status(),"");
                boolean complete=cached!=null&&Set.of("OCR_OK","OCR_NO_TEXT","OCR_LOW_SIGNAL","OCR_SKIPPED_SMALL","LEGACY_TEXT_REUSED").contains(analysisStatus)&&(!analysisStatus.equals("OCR_OK")||GuideImageSemantics.usableOcr(cached.text()));
                boolean sameStep=grounding!=null&&(image.step()==null||image.step().isBlank()||Objects.toString(grounding.stepText(),"").endsWith(image.step()));
                if(image.status().equals("AVAILABLE")&&complete&&sameStep){context.result().reusedImageAnalyses++;completed++;continue;}
                enricher.enrich(context,image.document(),image.id(),asset,image.caption(),image.block());
                try(var c=context.database().getConnection();var p=c.prepareStatement("UPDATE rag_web_image_reference SET status='AVAILABLE',error_text=NULL WHERE id=?")){p.setString(1,image.id());p.executeUpdate();}
            }catch(java.util.concurrent.CancellationException cancelled){throw cancelled;}
            catch(java.io.IOException|IllegalArgumentException failed){failedUrls.put(image.url(),Objects.toString(failed.getMessage(),"图片获取失败"));try(var c=context.database().getConnection();var p=c.prepareStatement("UPDATE rag_web_image_reference SET status='FAILED',error_text=? WHERE id=?")){p.setString(1,failed.getMessage());p.setString(2,image.id());p.executeUpdate();}context.result().deferredWebImages++;}
            completed++;
        }
        if(context.progress()!=null)context.progress().onProgress("web-images",completed,images.size(),"正文图片缓存处理完成");
    }
}
