package yagen.waitmydawn.kb.service;

import java.nio.file.Path;
import java.util.*;
import yagen.waitmydawn.kb.model.*;

/** 问答先保存正文和图片引用，仅对当前证据选中的图片执行下载。 */
public final class WebPageMediaService {
    private static final Object[] PAGE_LOCKS=new Object[64];
    static {for(int i=0;i<PAGE_LOCKS.length;i++)PAGE_LOCKS[i]=new Object();}
    private final DatabaseBuilder db;private final EmbeddingService embedder;private final Path directory;private final WikiScraperService scraper;
    public WebPageMediaService(DatabaseBuilder db,EmbeddingService embedder,Path directory,WikiScraperService scraper){this.db=db;this.embedder=embedder;this.directory=directory;this.scraper=scraper;}
    private KnowledgeBuilder.BuildResult lastResult=new KnowledgeBuilder.BuildResult();
    KnowledgeBuilder.BuildResult lastResult(){return lastResult;}
    private BuildSourceProcessor.Context context(){return new BuildSourceProcessor.Context(db,new KnowledgeContentStore(db),new ImageAssetStore(directory),new VectorStore(db,embedder.getDimension()),embedder,null,new KnowledgeBuilder.BuildResult(),true,false,new HashSet<>());}
    public void acquire(String url){
        try{
            var source=BuildRequest.Source.of(BuildRequest.Kind.URL,url);var context=context();
            if(new SourceLifecycleStore(db).strings("SELECT id FROM rag_knowledge_source WHERE id=?",source.id()).isEmpty())context.content().saveSource(source);new UrlBuildProcessor(scraper).withoutImageDownloads().processDocument(source,url,context);
        }catch(java.util.concurrent.CancellationException cancelled){throw cancelled;}
        catch(Exception failed){throw new IllegalStateException("网页图文处理未完成："+url,failed);}
    }
    /** 公共网页知识仅维护一套结构化块；会话库不再复制同一页面的另一套切块。 */
    public List<TextChunker.TextChunk> acquirePage(String url,String mod,String name,String content) {
        synchronized(PAGE_LOCKS[Math.floorMod(WebUrlPolicy.normalize(url).hashCode(),PAGE_LOCKS.length)]){return syncPage(url,mod,name,content);}
    }
    private List<TextChunker.TextChunk> syncPage(String url,String mod,String name,String content) {
        String location=WebUrlPolicy.normalize(url);long started=System.nanoTime();
        try {
            var snapshot=new PublicKnowledgeCache(db).snapshot(location);
            String stamp=BuildRequest.fingerprint("structured-qa-v2-clean-body\n"+embedder.profile()+"\n"+mod+"\n"+content+"\n"+(snapshot==null?0:snapshot.fetchedAt()));
            String document=BuildRequest.fingerprint(location);boolean fresh=false;
            try(var c=db.getConnection();var s=c.createStatement()){s.execute("CREATE TABLE IF NOT EXISTS rag_qa_page_state(url VARCHAR(4000) PRIMARY KEY,stamp VARCHAR(64) NOT NULL)");}
            try(var c=db.getConnection();var p=c.prepareStatement("SELECT stamp FROM rag_qa_page_state WHERE url=?")){p.setString(1,location);try(var r=p.executeQuery()){fresh=r.next()&&stamp.equals(r.getString(1));}}
            var stored=pageChunks(location);
            if(fresh&&!stored.isEmpty()){RetrievalTelemetry.operation("page.index.reused",System.nanoTime()-started,stored.size());return stored;}
            var source=BuildRequest.Source.of(BuildRequest.Kind.URL,location);var original=context();var context=new BuildSourceProcessor.Context(original.database(),original.content(),original.images(),original.vectors(),original.embedder(),original.progress(),original.result(),true,stored.isEmpty(),original.visitedDocuments());context.content().saveSource(source);context.content().setSourceScope(source.id(),location,"WEB",name,mod);
            new UrlBuildProcessor(scraper).withoutImageDownloads().processDocument(source,location,context,mod,name);
            if(mod!=null)try(var c=db.getConnection();var p=c.prepareStatement("UPDATE rag_vectors SET mod_id=?,mod_name=? WHERE chunk_id IN (SELECT id FROM rag_content_chunk WHERE document_id=?) AND (mod_id IS NULL OR mod_id=?)")){p.setString(1,mod);p.setString(2,name);p.setString(3,document);p.setString(4,mod);p.executeUpdate();}
            stored=pageChunks(location);if(stored.isEmpty())throw new IllegalStateException("网页未生成可检索正文");
            try(var c=db.getConnection();var p=c.prepareStatement("MERGE INTO rag_qa_page_state KEY(url) VALUES(?,?)")){p.setString(1,location);p.setString(2,stamp);p.executeUpdate();}
            RetrievalTelemetry.operation("page.index.imported",System.nanoTime()-started,stored.size());return stored;
        }catch(Exception e){throw new IllegalStateException("公共网页知识同步失败："+location,e);}
    }
    private List<TextChunker.TextChunk> pageChunks(String location)throws Exception {
        var list=new ArrayList<TextChunker.TextChunk>();try(var c=db.getConnection();var p=c.prepareStatement("SELECT v.chunk_id,v.chunk_text,v.mod_id,v.mod_name,v.chunk_type,v.source_path FROM rag_vectors v JOIN rag_content_chunk k ON k.id=v.chunk_id JOIN rag_document d ON d.id=k.document_id WHERE d.location=? ORDER BY k.ordinal")){p.setString(1,location);try(var r=p.executeQuery()){while(r.next())list.add(new TextChunker.TextChunk(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6)));}}return list;
    }
    public void completeSelected(Collection<String> documents,Set<String> references){
        try(var reason=RetrievalTelemetry.resourceCause("ANSWER_SELECTED_IMAGES")){var context=context();lastResult=context.result();new WebImageCompletion(scraper,new WebImageEnricher()).completeSelected(documents,context,references);}
        catch(java.util.concurrent.CancellationException cancelled){throw cancelled;}
        catch(Exception failed){throw new IllegalStateException("选定网页图片处理失败",failed);}
    }
    public void complete(Collection<String> documents){
        try(var reason=RetrievalTelemetry.resourceCause("HISTORY_PAGE_IMAGES")){var context=context();lastResult=context.result();new WebImageCompletion(scraper,new WebImageEnricher()).complete(documents,context);}
        catch(java.util.concurrent.CancellationException cancelled){throw cancelled;}
        catch(Exception failed){throw new IllegalStateException("网页图片补全未完成",failed);}
    }

    /**
     * 把缺步骤或整轮零图时挑出的引用变成可显示插图。已有下载资产的直接复用；
     * 只有来源章节确实没有尺寸合规图时，才补取小图标并在图注中明确标注。
     */
    public List<AnswerArtifacts.Picture> fallbackPictures(List<WebImagePolicy.Fallback> candidates,List<AnswerArtifacts.Source> sources) {
        if(candidates==null||candidates.isEmpty())return List.of();
        var store=new ImageAssetStore(directory);var pictures=new ArrayList<AnswerArtifacts.Picture>();
        var assets=new HashSet<String>();var ids=new HashSet<String>();
        for(var candidate:candidates) {
            if(!ids.add(candidate.referenceId()))continue;
            var source=sources.stream().filter(item->Objects.equals(documentId(item.documentId()),candidate.documentId())).findFirst().orElse(null);
            if(source==null)continue;
            String asset=candidate.assetId(),path=candidate.relativePath();
            if(asset==null||path==null||!store.available(asset,path)) {
                try(var cause=RetrievalTelemetry.resourceCause("ANSWER_FALLBACK_IMAGES")){
                    var response=scraper.fetchBuildResource(candidate.url(),source.location(),true);
                    if(response.status()!=200||response.bytes()==null)continue;
                    var decoded=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(response.bytes()));
                    if(decoded==null)continue;
                    var saved=store.put(response.bytes());asset=saved.id();path=saved.relativePath();
                }catch(java.io.IOException|RuntimeException failed){continue;}
            }
            if(!assets.add(asset))continue;
            String heading=Objects.toString(candidate.heading(),""),body=Objects.toString(candidate.blockText(),"");
            String step=(heading.isBlank()?"":heading+"\n")+(body.isBlank()?Objects.toString(source.evidence(),""):body);
            if(step.length()>1800)step=step.substring(0,1800);
            String caption=fallbackCaption(candidate,source,heading);
            if("EXCLUDED_SMALL".equals(candidate.status()))caption=caption+"（来源图标）";
            String id="i"+BuildRequest.fingerprint(candidate.referenceId()).substring(0,20);
            var grounding=new AnswerArtifacts.ImageGrounding("ILLUSTRATION","CONTEXT_INFERRED",candidate.blockId(),step,"INLINE_STEP","CONTEXT_ONLY");
            pictures.add(new AnswerArtifacts.Picture(id,asset,path,caption,source.id(),"ILLUSTRATION",List.of(),step,
                    candidate.url(),source.location(),grounding));
            if(pictures.size()>=4)break;
        }
        if(!pictures.isEmpty())log.info("web images: fallback selected={} statuses={}",pictures.size(),
                candidates.stream().map(WebImagePolicy.Fallback::status).distinct().toList());
        return List.copyOf(pictures);
    }
    private static String documentId(String id){return id!=null&&id.startsWith("vector:")?id.substring("vector:".length()):id;}
    /** 网页表格图沿用“第N张图片”生成名时，用该行说明或页面章节给出可读图注。 */
    private static String fallbackCaption(WebImagePolicy.Fallback candidate,AnswerArtifacts.Source source,String heading) {
        String caption=Objects.toString(candidate.caption(),"");
        if(!caption.isBlank()&&!caption.matches("(?s).*-第[0-9]+张图片$"))return caption;
        String text=Objects.toString(candidate.stepText(),"");
        if(text.isBlank())text=Objects.toString(candidate.blockText(),"");
        text=text.replaceFirst("^表格行：","").replaceFirst("^首行（原文）：","").strip();
        String best="";
        var parts=text.split("\\|");
        for(int i=0;i<Math.min(3,parts.length);i++){String part=parts[i].strip();if(part.length()>best.length()&&!part.startsWith("表格行"))best=part;}
        // 表格行常把“中文名 (English) 中文名”重复一遍，图注只保留第一次出现的名称。
        int repeat=best.length()>8?best.indexOf(best.substring(0,8),1):-1;
        if(repeat>0)best=best.substring(0,repeat).strip();
        if(best.length()>60)best=best.substring(0,60);
        if(!best.isBlank()&&!best.startsWith("表格行"))return best;
        String ordinal=caption.matches("(?s).*-(第[0-9]+张图片)$")?caption.replaceFirst("(?s).*-(第[0-9]+张图片)$","$1"):"";
        return Objects.toString(source.title(),"网页插图")+(heading==null||heading.isBlank()?"":" · "+heading)
                +(ordinal.isBlank()?"":"（"+ordinal+"）");
    }
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(WebPageMediaService.class);
}
