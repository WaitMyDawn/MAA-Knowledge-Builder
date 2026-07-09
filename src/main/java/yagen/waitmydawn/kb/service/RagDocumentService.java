package yagen.waitmydawn.kb.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 文档分块 + 简易 TF-IDF 语义检索。
 * 使用 bigram 处理中文文本，unigram 处理英文。
 */
public class RagDocumentService {

    private static final Logger log = LoggerFactory.getLogger(RagDocumentService.class);

    private final Map<String, Map<String, Double>> tfidfIndex = new HashMap<>(); // docId → term → weight
    private final Map<String, Double> idf = new HashMap<>();                       // term → idf
    private final Map<String, String> docStore = new HashMap<>();                  // docId → full text
    private final Map<String, String> docTitle = new HashMap<>();                  // docId → title
    private int totalDocs = 0;

    private static final int MAX_CHUNK_SIZE = 1500;
    private static final int MIN_CHUNK_SIZE = 300;

    /**
     * 将长文本分块并索引。
     */
    public void addDocument(String docId, String title, String text) {
        List<String> chunks = chunk(text);
        for (int i = 0; i < chunks.size(); i++) {
            String chunkId = docId + "#" + i;
            Map<String, Double> tf = computeTf(chunks.get(i));
            tfidfIndex.put(chunkId, tf);
            docStore.put(chunkId, chunks.get(i));
            docTitle.put(chunkId, title);
            totalDocs++;

            // 更新 IDF (增量)
            for (String term : tf.keySet()) {
                idf.merge(term, 1.0, Double::sum);
            }
        }
        log.debug("Indexed doc {} with {} chunks", docId, chunks.size());
    }

    /**
     * TF-IDF 语义搜索，返回最相关的文档片段。
     * @param query 查询文本
     * @param topK 返回数量
     * @return docId → similarity score
     */
    public List<Map.Entry<String, Double>> search(String query, int topK) {
        Map<String, Double> queryTf = computeTf(query);
        Map<String, Double> scores = new LinkedHashMap<>();

        for (Map.Entry<String, Map<String, Double>> entry : tfidfIndex.entrySet()) {
            String docId = entry.getKey();
            Map<String, Double> docTf = entry.getValue();
            double sim = cosineSimilarity(queryTf, docTf);
            scores.put(docId, sim);
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .filter(e -> e.getValue() > 0.01)
                .limit(topK)
                .toList();
    }

    /** 获取文档文本 */
    public String getDocText(String docId) { return docStore.get(docId); }

    /** 获取文档标题 */
    public String getDocTitle(String docId) { return docTitle.get(docId); }

    // --- 内部 ---

    /** 文本分块: 尽量在句子边界断开 */
    private List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) return chunks;

        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            buf.append(c);

            // 在句号/换行处且大小合适时截断
            boolean isBreak = c == '\n' || c == '.' || c == '。' || c == '！' || c == '？'
                    || c == '!' || c == '?' || c == '；' || c == ';';
            if (isBreak && buf.length() >= MIN_CHUNK_SIZE) {
                chunks.add(buf.toString().trim());
                buf.setLength(0);
            } else if (buf.length() >= MAX_CHUNK_SIZE) {
                chunks.add(buf.toString().trim());
                buf.setLength(0);
            }
        }
        if (!buf.isEmpty()) chunks.add(buf.toString().trim());

        return chunks;
    }

    /** 计算词频 (TF) */
    private Map<String, Double> computeTf(String text) {
        Map<String, Double> tf = new LinkedHashMap<>();
        List<String> tokens = tokenize(text);
        for (String t : tokens) {
            tf.merge(t, 1.0, Double::sum);
        }
        // 归一化
        double norm = tokens.size() + 1;
        for (Map.Entry<String, Double> e : tf.entrySet()) {
            e.setValue(e.getValue() / norm);
        }
        return tf;
    }

    /** 分词: 中文 bigram + 英文 unigram */
    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder englishWord = new StringBuilder();
        StringBuilder chineseSeq = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                // 英文字母 → 累积
                if (!chineseSeq.isEmpty()) {
                    addBigrams(tokens, chineseSeq.toString());
                    chineseSeq.setLength(0);
                }
                englishWord.append(c);
            } else if (isCJK(c)) {
                if (!englishWord.isEmpty()) {
                    tokens.add(englishWord.toString().toLowerCase());
                    englishWord.setLength(0);
                }
                chineseSeq.append(c);
            } else {
                if (!englishWord.isEmpty()) {
                    tokens.add(englishWord.toString().toLowerCase());
                    englishWord.setLength(0);
                }
                if (!chineseSeq.isEmpty()) {
                    addBigrams(tokens, chineseSeq.toString());
                    chineseSeq.setLength(0);
                }
            }
        }
        if (!englishWord.isEmpty()) tokens.add(englishWord.toString().toLowerCase());
        if (!chineseSeq.isEmpty()) addBigrams(tokens, chineseSeq.toString());

        return tokens;
    }

    private void addBigrams(List<String> tokens, String seq) {
        if (seq.length() <= 2) {
            tokens.add(seq);
            return;
        }
        for (int i = 0; i < seq.length() - 1; i++) {
            tokens.add(seq.substring(i, i + 2));
        }
    }

    private boolean isCJK(char c) {
        return Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION;
    }

    /** Cosine similarity between query TF and doc TF (with IDF weighting) */
    private double cosineSimilarity(Map<String, Double> queryTf, Map<String, Double> docTf) {
        double dot = 0, normQ = 0, normD = 0;

        for (Map.Entry<String, Double> e : queryTf.entrySet()) {
            double q = e.getValue() * getWeight(e.getKey());
            double d = docTf.getOrDefault(e.getKey(), 0.0) * getWeight(e.getKey());
            dot += q * d;
            normQ += q * q;
        }
        for (Map.Entry<String, Double> e : docTf.entrySet()) {
            double d = e.getValue() * getWeight(e.getKey());
            normD += d * d;
        }

        if (normQ == 0 || normD == 0) return 0;
        return dot / (Math.sqrt(normQ) * Math.sqrt(normD));
    }

    private double getWeight(String term) {
        double df = idf.getOrDefault(term, 1.0);
        return Math.log((totalDocs + 1) / (df + 1)) + 1;
    }
}
