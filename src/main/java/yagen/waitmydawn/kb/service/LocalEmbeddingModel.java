package yagen.waitmydawn.kb.service;

import ai.onnxruntime.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yagen.waitmydawn.kb.config.AppConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 本地 ONNX Embedding 模型。
 * 内置: all-MiniLM-L6-v2 (384 dims, English+subword)
 * 用户可添加自定义模型到 {data}/models/{name}/
 */
public class LocalEmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(LocalEmbeddingModel.class);
    private static final int MAX_LEN = 512;

    // Bundled model definitions
    private static final Map<String, ModelDef> BUNDLED = Map.of(
            "all-MiniLM-L6-v2", new ModelDef("all-MiniLM-L6-v2", 384)
    );

    record ModelDef(String name, int dims) {}

    private final AppConfig config;
    private OrtEnvironment env;
    private OrtSession session;
    private WordPieceTokenizer tokenizer;
    private boolean ready = false;
    private int loadedDim = 384;
    private String loadedModel = null;

    public LocalEmbeddingModel(AppConfig config) { this.config = config; }

    // ==================== Public API ====================

    public synchronized boolean loadModel(String modelName) {
        if (ready && loadedModel != null && loadedModel.equals(modelName)) return true;

        // Release previous
        close();

        ModelDef def = BUNDLED.get(modelName);
        try {
            Path modelDir;
            if (def != null) {
                // Bundled: extract from classpath to data dir
                modelDir = config.getDataDir().resolve("models").resolve(def.name());
                Files.createDirectories(modelDir);
                Path onnx = modelDir.resolve("model.onnx");
                Path vocab = modelDir.resolve("vocab.txt");
                if (!Files.exists(onnx) || Files.size(onnx) < 1000)
                    extractResource("/models/" + def.name() + "/model.onnx", onnx);
                if (!Files.exists(vocab) || Files.size(vocab) < 1000)
                    extractResource("/models/" + def.name() + "/vocab.txt", vocab);
            } else {
                // User-provided: {data}/models/{name}/
                modelDir = config.getDataDir().resolve("models").resolve(modelName);
                if (!Files.isDirectory(modelDir)) return false;
                Path onnx = modelDir.resolve("model.onnx");
                Path vocab = modelDir.resolve("vocab.txt");
                if (!Files.exists(onnx) || !Files.exists(vocab)) return false;
            }

            Path onnxFile = modelDir.resolve("model.onnx");
            Path vocabFile = modelDir.resolve("vocab.txt");

            env = OrtEnvironment.getEnvironment();
            session = env.createSession(onnxFile.toString(), new OrtSession.SessionOptions());
            tokenizer = new WordPieceTokenizer(vocabFile);
            ready = true;
            loadedDim = (def != null) ? def.dims() : 384; // default 384 for custom
            loadedModel = modelName;
            log.info("Model loaded: {} ({} dims)", modelName, loadedDim);
            return true;
        } catch (Exception e) {
            log.warn("Failed to load model '{}': {}", modelName, e.getMessage());
            return false;
        }
    }

    public boolean isReady() { return ready; }
    public int getDimension() { return loadedDim; }
    public String getActiveModel() { return loadedModel; }

    public float[] embed(String text) {
        if (!ready) return null;
        try {
            long[] inputIds = tokenizer.encode(text, MAX_LEN);
            int seqLen = inputIds.length;
            long[] attentionMask = new long[seqLen], tokenTypeIds = new long[seqLen];
            Arrays.fill(attentionMask, 1L);

            try (var ids = OnnxTensor.createTensor(env, new long[][]{inputIds});
                 var mask = OnnxTensor.createTensor(env, new long[][]{attentionMask});
                 var typeIds = OnnxTensor.createTensor(env, new long[][]{tokenTypeIds})) {

                Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
                inputs.put("input_ids", ids);
                inputs.put("attention_mask", mask);
                inputs.put("token_type_ids", typeIds);

                OrtSession.Result result = session.run(inputs);
                float[][][] output = (float[][][]) result.get(0).getValue();
                result.close();
                return meanPool(output[0], seqLen);
            }
        } catch (Exception e) {
            log.warn("ONNX inference failed: {}", e.getMessage());
            return null;
        }
    }

    public void close() {
        try { if (session != null) { session.close(); session = null; } } catch (Exception ignored) {}
        try { if (env != null) { env.close(); env = null; } } catch (Exception ignored) {}
        ready = false; loadedModel = null; tokenizer = null;
    }

    // ==================== Helpers ====================

    private void extractResource(String path, Path dest) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) throw new IOException("Resource not found: " + path);
            Files.copy(is, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private float[] meanPool(float[][] tokEmb, int seqLen) {
        int dim = tokEmb[0].length;
        float[] vec = new float[dim];
        for (int i = 0; i < seqLen; i++)
            for (int j = 0; j < dim; j++) vec[j] += tokEmb[i][j];
        float norm = 0;
        for (int j = 0; j < dim; j++) { vec[j] /= seqLen; norm += vec[j] * vec[j]; }
        norm = (float) Math.sqrt(norm) + 1e-8f;
        for (int j = 0; j < dim; j++) vec[j] /= norm;
        return vec;
    }

    // ==================== Model listing ====================

    public static Map<String, String> listAvailableModels(Path dataDir) {
        Map<String, String> map = new LinkedHashMap<>();
        // Bundled
        for (var e : BUNDLED.entrySet()) {
            map.put(e.getKey(), e.getKey() + " (bundled, " + e.getValue().dims() + " dims)");
        }
        // User custom models
        if (dataDir != null) {
            Path modelsDir = dataDir.resolve("models");
            if (Files.isDirectory(modelsDir)) {
                try (var dirs = Files.list(modelsDir)) {
                    dirs.filter(Files::isDirectory).forEach(dir -> {
                        String name = dir.getFileName().toString();
                        if (BUNDLED.containsKey(name)) return;
                        if (Files.exists(dir.resolve("model.onnx")) && Files.exists(dir.resolve("vocab.txt"))) {
                            map.put(name, name + " (custom)");
                        }
                    });
                } catch (Exception ignored) {}
            }
        }
        return map;
    }
}
