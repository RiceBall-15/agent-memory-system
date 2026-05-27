package com.memoryplatform.scorer;

import com.memoryplatform.model.VectorRecord;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;
/**
 * BM25评分器 - 基于词频的文本相似度计算
 * <p>
 * 实现标准BM25算法，支持中文分词（按字切分 + 按词典切分）和停用词过滤。
 * 线程安全，支持并发索引构建和查询。
 * <p>
 * BM25公式: score(Q, D) = Σ IDF(qi) * (f(qi, D) * (k1+1)) / (f(qi, D) + k1 * (1 - b + b * |D|/avgdl))
 * <p>
 * IDF计算: log((N - n + 0.5) / (n + 0.5) + 1)
 *
 * @author MemoryPlatform
 * @since 1.0
 */
@Slf4j
public class Bm25Scorer {

    /** 默认k1参数 - 控制词频饱和度 */
    public static final double DEFAULT_K1 = 1.5;

    /** 默认b参数 - 控制文档长度归一化 */
    public static final double DEFAULT_B = 0.75;

    /** BM25算法参数k1 */
    private final double k1;

    /** BM25算法参数b */
    private final double b;

    /** 文档总数 N */
    private final AtomicLong totalDocCount = new AtomicLong(0);

    /** 平均文档长度 */
    private volatile double avgDocLength = 0.0;

    /**
     * 文档ID -> 文档长度（分词后token数）的映射
     */
    private final ConcurrentHashMap<String, Integer> docLengths = new ConcurrentHashMap<>();

    /**
     * 文档ID -> 分词后token列表的映射
     */
    private final ConcurrentHashMap<String, List<String>> docTokens = new ConcurrentHashMap<>();

    /**
     * 词项 -> 包含该词项的文档数（文档频率）
     */
    private final ConcurrentHashMap<String, Integer> termDocFreq = new ConcurrentHashMap<>();

    /**
     * 词项 -> 包含该词项的文档ID集合
     */
    private final ConcurrentHashMap<String, Set<String>> invertedIndex = new ConcurrentHashMap<>();

    /** 中文正则 - 匹配中文字符 */
    private static final Pattern CHINESE_PATTERN = Pattern.compile("[\\u4e00-\\u9fff]+");

    /** 英文正则 - 匹配英文单词 */
    private static final Pattern ENGLISH_PATTERN = Pattern.compile("[a-zA-Z]+");

    /** 数字正则 - 匹配数字 */
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+");

