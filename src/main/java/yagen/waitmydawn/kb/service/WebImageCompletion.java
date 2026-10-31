package yagen.waitmydawn.kb.service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import yagen.waitmydawn.kb.model.*;
import yagen.waitmydawn.kb.model.KnowledgeContent.ImageAsset;

/** 单下载线程与本地处理交错执行；队列限制在一份待处理响应，不截断正文图片清单。 */
public final class WebImageCompletion {
    record Image(String id,String document,String url,String caption,int block,String location,String asset,String path,String type,int width,int height,String status,String step,String boundStep) {
        int ordinal(){try{return Integer.parseInt(id.substring(id.lastIndexOf("/image/")+7));}catch(NumberFormatException failure){return block;}}
    }
    private record Download(List<Image> references,ImageAsset asset,byte[] bytes,String failure,boolean reused){}
    private static final java.util.concurrent.locks.ReentrantLock[] URL_LOCKS=new java.util.concurrent.locks.ReentrantLock[256];
    static {for(int i=0;i<URL_LOCKS.length;i++)URL_LOCKS[i]=new java.util.concurrent.locks.ReentrantLock();}
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
    public void complete(Collection<String> documents,BuildSourceProcessor.Context context)throws Exception {completeSelected(documents,context,null);}
    public void completeSelected(Collection<String> documents,BuildSourceProcessor.Context context,Set<String> selected)throws Exception {
        try(var timingScope=BuildTrace.phase("images.pipeline",documents.size()+" documents")) {

        var groups=new LinkedHashMap<String,List<Image>>();int total=0;
        try(var c=context.database().getConnection();var p=c.prepareStatement("SELECT r.*,d.location,o.image_id,a.relative_path,a.media_type,a.width,a.height,g.step_text AS bound_step FROM rag_web_image_reference r JOIN rag_document d ON d.id=r.document_id LEFT JOIN rag_image_occurrence o ON o.id=r.id LEFT JOIN rag_image_asset a ON a.id=o.image_id LEFT JOIN rag_image_grounding g ON g.id=r.id AND g.analysis_version=? WHERE r.document_id=? ORDER BY r.block_ordinal,r.id")) {
            for(String document:new LinkedHashSet<>(documents)){p.setString(1,GuideImageSemantics.VERSION);p.setString(2,document);try(var rows=p.executeQuery()){while(rows.next()) {
                if(selected!=null&&!selected.contains(rows.getString("id")))continue;
                if(!scraper.buildRefreshing()&&"EXCLUDED_SMALL".equals(rows.getString("status")))continue;
                String url=rows.getString("url"),key;try{key=WebUrlPolicy.normalize(url);}catch(IllegalArgumentException invalid){key=url;}
                groups.computeIfAbsent(key,ignored->new ArrayList<>()).add(new Image(rows.getString("id"),document,url,rows.getString("caption"),rows.getInt("block_ordinal"),rows.getString("location"),rows.getString("image_id"),rows.getString("relative_path"),rows.getString("media_type"),rows.getInt("width"),rows.getInt("height"),rows.getString("status"),rows.getString("step_text"),rows.getString("bound_step")));total++;
            }}}
        }
        if(groups.isEmpty())return;
        var cache=new WebImageDownloadCache(scraper.resourceDatabase()==null?context.database():scraper.resourceDatabase(),context.images());
        var queue=new ArrayBlockingQueue<Download>(1);var attempts=new AtomicInteger();var peak=new AtomicInteger();var stop=new java.util.concurrent.atomic.AtomicBoolean();long[] fetchNanos={0};
        var executor=Executors.newSingleThreadExecutor(r->{var thread=new Thread(r,"mkb-web-image-download-"+UUID.randomUUID());thread.setDaemon(true);return thread;});
        var decoded=new java.util.concurrent.ConcurrentHashMap<String,ImageAsset>();
        var producer=executor.submit(BuildTrace.carry(BuildTaskJournal.carry(RetrievalTelemetry.carry(BuildCancellation.carry(stop::get,()-> {
            var checked=new HashMap<String,Boolean>();var blockedSites=new HashMap<String,String>();
            var task=BuildTaskJournal.current();
            for(var entry:groups.entrySet()) {
                context.checkCancelled();var references=entry.getValue();var first=references.getFirst();ImageAsset asset=null;byte[] bytes=null;String failure=null;boolean reused=false;
                long started=System.nanoTime();
                var lock=URL_LOCKS[Math.floorMod((context.images().directory()+entry.getKey()).hashCode(),URL_LOCKS.length)];
                try{while(!lock.tryLock(200,TimeUnit.MILLISECONDS))context.checkCancelled();}
                catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw new CancellationException("Image URL lock cancelled");}
                try {
                    WebUrlPolicy.normalize(entry.getKey());
                    if(!scraper.buildRefreshing())for(var image:references)if(image.asset()!=null&&checked.computeIfAbsent(image.asset()+"/"+image.path(),key->context.images().available(image.asset(),image.path()))) {
                        asset=new ImageAsset(image.asset(),image.path(),image.type(),image.width(),image.height(),null,null);break;
                    }
                    if(asset==null&&!scraper.buildRefreshing())asset=cache.find(entry.getKey(),scraper.buildCacheOnly());
                    if(asset!=null){reused=true;RetrievalTelemetry.operation("images.cache.hit",0,1);}
                    else {
                        String site=HostRequestGate.site(entry.getKey());failure=blockedSites.get(site);
                        if(!scraper.buildRefreshing()&&cache.rejected(entry.getKey()))failure="SMALL: 已确认的小图";
                        if(failure==null&&task!=null)failure=task.imageFailure(context.images().directory()+"\n"+entry.getKey());
                        if(failure==null) {
                            attempts.incrementAndGet();var response=scraper.fetchBuildResource(entry.getKey(),first.location(),true);
                            if(response.status()==200&&response.bytes()!=null){
                                bytes=response.bytes();WebImagePolicy.check(bytes);
                                String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
                                synchronized(decoded){asset=decoded.get(hash);if(asset==null){asset=context.images().put(bytes);decoded.put(hash,asset);context.result().webImageDecodes++;}}
                                cache.save(entry.getKey(),asset);bytes=null;
                            }
                            else {
                                int status=response.status();failure=(status==403||status==429?"SITE_COOLDOWN":status==404||status==410?"PERMANENT":"TEMPORARY")+": HTTP "+status;
                                if(status==403||status==429)blockedSites.put(site,failure);
                                if(task!=null)task.imageFailure(context.images().directory()+"\n"+entry.getKey(),failure);
                            }
                        }
                    }
                }catch(java.io.IOException|java.security.NoSuchAlgorithmException|IllegalArgumentException invalid){failure="PERMANENT: "+Objects.toString(invalid.getMessage(),"无效图片");if(failure.contains("SMALL:"))cache.reject(entry.getKey());}
                finally{lock.unlock();}
                fetchNanos[0]+=System.nanoTime()-started;
                try{var value=new Download(references,asset,bytes,failure,reused);while(!queue.offer(value,200,TimeUnit.MILLISECONDS))context.checkCancelled();peak.accumulateAndGet(queue.size(),Math::max);}
                catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw new CancellationException("Image download cancelled");}
            }
        })))));
        var bindings=new WebImageBindings(context);var batch=new ArrayList<WebImageBindings.Ready>();
        int completed=0;long lastProgress=0;long processNanos=0,storeNanos=0;
        try {
            progress(context,0,total,attempts.get(),"开始正文图片流水线");
            for(int received=0;received<groups.size();) {
                context.checkCancelled();Download download;
                try{download=queue.poll(200,TimeUnit.MILLISECONDS);}catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw new CancellationException("Image processing cancelled");}
                if(download==null) {
                    if(producer.isDone())producer.get();
                    if(System.nanoTime()-lastProgress>2_000_000_000L){progress(context,completed,total,attempts.get(),"等待图片获取／站点限流");lastProgress=System.nanoTime();}continue;
                }
                received++;long started=System.nanoTime();ImageAsset asset=download.asset();String failure=download.failure();
                if(failure==null&&asset==null)try {
                    String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(download.bytes()));
                    asset=decoded.get(hash);if(asset==null){WebImagePolicy.check(download.bytes());asset=context.images().put(download.bytes());decoded.put(hash,asset);context.result().webImageDecodes++;}
                    cache.save(download.references().getFirst().url(),asset);
                }catch(java.io.IOException|java.security.NoSuchAlgorithmException|IllegalArgumentException invalid){failure="PERMANENT: "+Objects.toString(invalid.getMessage(),"图片格式无效");}
                if(asset!=null&&!WebImagePolicy.allowed(asset.width(),asset.height())){asset=null;failure="SMALL: 图片宽高须分别大于160";}
                if(failure!=null&&failure.contains("SMALL:"))cache.reject(download.references().getFirst().url());
                if(failure!=null&&BuildTaskJournal.current()!=null)BuildTaskJournal.current().imageFailure(context.images().directory()+"\n"+download.references().getFirst().url(),failure);
                processNanos+=System.nanoTime()-started;
                for(var image:download.references()) {
                    context.checkCancelled();
                    if(failure!=null&&failure.contains("SMALL:")) {
                        context.result().excludedSmallWebImages++;RetrievalTelemetry.operation("images.excluded.small",0,1);
                    }else if(failure!=null) {
                        context.result().deferredWebImages++;
                        if(failure.startsWith("PERMANENT"))context.result().permanentImageFailures++;else if(failure.startsWith("SITE_COOLDOWN"))context.result().cooldownImageFailures++;else context.result().temporaryImageFailures++;
                    }else {
                        if(download.reused())context.result().reusedWebImages++;
                        if(image.asset()==null)context.result().webImages++;
                    }
                    if(!enricher.requiresOcr()&&failure==null&&download.reused()&&asset.id().equals(image.asset())&&image.status().equals("AVAILABLE")&&image.boundStep()!=null
                            &&(image.step()==null||image.step().isBlank()||image.boundStep().endsWith(image.step()))) {completed++;continue;}
                    if(enricher.requiresOcr()&&failure==null) {
                        var index=new GuideImageIndex(context.database());var analysis=index.cached(asset.id());var grounding=index.find(image.id());
                        if(image.status().equals("AVAILABLE")&&analysis!=null&&Set.of("OCR_OK","OCR_NO_TEXT","OCR_LOW_SIGNAL","OCR_SKIPPED_SMALL","LEGACY_TEXT_REUSED").contains(Objects.toString(analysis.status(),""))&&grounding!=null
                                &&(image.step()==null||image.step().isBlank()||Objects.toString(grounding.stepText(),"").endsWith(image.step()))) {context.result().reusedImageAnalyses++;completed++;continue;}
                        context.content().saveImage(asset);context.content().saveOccurrence(new KnowledgeContent.ImageOccurrence(image.id(),image.document(),asset.id(),image.caption(),image.url(),null,image.ordinal()));
                        try(var c=context.database().getConnection();var p=c.prepareStatement("MERGE INTO rag_chunk_image(chunk_id,occurrence_id,relation) KEY(chunk_id,occurrence_id) SELECT DISTINCT l.chunk_id,?,'same_block' FROM rag_chunk_block l JOIN rag_content_block b ON b.id=l.block_id WHERE b.document_id=? AND b.ordinal=? AND NOT EXISTS(SELECT 1 FROM rag_chunk_image previous WHERE previous.chunk_id=l.chunk_id AND previous.occurrence_id=?)")){p.setString(1,image.id());p.setString(2,image.document());p.setInt(3,image.block());p.setString(4,image.id());p.executeUpdate();}
                        enricher.enrich(context,image.document(),image.id(),asset,image.caption(),image.block(),image.step());
                    }else batch.add(new WebImageBindings.Ready(image,asset,failure));
                    if(batch.size()>=16){long storing=System.nanoTime();bindings.store(batch);batch.clear();storeNanos+=System.nanoTime()-storing;}
                    if(enricher.requiresOcr()&&failure==null)try(var c=context.database().getConnection();var p=c.prepareStatement("UPDATE rag_web_image_reference SET status='AVAILABLE',error_text=NULL WHERE id=?")){p.setString(1,image.id());p.executeUpdate();}
                    completed++;
                }
                if(received==1||completed==total||completed%16==0||System.nanoTime()-lastProgress>=200_000_000L){progress(context,completed,total,attempts.get(),"图片下载／复用与正文关联");lastProgress=System.nanoTime();}
            }
            producer.get();context.checkCancelled();long storing=System.nanoTime();bindings.store(batch);context.checkCancelled();storeNanos+=System.nanoTime()-storing;
        }catch(ExecutionException failure) {
            if(failure.getCause() instanceof RuntimeException runtime)throw runtime;
            if(failure.getCause() instanceof Error error)throw error;
            throw new IllegalStateException("图片下载任务失败",failure.getCause());
        }catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw new CancellationException("Image processing cancelled");}
        finally {
            boolean interrupted=Thread.interrupted();stop.set(true);producer.cancel(false);executor.shutdown();
            try{while(!executor.awaitTermination(1,TimeUnit.SECONDS)){}}
            catch(InterruptedException cancelled){interrupted=true;while(!executor.isTerminated())try{executor.awaitTermination(1,TimeUnit.SECONDS);}catch(InterruptedException again){interrupted=true;}}
            finally {
                context.result().webImageAttempts+=attempts.get();context.result().webImageQueuePeak=Math.max(context.result().webImageQueuePeak,peak.get());
                BuildTrace.work("images.fetch_or_reuse",fetchNanos[0],attempts.get());BuildTrace.work("images.process",processNanos,completed);BuildTrace.work("images.bind",storeNanos,completed);
                context.result().webImageFetchMs+=fetchNanos[0]/1_000_000;context.result().webImageProcessMs+=processNanos/1_000_000;context.result().webImageStoreMs+=storeNanos/1_000_000;
                if(interrupted)Thread.currentThread().interrupt();
            }
        }
        RetrievalTelemetry.operation("images.fetch_or_reuse",fetchNanos[0],attempts.get());
        RetrievalTelemetry.operation("images.bind",storeNanos,completed);
        progress(context,completed,total,attempts.get(),"正文图片处理完成");
        }
    }
    private static void progress(BuildSourceProcessor.Context context,int completed,int total,int attempts,String message) {
        if(context.progress()!=null)context.progress().onProgress("web-images",completed,total,message+"；获取尝试 "+attempts+"，复用 "+context.result().reusedWebImages+"，已关联 "+context.result().webImageBindings+"，失败 "+context.result().deferredWebImages);
    }
}
