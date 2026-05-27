package com.memoryplatform.storage;

import com.memoryplatform.model.VectorRecord;
import com.memoryplatform.model.SearchResult;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * 向量存储统一接口 - 所有向量库适配器实现此接口
 * <p>
 * 支持SPI自动发现和Spring动态注册，实现Plugin化存储层。
 * 所有方法提供default实现，增强容错性——未实现的方法返回安全默认值而非抛出异常。
 * </p>
 *
 * @author Agent Memory Platform
 * @since 1.0
 */
@Slf4j
public interface VectorStore {

    /**
     * 获取存储名称（用于SPI发现和注册表键）
     * <p>默认返回实现类的简单类名。</p>
     */
    default String getStoreName() {
        return getClass().getSimpleName();
    }

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
    default boolean createCollection(String name, int dimension, String metric) {
        log.error("[VectorStore] createCollection not implemented by " + getClass().getSimpleName());
        return false;
    }

    /**
     * 批量写入向量
     * @param collection 集合名称
     * @param records 向量记录列表
     * @return 是否成功
     */
    default boolean upsert(String collection, List<VectorRecord> records) {
        log.error("[VectorStore] upsert not implemented by " + getClass().getSimpleName());
        return false;
    }

    /**
     * 向量搜索
     * @param collection 集合名称
     * @param queryVector 查询向量
     * @param topK 返回数量
     * @param filters 过滤条件
     * @return 搜索结果列表
     */
    default List<SearchResult> search(String collection, float[] queryVector,
                             int topK, Map<String, Object> filters) {
        log.error("[VectorStore] search not implemented by " + getClass().getSimpleName());
        return Collections.emptyList();
    }

    /**
     * 删除向量
     * @param collection 集合名称
     * @param ids 要删除的ID列表
     * @return 是否成功
     */
    default boolean delete(String collection, List<String> ids) {
        log.error("[VectorStore] delete not implemented by " + getClass().getSimpleName());
        return false;
    }

    /**
     * 获取向量
     * @param collection 集合名称
     * @param ids ID列表
     * @return 向量记录列表
     */
    default List<VectorRecord> get(String collection, List<String> ids) {
        log.error("[VectorStore] get not implemented by " + getClass().getSimpleName());
        return Collections.emptyList();
    }

    /**
     * 健康检查
     * @return 是否健康
     */
    default boolean healthCheck() {
        return false;
    }

    /**
     * 获取统计信息
     * @param collection 集合名称
     * @return 统计信息
     */
    default Map<String, Object> getStats(String collection) {
        log.error("[VectorStore] getStats not implemented by " + getClass().getSimpleName());
        return Collections.emptyMap();
    }
}
