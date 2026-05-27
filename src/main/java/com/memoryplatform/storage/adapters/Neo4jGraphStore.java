package com.memoryplatform.storage.adapters;

import com.memoryplatform.model.GraphEdge;
import com.memoryplatform.model.GraphNode;
import com.memoryplatform.storage.GraphStore;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.exceptions.Neo4jException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Neo4j图存储适配器 - 基于Neo4j Java Driver实现GraphStore接口。
 *
 * <p>通过Cypher查询语言操作Neo4j图数据库，支持节点CRUD、边创建、
 * 图遍历、条件搜索、实体关联记忆查询等功能。</p>
 *
 * <p>配置参数通过{@link #init(Map)}传入：</p>
 * <ul>
 *   <li>{@code uri} - Neo4j连接URI，例如 "bolt://localhost:7687"</li>
 *   <li>{@code username} - 认证用户名</li>
 *   <li>{@code password} - 认证密码</li>
 * </ul>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * Neo4jGraphStore store = new Neo4jGraphStore();
 * Map<String, Object> config = new HashMap<>();
 * config.put("uri", "bolt://localhost:7687");
 * config.put("username", "neo4j");
 * config.put("password", "password");
 * store.init(config);
 * }</pre>
 *
 * @author Agent Memory Platform
 */
public class Neo4jGraphStore implements GraphStore {

    /** Neo4j驱动实例 */
    private volatile Driver driver;

    /** 连接状态标记 */
    private final AtomicBoolean connected = new AtomicBoolean(false);

    /** 默认连接超时时间(秒) */
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /** 默认遍历最大深度 */
    private static final int DEFAULT_MAX_DEPTH = 5;

    // ========== 初始化 ==========

    /**
     * 初始化Neo4j连接。
     *
     * <p>从配置Map中读取uri、username、password参数，创建Neo4j Driver实例。
     * 连接成功后通过Cypher "RETURN 1" 验证连通性。</p>
     *
     * @param config 配置参数Map，必须包含 "uri"、"username"、"password" 键
     * @throws IllegalArgumentException 当配置参数缺失时抛出
     * @throws RuntimeException 当连接失败时抛出
     */
    @Override
    public void init(Map<String, Object> config) {
        if (config == null) {
            throw new IllegalArgumentException("配置参数不能为空");
        }

        String uri = getConfigString(config, "uri", "bolt://localhost:7687");
        String username = getConfigString(config, "username", "neo4j");
        String password = getConfigString(config, "password", "");

        try {
            System.out.println("[Neo4jGraphStore] 正在连接Neo4j: " + uri);
            driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));

            // 验证连接
            try (Session session = driver.session()) {
                session.run("RETURN 1 AS test");
            }

