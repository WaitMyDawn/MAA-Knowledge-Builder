package yagen.waitmydawn.kb.service;

/** 每轮请求的实际 I/O 与模型推理计数；不保存正文、问题或凭据。 */
public final class RetrievalTelemetry {
    private static final ThreadLocal<Counters> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<String> CAUSE=new ThreadLocal<>();
    private RetrievalTelemetry() {}
    public static Counters begin() { var counters = new Counters(); CURRENT.set(counters); return counters; }
    public static void end() { CURRENT.remove();CAUSE.remove(); }
    public static Counters current() { return CURRENT.get(); }
    static Runnable carry(Runnable work) {
        var captured=CURRENT.get();String cause=CAUSE.get();
        return ()->{var previous=CURRENT.get();String oldCause=CAUSE.get();if(captured==null)CURRENT.remove();else CURRENT.set(captured);if(cause==null)CAUSE.remove();else CAUSE.set(cause);try{work.run();}finally{if(previous==null)CURRENT.remove();else CURRENT.set(previous);if(oldCause==null)CAUSE.remove();else CAUSE.set(oldCause);}};
    }
    public interface ResourceScope extends AutoCloseable {void close();}
    public static ResourceScope resourceCause(String cause){String previous=CAUSE.get();CAUSE.set(cause);return ()->{if(previous==null)CAUSE.remove();else CAUSE.set(previous);};}
    static String resourceCause(){return CAUSE.get()==null?"RESOURCE_ACQUISITION":CAUSE.get();}
    public record Request(String kind,String host,String path,String cause,int status,long elapsedMs,boolean retry,boolean redirect){}
    public record ResourceAudit(int requests,int documents,int images,int unknown,int retries,int redirects,long waitMs,long backoffMs,long fetchMs,java.util.List<Request> events,boolean truncated){}
    public static ResourceAudit resources() {
        var c=CURRENT.get();if(c==null)return null;
        synchronized(c){return new ResourceAudit(c.httpRequests,c.documentRequests,c.imageRequests,Math.max(0,c.httpRequests-c.documentRequests-c.imageRequests),c.retries,c.redirects,c.waitNanos/1_000_000,c.backoffNanos/1_000_000,c.fetchNanos/1_000_000,java.util.List.copyOf(c.events),c.eventsTruncated);}
    }
    public record Work(long count,long millis){}
    public static java.util.Map<String,Work> work(){var c=CURRENT.get();if(c==null)return java.util.Map.of();synchronized(c){var result=new java.util.TreeMap<String,Work>();c.work.forEach((key,value)->result.put(key,new Work(value[0],value[1]/1_000_000)));return java.util.Map.copyOf(result);}}
    public static void operation(String name,long nanos,long count){var c=CURRENT.get();if(c!=null)synchronized(c){var value=c.work.computeIfAbsent(name,k->new long[2]);value[0]+=count;value[1]+=Math.max(0,nanos);}}
    static void waitTime(long nanos){BuildTrace.work("network.wait",nanos,1);var c=CURRENT.get();if(c!=null)synchronized(c){c.waitNanos+=Math.max(0,nanos);}}
    static void backoffTime(long nanos){BuildTrace.work("network.backoff",nanos,1);var c=CURRENT.get();if(c!=null)synchronized(c){c.backoffNanos+=Math.max(0,nanos);}}
    static void resourceAttempt(boolean image,String url,int status,long nanos,boolean retry,boolean redirect) {
        BuildTrace.network(image&&!resourceCause().contains("SOURCE_DOCUMENT"),url,status,nanos,retry,redirect);
        var c=CURRENT.get();if(c==null)return;
        String cause=resourceCause();boolean imageKind=image&&!cause.contains("SOURCE_DOCUMENT");
        String host="",path="";try{var uri=java.net.URI.create(url);host=java.util.Objects.toString(uri.getHost(),"");path=java.util.Objects.toString(uri.getPath(),"");if(path.length()>180)path=path.substring(0,180);}catch(IllegalArgumentException ignored){}
        synchronized(c) {
            if(imageKind)c.imageRequests++;else c.documentRequests++;if(status==0)c.httpErrors++;if(retry)c.retries++;if(redirect)c.redirects++;c.fetchNanos+=Math.max(0,nanos);
            if(c.events.size()<200)c.events.add(new Request(imageKind?"IMAGE":"DOCUMENT",host,path,cause,status,nanos/1_000_000,retry,redirect));else c.eventsTruncated=true;
        }
    }
    static void httpAttempt() { var c = CURRENT.get(); if (c != null) c.httpRequests++; }
    static void httpResponse(int status, int bytes) {
        var c = CURRENT.get(); if (c != null) { c.responseBytes += bytes; if (status >= 400) c.httpErrors++; }
    }
    static void pageHit(boolean persistent) {
        BuildTrace.work(persistent?"cache.page.persistent":"cache.page.memory",0,1);
        var c = CURRENT.get(); if (c != null) { if (persistent) c.persistentPageHits++; else c.memoryPageHits++; }
    }
    static void vectorHit(int count) { BuildTrace.work("embedding.reused",0,count);var c = CURRENT.get(); if (c != null) c.reusedEmbeddings += count; }
    static void inference(boolean document, long nanos) {
        BuildTrace.work(document?"embedding.document":"embedding.query",nanos,1);
        var c = CURRENT.get(); if (c != null) { if (document) c.documentEmbeddings++; else c.queryEmbeddings++; c.embeddingNanos += nanos; }
    }
    public static final class Counters {
        public int httpRequests, httpErrors, memoryPageHits, persistentPageHits;
        public int documentEmbeddings, queryEmbeddings, reusedEmbeddings;
        public long responseBytes, embeddingNanos;
        private int documentRequests,imageRequests,retries,redirects;
        private long waitNanos,backoffNanos,fetchNanos;
        private final java.util.List<Request> events=new java.util.ArrayList<>();
        private boolean eventsTruncated;
        private final java.util.Map<String,long[]> work=new java.util.HashMap<>();
    }
}
