package yagen.waitmydawn.kb.service;

/** 每轮请求的实际 I/O 与模型推理计数；不保存正文、问题或凭据。 */
public final class RetrievalTelemetry {
    private static final ThreadLocal<Counters> CURRENT = new ThreadLocal<>();
    private RetrievalTelemetry() {}
    public static Counters begin() { var counters = new Counters(); CURRENT.set(counters); return counters; }
    public static void end() { CURRENT.remove(); }
    public static Counters current() { return CURRENT.get(); }
    static void httpAttempt() { var c = CURRENT.get(); if (c != null) c.httpRequests++; }
    static void httpResponse(int status, int bytes) {
        var c = CURRENT.get(); if (c != null) { c.responseBytes += bytes; if (status >= 400) c.httpErrors++; }
    }
    static void pageHit(boolean persistent) {
        var c = CURRENT.get(); if (c != null) { if (persistent) c.persistentPageHits++; else c.memoryPageHits++; }
    }
    static void vectorHit(int count) { var c = CURRENT.get(); if (c != null) c.reusedEmbeddings += count; }
    static void inference(boolean document, long nanos) {
        var c = CURRENT.get(); if (c != null) { if (document) c.documentEmbeddings++; else c.queryEmbeddings++; c.embeddingNanos += nanos; }
    }
    public static final class Counters {
        public int httpRequests, httpErrors, memoryPageHits, persistentPageHits;
        public int documentEmbeddings, queryEmbeddings, reusedEmbeddings;
        public long responseBytes, embeddingNanos;
    }
}
