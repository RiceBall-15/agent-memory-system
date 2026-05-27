package com.memoryplatform.scorer;

import com.memoryplatform.model.VectorRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BM25评分器单元测试
 */
class Bm25ScorerTest {

    @Test
    void testScoreWithIndexedDocuments() {
        Bm25Scorer scorer = new Bm25Scorer();

        List<VectorRecord> docs = List.of(
            VectorRecord.builder().id("d1").text("Java编程语言非常流行").userId("u1").build(),
            VectorRecord.builder().id("d2").text("Python也是热门语言").userId("u1").build(),
            VectorRecord.builder().id("d3").text("Go语言适合并发编程").userId("u1").build()
        );
        scorer.index(docs);

        // "Java"只出现在d1中，d1应得高分
        double score1 = scorer.score("Java", "Java编程语言非常流行");
        double score2 = scorer.score("Java", "Python也是热门语言");

        assertTrue(score1 > 0, "包含查询词的文档应有正分");
        assertTrue(score2 == 0, "不包含查询词的文档应得0分");
        assertTrue(score1 > score2, "包含查询词的文档分数应更高");
    }

    @Test
    void testTokenizeChinese() {
        List<String> tokens = Bm25Scorer.tokenize("人工智能");
        // 中文逐字切分 + bigram: 人, 工, 智, 能, 人工, 工智, 智能
        assertTrue(tokens.contains("人"), "应包含单字'人'");
        assertTrue(tokens.contains("工"), "应包含单字'工'");
        assertTrue(tokens.contains("智能"), "应包含bigram'智能'");
    }

    @Test
    void testTokenizeEnglish() {
        List<String> tokens = Bm25Scorer.tokenize("machine learning is great");
        assertTrue(tokens.contains("machine"), "应包含'machine'");
        assertTrue(tokens.contains("learning"), "应包含'learning'");
        assertTrue(tokens.contains("great"), "应包含'great'");
        // 停用词应被过滤
        assertFalse(tokens.contains("is"), "停用词'is'应被过滤");
    }

    @Test
    void testTokenizeStopWordsFiltering() {
        List<String> tokens = Bm25Scorer.tokenize("the a an is are was");
        // 所有词都是停用词，应全部被过滤（英文要求长度>1，但停用词本身也会被过滤）
        assertTrue(tokens.isEmpty() || tokens.stream().noneMatch(t ->
            t.equals("the") || t.equals("a") || t.equals("an") || t.equals("is")),
            "停用词应被过滤"
        );
    }

    @Test
    void testTokenizeNullAndEmpty() {
        assertTrue(Bm25Scorer.tokenize(null).isEmpty());
        assertTrue(Bm25Scorer.tokenize("").isEmpty());
    }

    @Test
    void testScoreQueryBatch() {
        Bm25Scorer scorer = new Bm25Scorer();
        List<VectorRecord> docs = List.of(
            VectorRecord.builder().id("d1").text("机器学习算法").userId("u1").build(),
            VectorRecord.builder().id("d2").text("深度学习框架").userId("u1").build(),
            VectorRecord.builder().id("d3").text("数据库索引优化").userId("u1").build()
        );
        scorer.index(docs);

        Map<String, Double> scores = scorer.scoreQuery("学习", docs);
        assertEquals(3, scores.size());
        // d1和d2包含"学习"，d3不包含
        assertTrue(scores.get("d1") > 0, "d1应有正分");
        assertTrue(scores.get("d2") > 0, "d2应有正分");
        assertEquals(0.0, scores.get("d3"), 0.001, "d3应为0分");
    }

    @Test
    void testIndexAndClear() {
        Bm25Scorer scorer = new Bm25Scorer();
        List<VectorRecord> docs = List.of(
            VectorRecord.builder().id("d1").text("测试文档").userId("u1").build()
        );
        scorer.index(docs);
        assertEquals(1, scorer.getDocCount());

        scorer.clear();
        assertEquals(0, scorer.getDocCount());
    }

    @Test
    void testStats() {
        Bm25Scorer scorer = new Bm25Scorer();
        List<VectorRecord> docs = List.of(
            VectorRecord.builder().id("d1").text("hello world").userId("u1").build(),
            VectorRecord.builder().id("d2").text("hello java").userId("u1").build()
        );
        scorer.index(docs);

        Map<String, Object> stats = scorer.getStats();
        assertEquals(2L, stats.get("totalDocs"));
        assertEquals(Bm25Scorer.DEFAULT_K1, stats.get("k1"));
        assertEquals(Bm25Scorer.DEFAULT_B, stats.get("b"));
        assertTrue((double) stats.get("avgDocLength") > 0);
    }

    @Test
    void testInvalidParams() {
        assertThrows(IllegalArgumentException.class, () -> new Bm25Scorer(-1, 0.75));
        assertThrows(IllegalArgumentException.class, () -> new Bm25Scorer(1.5, -0.1));
        assertThrows(IllegalArgumentException.class, () -> new Bm25Scorer(1.5, 1.5));
    }

    @Test
    void testScoreNullInputs() {
        Bm25Scorer scorer = new Bm25Scorer();
        assertEquals(0.0, scorer.score(null, "doc"));
        assertEquals(0.0, scorer.score("query", null));
    }
}
