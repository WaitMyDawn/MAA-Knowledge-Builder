package yagen.waitmydawn.kb.service;

import yagen.waitmydawn.kb.model.*;
import java.util.*;

/** 只从已有原始 HTML 恢复图片位置，且局部文字必须与已发布正文一致，不发起联网刷新。 */
final class GuideImageStepBackfill {
    private final DatabaseBuilder db;
    private final DatabaseBuilder cache;
    GuideImageStepBackfill(DatabaseBuilder db,DatabaseBuilder cache){this.db=db;this.cache=cache==null?db:cache;}
    void run(BuildRequest.Source source,BuildSourceProcessor.Context context)throws Exception {
        record Document(String id,String location){}
        var docs=new ArrayList<Document>();
        try(var c=db.getConnection()) {
            try(var cached=cache.getConnection();var tables=cached.getMetaData().getTables(null,null,"rag_public_resource",null)){if(!tables.next())return;}
            try(var p=c.prepareStatement("SELECT d.id,d.location FROM rag_document d WHERE (d.source_id=? OR EXISTS(SELECT 1 FROM rag_document_source l WHERE l.document_id=d.id AND l.source_id=?)) AND EXISTS(SELECT 1 FROM rag_web_image_reference r WHERE r.document_id=d.id AND r.step_text IS NULL) ORDER BY d.id LIMIT 501")) {
                p.setString(1,source.id());p.setString(2,source.id());try(var r=p.executeQuery()){while(r.next())docs.add(new Document(r.getString(1),r.getString(2)));}
            }
        }
        long bytes=0;
        for(var doc:docs.stream().limit(500).toList()) {
            context.checkCancelled();WikiScraperService.Resource cached=null;
            try(var c=cache.getConnection();var p=c.prepareStatement("SELECT final_url,body,content_type FROM rag_public_resource WHERE url=? AND OCTET_LENGTH(body)<=4194304 AND LOWER(content_type) LIKE '%html%'")) {
                p.setString(1,doc.location());try(var r=p.executeQuery()){if(r.next()){byte[] body=r.getBytes(2);if((bytes+=body.length)>32L*1024*1024)break;cached=new WikiScraperService.Resource(200,body,r.getString(3),r.getString(1),null,null);}}
            }
            if(cached==null)continue;
            var contexts=new HashMap<String,String>();
            for(var block:WebDocumentParser.parse(cached.document(),cached.url()).blocks())for(var image:block.images())if(!image.stepText().isBlank())contexts.putIfAbsent(image.url(),image.stepText());
            try(var c=db.getConnection();var p=c.prepareStatement("SELECT r.id,r.url,b.content FROM rag_web_image_reference r JOIN rag_content_block b ON b.document_id=r.document_id AND b.ordinal=r.block_ordinal AND b.kind<>'IMAGE_OCR' WHERE r.document_id=? AND r.step_text IS NULL ORDER BY r.id")) {
                p.setString(1,doc.id());try(var r=p.executeQuery()){while(r.next()) {
                    String step=contexts.get(r.getString(2));if(step==null||step.strip().length()<6||!KnowledgeSearchText.normalize(r.getString(3)).contains(KnowledgeSearchText.normalize(step)))continue;
                    context.content().setImageStep(r.getString(1),step);context.result().imageStepBackfills++;
                }}
            }
        }
    }
}
