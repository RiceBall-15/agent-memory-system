package com.memoryplatform.storage;

import com.memoryplatform.model.GraphNode;
import com.memoryplatform.model.GraphEdge;
import java.util.List;
import java.util.Map;

/**
 * 图存储统一接口 - 所有图数据库适配器实现此接口
 */
public interface GraphStore {

    /**
     * 初始化存储连接
     */
    default void init(Map<String, Object> config) {}

    /**
     * 创建节点
     * @param node 图节点
     * @return 节点ID
     */
    String createNode(GraphNode node);

    /**
     * 创建边
     * @param edge 图边
     * @return 边ID
     */
    String createEdge(GraphEdge edge);

    /**
     * 获取节点
     * @param id 节点ID
     * @return 图节点
     */
    GraphNode getNode(String id);

    /**
     * 图遍历
     * @param startNodeId 起始节点ID
     * @param relationshipTypes 关系类型列表
     * @param direction 方向 (INCOMING, OUTGOING, BOTH)
     * @param maxDepth 最大深度
     * @return 遍历结果
     */
    List<Map<String, Object>> traverse(String startNodeId, 
                                       List<String> relationshipTypes,
                                       String direction, int maxDepth);

    /**
     * 搜索节点
     * @param label 节点标签
     * @param properties 属性条件
     * @param limit 返回数量
     * @return 节点列表
     */
    List<GraphNode> searchNodes(String label, Map<String, Object> properties, int limit);

    /**
     * 删除节点和边
     * @param nodeIds 节点ID列表
     * @param edgeIds 边ID列表
     * @return 是否成功
     */
    boolean delete(List<String> nodeIds, List<String> edgeIds);

    /**
     * 查找包含指定实体的记忆ID
     * @param entityName 实体名称
     * @return 记忆ID列表
     */
    List<String> findMemoriesByEntity(String entityName);

    /**
     * 健康检查
     * @return 是否健康
     */
    boolean healthCheck();
}
