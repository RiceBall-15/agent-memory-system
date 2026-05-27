package com.memoryplatform.scorer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import lombok.extern.slf4j.Slf4j;
/**
 * 融合评分器 - 多信号分数融合
 * <p>
 * 融合三路信号: semanticScore（语义相似度）, bm25Score（关键词匹配）, entityBoost（实体关联增强）
 * <p>
 * 融合公式: finalScore = W_SEM * semantic + W_BM25 * bm25 + W_ENTITY * entityBoost
 * <p>
 * 默认权重: W_SEM=0.45, W_BM25=0.25, W_ENTITY=0.30
 * <p>
 * 特性：
 * <ul>
 *   <li>支持动态权重调整（运行时热更新）</li>
 *   <li>归一化：将不同量纲的分数归一化到[0,1]</li>
 *   <li>线程安全：权重更新使用AtomicReference</li>
 * </ul>
 *
 * @author MemoryPlatform
 * @since 1.0
 */
@Slf4j
public class FusionScorer {

    /** 默认语义权重 */
    public static final double DEFAULT_W_SEMANTIC = 0.45;

    /** 默认BM25权重 */
    public static final double DEFAULT_W_BM25 = 0.25;

    /** 默认实体boost权重 */
    public static final double DEFAULT_W_ENTITY = 0.30;

    /**
     * 权重配置 - 不可变对象，通过AtomicReference实现线程安全的热更新
     */
    public static class Weights {
        public final double semantic;
        public final double bm25;
        public final double entity;

        public Weights(double semantic, double bm25, double entity) {
            this.semantic = semantic;
            this.bm25 = bm25;
            this.entity = entity;
        }

        /** 验证权重和是否接近1.0 */
        public boolean isValid() {
            double sum = semantic + bm25 + entity;
            return Math.abs(sum - 1.0) < 0.01;
        }
    }

    /** 当前权重配置（线程安全） */
    private final AtomicReference<Weights> weightsRef;

    /** 权重变更历史记录 */
    private final ConcurrentHashMap<Long, Weights> weightHistory = new ConcurrentHashMap<>();

    /** 权重变更计数器 */
    private long weightChangeCount = 0;

    /** 默认构造 - 使用标准权重 */
    public FusionScorer() {
        this(DEFAULT_W_SEMANTIC, DEFAULT_W_BM25, DEFAULT_W_ENTITY);
    }

    /**
     * 自定义权重构造
     *
     * @param wSemantic 语义分数权重
     * @param wBm25     BM25分数权重
     * @param wEntity   实体boost权重
     * @throws IllegalArgumentException 如果权重和不接近1.0
     */
    public FusionScorer(double wSemantic, double wBm25, double wEntity) {
        Weights w = new Weights(wSemantic, wBm25, wEntity);
        if (!w.isValid()) {
            throw new IllegalArgumentException(
                String.format("Weights must sum to 1.0 (±0.01), got: %.4f + %.4f + %.4f = %.4f",
                    wSemantic, wBm25, wEntity, wSemantic + wBm25 + wEntity));
        }
        this.weightsRef = new AtomicReference<>(w);
        log.info("[FusionScorer] init: weights sem=" + wSemantic +
            " bm25=" + wBm25 + " entity=" + wEntity)
    }

    /**
     * 融合三路分数为最终得分
     *
     * @param semanticScore  语义相似度分数（通常为[0,1]或余弦距离）
     * @param bm25Score      BM25分数（通常为非负数，无固定上界）
     * @param entityBoost    实体关联增强分数（通常为[0,1]）
     * @param bm25Max        BM25最大值（用于归一化）
     * @return 融合后的最终分数 [0, 1]
     */
    public double fuse(double semanticScore, double bm25Score, double entityBoost, double bm25Max) {
        Weights w = weightsRef.get();

        // 归一化: semanticScore 假设已在[0,1]
        double normSemantic = clamp01(semanticScore);

        // 归一化: bm25Score 通过最大值归一化到[0,1]
        double normBm25 = bm25Max > 0 ? clamp01(bm25Score / bm25Max) : 0.0;

        // 归一化: entityBoost 假设已在[0,1]
        double normEntity = clamp01(entityBoost);

        double finalScore = w.semantic * normSemantic + w.bm25 * normBm25 + w.entity * normEntity;

        return clamp01(finalScore);
    }

    /**
     * 融合三路分数（预归一化版本）
     * <p>
     * 假设所有输入分数已在[0,1]范围内。
     *
     * @param semanticScore  归一化后的语义分数 [0,1]
     * @param bm25Score      归一化后的BM25分数 [0,1]
     * @param entityBoost    归一化后的实体boost [0,1]
     * @return 融合后的最终分数 [0, 1]
     */
    public double fuseNormalized(double semanticScore, double bm25Score, double entityBoost) {
        Weights w = weightsRef.get();

        double finalScore = w.semantic * clamp01(semanticScore)
                          + w.bm25 * clamp01(bm25Score)
                          + w.entity * clamp01(entityBoost);

        return clamp01(finalScore);
    }

    /**
     * 批量归一化分数 - Min-Max归一化
     * <p>
     * 将一组分数归一化到[0,1]范围。如果最大值等于最小值，则所有分数归一化为0.5。
     *
     * @param scores 原始分数数组
     * @return 归一化后的分数数组
     */
    public static double[] normalizeBatch(double[] scores) {
        if (scores == null || scores.length == 0) return new double[0];

        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;
        for (double s : scores) {
            if (s < min) min = s;
            if (s > max) max = s;
        }

        double[] result = new double[scores.length];
        double range = max - min;

        if (range < 1e-10) {
            // 所有分数相同，返回0.5
            Arrays.fill(result, 0.5);
        } else {
            for (int i = 0; i < scores.length; i++) {
                result[i] = (scores[i] - min) / range;
            }
        }

        return result;
    }

    /**
     * 动态调整权重
     *
     * @param newWeights 新的权重配置
     * @throws IllegalArgumentException 如果权重和不接近1.0
     */
    public void setWeights(double wSemantic, double wBm25, double wEntity) {
        Weights newW = new Weights(wSemantic, wBm25, wEntity);
        if (!newW.isValid()) {
            throw new IllegalArgumentException(
                String.format("Weights must sum to 1.0 (±0.01), got: %.4f", wSemantic + wBm25 + wEntity));
        }
        weightsRef.set(newW);
        weightChangeCount++;
        weightHistory.put(weightChangeCount, newW);

        log.info("[FusionScorer] setWeights: #" + weightChangeCount +
            " sem=" + wSemantic + " bm25=" + wBm25 + " entity=" + wEntity)
    }

    /**
     * 获取当前权重配置
     *
     * @return 当前权重
     */
    public Weights getWeights() {
        return weightsRef.get();
    }

    /**
     * 获取权重变更历史
     *
     * @return 权重历史Map（变更序号 -> 权重配置）
     */
    public Map<Long, Weights> getWeightHistory() {
        return Map.copyOf(weightHistory);
    }

    /**
     * 获取权重变更次数
     *
     * @return 变更次数
     */
    public long getWeightChangeCount() {
        return weightChangeCount;
    }

    /**
     * 将值限制在[0,1]范围内
     */
    private static double clamp01(double value) {
        if (value < 0.0) return 0.0;
        if (value > 1.0) return 1.0;
        return value;
    }
}
