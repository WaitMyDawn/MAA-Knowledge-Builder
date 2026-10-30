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
        docCount += texts.size();
        List<float[]> result = new ArrayList<>(texts.size());
        for (String t : texts) {
            BuildCancellation.check();
            result.add(embedMeasured(t,true));
        }
        return result;
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
    public void close() { localModel.close(); onnxReady = false; }
}