    /** 基础中文停用词表 */
    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
        "的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都", "一", "一个",
        "上", "也", "很", "到", "说", "要", "去", "你", "会", "着", "没有", "看", "好",
        "自己", "这", "他", "她", "它", "们", "那", "些", "什么", "被", "让", "把", "还",
        "而", "但", "如果", "或", "但", "及", "与", "对", "以", "从", "为", "用",
        "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
        "have", "has", "had", "do", "does", "did", "will", "would", "could",
        "should", "may", "might", "can", "shall", "to", "of", "in", "for",
        "on", "with", "at", "by", "from", "as", "into", "through", "during",
        "before", "after", "above", "below", "between", "out", "off", "over",
        "under", "again", "further", "then", "once", "and", "but", "or", "nor",
        "not", "so", "yet", "both", "each", "few", "more", "most", "other",
        "some", "such", "no", "only", "own", "same", "than", "too", "very",
        "just", "that", "this", "these", "those", "it", "its"
    ));

    /** 默认构造 - 使用标准参数 k1=1.5, b=0.75 */
    public Bm25Scorer() {
        this(DEFAULT_K1, DEFAULT_B);
    }

    /**
     * 自定义参数构造
     *
     * @param k1 k1参数，控制词频饱和度，越大则高频词贡献越大
     * @param b  b参数，控制文档长度归一化，0为不归一化，1为完全归一化
     */
    public Bm25Scorer(double k1, double b) {
        if (k1 < 0) throw new IllegalArgumentException("k1 must be >= 0, got: " + k1);
        if (b < 0 || b > 1) throw new IllegalArgumentException("b must be in [0, 1], got: " + b);
        this.k1 = k1;
        this.b = b;
    }

    /**
     * 构建BM25索引
     * <p>
     * 对文档集进行分词并构建倒排索引。此方法是线程安全的，
     * 支持多次调用以增量添加文档。
     *
     * @param docs 文档列表，每个VectorRecord的text字段作为文档内容
     */
    public synchronized void index(List<VectorRecord> docs) {
        if (docs == null || docs.isEmpty()) {
            log.info("[Bm25Scorer] index: empty doc list, skip")
            return;
        }

        log.info("[Bm25Scorer] index: indexing " + docs.size() + " documents...")

        long totalLength = 0;

        for (VectorRecord doc : docs) {
            if (doc.getId() == null || doc.getText() == null) continue;

            List<String> tokens = tokenize(doc.getText());
            docTokens.put(doc.getId(), tokens);
            docLengths.put(doc.getId(), tokens.size());
            totalLength += tokens.size();

            // 更新倒排索引
            Set<String> uniqueTokens = new HashSet<>(tokens);
            for (String token : uniqueTokens) {
                invertedIndex.computeIfAbsent(token, k -> ConcurrentHashMap.newKeySet()).add(doc.getId());
                termDocFreq.merge(token, 1, Integer::sum);
            }
        }

        long count = totalDocCount.addAndGet(docs.size());
        avgDocLength = (double) totalLength / count;

        log.info("[Bm25Scorer] index: indexed " + count + " docs, avgLen=" +
            String.format("%.2f", avgDocLength) + ", terms=" + termDocFreq.size())
    }

    /**
     * 对单个查询-文档对计算BM25分数
     *
     * @param query 查询文本
     * @param doc   文档文本
     * @return BM25分数，越高越相关
     */
    public double score(String query, String doc) {
        if (query == null || doc == null) return 0.0;

        List<String> queryTokens = tokenize(query);
        List<String> docTokensList = tokenize(doc);
        int docLen = docTokensList.length();

        // 计算文档中每个词的词频
        Map<String, Integer> tf = new HashMap<>();
        for (String token : docTokensList) {
            tf.merge(token, 1, Integer::sum);
        }

        double score = 0.0;
        long N = totalDocCount.get();
        double avgDl = avgDocLength > 0 ? avgDocLength : 1.0;

        for (String qt : queryTokens) {
            int n = termDocFreq.getOrDefault(qt, 0);
            int f = tf.getOrDefault(qt, 0);

            if (f == 0) continue;

            double idf = Math.log((double)(N - n + 0.5) / (n + 0.5) + 1.0);
            double tfNorm = (f * (k1 + 1.0)) / (f + k1 * (1.0 - b + b * docLen / avgDl));
            score += idf * tfNorm;
        }

        return score;
    }

    /**
     * 对查询文本计算BM25分数
     *
     * @param query 查询文本
     * @param docId 文档ID（必须已通过index()索引）
     * @return BM25分数
     */
    public double score(String query, String docId, boolean isDocId) {
        if (!isDocId) return score(query, docId);

        List<String> tokens = docTokens.get(docId);
        if (tokens == null) {
            log.info("[Bm25Scorer] score: docId " + docId + " not found in index")
            return 0.0;
        }

        List<String> queryTokens = tokenize(query);
        int docLen = tokens.size();

        Map<String, Integer> tf = new HashMap<>();
        for (String token : tokens) {
            tf.merge(token, 1, Integer::sum);
        }

        double bm25Score = 0.0;
        long N = totalDocCount.get();
        double avgDl = avgDocLength > 0 ? avgDocLength : 1.0;

        for (String qt : queryTokens) {
            int n = termDocFreq.getOrDefault(qt, 0);
            int f = tf.getOrDefault(qt, 0);

            if (f == 0) continue;

            double idf = Math.log((double)(N - n + 0.5) / (n + 0.5) + 1.0);
            double tfNorm = (f * (k1 + 1.0)) / (f + k1 * (1.0 - b + b * docLen / avgDl));
            bm25Score += idf * tfNorm;
        }

        return bm25Score;
    }

    /**
     * 批量计算查询对多个文档的BM25分数
     *
     * @param query 查询文本
     * @param docs  文档列表（必须已通过index()索引）
     * @return 文档ID到BM25分数的映射
     */
    public synchronized Map<String, Double> scoreQuery(String query, List<VectorRecord> docs) {
        Map<String, Double> scores = new LinkedHashMap<>();

        if (query == null || docs == null || docs.isEmpty()) {
            return scores;
        }

        for (VectorRecord doc : docs) {
            if (doc.getId() == null) continue;

            // 优先从索引中查找已索引的分数
            if (docTokens.containsKey(doc.getId())) {
                scores.put(doc.getId(), score(query, doc.getId(), true));
            } else if (doc.getText() != null) {
                // 如果文档未被索引，直接计算
                scores.put(doc.getId(), score(query, doc.getText()));
            }
        }

        return scores;
    }

    /**
     * 对已索引的文档ID批量计算BM25分数
     *
     * @param query  查询文本
     * @param docIds 文档ID列表
     * @return 文档ID到BM25分数的映射
     */
    public Map<String, Double> scoreQueryByIds(String query, List<String> docIds) {
        Map<String, Double> scores = new LinkedHashMap<>();

        if (query == null || docIds == null || docIds.isEmpty()) {
            return scores;
        }

        for (String docId : docIds) {
            scores.put(docId, score(query, docId, true));
        }

        return scores;
    }

    /**
     * 中文分词 - 按字切分 + 英文按词切分 + 停用词过滤
     * <p>
     * 策略：
     * 1. 中文连续字符按单字切分（简化版，未接入词典）
     * 2. 英文按空格和标点分词
     * 3. 过滤停用词
     * 4. 全部转小写
     *
     * @param text 输入文本
     * @return 分词后的token列表
     */
    public static List<String> tokenize(String text) {
        if (text == null || text.isEmpty()) return Collections.emptyList();

        List<String> tokens = new ArrayList<>();

        // 提取中文字符并逐字切分
        Matcher chineseMatcher = CHINESE_PATTERN.matcher(text);
        while (chineseMatcher.find()) {
            String segment = chineseMatcher.group();
            for (int i = 0; i < segment.length(); i++) {
                String token = String.valueOf(segment.charAt(i));
                if (!STOP_WORDS.contains(token)) {
                    tokens.add(token);
                }
            }
            // 双字组合（bigram）增强语义
            for (int i = 0; i < segment.length() - 1; i++) {
                String bigram = segment.substring(i, i + 2);
                if (!STOP_WORDS.contains(bigram)) {
                    tokens.add(bigram);
                }
            }
        }

        // 提取英文单词
        Matcher englishMatcher = ENGLISH_PATTERN.matcher(text);
        while (englishMatcher.find()) {
            String token = englishMatcher.group().toLowerCase();
            if (!STOP_WORDS.contains(token) && token.length() > 1) {
                tokens.add(token);
            }
        }

        return tokens;
    }

    /**
     * 获取索引统计信息
     *
     * @return 统计信息Map
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalDocs", totalDocCount.get());
        stats.put("avgDocLength", avgDocLength);
        stats.put("totalTerms", termDocFreq.size());
        stats.put("k1", k1);
        stats.put("b", b);
        return stats;
    }

    /**
     * 清空索引
     */
    public synchronized void clear() {
        totalDocCount.set(0);
        avgDocLength = 0.0;
        docLengths.clear();
        docTokens.clear();
        termDocFreq.clear();
        invertedIndex.clear();
        log.info("[Bm25Scorer] clear: index cleared")
    }

    /**
     * 获取当前索引的文档数
     *
     * @return 文档总数
     */
    public long getDocCount() {
        return totalDocCount.get();
    }
}
