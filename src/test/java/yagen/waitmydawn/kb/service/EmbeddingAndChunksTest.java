package yagen.waitmydawn.kb.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EmbeddingAndChunksTest {
    @TempDir Path temp;

    @Test void fallbackIsStableAcrossBatchesInstancesAndLocales() {
        var first = new EmbeddingService(null);
        float[] expected = first.embed("中文 Alpha ICE");
        first.embedBatch(List.of("unrelated", "different corpus"));
        assertArrayEquals(expected, first.embed("中文 Alpha ICE"));
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertArrayEquals(expected, new EmbeddingService(null).embed("中文 Alpha ICE"));
        } finally { Locale.setDefault(previous); }
    }

    @Test void sourceIdentityAndChunkBoundariesArePreserved() {
        var chunker = new TextChunker();
        String text = "知识库文本".repeat(200);
        var a = chunker.fromWebPage(text, "minecraft", "Minecraft", "mcwiki", "https://example.invalid/a");
        var b = chunker.fromWebPage(text, "minecraft", "Minecraft", "mcwiki", "https://example.invalid/b");
        assertNotEquals(a.getFirst().id(), b.getFirst().id());
        assertTrue(a.stream().allMatch(c -> c.text().length() <= 180));
        assertTrue(a.size() > 1);
        assertFalse(chunker.fromLangJson("{\"block.minecraft.beacon\":\"信标方块中文名称用于知识检索与准确实体识别\"}",
                "minecraft", "Minecraft", "zh_cn.json").isEmpty());
    }

    @Test void truncatedWordPiecesKeepSepAndUnknownWordsRemainAtomic() throws Exception {
        Path vocab = temp.resolve("vocab.txt");
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 103; i++) tokens.add("unused" + i);
        tokens.set(100, "[UNK]"); tokens.set(101, "[CLS]"); tokens.set(102, "[SEP]");
        tokens.add("a"); tokens.add("##a");
        Files.write(vocab, tokens);
        var tokenizer = new WordPieceTokenizer(vocab);
        assertArrayEquals(new long[]{101, 100, 102}, tokenizer.encode("ab", 10));
        long[] encoded = tokenizer.encode("aaaaaa", 4);
        assertEquals(4, encoded.length); assertEquals(102, encoded[3]);
    }
}
