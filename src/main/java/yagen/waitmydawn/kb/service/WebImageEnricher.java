package yagen.waitmydawn.kb.service;

import yagen.waitmydawn.kb.model.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/** 网页图片默认仅关联正文与步骤；显式 OCR 调用保留用于独立分析。 */
public final class WebImageEnricher {
    private final LocalOcrEngine ocr;
    public WebImageEnricher(){this(null);}
    WebImageEnricher(LocalOcrEngine ocr){this.ocr=ocr;}
    boolean requiresOcr(){return ocr!=null;}
    void enrich(BuildSourceProcessor.Context context,String document,String occurrence,KnowledgeContent.ImageAsset image,String caption,int ordinal) throws Exception {
        enrich(context,document,occurrence,image,caption,ordinal,null);
    }
    void enrich(BuildSourceProcessor.Context context,String document,String occurrence,KnowledgeContent.ImageAsset image,String caption,int ordinal,String inlineStep) throws Exception {
        context.checkCancelled();var index=new GuideImageIndex(context.database());
        if(!requiresOcr()) {
            index.bind(occurrence,document,image.id(),caption,"","CONTEXT_ONLY",ordinal);
            if(inlineStep==null)try(var c=context.database().getConnection();var p=c.prepareStatement("SELECT step_text FROM rag_web_image_reference WHERE id=?")){p.setString(1,occurrence);try(var r=p.executeQuery()){if(r.next())inlineStep=r.getString(1);}}
            if(inlineStep!=null&&inlineStep.strip().length()>=6) {
                var anchor=index.anchor(document,ordinal);
                if(anchor!=null)index.save(occurrence,image.id(),GuideImageSemantics.describe(caption,"","CONTEXT_ONLY",anchor.id(),anchor.lineage(),anchor.heading()+"\n"+inlineStep,"INLINE_STEP"));
            }
            context.content().setOccurrenceMetadata(occurrence,"ILLUSTRATION","DOM block "+ordinal,null,"CONTEXT_ONLY");
            context.result().enrichedWebImages++;return;
        }
        var cached=index.cached(image.id());
        String text=cached==null?"":Objects.toString(cached.text(),""),description=cached==null?null:cached.description(),status=cached==null?null:cached.status();
        if(!text.isBlank()&&!GuideImageSemantics.usableOcr(text)&&"OCR_OK".equals(status)){status="OCR_LOW_SIGNAL";index.analysis(image.id(),status,cached.language());}
        boolean complete=status!=null&&Set.of("OCR_OK","OCR_NO_TEXT","OCR_LOW_SIGNAL","OCR_SKIPPED_SMALL","LEGACY_TEXT_REUSED").contains(status);
        if(complete)context.result().reusedImageAnalyses++;
        else if(!text.isBlank()){status="LEGACY_TEXT_REUSED";index.analysis(image.id(),status,"");context.result().reusedImageAnalyses++;}
        else if(image.width()<32||image.height()<16||(long)image.width()*image.height()<2048){status="OCR_SKIPPED_SMALL";index.analysis(image.id(),status,"");}
        else if(context.result().webOcrAttempts>=Math.max(0,Integer.getInteger("maa.web.maxOcrImages",Integer.MAX_VALUE))){status="OCR_DEFERRED";context.result().deferredImageAnalyses++;index.analysis(image.id(),status,"");}
        else {
            context.result().webOcrAttempts++;context.result().ocrAttempts++;
            try {
                var path=context.images().resolve(image.id(),image.relativePath());var decoded=ImageIO.read(path.toFile());if(decoded==null)throw new java.io.IOException("无法解码网页图片");
                var result=ocr.recognize(decoded);text=GuideImageSemantics.limit(result.text(),12000);
                status=result.status().equals("OK")?(text.isBlank()?"OCR_NO_TEXT":GuideImageSemantics.usableOcr(text)?"OCR_OK":"OCR_LOW_SIGNAL"):"OCR_"+result.status();
                if(!text.isBlank()){context.result().ocrImages++;context.result().webOcrImages++;}
                index.analysis(image.id(),status,result.language());
            }catch(java.util.concurrent.CancellationException cancel){throw cancel;}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException("Web OCR cancelled");}
            catch(Exception failed){status="OCR_UNAVAILABLE";index.analysis(image.id(),status,"");}
        }
        context.content().saveImage(new KnowledgeContent.ImageAsset(image.id(),image.relativePath(),image.mediaType(),image.width(),image.height(),text.isBlank()?null:text,description));
        index.bind(occurrence,document,image.id(),caption,text,status,ordinal);context.result().enrichedWebImages++;
        if(inlineStep==null)try(var c=context.database().getConnection();var p=c.prepareStatement("SELECT step_text FROM rag_web_image_reference WHERE id=?")){p.setString(1,occurrence);try(var r=p.executeQuery()){if(r.next())inlineStep=r.getString(1);}}
        if(inlineStep!=null&&inlineStep.strip().length()>=6) {
            var anchor=index.anchor(document,ordinal);
            if(anchor!=null)index.save(occurrence,image.id(),GuideImageSemantics.describe(caption,text,status,anchor.id(),anchor.lineage(),anchor.heading()+"\n"+inlineStep,"INLINE_STEP"));
        }
        context.content().setOccurrenceMetadata(occurrence,"ILLUSTRATION","DOM block "+ordinal,null,status);
        if(text.strip().length()<2||!GuideImageSemantics.usableOcr(text)){discardLowSignalIndex(context,occurrence);return;}
        String blockId=occurrence+"/ocr";
        try(var c=context.database().getConnection();var p=c.prepareStatement("SELECT content FROM rag_content_block WHERE id=?")) {
            p.setString(1,blockId);try(var r=p.executeQuery()){if(r.next()&&r.getString(1).equals("图片 OCR（识别可能有误，需结合正文核对）：\n"+text))return;}
        }
        var anchor=index.anchor(document,ordinal);if(anchor==null)return;
        String title="网页图片",location="",sourceName="";String modId=null;
        try(var c=context.database().getConnection();var p=c.prepareStatement("SELECT d.title,d.location,s.display_name,s.mod_id FROM rag_document d JOIN rag_knowledge_source s ON s.id=d.source_id WHERE d.id=?")) {
            p.setString(1,document);try(var r=p.executeQuery()){if(r.next()){title=r.getString(1);location=r.getString(2);sourceName=r.getString(3);modId=r.getString(4);}}
        }
        var raw=new KnowledgeContent.Block(blockId,document,anchor.heading(),anchor.lineage(),ordinal,anchor.page(),"IMAGE_OCR",null,"图片 OCR（识别可能有误，需结合正文核对）：\n"+text,"OCR");context.content().saveBlock(raw);
        var vectors=new ArrayList<TextChunker.TextChunk>();int sequence=0;
        for(var indexed:StructuredChunker.split(List.of(raw),title,modId,sourceName,"web",location,context.embedder())) {
            var original=indexed.chunk();String id=occurrence+"/ocr/chunk/"+sequence;
            var chunk=new TextChunker.TextChunk(id,original.text(),original.modId(),original.modName(),original.type(),original.sourcePath());
            context.content().saveChunk(new KnowledgeContent.Chunk(id,document,chunk.text(),anchor.heading(),anchor.page(),1_000_000+ordinal*100+sequence++));context.content().setChunkMethod(id,"OCR");
            context.content().linkBlock(id,blockId);context.content().linkBlock(id,anchor.id());context.content().saveLink(new KnowledgeContent.ChunkImageLink(id,occurrence,"image_ocr"));vectors.add(chunk);
        }
        context.vectors().storeBatch(vectors,context.embedder());context.result().textChunks+=vectors.size();context.result().webOcrChunks+=vectors.size();
    }
    private static void discardLowSignalIndex(BuildSourceProcessor.Context context,String occurrence)throws java.sql.SQLException {
        String prefix=(occurrence+"/ocr/chunk/").replace("!","!!").replace("_","!_").replace("%","!%")+"%";
        try(var c=context.database().getConnection()) {
            var ids=new ArrayList<String>();try(var p=c.prepareStatement("SELECT id FROM rag_content_chunk WHERE id LIKE ? ESCAPE '!'") ) {p.setString(1,prefix);try(var r=p.executeQuery()){while(r.next())ids.add(r.getString(1));}}
            for(String id:ids)for(String sql:List.of("DELETE FROM rag_vectors WHERE chunk_id=?","DELETE FROM rag_chunk_image WHERE chunk_id=?","DELETE FROM rag_chunk_block WHERE chunk_id=?","DELETE FROM rag_content_chunk WHERE id=?"))try(var p=c.prepareStatement(sql)){p.setString(1,id);p.executeUpdate();}
            try(var p=c.prepareStatement("DELETE FROM rag_content_block WHERE id=? AND kind='IMAGE_OCR'")){p.setString(1,occurrence+"/ocr");p.executeUpdate();}
        }
    }
    public void backfill(BuildRequest.Source source,BuildSourceProcessor.Context context)throws Exception {
        backfill(source,context,context.database());
    }
    void backfill(BuildRequest.Source source,BuildSourceProcessor.Context context,DatabaseBuilder cache)throws Exception {
        new GuideImageStepBackfill(context.database(),cache).run(source,context);
        var scraper=new WikiScraperService();scraper.setResourceDatabase(cache);
        new WebImageCompletion(scraper,this).completeSource(source,context);
    }
}
