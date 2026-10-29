package yagen.waitmydawn.kb.service;

import yagen.waitmydawn.kb.model.*;
import java.nio.file.*;
import java.util.*;

/** 扫描与离线恢复共用内容校验；网络查询只处理新增、变化或需要重试的条目。 */
public final class ModScanService {
    @FunctionalInterface public interface Progress{void update(int done,int total,ModEntry mod,String message);}
    public record Metrics(long totalMillis,long hashMillis,long metadataMillis,long lookupMillis,long httpRequests,int cacheHits,int skipped){
        public String summary(){return String.format(Locale.ROOT,"Scan 总耗时 %.2fs；内容校验 %.2fs；元数据 %.2fs；Modrinth 查询 %.2fs（%d 次 HTTP）；复用 %d，跳过 %d。",totalMillis/1000d,hashMillis/1000d,metadataMillis/1000d,lookupMillis/1000d,httpRequests,cacheHits,skipped);}
    }
    public record Result(List<ModEntry> mods,int completed,int total,Metrics metrics,String warning){
        public int scanPrepared(){return completed-(int)mods.stream().filter(m->!JarScanCache.scanReady(m)).count();}
    }
    public Result scan(Path folder,GameEnvironment environment,DatabaseBuilder db,boolean append,ModrinthBinder binder,boolean restoreOnly,Progress progress)throws Exception{
        return scan(folder,environment,db,append,binder,restoreOnly,progress,new JarScanCache(folder));
    }
    public Result scan(Path folder,GameEnvironment environment,DatabaseBuilder db,boolean append,ModrinthBinder binder,boolean restoreOnly,Progress progress,JarScanCache cache)throws Exception{
        long initialRequests=binder.requestCount();long started=System.nanoTime(),hashTime=0,metadataTime=0,lookupTime=0;int hits=0,skipped=0,done=0,valid=0;
        var jars=new JarScannerService().scan(folder).stream().sorted().toList();var mods=new ArrayList<ModEntry>();var drafts=new ModBindingStore(db);var parser=new ModMetadataParser();
        if(restoreOnly&&!cache.matches(environment))throw new IllegalStateException("没有匹配当前游戏环境的 Scan 记录，请先 Scan。");
        if(!restoreOnly)cache.begin(environment);cache.retain(jars);
        progress.update(0,jars.size(),null,restoreOnly?"正在校验 Scan 记录":"开始 Scan");
        try{
            for(var jar:jars){
                if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("扫描已取消，已完成记录保留");
                long t=System.nanoTime();var hashes=JarScanCache.hashes(jar);String hash=hashes.sha512();hashTime+=System.nanoTime()-t;
                var entry=cache.reusable(jar,hash,environment);boolean reused=entry!=null||cache.reusableSkipped(jar,hash,environment);
                if(restoreOnly&&!reused){done++;progress.update(valid,jars.size(),null,"文件新增或变化，需重新 Scan："+jar.getFileName());continue;}
                if(reused)hits++;else{t=System.nanoTime();entry=parser.parse(jar,environment);metadataTime+=System.nanoTime()-t;}
                valid++;
                if(entry==null){skipped++;if(!restoreOnly)cache.put(jar,hash,null);done++;progress.update(restoreOnly?valid:done,jars.size(),null,"无可识别元数据，已跳过："+jar.getFileName());continue;}
                drafts.apply(entry,db.findModEntry(entry.getModId()));
                var status=append?new JarBuildIndex(db).inspect(entry,hashes.sha256()):JarBuildIndex.Status.NEW;entry.setScanStatus(status.toString());
                boolean lookedUp=false;
                if(!restoreOnly&&status==JarBuildIndex.Status.NEW&&ModBindingStore.shouldLookupSlug(entry)&&(!reused||cache.retryLookup(jar))){
                    lookedUp=true;t=System.nanoTime();var bound=binder.bindHashed(jar,hash,entry.getModId(),entry.getDisplayName(),entry.getVersion(),environment.minecraftVersion(),environment.loader());lookupTime+=System.nanoTime()-t;
                    entry.setSource(bound.source());entry.setSlug(bound.slug());entry.setModrinthUrl(bound.modrinthUrl());
                }
                mods.add(entry);
                drafts.save(entry);
                if(restoreOnly)cache.updateBinding(entry);
                if(!restoreOnly){cache.put(jar,hash,entry,!reused||lookedUp);if((done+1)%10==0)cache.save(environment);}
                done++;progress.update(restoreOnly?valid:done,jars.size(),entry,(reused?"复用：":"识别：")+entry.getModId());
            }
        }finally{if(!restoreOnly||valid==jars.size())cache.save(environment);}
        return new Result(List.copyOf(mods),valid,jars.size(),new Metrics((System.nanoTime()-started)/1000000,hashTime/1000000,metadataTime/1000000,lookupTime/1000000,binder.requestCount()-initialRequests,hits,skipped),cache.warning());
    }
}
