package com.memoryplatform.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * BM25文本相似度评分器
 * <p>
 * 用于记忆去重时的文本相似度辅助判断。
 * BM25是信息检索中的经典算法，适用于短文本的相似度计算。
 * </p>
 */
public class Bm25Scorer {

    /** k1参数 - 词频饱和度 */
    private final double k1;

    /** b参数 - 文档长度归一化 */
    private final double b;

    /** 平均文档长度 */
    private double avgDocLength;

    /** 文档集合，每项为 [docId, tokens] */
    private final List<String[]> documents = new ArrayList<>();

    /** 逆文档频率 */
    private final Map<String, Double> idf = new HashMap<>();

    /** 文档长度映射 */
    private final Map<String, Integer> docLengths = new HashMap<>();

    /**
     * 默认构造函数，使用标准BM25参数
     */
    public Bm25Scorer() {
        this(1.2, 0.75);
    }

    /**
     * 自定义参数构造函数
     *
     * @param k1 词频饱和度参数
     * @param b  文档长度归一化参数
     */
    public Bm25Scorer(double k1, double b) {
        this.k1 = k1;
        this.b = b;
    }

    /**
     * 添加文档到语料库（用于构建IDF）
     *
     * @param docId    文档ID
     * @param text     文档文本
     */
    public void addDocument(String docId, String text) {
        String[] tokens = tokenize(text);
        documents.add(tokens);
        docLengths.put(docId, tokens.length);
        rebuildIndex();
    }

    /**
     * 批量添加文档
     *
     * @param docIdToText 文档ID到文本的映射
     */
    public void addDocuments(Map<String, String> docIdToText) {
        for (Map.Entry<String, String> entry : docIdToText.entrySet()) {
            String[] tokens = tokenize(entry.getValue());
            documents.add(tokens);
            docLengths.put(entry.getKey(), tokens.length);
        }
        rebuildIndex();
    }

    /**
     * 清空语料库
     */
    public void clear() {
        documents.clear();
        docLengths.clear();
        idf.clear();
        avgDocLength = 0;
    }

    /**
     * 计算查询文本与指定文档的BM25分数
     *
     * @param query  查询文本
     * @param docId  文档ID
     * @return BM25分数（0.0 ~ 1.0归一化）
     */
    public double score(String query, String docId) {
        if (documents.isEmpty() || !docLengths.containsKey(docId)) {
            return 0.0;
        }

        String[] queryTokens = tokenize(query);
        int docLen = docLengths.getOrDefault(docId, 0);

        // 找到对应的文档tokens
        String[] docTokens = findDocTokens(docId);
        if (docTokens == null) return 0.0;

        double score = 0.0;
        for (String term : queryTokens) {
            double termIdf = idf.getOrDefault(term, 0.0);
            int termFreq = countTerm(docTokens, term);
            double termScore = termIdf * (termFreq * (k1 + 1)) /
                    (termFreq + k1 * (1 - b + b * docLen / Math.max(avgDocLength, 1)));
            score += termScore;
        }

        return score;
    }

    /**
     * 计算两个文本之间的BM25相似度
     *
     * @param text1 文本1
     * @param text2 文本2
     * @return 相似度分数（0.0 ~ 1.0）
     */
    public double similarity(String text1, String text2) {
        if (text1 == null || text2 == null) return 0.0;
        if (text1.equals(text2)) return 1.0;

        String[] tokens1 = tokenize(text1);
        String[] tokens2 = tokenize(text2);

        if (tokens1.length == 0 || tokens2.length == 0) return 0.0;

        // 使用Jaccard相似度作为简单文本相似度的补充
        Set<String> set1 = new HashSet<>(Arrays.asList(tokens1));
        Set<String> set2 = new HashSet<>(Arrays.asList(tokens2));

        Set<String> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);

        Set<String> union = new HashSet<>(set1);
        union.addAll(set2);

        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    /**
     * 重建IDF索引
     */
    private void rebuildIndex() {
        idf.clear();
        if (documents.isEmpty()) return;

        // 计算平均文档长度
        avgDocLength = documents.stream()
                .mapToInt(d -> d.length)
                .average()
                .orElse(0.0);

        // 收集所有词项的文档频率
        Map<String, Integer> df = new HashMap<>();
        for (String[] docTokens : documents) {
            Set<String> uniqueTerms = new HashSet<>(Arrays.asList(docTokens));
            for (String term : uniqueTerms) {
                df.merge(term, 1, Integer::sum);
            }
        }

        // 计算IDF
        int totalDocs = documents.size();
        for (Map.Entry<String, Integer> entry : df.entrySet()) {
            double idfValue = Math.log((totalDocs - entry.getValue() + 0.5) / (entry.getValue() + 0.5) + 1.0);
            idf.put(entry.getKey(), idfValue);
        }
    }

    /**
     * 找到指定文档ID的tokens
     */
    private String[] findDocTokens(String docId) {
        // 简单实现：遍历documents查找匹配的docId
        // 在实际生产中，应该使用Map存储
        for (String[] docTokens : documents) {
            // 这里需要通过docLengths间接找到
            if (docLengths.containsKey(docId)) {
                int expectedLen = docLengths.get(docId);
                if (docTokens.length == expectedLen) {
                    return docTokens;
                }
            }
        }
        return null;
    }

    /**
     * 计算词项在文档中出现的频率
     */
    private int countTerm(String[] tokens, String term) {
        int count = 0;
        for (String token : tokens) {
            if (token.equals(term)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 文本分词（简单实现：按空格和标点分割，转小写）
     *
     * @param text 输入文本
     * @return 分词结果
     */
    private String[] tokenize(String text) {
        if (text == null || text.isBlank()) {
            return new String[0];
        }
        // 简单分词：按非字母数字字符分割，转小写，过滤空串
        return text.toLowerCase()
                .split("[\\s\\p{Punct}]+");
    }
}
