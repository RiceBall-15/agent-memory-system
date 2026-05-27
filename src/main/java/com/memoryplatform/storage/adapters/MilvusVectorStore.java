package com.memoryplatform.storage.adapters;

import com.memoryplatform.model.SearchResult;
import com.memoryplatform.model.VectorRecord;
import com.memoryplatform.storage.VectorStore;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.*;
import io.milvus.param.*;
import io.milvus.param.collection.*;
import io.milvus.param.dml.*;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.response.SearchResultsWrapper;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
/**
 * Milvus向量存储适配器
 * <p>
 * 基于Milvus 2.3.x SDK实现VectorStore接口，提供向量的增删改查能力。
 * 支持L2/IP/COSINE距离度量，支持按user_id和agent_id过滤搜索。
 * </p>
 *
 * <h3>配置参数</h3>
 * <ul>
 *     <li>{@code host} - Milvus服务地址，默认 "localhost"</li>
 *     <li>{@code port} - Milvus服务端口，默认 19530</li>
 *     <li>{@code username} - 认证用户名，可选</li>
 *     <li>{@code password} - 认证密码，可选</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>使用AtomicBoolean标记连接状态，ConcurrentHashMap存储ID映射关系，保证并发安全。</p>
 *
 * @author Agent Memory Platform
 * @version 1.0.0
 */
@Slf4j
    @Component
public class MilvusVectorStore implements VectorStore {

    /** Milvus服务客户端 */
    private volatile MilvusServiceClient client;

    /** 连接状态标记，线程安全 */
    private final AtomicBoolean connected = new AtomicBoolean(false);

    /** 字符串ID到Milvus内部Long ID的映射表（每个集合一个映射） */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Long>> idMappings = new ConcurrentHashMap<>();

    /** 集合名称 -> 默认向量维度缓存 */
    private final ConcurrentHashMap<String, Integer> dimensionCache = new ConcurrentHashMap<>();

    /** 默认向量维度 */
    private static final int DEFAULT_DIMENSION = 768;

    /** 默认最大批量插入大小 */
    private static final int MAX_BATCH_SIZE = 1000;

    /**
     * 初始化Milvus连接
     *
     * @param config 配置参数Map，支持以下key:
     *               <ul>
     *                   <li>host (String) - 服务地址</li>
     *                   <li>port (Integer) - 服务端口</li>
     *                   <li>username (String) - 用户名</li>
     *                   <li>password (String) - 密码</li>
     *               </ul>
     */
    @Override
    public void init(Map<String, Object> config) {
        try {
            String host = getConfigString(config, "host", "localhost");
            int port = getConfigInt(config, "port", 19530);
            String username = getConfigString(config, "username", null);
            String password = getConfigString(config, "password", null);

            // 构建连接参数
            MilvusServiceClient.Builder builder = MilvusServiceClient.builder()
                    .withHost(host)
                    .withPort(port);

            // 如果提供了认证信息，则添加
            if (username != null && !username.isEmpty() && password != null) {
                builder.withAuthorization(username, password);
            }

            // 关闭旧连接（如果存在）
            closeClient();

            client = builder.build();
            connected.set(true);

            log.info("[MilvusVectorStore] 连接初始化成功 -> " + host + ":" + port)
        } catch (Exception e) {
            connected.set(false);
            log.error("[MilvusVectorStore] 连接初始化失败: " + e.getMessage());
            throw new RuntimeException("Milvus连接初始化失败", e);
        }
    }

