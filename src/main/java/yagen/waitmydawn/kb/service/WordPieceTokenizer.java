package yagen.waitmydawn.kb.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Minimal BERT WordPiece tokenizer.
 * 特殊 Token ID 来自词表，适用于兼容 BERT 的小写 WordPiece 模型。
 */
public class WordPieceTokenizer {

    private final Map<String, Integer> vocab = new HashMap<>(32000);
    private final Map<Integer, String> idToToken = new HashMap<>(32000);
    private final int UNK_ID;
    private final int CLS_ID;
    private final int SEP_ID;

    public WordPieceTokenizer(Path vocabFile) throws IOException {
        try (BufferedReader br = Files.newBufferedReader(vocabFile)) {
            String line;
            int id = 0;
            while ((line = br.readLine()) != null) {
                String token = line.trim();
                vocab.put(token, id);
                idToToken.put(id, token);
                id++;
            }
        }
        for (String special : List.of("[UNK]", "[CLS]", "[SEP]"))
            if (!vocab.containsKey(special)) throw new IOException("Missing tokenizer special token: " + special);
        UNK_ID = vocab.get("[UNK]"); CLS_ID = vocab.get("[CLS]"); SEP_ID = vocab.get("[SEP]");
    }

    /**
     * Encode text to token IDs.
     */
    public long[] encode(String text, int maxLen) {
        if (maxLen < 2) throw new IllegalArgumentException("Token limit must include CLS and SEP");
        List<Integer> tokens = new ArrayList<>();
        tokens.add(CLS_ID);

        // Split and tokenize
        for (String word : basicTokenize(text)) {
            wordTokenize(word, tokens);
            if (tokens.size() >= maxLen - 1) break;
        }

        if (tokens.size() >= maxLen) tokens = new ArrayList<>(tokens.subList(0, maxLen - 1));
        tokens.add(SEP_ID);

        long[] ids = new long[tokens.size()];
        for (int i = 0; i < tokens.size(); i++) ids[i] = tokens.get(i);
        return ids;
    }

    /** Simple whitespace + punctuation split */
    private List<String> basicTokenize(String text) {
        List<String> words = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || isPunctuation(c)) {
                if (!buf.isEmpty()) { words.add(buf.toString().toLowerCase(Locale.ROOT)); buf.setLength(0); }
                // Add punctuation as a separate token if it's in vocab
                String punct = String.valueOf(c);
                if (vocab.containsKey(punct)) words.add(punct);
            } else if (isCJK(c)) {
                if (!buf.isEmpty()) { words.add(buf.toString().toLowerCase(Locale.ROOT)); buf.setLength(0); }
                // CJK chars as individual tokens or try bigrams
                words.add(String.valueOf(c));
            } else {
                buf.append(c);
            }
        }
        if (!buf.isEmpty()) words.add(buf.toString().toLowerCase(Locale.ROOT));
        return words;
    }

    /** Greedy longest-match-first subword tokenization */
    private void wordTokenize(String word, List<Integer> tokenIds) {
        if (word.isEmpty()) return;

        // Direct match?
        if (vocab.containsKey(word)) {
            tokenIds.add(vocab.get(word));
            return;
        }

        // Try subword split
        int initialSize = tokenIds.size();
        int start = 0;
        while (start < word.length()) {
            int end = word.length();
            String sub = null;
            while (end > start) {
                String candidate = start == 0 ? word.substring(start, end)
                        : "##" + word.substring(start, end);
                if (vocab.containsKey(candidate)) {
                    sub = candidate;
                    break;
                }
                end--;
            }
            if (sub == null) {
                tokenIds.subList(initialSize, tokenIds.size()).clear();
                tokenIds.add(UNK_ID);
                break;
            }
            tokenIds.add(vocab.get(sub));
            start = end;
        }
    }

    private boolean isPunctuation(char c) { return ",.!?;:()[]{}\"'".indexOf(c) >= 0; }

    private boolean isCJK(char c) {
        return Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A;
    }
}
