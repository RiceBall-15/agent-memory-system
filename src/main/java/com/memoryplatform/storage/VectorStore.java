package com.memoryplatform.storage;

import com.memoryplatform.model.VectorRecord;
import com.memoryplatform.model.SearchResult;
import java.util.List;
import java.util.Map;

/**
 * 向量存储统一接口 - 所有向量库适配器实现此接口
 */
public interface VectorStore {

    /**
     * 初始化存储连接
     */
    default void init(Map<String, Object> config) {}

    /**
     * 创建集合/索引
     * @param name 集合名称
     * @param dimension 向量维度
     * @param metric 距离度量 (L2, IP, COSINE)
     * @return 是否成功
     */
    boolean createCollection(String name, int dimension, String metric);

    /**
     * 批量写入向量
     * @param collection 集合名称
     * @param records 向量记录列表
     * @return 是否成功
     */
    boolean upsert(String collection, List<VectorRecord> records);

    /**
     * 向量搜索
     * @param collection 集合名称
     * @param queryVector 查询向量
     * @param topK 返回数量
     * @param filters 过滤条件
     * @return 搜索结果列表
     */
    List<SearchResult> search(String collection, float[] queryVector, 
                             int topK, Map<String, Object> filters);

    /**
     * 删除向量
     * @param collection 集合名称
     * @param ids 要删除的ID列表
     * @return 是否成功
     */
    boolean delete(String collection, List<String> ids);

    /**
     * 获取向量
     * @param collection 集合名称
     * @param ids ID列表
     * @return 向量记录列表
     */
    List<VectorRecord> get(String collection, List<String> ids);

    /**
     * 健康检查
     * @return 是否健康
     */
    boolean healthCheck();

    /**
     * 获取统计信息
     * @param collection 集合名称
     * @return 统计信息
     */
    Map<String, Object> getStats(String collection);
}
