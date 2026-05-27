package com.memoryplatform.scorer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FusionScorer融合评分器单元测试
 */
class FusionScorerTest {

    @Test
    void testDefaultWeightsFuseNormalized() {
        FusionScorer scorer = new FusionScorer();

        // 所有输入为1.0，融合结果应为1.0
        double result = scorer.fuseNormalized(1.0, 1.0, 1.0);
        assertEquals(1.0, result, 0.001);

        // 所有输入为0.0，融合结果应为0.0
        result = scorer.fuseNormalized(0.0, 0.0, 0.0);
        assertEquals(0.0, result, 0.001);
    }

    @Test
    void testFuseWithNormalization() {
        FusionScorer scorer = new FusionScorer();

        // semantic=1.0, bm25=10.0(归一化为1.0), entity=1.0, bm25Max=10.0
        double result = scorer.fuse(1.0, 10.0, 1.0, 10.0);
        assertEquals(1.0, result, 0.001);

        // semantic=0.5, bm25=5.0(归一化为0.5), entity=0.5, bm25Max=10.0
        // = 0.45*0.5 + 0.25*0.5 + 0.30*0.5 = 0.5
        result = scorer.fuse(0.5, 5.0, 0.5, 10.0);
        assertEquals(0.5, result, 0.001);
    }

    @Test
    void testFuseClampsOutOfRangeValues() {
        FusionScorer scorer = new FusionScorer();

        // 超过1.0应被clamp到1.0
        double result = scorer.fuseNormalized(2.0, 2.0, 2.0);
        assertEquals(1.0, result, 0.001);

        // 负值应被clamp到0.0
        result = scorer.fuseNormalized(-1.0, -1.0, -1.0);
        assertEquals(0.0, result, 0.001);
    }

    @Test
    void testCustomWeights() {
        FusionScorer scorer = new FusionScorer(0.5, 0.3, 0.2);

        FusionScorer.Weights w = scorer.getWeights();
        assertEquals(0.5, w.semantic, 0.001);
        assertEquals(0.3, w.bm25, 0.001);
        assertEquals(0.2, w.entity, 0.001);
        assertTrue(w.isValid());
    }

    @Test
    void testInvalidWeightsSum() {
        assertThrows(IllegalArgumentException.class, () ->
            new FusionScorer(0.5, 0.5, 0.5) // sum = 1.5
        );
    }

    @Test
    void testSetWeightsDynamically() {
        FusionScorer scorer = new FusionScorer();

        // 初始权重
        assertEquals(0, scorer.getWeightChangeCount());

        // 动态调整权重
        scorer.setWeights(0.6, 0.2, 0.2);
        assertEquals(1, scorer.getWeightChangeCount());
        assertEquals(0.6, scorer.getWeights().semantic, 0.001);

        // 再次调整
        scorer.setWeights(0.4, 0.4, 0.2);
        assertEquals(2, scorer.getWeightChangeCount());

        // 验证权重变更历史
        assertEquals(2, scorer.getWeightHistory().size());
    }

    @Test
    void testSetInvalidWeightsThrows() {
        FusionScorer scorer = new FusionScorer();
        assertThrows(IllegalArgumentException.class, () ->
            scorer.setWeights(0.9, 0.9, 0.9) // sum = 2.7
        );
    }

    @Test
    void testNormalizeBatch() {
        double[] scores = {1.0, 2.0, 3.0, 4.0, 5.0};
        double[] normalized = FusionScorer.normalizeBatch(scores);

        assertEquals(5, normalized.length);
        assertEquals(0.0, normalized[0], 0.001);   // min -> 0
        assertEquals(1.0, normalized[4], 0.001);   // max -> 1
        assertEquals(0.5, normalized[2], 0.001);   // mid -> 0.5
    }

    @Test
    void testNormalizeBatchAllSame() {
        double[] scores = {3.0, 3.0, 3.0};
        double[] normalized = FusionScorer.normalizeBatch(scores);

        assertEquals(3, normalized.length);
        for (double v : normalized) {
            assertEquals(0.5, v, 0.001, "全部相同分数应归一化为0.5");
        }
    }

    @Test
    void testNormalizeBatchNullAndEmpty() {
        assertEquals(0, FusionScorer.normalizeBatch(null).length);
        assertEquals(0, FusionScorer.normalizeBatch(new double[0]).length);
    }

    @Test
    void testWeightsIsValid() {
        FusionScorer.Weights valid = new FusionScorer.Weights(0.4, 0.3, 0.3);
        assertTrue(valid.isValid());

        FusionScorer.Weights almostValid = new FusionScorer.Weights(0.4, 0.3, 0.299);
        assertTrue(almostValid.isValid()); // 差0.001 < 0.01

        FusionScorer.Weights invalid = new FusionScorer.Weights(0.4, 0.3, 0.2);
        assertFalse(invalid.isValid()); // sum=0.9, diff=0.1 > 0.01
    }
}
