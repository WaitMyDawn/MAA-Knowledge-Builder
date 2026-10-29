package yagen.waitmydawn.kb.service;

import java.nio.file.Path;
import java.util.*;
import yagen.waitmydawn.kb.model.*;

/** 问答新网页与预热详情复用构建的图文解析、图片缓存和 OCR 索引。 */
public final class WebPageMediaService {
    private final DatabaseBuilder db;private final EmbeddingService embedder;private final Path directory;private final WikiScraperService scraper;
    public WebPageMediaService(DatabaseBuilder db,EmbeddingService embedder,Path directory,WikiScraperService scraper){this.db=db;this.embedder=embedder;this.directory=directory;this.scraper=scraper;}
    private KnowledgeBuilder.BuildResult lastResult=new KnowledgeBuilder.BuildResult();
    KnowledgeBuilder.BuildResult lastResult(){return lastResult;}
    private BuildSourceProcessor.Context context(){return new BuildSourceProcessor.Context(db,new KnowledgeContentStore(db),new ImageAssetStore(directory),new VectorStore(db,embedder.getDimension()),embedder,null,new KnowledgeBuilder.BuildResult(),true,false,new HashSet<>());}
    public void acquire(String url){
        try{
            var source=BuildRequest.Source.of(BuildRequest.Kind.URL,url);var context=context();
            if(new SourceLifecycleStore(db).strings("SELECT id FROM rag_knowledge_source WHERE id=?",source.id()).isEmpty())context.content().saveSource(source);new UrlBuildProcessor(scraper).processDocument(source,url,context);new WebImageCompletion(scraper,new WebImageEnricher()).completeSource(source,context);
        }catch(java.util.concurrent.CancellationException cancelled){throw cancelled;}
        catch(Exception failed){throw new IllegalStateException("网页图文处理未完成："+url,failed);}
    }
    public void complete(Collection<String> documents){
        try{var context=context();lastResult=context.result();new WebImageCompletion(scraper,new WebImageEnricher()).complete(documents,context);}
        catch(java.util.concurrent.CancellationException cancelled){throw cancelled;}
        catch(Exception failed){throw new IllegalStateException("网页图片补全未完成",failed);}
    }
}