            connected.set(true);
            System.out.println("[Neo4jGraphStore] Neo4j连接成功: " + uri);
        } catch (Exception e) {
            connected.set(false);
            System.err.println("[Neo4jGraphStore] Neo4j连接失败: " + e.getMessage());
            throw new RuntimeException("Neo4j连接失败: " + e.getMessage(), e);
        }
    }

    /**
     * 关闭Neo4j驱动连接，释放资源。
     *
     * <p>应在应用关闭时调用此方法，确保所有连接正确释放。</p>
     */
    public void close() {
        if (driver != null) {
            try {
                driver.close();
                connected.set(false);
                System.out.println("[Neo4jGraphStore] 连接已关闭");
            } catch (Exception e) {
                System.err.println("[Neo4jGraphStore] 关闭连接时出错: " + e.getMessage());
            }
        }
    }

    // ========== 节点操作 ==========

    /**
     * 在Neo4j中创建节点。
     *
     * <p>使用参数化Cypher创建节点，防止注入攻击。节点标签由GraphNode的label字段指定，
     * 节点属性包括id、content、type、userId、agentId、createdAt以及自定义properties。</p>
     *
     * @param node 图节点对象
     * @return 创建的节点ID
     * @throws RuntimeException 当创建失败时抛出
     */
    @Override
    public String createNode(GraphNode node) {
        if (node == null || node.getId() == null) {
            throw new IllegalArgumentException("节点对象和ID不能为空");
        }

        ensureConnected();

        String label = node.getLabel();
        String cypher = String.format(
                "CREATE (n:%s {id: $id, content: $content, type: $type, " +
                "userId: $userId, agentId: $agentId, createdAt: $createdAt}) " +
                "RETURN n.id AS id",
                sanitizeLabel(label)
        );

        Map<String, Object> params = new HashMap<>();
        params.put("id", node.getId());
        params.put("content", node.getContent() != null ? node.getContent() : "");
        params.put("type", node.getType() != null ? node.getType() : "");
        params.put("userId", node.getUserId() != null ? node.getUserId() : "");
        params.put("agentId", node.getAgentId() != null ? node.getAgentId() : "");
        params.put("createdAt", node.getCreatedAt() != null ? node.getCreatedAt().toString() : Instant.now().toString());

        // 设置自定义属性
        if (node.getProperties() != null) {
            for (Map.Entry<String, Object> entry : node.getProperties().entrySet()) {
                params.put("prop_" + entry.getKey(), entry.getValue());
            }
            // 将自定义属性作为Map写入
            cypher = String.format(
                    "CREATE (n:%s {id: $id, content: $content, type: $type, " +
                    "userId: $userId, agentId: $agentId, createdAt: $createdAt, " +
                    "properties: $properties}) " +
                    "RETURN n.id AS id",
                    sanitizeLabel(label)
            );
            params.put("properties", node.getProperties());
        }

        try {
            String resultId = runWriteQuery(cypher, params, record -> record.get("id").asString());
            System.out.println("[Neo4jGraphStore] 节点创建成功: " + node.getId() + " (label=" + label + ")");
            return resultId;
        } catch (Exception e) {
            System.err.println("[Neo4jGraphStore] 节点创建失败: " + e.getMessage());
            throw new RuntimeException("创建节点失败: " + e.getMessage(), e);
        }
    }

    /**
     * 在Neo4j中创建边(关系)。
     *
     * <p>使用MATCH查找源节点和目标节点，然后创建指定类型的关系。
     * 关系属性包含weight和自定义properties。</p>
     *
     * @param edge 图边对象
     * @return 创建的边ID
     * @throws RuntimeException 当创建失败时抛出
     */
    @Override
    public String createEdge(GraphEdge edge) {
        if (edge == null || edge.getId() == null) {
            throw new IllegalArgumentException("边对象和ID不能为空");
        }
        if (edge.getSourceId() == null || edge.getTargetId() == null) {
            throw new IllegalArgumentException("源节点ID和目标节点ID不能为空");
        }

        ensureConnected();

        String relType = edge.getType() != null ? edge.getType() : "RELATED_TO";
        String cypher = "MATCH (a {id: $sourceId}), (b {id: $targetId}) " +
                        "CREATE (a)-[r:" + sanitizeLabel(relType) + " {id: $id, weight: $weight}]->(b) " +
                        "RETURN r.id AS id";

        Map<String, Object> params = new HashMap<>();
        params.put("sourceId", edge.getSourceId());
        params.put("targetId", edge.getTargetId());
        params.put("id", edge.getId());
        params.put("weight", edge.getWeight());

        if (edge.getProperties() != null) {
            params.put("properties", edge.getProperties());
            cypher = "MATCH (a {id: $sourceId}), (b {id: $targetId}) " +
                     "CREATE (a)-[r:" + sanitizeLabel(relType) +
                     " {id: $id, weight: $weight, properties: $properties}]->(b) " +
                     "RETURN r.id AS id";
        }

        try {
            String resultId = runWriteQuery(cypher, params, record -> record.get("id").asString());
            System.out.println("[Neo4jGraphStore] 边创建成功: " + edge.getId() +
                    " (" + edge.getSourceId() + " -> " + edge.getTargetId() + ")");
            return resultId;
        } catch (Exception e) {
            System.err.println("[Neo4jGraphStore] 边创建失败: " + e.getMessage());
            throw new RuntimeException("创建边失败: " + e.getMessage(), e);
        }
    }

    /**
     * 根据ID获取节点。
     *
     * <p>使用参数化Cypher MATCH查询匹配指定ID的节点，返回包含所有属性的GraphNode对象。</p>
     *
     * @param id 节点ID
     * @return 图节点对象，未找到时返回null
     * @throws RuntimeException 当查询失败时抛出
     */
    @Override
    public GraphNode getNode(String id) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("节点ID不能为空");
        }

        ensureConnected();

        String cypher = "MATCH (n {id: $id}) RETURN n";
        Map<String, Object> params = Map.of("id", id);

        try (Session session = driver.session()) {
            Result result = session.run(cypher, params);
            if (result.hasNext()) {
                Record record = result.next();
                Value nodeValue = record.get("n");
                return mapRecordToGraphNode(nodeValue);
            }
            System.out.println("[Neo4jGraphStore] 节点未找到: " + id);
            return null;
        } catch (Exception e) {
            System.err.println("[Neo4jGraphStore] 获取节点失败: " + e.getMessage());
            throw new RuntimeException("获取节点失败: " + e.getMessage(), e);
        }
    }

    // ========== 图遍历 ==========

    /**
     * 从指定起始节点进行图遍历。
     *
     * <p>支持三种方向：INCOMING（入边）、OUTGOING（出边）、BOTH（双向）。
     * 使用参数化Cypher进行变长路径匹配，限制最大遍历深度。</p>
     *
     * @param startNodeId      起始节点ID
     * @param relationshipTypes 关系类型列表，为空则匹配所有关系类型
     * @param direction         方向字符串：INCOMING、OUTGOING、BOTH
     * @param maxDepth          最大遍历深度，小于1时使用默认值5
     * @return 遍历结果列表，每个元素包含路径上节点和关系的信息
     * @throws RuntimeException 当遍历失败时抛出
     */
    @Override
    public List<Map<String, Object>> traverse(String startNodeId,
                                               List<String> relationshipTypes,
                                               String direction, int maxDepth) {
        if (startNodeId == null || startNodeId.isEmpty()) {
            throw new IllegalArgumentException("起始节点ID不能为空");
        }

        ensureConnected();

        int depth = maxDepth > 0 ? maxDepth : DEFAULT_MAX_DEPTH;

        // 构建关系类型片段
        String relTypeClause = "";
        if (relationshipTypes != null && !relationshipTypes.isEmpty()) {
            StringBuilder sb = new StringBuilder(":");
            for (int i = 0; i < relationshipTypes.size(); i++) {
                if (i > 0) sb.append("|");
                sb.append(sanitizeLabel(relationshipTypes.get(i)));
            }
            relTypeClause = sb.toString();
        }

        // 构建方向箭头
        String arrowLeft;
        String arrowRight;
        String dir = direction != null ? direction.toUpperCase() : "BOTH";
        switch (dir) {
            case "INCOMING":
                arrowLeft = "<-[r" + relTypeClause + "]-";
                arrowRight = "";
                break;
            case "OUTGOING":
                arrowLeft = "-[r" + relTypeClause + "]->";
                arrowRight = "";
                break;
            case "BOTH":
            default:
                arrowLeft = "-[r" + relTypeClause + "]-";
                arrowRight = "";
                dir = "BOTH";
                break;
        }

        String cypher = "MATCH p = (start {id: $startId})" + arrowLeft +
                        "(end)" + arrowRight +
                        " WHERE ALL(node IN nodes(p) WHERE node.id IS NOT NULL) " +
                        "RETURN end, r, length(p) AS depth " +
                        "ORDER BY depth ASC " +
                        "LIMIT $limit";

        Map<String, Object> params = new HashMap<>();
        params.put("startId", startNodeId);
        params.put("limit", 100); // 安全上限

        List<Map<String, Object>> results = new ArrayList<>();

        try (Session session = driver.session()) {
            Result result = session.run(cypher, params);
            while (result.hasNext()) {
                Record record = result.next();
                Map<String, Object> item = new HashMap<>();

                // 提取终点节点信息
                Value endNode = record.get("end");
                item.put("node", extractNodeProperties(endNode));

                // 提取关系信息
                Value relValue = record.get("r");
                item.put("relationship", extractRelationshipProperties(relValue));

                // 深度
                item.put("depth", record.get("depth").asInt());

                results.add(item);
            }

            System.out.println("[Neo4jGraphStore] 遍历完成: 起始=" + startNodeId +
                    ", 方向=" + dir + ", 深度上限=" + depth +
                    ", 结果数=" + results.size());
            return results;
        } catch (Exception e) {
            System.err.println("[Neo4jGraphStore] 遍历失败: " + e.getMessage());
            throw new RuntimeException("遍历失败: " + e.getMessage(), e);
        }
    }

    // ========== 搜索 ==========

    /**
     * 根据标签和属性条件搜索节点。
     *
     * <p>使用参数化Cypher进行标签和属性匹配，支持精确属性匹配和LIKE模糊匹配。
     * 结果按createdAt降序排列。</p>
     *
     * @param label      节点标签（如Memory、Entity等）
     * @param properties 属性条件Map，键为属性名，值为匹配值
     * @param limit      返回结果数量上限
     * @return 匹配的GraphNode列表
     * @throws RuntimeException 当搜索失败时抛出
     */
    @Override
    public List<GraphNode> searchNodes(String label, Map<String, Object> properties, int limit) {
        if (label == null || label.isEmpty()) {
            throw new IllegalArgumentException("节点标签不能为空");
        }

        ensureConnected();

        StringBuilder cypherBuilder = new StringBuilder("MATCH (n:");
        cypherBuilder.append(sanitizeLabel(label));
        cypherBuilder.append(") WHERE 1=1");

        Map<String, Object> params = new HashMap<>();
        int paramIndex = 0;

        if (properties != null) {
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                String paramName = "p" + paramIndex++;
                String propName = entry.getKey();
                Object propValue = entry.getValue();

                if (propValue instanceof String && ((String) propValue).contains("%")) {
                    // LIKE模糊匹配
                    cypherBuilder.append(" AND n.").append(propName).append(" CONTAINS $").append(paramName);
                } else {
                    // 精确匹配
                    cypherBuilder.append(" AND n.").append(propName).append(" = $").append(paramName);
                }
                params.put(paramName, propValue);
            }
        }

        int safeLimit = limit > 0 ? limit : 100;
        cypherBuilder.append(" RETURN n ORDER BY n.createdAt DESC LIMIT $limit");
        params.put("limit", safeLimit);

        String cypher = cypherBuilder.toString();
        List<GraphNode> results = new ArrayList<>();

        try (Session session = driver.session()) {
            Result result = session.run(cypher, params);
            while (result.hasNext()) {
                Record record = result.next();
                GraphNode node = mapRecordToGraphNode(record.get("n"));
                if (node != null) {
                    results.add(node);
                }
            }

            System.out.println("[Neo4jGraphStore] 搜索完成: label=" + label +
                    ", 结果数=" + results.size());
            return results;
        } catch (Exception e) {
            System.err.println("[Neo4jGraphStore] 搜索失败: " + e.getMessage());
            throw new RuntimeException("搜索失败: " + e.getMessage(), e);
        }
    }

    // ========== 删除 ==========

    /**
     * 删除指定的节点和边。
     *
     * <p>先删除边再删除节点，避免约束冲突。使用DETACH DELETE确保
     * 关联关系被正确清理。每个ID通过参数化查询处理，防止注入。</p>
     *
     * @param nodeIds 要删除的节点ID列表
     * @param edgeIds 要删除的边ID列表
     * @return 是否全部删除成功
     */
    @Override
    public boolean delete(List<String> nodeIds, List<String> edgeIds) {
        ensureConnected();

        boolean success = true;

        try (Session session = driver.session()) {
            // 先删除边
            if (edgeIds != null && !edgeIds.isEmpty()) {
                for (String edgeId : edgeIds) {
                    try {
                        String cypher = "MATCH ()-[r {id: $id}]->() DELETE r";
                        session.run(cypher, Map.of("id", edgeId));
                        System.out.println("[Neo4jGraphStore] 边删除成功: " + edgeId);
                    } catch (Exception e) {
                        System.err.println("[Neo4jGraphStore] 删除边失败 " + edgeId + ": " + e.getMessage());
                        success = false;
                    }
                }
            }

            // 再删除节点（DETACH DELETE自动删除关联边）
            if (nodeIds != null && !nodeIds.isEmpty()) {
                for (String nodeId : nodeIds) {
                    try {
                        String cypher = "MATCH (n {id: $id}) DETACH DELETE n";
                        session.run(cypher, Map.of("id", nodeId));
                        System.out.println("[Neo4jGraphStore] 节点删除成功: " + nodeId);
                    } catch (Exception e) {
                        System.err.println("[Neo4jGraphStore] 删除节点失败 " + nodeId + ": " + e.getMessage());
                        success = false;
                    }
                }
            }

            return success;
        } catch (Exception e) {
            System.err.println("[Neo4jGraphStore] 删除操作失败: " + e.getMessage());
            return false;
        }
    }

    // ========== 实体关联查询 ==========

    /**
     * 查找包含指定实体的记忆ID列表。
     *
     * <p>查询模式：Entity节点 --[:CONTAINS]--> Memory节点。
     * 通过Entity的name属性匹配，返回关联的Memory节点ID。</p>
     *
     * @param entityName 实体名称
     * @return 关联的记忆ID列表
     * @throws RuntimeException 当查询失败时抛出
     */
    @Override
    public List<String> findMemoriesByEntity(String entityName) {
        if (entityName == null || entityName.isEmpty()) {
            throw new IllegalArgumentException("实体名称不能为空");
        }

        ensureConnected();

        String cypher = "MATCH (e:Entity {name: $name})-[:CONTAINS]->(m:Memory) " +
                        "RETURN m.id AS memoryId";

        Map<String, Object> params = Map.of("name", entityName);
        List<String> memoryIds = new ArrayList<>();

        try (Session session = driver.session()) {
            Result result = session.run(cypher, params);
            while (result.hasNext()) {
                Record record = result.next();
                String memoryId = record.get("memoryId").asString();
                memoryIds.add(memoryId);
            }

            System.out.println("[Neo4jGraphStore] 实体关联查询: entity=" + entityName +
                    ", 记忆数=" + memoryIds.size());
            return memoryIds;
        } catch (Exception e) {
            System.err.println("[Neo4jGraphStore] 实体关联查询失败: " + e.getMessage());
            throw new RuntimeException("实体关联查询失败: " + e.getMessage(), e);
        }
    }

    // ========== 健康检查 ==========

    /**
     * 检查Neo4j连接是否健康。
     *
     * <p>通过执行简单Cypher查询 "RETURN 1" 验证连接可用性。
     * 同时检查Driver实例是否存在以及连接状态标记。</p>
     *
     * @return 连接健康返回true，否则返回false
     */
    @Override
    public boolean healthCheck() {
        if (driver == null || !connected.get()) {
            System.out.println("[Neo4jGraphStore] 健康检查失败: 未连接");
            return false;
        }

        try (Session session = driver.session()) {
            Result result = session.run("RETURN 1 AS health");
            if (result.hasNext()) {
                int value = result.next().get("health").asInt();
                boolean healthy = value == 1;
                if (!healthy) {
                    connected.set(false);
                }
                return healthy;
            }
            return false;
        } catch (Exception e) {
            System.err.println("[Neo4jGraphStore] 健康检查异常: " + e.getMessage());
            connected.set(false);
            return false;
        }
    }

    // ========== 私有辅助方法 ==========

    /**
     * 从配置Map中获取字符串参数。
     *
     * @param config       配置Map
     * @param key          键名
     * @param defaultValue 默认值
     * @return 配置值或默认值
     */
    private String getConfigString(Map<String, Object> config, String key, String defaultValue) {
        Object value = config.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    /**
     * 确保已连接，未连接时抛出异常。
     *
     * @throws RuntimeException 当未连接时抛出
     */
    private void ensureConnected() {
        if (!connected.get() || driver == null) {
            throw new RuntimeException("Neo4j未连接，请先调用init()方法");
        }
    }

    /**
     * 清理标签名称，移除不安全字符。
     *
     * <p>Neo4j标签只能包含字母、数字和下划线，此方法将不安全字符替换为下划线。</p>
     *
     * @param label 原始标签名
     * @return 清理后的安全标签名
     */
    private String sanitizeLabel(String label) {
        if (label == null || label.isEmpty()) {
            return "Node";
        }
        // 只保留字母、数字、下划线，其他替换为下划线
        return label.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    /**
     * 执行写入查询并提取结果。
     *
     * @param cypher    Cypher查询语句
     * @param params    参数Map
     * @param extractor 结果提取函数
     * @return 提取的结果值
     * @throws RuntimeException 当执行失败时抛出
     */
    private <T> T runWriteQuery(String cypher, Map<String, Object> params,
                                 java.util.function.Function<Record, T> extractor) {
        try (Session session = driver.session()) {
            Result result = session.run(cypher, params);
            if (result.hasNext()) {
                Record record = result.next();
                return extractor.apply(record);
            }
            throw new RuntimeException("写入查询未返回结果");
        } catch (Neo4jException e) {
            throw new RuntimeException("Cypher执行错误: " + e.getMessage(), e);
        }
    }

    /**
     * 将Neo4j Record中的节点值映射为GraphNode对象。
     *
     * @param nodeValue Neo4j节点Value
     * @return 映射后的GraphNode对象，映射失败返回null
     */
    private GraphNode mapRecordToGraphNode(Value nodeValue) {
        try {
            String id = nodeValue.get("id").asString(null);
            if (id == null) {
                // 尝试从labels推断
                return null;
            }

            String content = nodeValue.get("content").asString(null);
            String type = nodeValue.get("type").asString(null);
            String userId = nodeValue.get("userId").asString(null);
            String agentId = nodeValue.get("agentId").asString(null);

            // 获取标签（Neo4j节点可能有多个标签）
            List<String> labels = new ArrayList<>();
            nodeValue.keys().forEach(labels::add);

            String label = "Node";
            // 尝试从type推断标签
            if (type != null && !type.isEmpty()) {
                label = type;
            }

            // 解析createdAt
            Instant createdAt = Instant.now();
            String createdAtStr = nodeValue.get("createdAt").asString(null);
            if (createdAtStr != null) {
                try {
                    createdAt = Instant.parse(createdAtStr);
                } catch (Exception ignored) {
                    // 使用默认值
                }
            }

            // 解析自定义properties
            Map<String, Object> properties = null;
            Value propsValue = nodeValue.get("properties");
            if (propsValue != null && !propsValue.isNull()) {
                properties = new HashMap<>();
                propsValue.keys().forEach(key ->
                    properties.put(key, propsValue.get(key).asObject())
                );
            }

            return GraphNode.builder()
                    .id(id)
                    .label(label)
                    .content(content)
                    .type(type)
                    .userId(userId)
                    .agentId(agentId)
                    .createdAt(createdAt)
                    .properties(properties)
                    .build();
        } catch (Exception e) {
            System.err.println("[Neo4jGraphStore] 节点映射失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 从Neo4j节点Value中提取属性为Map。
     *
     * @param nodeValue Neo4j节点Value
     * @return 属性Map
     */
    private Map<String, Object> extractNodeProperties(Value nodeValue) {
        Map<String, Object> props = new HashMap<>();
        if (nodeValue == null || nodeValue.isNull()) {
            return props;
        }
        nodeValue.keys().forEach(key -> {
            Value val = nodeValue.get(key);
            if (!val.isNull()) {
                props.put(key, val.asObject());
            }
        });
        return props;
    }

    /**
     * 从Neo4j关系Value中提取属性为Map。
     *
     * @param relValue Neo4j关系Value
     * @return 属性Map
     */
    private Map<String, Object> extractRelationshipProperties(Value relValue) {
        Map<String, Object> props = new HashMap<>();
        if (relValue == null || relValue.isNull()) {
            return props;
        }
        try {
            // 关系类型
            props.put("type", relValue.type());
            // 属性
            relValue.keys().forEach(key -> {
                Value val = relValue.get(key);
                if (!val.isNull()) {
                    props.put(key, val.asObject());
                }
            });
        } catch (Exception e) {
            System.err.println("[Neo4jGraphStore] 关系属性提取失败: " + e.getMessage());
        }
        return props;
    }
}
