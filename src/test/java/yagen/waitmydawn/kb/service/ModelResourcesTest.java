package yagen.waitmydawn.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class ModelResourcesTest {
    @Test void bundledOnnxProducesStableNormalizedVectors() throws Exception {
        if (getClass().getResource("/models/all-MiniLM-L6-v2/model.onnx") == null) {
            org.junit.jupiter.api.Assumptions.assumeFalse(Boolean.getBoolean("maa.release"));
            return;
        }
        var root = java.nio.file.Files.createTempDirectory(java.nio.file.Path.of("target"), "model-smoke-");
        var model = new LocalEmbeddingModel(root);
        try {
            assertTrue(model.loadModel("all-MiniLM-L6-v2"));
            float[] first = model.embed("Minecraft stone furnace recipe");
            float[] again = model.embed("Minecraft stone furnace recipe");
            assertNotNull(first); assertEquals(384, first.length);
            assertArrayEquals(first, again, 0.000001f);
            double norm = 0;
            for (float value : first) { assertTrue(Float.isFinite(value)); norm += value * value; }
            assertEquals(1, norm, 0.001);
            assertTrue(model.profile().contains("mean-l2"));
        } finally {
            model.close();
            var assets = root.resolve("models/all-MiniLM-L6-v2");
            java.nio.file.Files.deleteIfExists(assets.resolve("model.onnx"));
            java.nio.file.Files.deleteIfExists(assets.resolve("vocab.txt"));
            java.nio.file.Files.deleteIfExists(assets);
            java.nio.file.Files.deleteIfExists(root.resolve("models"));
            java.nio.file.Files.deleteIfExists(root);
        }
    }

    @Test void releaseIncludesLockedModelAssets() throws Exception {
        try (var input = getClass().getResourceAsStream("/embedding-model-lock.json")) {
            assertNotNull(input);
            var lock = new ObjectMapper().readTree(input);
            var files = lock.path("files").fields();
            while (files.hasNext()) {
                var file = files.next();
                try (var asset = getClass().getResourceAsStream("/models/" + lock.path("model").asText() + "/" + file.getKey())) {
                    if (asset == null && !Boolean.getBoolean("maa.release")) continue;
                    assertNotNull(asset, "Release requires verified model asset: " + file.getKey());
                    var digest = MessageDigest.getInstance("SHA-256");
                    byte[] bytes = new byte[65536]; int n;
                    while ((n = asset.read(bytes)) >= 0) digest.update(bytes, 0, n);
                    assertEquals(file.getValue().asText(), HexFormat.of().formatHex(digest.digest()));
                }
            }
        }
    }
}
