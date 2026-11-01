package yagen.waitmydawn.kb.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yagen.waitmydawn.kb.config.AppConfig;

import java.util.*;

/**
 * 向量嵌入服务。优先级:
 *   1. 本地 ONNX 模型
 *   2. 固定权重的哈希 n-gram，跨批次和进程保持同一向量空间。
 */
public class EmbeddingService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);
    static final int DIM = 384; // matches all-MiniLM-L6-v2 output

    private final AppConfig config;
    private final LocalEmbeddingModel localModel;
    private boolean onnxReady = false;
    private java.sql.Connection textCache;
    private final Map<String,float[]> recent=new LinkedHashMap<>(128,0.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,float[]> entry){return size()>8192;}
    };

    // Fixed-weight hash n-gram fallback
    private int docCount = 0;

    public EmbeddingService(AppConfig config) {
        this.config = config;
        this.localModel = new LocalEmbeddingModel(config);
    }

    public int getDimension() { return DIM; }

    public String initModel(String modelName) {
        if (onnxReady && Objects.equals(modelName, localModel.getActiveModel())) return localModel.getActiveModel();
        if ("none".equals(modelName) || "hash-ngram-v2".equals(modelName)) {
            close();
            return "hash-ngram-v2";
        }
        String m = (modelName != null && !modelName.isBlank()) ? modelName : "all-MiniLM-L6-v2";
        onnxReady = localModel.loadModel(m);
        if (!onnxReady) log.warn("Model {} unavailable — using hash-ngram-v2; existing ONNX indexes require the original model", m);
        return activeModel();
    }

    public Map<String, String> listModels() {
        return LocalEmbeddingModel.listAvailableModels(config.getDataDir());
    }
    public boolean onnxAvailable() { return onnxReady; }
    public String activeModel() { return onnxReady ? localModel.getActiveModel() : "hash-ngram-v2"; }
    public String profile() { return onnxReady ? localModel.profile() : "hash-ngram-v2:384:lower-root"; }
    /** 包含起止 Token；哈希模式采用字符上界，保持分块可供短文本模型重建。 */
    public int tokenCount(String text) {return onnxReady?localModel.tokenCount(text):text.codePointCount(0,text.length())+2;}
    public String vectorStoreCount() { return String.valueOf(docCount); }

    // ==================== Embed ====================

    public float[] embed(String text) {
        return embedMeasured(text, false);
    }

    private float[] embedMeasured(String text, boolean document) {
        long start=System.nanoTime();
        try {
        if (onnxReady) {
            float[] v = localModel.embed(text);
            if (v != null) return v;
            throw new IllegalStateException("ONNX inference failed; refusing to mix embedding spaces");
        }
        return ngramVector(text);
        } finally { RetrievalTelemetry.inference(document,System.nanoTime()-start); }
    }

    public List<float[]> embedBatch(List<String> texts) {
        return embedBatch(texts,0);
    }

    public synchronized List<float[]> embedBatch(List<String> texts,int reused) {
        String profile=profile();var resolved=new LinkedHashMap<String,float[]>();
        var pending=new LinkedHashSet<String>();
        for(String text:texts){BuildCancellation.check();if(resolved.containsKey(text)||pending.contains(text))continue;float[] cached=cached(profile,text);if(cached==null)pending.add(text);else resolved.put(text,cached);}
        int hits=texts.size()-pending.size();RetrievalTelemetry.vectorHit(hits);docCount+=pending.size();
        var progress=BuildTrace.counter("embedding.document","向量推理",texts.size()+reused,reused+hits);
        int done=0;
        for(String text:pending){BuildCancellation.check();float[] vector=embedMeasured(text,true);resolved.put(text,vector);recent.put(profile+"\n"+text,vector.clone());progress.update(reused+hits+(++done));}
        persist(profile,pending,resolved);progress.complete();
        return texts.stream().map(text->resolved.get(text).clone()).toList();
    }
    private java.sql.Connection cacheConnection()throws Exception {
        if(config==null||config.getDataDir()==null)return null;
        if(textCache==null||textCache.isClosed()) {
            var directory=config.getDataDir().resolve("cache");java.nio.file.Files.createDirectories(directory);
            textCache=java.sql.DriverManager.getConnection("jdbc:h2:file:"+directory.resolve("text_embeddings").toAbsolutePath().toString().replace('\\','/')+";DATABASE_TO_LOWER=TRUE");
            try(var statement=textCache.createStatement()){statement.execute("CREATE TABLE IF NOT EXISTS text_embedding(profile VARCHAR(1000),text_hash VARCHAR(64),text CLOB NOT NULL,embedding BLOB NOT NULL,PRIMARY KEY(profile,text_hash))");}
        }
        return textCache;
    }
    private float[] cached(String profile,String text) {
        String key=profile+"\n"+text;var memory=recent.get(key);if(memory!=null)return memory.clone();
        try{var connection=cacheConnection();if(connection==null)return null;
            try(var query=connection.prepareStatement("SELECT text,embedding FROM text_embedding WHERE profile=? AND text_hash=?")) {
                query.setString(1,profile);query.setString(2,yagen.waitmydawn.kb.model.BuildRequest.fingerprint(text));
                try(var rows=query.executeQuery()){if(!rows.next())return null;if(!text.equals(rows.getString(1)))throw new IllegalStateException("Text embedding cache hash collision");
                    var bytes=java.nio.ByteBuffer.wrap(rows.getBytes(2));if(bytes.remaining()!=DIM*Float.BYTES)throw new IllegalStateException("Text embedding cache dimension mismatch");
                    float[] vector=new float[DIM];for(int i=0;i<DIM;i++){vector[i]=bytes.getFloat();if(!Float.isFinite(vector[i]))throw new IllegalStateException("Invalid cached embedding");}recent.put(key,vector);return vector.clone();}
            }
        }catch(Exception failure){throw new IllegalStateException("Text embedding cache read failed",failure);}
    }
    private void persist(String profile,Set<String> pending,Map<String,float[]> vectors) {
        try{var connection=cacheConnection();if(connection==null||pending.isEmpty())return;connection.setAutoCommit(false);
            try(var insert=connection.prepareStatement("MERGE INTO text_embedding KEY(profile,text_hash) VALUES(?,?,?,?)")) {
                int count=0;for(String text:pending){BuildCancellation.check();var bytes=java.nio.ByteBuffer.allocate(DIM*Float.BYTES);for(float value:vectors.get(text))bytes.putFloat(value);
                    insert.setString(1,profile);insert.setString(2,yagen.waitmydawn.kb.model.BuildRequest.fingerprint(text));insert.setString(3,text);insert.setBytes(4,bytes.array());insert.addBatch();if(++count%500==0){insert.executeBatch();insert.clearBatch();}}
                insert.executeBatch();connection.commit();
            }catch(Exception failure){connection.rollback();throw failure;}finally{connection.setAutoCommit(true);}
        }catch(java.util.concurrent.CancellationException cancelled){throw cancelled;}catch(Exception failure){throw new IllegalStateException("Text embedding cache write failed",failure);}
    }

    // ==================== Fixed-weight hash n-gram ====================

    private float[] ngramVector(String text) {
        List<String> tokens = tokenize(text);
        if (tokens.isEmpty()) return new float[DIM];

        Map<String, Integer> tf = new LinkedHashMap<>();
        for (String t : tokens) tf.merge(t, 1, Integer::sum);
        float maxTf = Collections.max(tf.values());
        float[] vec = new float[DIM];

        for (Map.Entry<String, Integer> e : tf.entrySet()) {
            float w = e.getValue() / maxTf;
            vec[Math.floorMod(e.getKey().hashCode(), DIM)] += w;
        }
        float norm = 0;
        for (float v : vec) norm += v * v;
        norm = (float) Math.sqrt(norm) + 1e-8f;
        for (int i = 0; i < DIM; i++) vec[i] /= norm;
        return vec;
    }

    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder cjk = new StringBuilder(), eng = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCJK(c)) {
                if (!eng.isEmpty()) { tokens.add("w:" + eng.toString().toLowerCase(Locale.ROOT)); eng.setLength(0); }
                cjk.append(c);
            } else if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
                if (!cjk.isEmpty()) { addCjk(tokens, cjk.toString()); cjk.setLength(0); }
                eng.append(c);
            } else {
                if (!cjk.isEmpty()) { addCjk(tokens, cjk.toString()); cjk.setLength(0); }
                if (!eng.isEmpty()) { tokens.add("w:" + eng.toString().toLowerCase(Locale.ROOT)); eng.setLength(0); }
            }
        }
        if (!cjk.isEmpty()) addCjk(tokens, cjk.toString());
        if (!eng.isEmpty()) tokens.add("w:" + eng.toString().toLowerCase(Locale.ROOT));
        return tokens;
    }

    private void addCjk(List<String> tokens, String s) {
        for (int i = 0; i < s.length(); i++) tokens.add("c:" + s.charAt(i));
        for (int i = 0; i < s.length() - 1; i++) tokens.add("b:" + s.substring(i, i + 2));
    }

    private boolean isCJK(char c) {
        return Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS;
    }

    @Override
    public synchronized void close() { localModel.close(); onnxReady = false;recent.clear();if(textCache!=null)try{textCache.close();}catch(java.sql.SQLException failure){log.warn("Text embedding cache close failed",failure);}finally{textCache=null;} }
}
