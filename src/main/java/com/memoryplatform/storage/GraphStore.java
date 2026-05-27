package com.memoryplatform.storage;

import com.memoryplatform.model.GraphNode;
import com.memoryplatform.model.GraphEdge;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * 图存储统一接口 - 所有图数据库适配器实现此接口
 * <p>
 * 支持SPI自动发现和Spring动态注册，实现Plugin化存储层。
 * 所有方法提供default实现，增强容错性——未实现的方法返回安全默认值而非抛出异常。
 * </p>
 *
 * @author Agent Memory Platform
 * @since 1.0
 */
@Slf4j
public interface GraphStore {

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
     * 创建节点
     * @param node 图节点
     * @return 节点ID
     */
    default String createNode(GraphNode node) {
        log.error("[GraphStore] createNode not implemented by " + getClass().getSimpleName());
        return null;
    }

    /**
     * 创建边
     * @param edge 图边
     * @return 边ID
     */
    default String createEdge(GraphEdge edge) {
        log.error("[GraphStore] createEdge not implemented by " + getClass().getSimpleName());
        return null;
    }

    /**
     * 获取节点
     * @param id 节点ID
     * @return 图节点
     */
    default GraphNode getNode(String id) {
        log.error("[GraphStore] getNode not implemented by " + getClass().getSimpleName());
        return null;
    }

    /**
     * 图遍历
     * @param startId 起始节点ID
     * @param maxDepth 最大深度
     * @return 遍历到的节点列表
     */
    default List<GraphNode> traverse(String startId, int maxDepth) {
        log.error("[GraphStore] traverse not implemented by " + getClass().getSimpleName());
        return Collections.emptyList();
    }

    /**
     * 搜索节点
     * @param query 搜索关键词
     * @param filters 过滤条件
     * @return 匹配的节点列表
     */
    default List<GraphNode> searchNodes(String query, Map<String, Object> filters) {
        log.error("[GraphStore] searchNodes not implemented by " + getClass().getSimpleName());
        return Collections.emptyList();
    }

    /**
     * 删除节点及其关联关系
     * @param id 节点ID
     * @return 是否成功
     */
    default boolean delete(String id) {
        log.error("[GraphStore] delete not implemented by " + getClass().getSimpleName());
        return false;
    }

    /**
     * 根据实体名称查找关联的记忆ID
     * @param entityName 实体名称
     * @return 关联的记忆ID列表
     */
    default List<String> findMemoriesByEntity(String entityName) {
        log.error("[GraphStore] findMemoriesByEntity not implemented by " + getClass().getSimpleName());
        return Collections.emptyList();
    }

    /**
     * 健康检查
     * @return 是否健康
     */
    default boolean healthCheck() {
        return false;
    }
}
