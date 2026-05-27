package com.memoryplatform.service;

/**
 * Embedding服务接口 - 文本向量化
 * <p>
 * 预留接口, 支持后续接入各种Embedding模型:
 * <ul>
 *   <li>Ollama本地Embedding (nomic-embed-text)</li>
 *   <li>OpenAI text-embedding-3-small</li>
 *   <li>本地模型 (sentence-transformers)</li>
 *   <li>自定义Embedding服务</li>
 * </ul>
 */
public interface EmbeddingService {

    /**
     * 将单个文本转换为向量
     * @param text 输入文本
     * @return 向量数组 (float[])
     */
    float[] embed(String text);

    /**
     * 批量文本转换为向量
     * @param texts 输入文本列表
     * @return 向量列表
     */
    float[][] embedBatch(String[] texts);

    /**
     * 获取向量维度
     * @return 维度数
     */
    int getDimension();

    /**
     * 健康检查
     * @return 是否可用
     */
    boolean isAvailable();

    /**
     * 默认的空实现 - 返回随机向量 (用于测试)
     */
    static EmbeddingService noOp() {
        return new EmbeddingService() {
            private final int dim = 384;

            @Override
            public float[] embed(String text) {
                float[] vec = new float[dim];
                java.util.Random rng = new java.util.Random(text.hashCode());
                for (int i = 0; i < dim; i++) {
                    vec[i] = rng.nextFloat() * 2 - 1;
                }
                return vec;
            }

            @Override
            public float[][] embedBatch(String[] texts) {
                float[][] result = new float[texts.length][];
                for (int i = 0; i < texts.length; i++) {
                    result[i] = embed(texts[i]);
                }
                return result;
            }

            @Override
            public int getDimension() { return dim; }

            @Override
            public boolean isAvailable() { return true; }
        };
    }
}