    /**
     * 创建Milvus集合和索引
     *
     * @param name      集合名称
     * @param dimension 向量维度
     * @param metric    距离度量，支持 "L2", "IP", "COSINE"
     * @return 是否创建成功
     */
    @Override
    public boolean createCollection(String name, int dimension, String metric) {
        try {
            ensureConnected();

            // 检查集合是否已存在
            if (hasCollection(name)) {
                log.info("[MilvusVectorStore] 集合已存在: " + name)
                dimensionCache.put(name, dimension);
                return true;
            }

            // 构建字段定义
            List<FieldType> fieldTypes = Arrays.asList(
                    // 主键字段 (Milvus要求主键为Int64或VARCHAR，这里使用VARCHAR存储字符串ID)
                    FieldType.newBuilder()
                            .withName("id")
                            .withDataType(DataType.VarChar)
                            .withMaxLength(128)
                            .withPrimaryKey(true)
                            .withAutoID(false)
                            .build(),
                    // 文本内容字段
                    FieldType.newBuilder()
                            .withName("text")
                            .withDataType(DataType.VarChar)
                            .withMaxLength(65535)
                            .build(),
                    // 用户ID字段
                    FieldType.newBuilder()
                            .withName("user_id")
                            .withDataType(DataType.VarChar)
                            .withMaxLength(128)
                            .build(),
                    // 智能体ID字段
                    FieldType.newBuilder()
                            .withName("agent_id")
                            .withDataType(DataType.VarChar)
                            .withMaxLength(128)
                            .build(),
                    // 重要性分数字段
                    FieldType.newBuilder()
                            .withName("importance")
                            .withDataType(DataType.Double)
                            .build(),
                    // 向量字段
                    FieldType.newBuilder()
                            .withName("embedding")
                            .withDataType(DataType.FloatVector)
                            .withDimension(dimension)
                            .build()
            );

            // 构建集合参数
            CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                    .withCollectionName(name)
                    .withShardsNum(2)
                    .withConsistencyLevel(ConsistencyLevelEnum.BOUNDED)
                    .withFieldTypeList(fieldTypes)
                    .build();

            R<RpcStatus> response = client.createCollection(createParam);
            if (response.getStatus() != R.Status.Success.getCode()) {
                log.error("[MilvusVectorStore] 创建集合失败: " + response.getMessage());
                return false;
            }

            // 创建向量索引
            String indexFieldName = "embedding";
            String indexType = "IVF_FLAT";
            String metricType = metric != null ? metric.toUpperCase() : "COSINE";

            CreateIndexParam indexParam = CreateIndexParam.newBuilder()
                    .withCollectionName(name)
                    .withFieldName(indexFieldName)
                    .withIndexType(IndexType.valueOf(indexType))
                    .withMetricType(MetricType.valueOf(metricType))
                    .withExtraParam("{\"nlist\": 128}")
                    .withSyncMode(Boolean.TRUE)
                    .build();

            R<RpcStatus> indexResponse = client.createIndex(indexParam);
            if (indexResponse.getStatus() != R.Status.Success.getCode()) {
                log.error("[MilvusVectorStore] 创建索引失败: " + indexResponse.getMessage());
                return false;
            }

            // 加载集合到内存
            client.loadCollection(LoadCollectionParam.newBuilder()
                    .withCollectionName(name)
                    .build());

            dimensionCache.put(name, dimension);
            idMappings.putIfAbsent(name, new ConcurrentHashMap<>());

            log.info("[MilvusVectorStore] 集合创建成功: " + name + " (维度=" + dimension + ", 度量=" + metricType + ")")
            return true;

        } catch (Exception e) {
            log.error("[MilvusVectorStore] createCollection异常: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 批量写入向量记录到Milvus
     * <p>
     * 使用HashMap维护字符串ID到Long类型内部ID的映射关系，
     * 通过FieldDataInfo构建批量数据进行插入。
     * </p>
     *
     * @param collection 集合名称
     * @param records    向量记录列表
     * @return 是否写入成功
     */
    @Override
    public boolean upsert(String collection, List<VectorRecord> records) {
        try {
            ensureConnected();

            if (records == null || records.isEmpty()) {
                log.info("[MilvusVectorStore] upsert: 空记录列表，跳过")
                return true;
            }

            // 获取或初始化ID映射表
            ConcurrentHashMap<String, Long> idMap = idMappings.computeIfAbsent(
                    collection, k -> new ConcurrentHashMap<>()
            );

            // 获取向量维度（从第一条记录推断）
            int dimension = records.get(0).getVector() != null ? records.get(0).getVector().length : DEFAULT_DIMENSION;
            dimensionCache.put(collection, dimension);

            // 分批插入，避免单次数据过大
            for (int batchStart = 0; batchStart < records.size(); batchStart += MAX_BATCH_SIZE) {
                int batchEnd = Math.min(batchStart + MAX_BATCH_SIZE, records.size());
                List<VectorRecord> batch = records.subList(batchStart, batchEnd);

                // 构建各字段的数据列表
                List<String> ids = new ArrayList<>();
                List<String> texts = new ArrayList<>();
                List<String> userIds = new ArrayList<>();
                List<String> agentIds = new ArrayList<>();
                List<Double> importances = new ArrayList<>();
                List<List<Float>> embeddings = new ArrayList<>();

                for (VectorRecord record : batch) {
                    // 生成内部Long ID（使用字符串ID的hashCode绝对值）
                    long internalId = Math.abs(record.getId().hashCode());
                    idMap.put(record.getId(), internalId);

                    ids.add(record.getId());
                    texts.add(record.getText() != null ? record.getText() : "");
                    userIds.add(record.getUserId() != null ? record.getUserId() : "");
                    agentIds.add(record.getAgentId() != null ? record.getAgentId() : "");
                    importances.add(record.getImportance());

                    // 转换float[]到List<Float>
                    List<Float> vectorList = new ArrayList<>();
                    if (record.getVector() != null) {
                        for (float f : record.getVector()) {
                            vectorList.add(f);
                        }
                    } else {
                        // 如果没有向量，填充零向量
                        for (int i = 0; i < dimension; i++) {
                            vectorList.add(0.0f);
                        }
                    }
                    embeddings.add(vectorList);
                }

                // 构建FieldData列表
                List<InsertParam.Field> fields = new ArrayList<>();
                fields.add(new InsertParam.Field("id", ids));
                fields.add(new InsertParam.Field("text", texts));
                fields.add(new InsertParam.Field("user_id", userIds));
                fields.add(new InsertParam.Field("agent_id", agentIds));
                fields.add(new InsertParam.Field("importance", importances));
                fields.add(new InsertParam.Field("embedding", embeddings));

                // 构建插入参数
                InsertParam insertParam = InsertParam.newBuilder()
                        .withCollectionName(collection)
                        .withFieldDataList(fields)
                        .build();

                R<MutationResult> response = client.insert(insertParam);
                if (response.getStatus() != R.Status.Success.getCode()) {
                    log.error("[MilvusVectorStore] 批次插入失败(batch " + batchStart + "-" + batchEnd + "): " + response.getMessage());
                    return false;
                }
            }

            log.info("[MilvusVectorStore] upsert成功: 集合=" + collection + ", 记录数=" + records.size())
            return true;

        } catch (Exception e) {
            log.error("[MilvusVectorStore] upsert异常: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 向量语义搜索
     * <p>
     * 构建SearchParam进行向量检索，支持按user_id和agent_id过滤。
     * 搜索结果的语义分数通过公式 {@code 1.0 / (1.0 + distance)} 计算。
     * </p>
     *
     * @param collection 集合名称
     * @param queryVector 查询向量
     * @param topK       返回结果数量
     * @param filters    过滤条件，支持 "user_id" 和 "agent_id" 键
     * @return 搜索结果列表，按相关性降序排列
     */
    @Override
    public List<SearchResult> search(String collection, float[] queryVector,
                                     int topK, Map<String, Object> filters) {
        try {
            ensureConnected();

            if (queryVector == null || queryVector.length == 0) {
                log.error("[MilvusVectorStore] search: 查询向量为空")
                return Collections.emptyList();
            }

            // 构建过滤表达式
            String expr = buildFilterExpression(filters);

            // 将查询向量转换为List<Float>
            List<Float> queryVectorList = new ArrayList<>();
            for (float f : queryVector) {
                queryVectorList.add(f);
            }

            // 构建搜索参数
            SearchParam.Builder searchBuilder = SearchParam.newBuilder()
                    .withCollectionName(collection)
                    .withTopK(topK)
                    .withMetricType(MetricType.COSINE)
                    .withOutFields(Arrays.asList("id", "text", "user_id", "agent_id", "importance"))
                    .withFloatVectors(Arrays.asList(queryVectorList))
                    .withVectorFieldName("embedding")
                    .withConsistencyLevel(ConsistencyLevelEnum.BOUNDED);

            // 设置过滤表达式
            if (expr != null && !expr.isEmpty()) {
                searchBuilder.withExpr(expr);
            }

            SearchParam searchParam = searchBuilder.build();

            // 执行搜索
            R<SearchResults> response = client.search(searchParam);
            if (response.getStatus() != R.Status.Success.getCode()) {
                log.error("[MilvusVectorStore] 搜索失败: " + response.getMessage());
                return Collections.emptyList();
            }

            SearchResults searchResults = response.getData();
            SearchResultsWrapper wrapper = new SearchResultsWrapper(searchResults.getResults());

            List<SearchResult> results = new ArrayList<>();

            // 解析搜索结果 - 获取所有返回的字段数据
            FieldData idFieldData = searchResults.getResults().getFieldsDataMap().get("id");
            FieldData textFieldData = searchResults.getResults().getFieldsDataMap().get("text");
            FieldData userIdFieldData = searchResults.getResults().getFieldsDataMap().get("user_id");
            FieldData agentIdFieldData = searchResults.getResults().getFieldsDataMap().get("agent_id");
            FieldData importanceFieldData = searchResults.getResults().getFieldsDataMap().get("importance");

            // 获取查询结果中的ID列表和分数
            for (int i = 0; i < searchResults.getResults().getIds().getStrId().getDataCount(); i++) {
                try {
                    String id = searchResults.getResults().getIds().getStrId().getData(i);
                    float score = searchResults.getResults().getScores(i);

                    String text = textFieldData != null && i < textFieldData.getScalars().getStringData().getDataCount() ?
                            textFieldData.getScalars().getStringData().getData(i) : "";

                    double importance = importanceFieldData != null && i < importanceFieldData.getScalars().getDoubleData().getDataCount() ?
                            importanceFieldData.getScalars().getDoubleData().getData(i) : 0.5;

                    // 计算语义分数: 1.0 / (1.0 + distance)
                    // Milvus返回的score是相似度，需要转换为距离
                    double distance = 1.0 - score; // 余弦相似度转距离
                    double semanticScore = 1.0 / (1.0 + Math.max(distance, 0.0));

                    // 构建元数据
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("user_id", userIdFieldData != null && i < userIdFieldData.getScalars().getStringData().getDataCount() ?
                            userIdFieldData.getScalars().getStringData().getData(i) : "");
                    metadata.put("agent_id", agentIdFieldData != null && i < agentIdFieldData.getScalars().getStringData().getDataCount() ?
                            agentIdFieldData.getScalars().getStringData().getData(i) : "");
                    metadata.put("importance", importance);
                    metadata.put("raw_score", score);

                    // 构建SearchResult，综合分数 = 语义分数
                    SearchResult result = new SearchResult(
                            id,
                            text,
                            semanticScore,
                            semanticScore,
                            0.0,
                            0.0,
                            metadata
                    );
                    results.add(result);
                } catch (Exception e) {
                    // 跳过解析失败的单条记录，继续处理
                    log.error("[MilvusVectorStore] 解析第" + i + "条结果失败: " + e.getMessage());
                }
            }

            // 按分数降序排列
            results.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

            log.info("[MilvusVectorStore] 搜索完成: 集合=" + collection + ", 返回=" + results.size() + "条")
            return results;

        } catch (Exception e) {
            log.error("[MilvusVectorStore] search异常: " + e.getMessage());
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    /**
     * 批量删除向量记录
     *
     * @param collection 集合名称
     * @param ids        要删除的ID列表
     * @return 是否删除成功
     */
    @Override
    public boolean delete(String collection, List<String> ids) {
        try {
            ensureConnected();

            if (ids == null || ids.isEmpty()) {
                log.info("[MilvusVectorStore] delete: 空ID列表，跳过")
                return true;
            }

            // 构建删除表达式: id in ["id1", "id2", ...]
            StringBuilder exprBuilder = new StringBuilder("id in [");
            for (int i = 0; i < ids.size(); i++) {
                exprBuilder.append("\"").append(escapeString(ids.get(i))).append("\"");
                if (i < ids.size() - 1) {
                    exprBuilder.append(", ");
                }
            }
            exprBuilder.append("]");

            String expr = exprBuilder.toString();

            // 构建删除参数
            DeleteParam deleteParam = DeleteParam.newBuilder()
                    .withCollectionName(collection)
                    .withExpr(expr)
                    .build();

            R<MutationResult> response = client.delete(deleteParam);
            if (response.getStatus() != R.Status.Success.getCode()) {
                log.error("[MilvusVectorStore] 删除失败: " + response.getMessage());
                return false;
            }

            // 清理ID映射
            ConcurrentHashMap<String, Long> idMap = idMappings.get(collection);
            if (idMap != null) {
                for (String id : ids) {
                    idMap.remove(id);
                }
            }

            log.info("[MilvusVectorStore] delete成功: 集合=" + collection + ", 删除数=" + ids.size())
            return true;

        } catch (Exception e) {
            log.error("[MilvusVectorStore] delete异常: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 根据ID列表获取向量记录
     *
     * @param collection 集合名称
     * @param ids        ID列表
     * @return 向量记录列表
     */
    @Override
    public List<VectorRecord> get(String collection, List<String> ids) {
        try {
            ensureConnected();

            if (ids == null || ids.isEmpty()) {
                return Collections.emptyList();
            }

            // 构建查询表达式: id in ["id1", "id2", ...]
            StringBuilder exprBuilder = new StringBuilder("id in [");
            for (int i = 0; i < ids.size(); i++) {
                exprBuilder.append("\"").append(escapeString(ids.get(i))).append("\"");
                if (i < ids.size() - 1) {
                    exprBuilder.append(", ");
                }
            }
            exprBuilder.append("]");

            String expr = exprBuilder.toString();

            // 构建查询参数
            QueryParam queryParam = QueryParam.newBuilder()
                    .withCollectionName(collection)
                    .withExpr(expr)
                    .withOutFields(Arrays.asList("id", "text", "user_id", "agent_id", "importance", "embedding"))
                    .build();

            R<QueryResults> response = client.query(queryParam);
            if (response.getStatus() != R.Status.Success.getCode()) {
                log.error("[MilvusVectorStore] 查询失败: " + response.getMessage());
                return Collections.emptyList();
            }

            // 解析查询结果
            QueryResultsWrapper wrapper = new QueryResultsWrapper(response.getData());
            List<VectorRecord> records = new ArrayList<>();

            try {
                // 获取返回的字段数据
                FieldData idFieldData = response.getData().getFieldsDataMap().get("id");
                if (idFieldData == null) {
                    log.error("[MilvusVectorStore] 查询结果中缺少id字段")
                    return Collections.emptyList();
                }

                int rowCount = idFieldData.getScalars().getStringData().getDataCount();

                // 逐行解析
                for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
                    // 获取各字段值
                    String id = idFieldData.getScalars().getStringData().getData(rowIndex);

                    FieldData textFieldData = response.getData().getFieldsDataMap().get("text");
                    String text = textFieldData != null ?
                            textFieldData.getScalars().getStringData().getData(rowIndex) : "";

                    FieldData userIdFieldData = response.getData().getFieldsDataMap().get("user_id");
                    String userId = userIdFieldData != null ?
                            userIdFieldData.getScalars().getStringData().getData(rowIndex) : "";

                    FieldData agentIdFieldData = response.getData().getFieldsDataMap().get("agent_id");
                    String agentId = agentIdFieldData != null ?
                            agentIdFieldData.getScalars().getStringData().getData(rowIndex) : "";

                    FieldData importanceFieldData = response.getData().getFieldsDataMap().get("importance");
                    double importance = importanceFieldData != null ?
                            importanceFieldData.getScalars().getDoubleData().getData(rowIndex) : 0.5;

                    // 提取向量
                    float[] vector = null;
                    FieldData embeddingFieldData = response.getData().getFieldsDataMap().get("embedding");
                    if (embeddingFieldData != null) {
                        FloatVector floatVector = embeddingFieldData.getVectors().getFloatVector();
                        if (floatVector != null && rowIndex < floatVector.getDataCount()) {
                            com.google.protobuf.FloatList floatData = floatVector.getData(rowIndex);
                            vector = new float[floatData.getDataCount()];
                            for (int j = 0; j < floatData.getDataCount(); j++) {
                                vector[j] = floatData.getData(j);
                            }
                        }
                    }

                    VectorRecord record = VectorRecord.builder()
                            .id(id)
                            .collection(collection)
                            .vector(vector)
                            .text(text)
                            .userId(userId)
                            .agentId(agentId)
                            .importance(importance)
                            .build();

                    records.add(record);
                }
            } catch (Exception parseEx) {
                log.error("[MilvusVectorStore] 解析查询结果异常: " + parseEx.getMessage());
                parseEx.printStackTrace();
            }

            log.info("[MilvusVectorStore] get完成: 集合=" + collection + ", 查询数=" + ids.size() + ", 返回数=" + records.size())
            return records;

        } catch (Exception e) {
            log.error("[MilvusVectorStore] get异常: " + e.getMessage());
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    /**
     * Milvus健康检查
     * <p>
     * 通过尝试获取Milvus版本信息来验证连接是否正常。
     * </p>
     *
     * @return 连接是否健康
     */
    @Override
    public boolean healthCheck() {
        try {
            ensureConnected();

            // 通过列出集合来验证连接
            R<ListCollectionsResponse> response = client.listCollections(
                    ListCollectionsParam.newBuilder().build()
            );

            if (response.getStatus() == R.Status.Success.getCode()) {
                return true;
            } else {
                log.error("[MilvusVectorStore] 健康检查失败: " + response.getMessage());
                return false;
            }
        } catch (Exception e) {
            log.error("[MilvusVectorStore] 健康检查异常: " + e.getMessage());
            connected.set(false);
            return false;
        }
    }

    /**
     * 获取集合统计信息
     *
     * @param collection 集合名称
     * @return 统计信息Map，包含:
     *         <ul>
     *             <li>collection - 集合名称</li>
     *             <li>row_count - 记录总数</li>
     *             <li>entity_count - 实体总数</li>
     *             <li>dimension - 向量维度</li>
     *             <li>index_type - 索引类型</li>
     *             <li>metric_type - 度量类型</li>
     *         </ul>
     */
    @Override
    public Map<String, Object> getStats(String collection) {
        try {
            ensureConnected();

            Map<String, Object> stats = new HashMap<>();
            stats.put("collection", collection);

            // 获取集合统计
            R<GetCollectionStatisticsResponse> response = client.getCollectionStatistics(
                    GetCollectionStatisticsParam.newBuilder()
                            .withCollectionName(collection)
                            .build()
            );

            if (response.getStatus() == R.Status.Success.getCode()) {
                GetCollectionStatisticsResponse statsResponse = response.getData();
                Map<String, String> statsMap = statsResponse.getStatsMap();
                stats.put("row_count", statsMap.getOrDefault("row_count", "0"));
                stats.put("entity_count", statsMap.getOrDefault("entity_count", "0"));
            } else {
                log.error("[MilvusVectorStore] 获取统计失败: " + response.getMessage());
                stats.put("error", response.getMessage());
            }

            // 获取集合描述信息
            R<DescribeCollectionResponse> descResponse = client.describeCollection(
                    DescribeCollectionParam.newBuilder()
                            .withCollectionName(collection)
                            .build()
            );

            if (descResponse.getStatus() == R.Status.Success.getCode()) {
                CollectionSchema schema = descResponse.getData().getSchema();
                // 查找embedding字段的维度
                for (FieldSchema field : schema.getFieldsList()) {
                    if ("embedding".equals(field.getName())) {
                        // 从类型参数中提取维度
                        String typeParams = field.getTypeParams().getDataMap().toString();
                        stats.put("dimension", dimensionCache.getOrDefault(collection, DEFAULT_DIMENSION));
                        break;
                    }
                }

                // 获取索引信息
                R<DescribeIndexResponse> indexResponse = client.describeIndex(
                        DescribeIndexParam.newBuilder()
                                .withCollectionName(collection)
                                .withFieldName("embedding")
                                .build()
                );

                if (indexResponse.getStatus() == R.Status.Success.getCode()) {
                    List<IndexDescription> indexes = indexResponse.getData().getIndexDescriptionsList();
                    if (!indexes.isEmpty()) {
                        IndexDescription indexDesc = indexes.get(0);
                        stats.put("index_type", indexDesc.getIndexType());
                        stats.put("metric_type", indexDesc.getMetricType());
                        stats.put("index_name", indexDesc.getIndexName());
                    }
                }
            }

            // 附加ID映射信息
            ConcurrentHashMap<String, Long> idMap = idMappings.get(collection);
            stats.put("local_id_mapping_count", idMap != null ? idMap.size() : 0);

            log.info("[MilvusVectorStore] getStats完成: " + stats)
            return stats;

        } catch (Exception e) {
            log.error("[MilvusVectorStore] getStats异常: " + e.getMessage());
            e.printStackTrace();
            Map<String, Object> errorStats = new HashMap<>();
            errorStats.put("collection", collection);
            errorStats.put("error", e.getMessage());
            return errorStats;
        }
    }

    /**
     * 关闭Milvus连接，释放资源
     */
    public void close() {
        closeClient();
        idMappings.clear();
        dimensionCache.clear();
        log.info("[MilvusVectorStore] 连接已关闭")
    }

    // ======================== 私有辅助方法 ========================

    /**
     * 检查集合是否已存在
     */
    private boolean hasCollection(String name) {
        try {
            R<HasCollectionResponse> response = client.hasCollection(
                    HasCollectionParam.newBuilder()
                            .withCollectionName(name)
                            .build()
            );
            return response.getStatus() == R.Status.Success.getCode() && response.getData().getValue();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 确保已连接到Milvus
     *
     * @throws RuntimeException 如果未连接
     */
    private void ensureConnected() {
        if (!connected.get() || client == null) {
            throw new RuntimeException("Milvus未连接，请先调用init()方法初始化连接");
        }
    }

    /**
     * 构建Milvus过滤表达式
     * <p>
     * 支持的过滤字段: user_id, agent_id
     * </p>
     *
     * @param filters 过滤条件Map
     * @return Milvus表达式字符串，如 'user_id == "u1" && agent_id == "a1"'
     */
    private String buildFilterExpression(Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return null;
        }

        List<String> conditions = new ArrayList<>();

        if (filters.containsKey("user_id")) {
            String userId = Objects.toString(filters.get("user_id"), "");
            if (!userId.isEmpty()) {
                conditions.add("user_id == \"" + escapeString(userId) + "\"");
            }
        }

        if (filters.containsKey("agent_id")) {
            String agentId = Objects.toString(filters.get("agent_id"), "");
            if (!agentId.isEmpty()) {
                conditions.add("agent_id == \"" + escapeString(agentId) + "\"");
            }
        }

        if (conditions.isEmpty()) {
            return null;
        }

        return String.join(" && ", conditions);
    }

    /**
     * 转义字符串中的特殊字符，防止表达式注入
     *
     * @param input 原始字符串
     * @return 转义后的字符串
     */
    private String escapeString(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("'", "\\'");
    }

    /**
     * 安全关闭Milvus客户端
     */
    private void closeClient() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.error("[MilvusVectorStore] 关闭连接异常: " + e.getMessage());
            }
            client = null;
        }
        connected.set(false);
    }

    /**
     * 从配置Map中获取字符串值
     *
     * @param config       配置Map
     * @param key          键名
     * @param defaultValue 默认值
     * @return 字符串值
     */
    private String getConfigString(Map<String, Object> config, String key, String defaultValue) {
        if (config == null || !config.containsKey(key)) {
            return defaultValue;
        }
        Object value = config.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    /**
     * 从配置Map中获取整数值
     *
     * @param config       配置Map
     * @param key          键名
     * @param defaultValue 默认值
     * @return 整数值
     */
    private int getConfigInt(Map<String, Object> config, String key, int defaultValue) {
        if (config == null || !config.containsKey(key)) {
            return defaultValue;
        }
        Object value = config.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
