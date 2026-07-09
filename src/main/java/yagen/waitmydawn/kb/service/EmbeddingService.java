package yagen.waitmydawn.kb.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yagen.waitmydawn.kb.config.AppConfig;

import java.util.*;

/**
 * 向量嵌入服务。优先级:
 *   1. 本地 ONNX 模型 (用户指定或自动下载)
 *   2. 内置 n-gram TF-IDF (零依赖, 始终可用)
 */
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);
    static final int DIM = 384; // matches all-MiniLM-L6-v2 output

    private final AppConfig config;
    private final LocalEmbeddingModel localModel;
    private boolean onnxTried = false;
    private boolean onnxReady = false;

    // N-gram TF-IDF fallback
    private final Map<String, Double> globalIdf = new HashMap<>();
    private int docCount = 0;

    public EmbeddingService(AppConfig config) {
        this.config = config;
        this.localModel = new LocalEmbeddingModel(config);
    }

    public int getDimension() { return DIM; }

    public String initModel(String modelName) {
        if (onnxReady) return localModel.getActiveModel();
        onnxTried = true;
        String m = (modelName != null && !modelName.isBlank()) ? modelName : "all-MiniLM-L6-v2";
        onnxReady = localModel.loadModel(m);
        if (!onnxReady) log.info("Model {} unavailable — using n-gram TF-IDF", m);
        return onnxReady ? localModel.getActiveModel() : "none";
    }

    public Map<String, String> listModels() {
        return LocalEmbeddingModel.listAvailableModels(config.getDataDir());
    }
    public boolean onnxAvailable() { return onnxReady; }
    public String activeModel() { return onnxReady ? "all-MiniLM-L6-v2" : "none"; }
    public String vectorStoreCount() { return String.valueOf(docCount); }

    // ==================== Embed ====================

    public float[] embed(String text) {
        if (onnxReady) {
            float[] v = localModel.embed(text);
            if (v != null) return v;
        }
        return ngramVector(text);
    }

    public List<float[]> embedBatch(List<String> texts) {
        updateIdf(texts);
        List<float[]> result = new ArrayList<>(texts.size());
        for (String t : texts) {
            if (onnxReady) {
                float[] v = localModel.embed(t);
                if (v != null) { result.add(v); continue; }
            }
            result.add(ngramVector(t));
        }
        return result;
    }

    // ==================== N-gram TF-IDF ====================

    private float[] ngramVector(String text) {
        List<String> tokens = tokenize(text);
        if (tokens.isEmpty()) return new float[DIM];

        Map<String, Integer> tf = new LinkedHashMap<>();
        for (String t : tokens) tf.merge(t, 1, Integer::sum);
        float maxTf = Collections.max(tf.values());
        float[] vec = new float[DIM];

        for (Map.Entry<String, Integer> e : tf.entrySet()) {
            double idf = globalIdf.getOrDefault(e.getKey(), Math.log(docCount + 2));
            float w = (float) ((e.getValue() / maxTf) * idf);
            vec[Math.abs(e.getKey().hashCode()) % DIM] += w;
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
                if (!eng.isEmpty()) { tokens.add("w:" + eng.toString().toLowerCase()); eng.setLength(0); }
                cjk.append(c);
            } else if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
                if (!cjk.isEmpty()) { addCjk(tokens, cjk.toString()); cjk.setLength(0); }
                eng.append(c);
            } else {
                if (!cjk.isEmpty()) { addCjk(tokens, cjk.toString()); cjk.setLength(0); }
                if (!eng.isEmpty()) { tokens.add("w:" + eng.toString().toLowerCase()); eng.setLength(0); }
            }
        }
        if (!cjk.isEmpty()) addCjk(tokens, cjk.toString());
        if (!eng.isEmpty()) tokens.add("w:" + eng.toString().toLowerCase());
        return tokens;
    }

    private void addCjk(List<String> tokens, String s) {
        for (int i = 0; i < s.length(); i++) tokens.add("c:" + s.charAt(i));
        for (int i = 0; i < s.length() - 1; i++) tokens.add("b:" + s.substring(i, i + 2));
    }

    private boolean isCJK(char c) {
        return Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS;
    }

    private void updateIdf(List<String> texts) {
        docCount += texts.size();
        for (String text : texts) {
            Set<String> seen = new HashSet<>();
            for (String t : tokenize(text)) {
                if (seen.add(t)) globalIdf.merge(t, 1.0, Double::sum);
            }
        }
        for (var e : globalIdf.entrySet())
            e.setValue(Math.log((docCount + 1.0) / (e.getValue() + 1.0)) + 1.0);
    }
}
