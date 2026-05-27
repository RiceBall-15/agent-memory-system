package com.memoryplatform.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.memoryplatform.extractor.EntityExtractor;
import com.memoryplatform.extractor.TimeParser;
import com.memoryplatform.llm.LlmClient;
import com.memoryplatform.model.*;
import com.memoryplatform.storage.GraphStore;
import com.memoryplatform.storage.MetadataStore;
import com.memoryplatform.storage.VectorStore;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 记忆提取服务 - 从对话文本中提取结构化记忆并持久化
 * <p>
 * 完整的提取流水线:
 * <ol>
 *   <li>使用AdditiveExtractionPrompt构建提示词</li>
 *   <li>调用LLM提取记忆文本和元数据</li>
 *   <li>使用EntityExtractor提取/补充实体</li>
 *   <li>使用TimeParser解析时间信息</li>
 *   <li>生成embedding向量 (预留接口)</li>
 *   <li>计算重要性分数</li>
 *   <li>构建Memory对象</li>
 *   <li>保存到向量库 + 图库 + 元数据库</li>
 * </ol>
 *
 * 线程安全: 本服务是线程安全的, 所有内部状态都是无状态的。
 */
public class MemoryExtractionService {

    private static final Gson GSON = new Gson();

    private final LlmClient llmClient;
    private final EntityExtractor entityExtractor;
    private final TimeParser timeParser;
    private final EmbeddingService embeddingService;

    // 存储层 (可选注入)
    private VectorStore vectorStore;
    private GraphStore graphStore;
    private MetadataStore metadataStore;

    /**
     * 构造函数
     * @param llmClient LLM客户端
     * @param entityExtractor 实体提取器 (可为null, 使用默认)
     * @param timeParser 时间解析器 (可为null, 使用默认)
     * @param embeddingService Embedding服务 (可为null, 使用noOp)
     */
    public MemoryExtractionService(
            LlmClient llmClient,
            EntityExtractor entityExtractor,
            TimeParser timeParser,
            EmbeddingService embeddingService
    ) {
        this.llmClient = llmClient;
        this.entityExtractor = entityExtractor != null ? entityExtractor : new EntityExtractor(llmClient);
        this.timeParser = timeParser != null ? timeParser : new TimeParser();
        this.embeddingService = embeddingService != null ? embeddingService : EmbeddingService.noOp();

        System.out.println("[MemoryExtractionService] 初始化完成");
        System.out.println("  - LLM Client: " + (llmClient != null ? llmClient.getConfig().getProvider() : "null"));
        System.out.println("  - EntityExtractor: " + (entityExtractor != null ? "自定义" : "默认"));
        System.out.println("  - EmbeddingService: " + (this.embeddingService.getClass().getSimpleName()));
    }

    /**
     * 使用默认配置创建服务
     * @param llmClient LLM客户端
     * @return MemoryExtractionService实例
     */
    public static MemoryExtractionService createDefault(LlmClient llmClient) {
        return new MemoryExtractionService(llmClient, null, null, null);
    }

    // ==================== 存储层注入 ====================

    /**
     * 注入向量存储
     */
    public void setVectorStore(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 注入图存储
     */
    public void setGraphStore(GraphStore graphStore) {
        this.graphStore = graphStore;
    }

    /**
     * 注入元数据存储
     */
    public void setMetadataStore(MetadataStore metadataStore) {
        this.metadataStore = metadataStore;
    }

    // ==================== 核心提取方法 ====================

    /**
     * 从完整对话中提取记忆
     * @param messages 对话消息列表
     * @param userId 用户ID
     * @param agentId Agent ID
     * @return 提取的记忆列表
     */
    public List<Memory> extractFromConversation(List<Message> messages, String userId, String agentId) {
        System.out.println("[MemoryExtractionService] 开始从对话提取记忆, 消息数=" + messages.size());

        if (messages == null || messages.isEmpty()) {
            System.out.println("[MemoryExtractionService] 空对话, 跳过");
            return List.of();
        }

        try {
            // 1. 调用LLM提取记忆
            List<JsonObject> rawMemories = callLlmForExtraction(messages, userId, agentId);
            System.out.println("[MemoryExtractionService] LLM提取到 " + rawMemories.size() + " 条原始记忆");

            // 2. 处理每条记忆
            List<Memory> memories = new ArrayList<>();
            for (JsonObject raw : rawMemories) {
                Memory memory = processRawMemory(raw, userId, agentId);
                if (memory != null) {
                    memories.add(memory);
                }
            }

            System.out.println("[MemoryExtractionService] 最终提取 " + memories.size() + " 条记忆");

            // 注意: 不在此处保存，由调用方(MemoryHandler)通过ConcurrentWriteService统一管理写入
            // 避免双重写入导致数据不一致

            return memories;

        } catch (Exception e) {
            System.out.println("[MemoryExtractionService] 提取失败: " + e.getMessage());
            e.printStackTrace();
            return List.of();
        }
    }

    /**
     * 从单条消息中提取记忆
     * @param message 消息
     * @param userId 用户ID
     * @param agentId Agent ID
     * @return 提取的记忆, 无有价值信息返回null
     */
    public Memory extractFromMessage(Message message, String userId, String agentId) {
        System.out.println("[MemoryExtractionService] 从单条消息提取记忆");

        if (message == null || message.getContent() == null || message.getContent().isBlank()) {
            return null;
        }

        try {
            // 1. 构建单条消息提示词
            List<Message> prompt = AdditiveExtractionPrompt.buildSingleMessagePrompt(message, userId, agentId);

            // 2. 调用LLM
            List<JsonObject> rawMemories = callLlmForExtractionFromJson(prompt);
            if (rawMemories.isEmpty()) {
                System.out.println("[MemoryExtractionService] LLM未提取到记忆");
                return null;
            }

            // 3. 处理第一条记忆
            Memory memory = processRawMemory(rawMemories.get(0), userId, agentId);

            // 4. 保存
            if (memory != null) {
                saveMemory(memory);
            }

            return memory;

        } catch (Exception e) {
            System.out.println("[MemoryExtractionService] 单消息提取失败: " + e.getMessage());
            return null;
        }
    }

    // ==================== LLM调用 ====================

    /**
     * 调用LLM进行记忆提取
     */
    private List<JsonObject> callLlmForExtraction(List<Message> messages, String userId, String agentId) throws LlmClient.LlmException {
        List<Message> prompt = AdditiveExtractionPrompt.buildExtractionPrompt(
                messages, userId, agentId, null);
        return callLlmForExtractionFromJson(prompt);
    }

    /**
     * 调用LLM并解析JSON响应
     */
    private List<JsonObject> callLlmForExtractionFromJson(List<Message> prompt) throws LlmClient.LlmException {
        String response = llmClient.chat(prompt);
        return parseExtractionResponse(response);
    }

    /**
     * 解析LLM的提取响应
     */
    private List<JsonObject> parseExtractionResponse(String response) {
        List<JsonObject> result = new ArrayList<>();
        if (response == null || response.isBlank()) return result;

        try {
            String jsonStr = extractJsonFromResponse(response);
            JsonArray arr = JsonParser.parseString(jsonStr).getAsJsonArray();

            for (JsonElement el : arr) {
                if (el.isJsonObject()) {
                    result.add(el.getAsJsonObject());
                }
            }
        } catch (Exception e) {
            System.out.println("[MemoryExtractionService] 响应解析失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 从响应文本中提取JSON
     */
    private String extractJsonFromResponse(String text) {
        // 提取 ```json ... ``` 代码块
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?```")
                .matcher(text);
        if (m.find()) {
            return m.group(1).trim();
        }

        // 找到第一个 [ 或 {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[' || c == '{') {
                return text.substring(i).trim();
            }
        }
        return text;
    }

    // ==================== 记忆处理 ====================

    /**
     * 处理LLM返回的原始记忆对象, 构建完整的Memory
     */
    private Memory processRawMemory(JsonObject raw, String userId, String agentId) {
        try {
            // 1. 提取文本
            String text = raw.has("text") ? raw.get("text").getAsString() : "";
            if (text.isBlank()) {
                return null;
            }

            // 2. 提取重要性分数
            double importance = 0.5;
            if (raw.has("importance")) {
                importance = Math.max(0.0, Math.min(1.0, raw.get("importance").getAsDouble()));
            }

            // 3. 提取LLM识别的实体
            List<Entity> llmEntities = extractEntitiesFromJson(raw);

            // 4. 补充提取实体 (规则+LLM)
            List<Entity> ruleEntities = entityExtractor.extract(text);

            // 5. 合并实体
            List<Entity> allEntities = mergeEntities(llmEntities, ruleEntities);

            // 6. 解析时间信息
            Instant timeContext = extractTimeContext(text);

            // 7. 生成embedding (预留)
            float[] embedding = generateEmbedding(text);

            // 8. 构建Memory对象
            String memoryId = generateId(userId, text);

            Memory.Builder builder = Memory.builder()
                    .id(memoryId)
                    .text(text)
                    .userId(userId)
                    .agentId(agentId)
                    .entities(allEntities)
                    .importance(importance);

            if (embedding != null) {
                // 将float[]转换为double[] (Memory模型使用double[])
                double[] doubleEmbedding = new double[embedding.length];
                for (int i = 0; i < embedding.length; i++) {
                    doubleEmbedding[i] = embedding[i];
                }
                builder.embedding(doubleEmbedding);
            }

            Memory memory = builder.build();
            System.out.println("[MemoryExtractionService] 处理记忆: " + text.substring(0, Math.min(50, text.length()))
                    + "... | 实体数=" + allEntities.size() + " | 重要性=" + importance);

            return memory;

        } catch (Exception e) {
            System.out.println("[MemoryExtractionService] 处理原始记忆异常: " + e.getMessage());
            return null;
        }
    }

    /**
     * 从JSON对象中提取LLM识别的实体
     */
    private List<Entity> extractEntitiesFromJson(JsonObject raw) {
        List<Entity> entities = new ArrayList<>();
        if (!raw.has("entities")) return entities;

        try {
            JsonArray entitiesArr = raw.getAsJsonArray("entities");
            for (JsonElement el : entitiesArr) {
                JsonObject entityObj = el.getAsJsonObject();
                String name = entityObj.has("name") ? entityObj.get("name").getAsString() : "";
                String typeStr = entityObj.has("type") ? entityObj.get("type").getAsString() : "TOPIC";
                double confidence = entityObj.has("confidence") ? entityObj.get("confidence").getAsDouble() : 0.7;

                EntityType type = parseEntityType(typeStr);
                if (!name.isBlank() && confidence > 0.3) {
                    entities.add(new Entity(name.trim(), type, confidence));
                }
            }
        } catch (Exception e) {
            System.out.println("[MemoryExtractionService] 实体JSON解析失败: " + e.getMessage());
        }

        return entities;
    }

    /**
     * 合并两组实体 (去重, 取高置信度)
     */
    private List<Entity> mergeEntities(List<Entity> primary, List<Entity> secondary) {
        Map<String, Entity> merged = new LinkedHashMap<>();

        for (Entity e : primary) {
            merged.put(e.getNormalizedId(), e);
        }

        for (Entity e : secondary) {
            merged.merge(e.getNormalizedId(), e, (existing, newEntity) -> {
                double combined = Math.min(Math.max(existing.getConfidence(), newEntity.getConfidence()) + 0.05, 1.0);
                return new Entity(existing.getName(), existing.getType(), combined);
            });
        }

        return new ArrayList<>(merged.values());
    }

    /**
     * 从文本中提取时间上下文
     */
    private Instant extractTimeContext(String text) {
        // 尝试相对时间
        Instant relative = timeParser.parseRelative(text);
        if (relative != null) return relative;

        // 尝试绝对时间
        Instant absolute = timeParser.parseAbsolute(text);
        if (absolute != null) return absolute;

        return Instant.now();
    }

    /**
     * 生成embedding向量 (预留接口)
     */
    private float[] generateEmbedding(String text) {
        try {
            if (embeddingService != null && embeddingService.isAvailable()) {
                return embeddingService.embed(text);
            }
        } catch (Exception e) {
            System.out.println("[MemoryExtractionService] Embedding生成失败: " + e.getMessage());
        }
        return null;
    }

    // ==================== 存储操作 ====================

    /**
     * 批量保存记忆到所有存储
     */
    private void saveMemories(List<Memory> memories) {
        if (memories.isEmpty()) return;

        System.out.println("[MemoryExtractionService] 开始保存 " + memories.size() + " 条记忆");

        // 保存到元数据库
        saveToMetadataStore(memories);

        // 保存到向量库
        saveToVectorStore(memories);

        // 保存到图库
        saveToGraphStore(memories);
    }

    /**
     * 保存单条记忆
     */
    private void saveMemory(Memory memory) {
        saveMemories(List.of(memory));
    }

    /**
     * 保存到元数据存储
     */
    private void saveToMetadataStore(List<Memory> memories) {
        if (metadataStore == null) {
            System.out.println("[MemoryExtractionService] MetadataStore未配置, 跳过");
            return;
        }

        try {
            List<MetadataRecord> records = memories.stream()
                    .map(m -> {
                        Map<String, Object> data = new HashMap<>();
                        data.put("agentId", m.getAgentId());
                        data.put("importance", m.getImportance());
                        data.put("entityCount", m.getEntities() != null ? m.getEntities().size() : 0);

                        List<String> entityNames = m.getEntities() != null
                                ? m.getEntities().stream().map(Entity::getName).collect(Collectors.toList())
                                : List.of();
                        data.put("entities", entityNames);

                        return new MetadataRecord(
                                m.getId(), "memories", m.getUserId(), m.getAgentId(),
                                m.getText(), m.getImportance(), data
                        );
                    })
                    .collect(Collectors.toList());

            List<String> ids = metadataStore.batchInsert("memories", records);
            System.out.println("[MemoryExtractionService] MetadataStore保存成功, ID数=" + ids.size());

        } catch (Exception e) {
            System.out.println("[MemoryExtractionService] MetadataStore保存失败: " + e.getMessage());
        }
    }

    /**
     * 保存到向量存储
     */
    private void saveToVectorStore(List<Memory> memories) {
        if (vectorStore == null) {
            System.out.println("[MemoryExtractionService] VectorStore未配置, 跳过");
            return;
        }

        try {
            List<VectorRecord> records = memories.stream()
                    .filter(m -> m.getEmbedding() != null)
                    .map(m -> {
                        float[] floatEmbedding = new float[m.getEmbedding().length];
                        for (int i = 0; i < m.getEmbedding().length; i++) {
                            floatEmbedding[i] = (float) m.getEmbedding()[i];
                        }

                        Map<String, Object> metadata = new HashMap<>();
                        metadata.put("importance", m.getImportance());

                        return VectorRecord.builder()
                                .id(m.getId())
                                .collection("memories")
                                .vector(floatEmbedding)
                                .text(m.getText())
                                .userId(m.getUserId())
                                .agentId(m.getAgentId())
                                .entities(m.getEntities())
                                .importance(m.getImportance())
                                .metadata(metadata)
                                .build();
                    })
                    .collect(Collectors.toList());

            if (!records.isEmpty()) {
                boolean success = vectorStore.upsert("memories", records);
                System.out.println("[MemoryExtractionService] VectorStore保存成功: " + success);
            }

        } catch (Exception e) {
            System.out.println("[MemoryExtractionService] VectorStore保存失败: " + e.getMessage());
        }
    }

    /**
     * 保存到图存储 - 创建记忆节点和实体节点及关系
     */
    private void saveToGraphStore(List<Memory> memories) {
        if (graphStore == null) {
            System.out.println("[MemoryExtractionService] GraphStore未配置, 跳过");
            return;
        }

        try {
            for (Memory memory : memories) {
                // 创建记忆节点
                GraphNode memoryNode = GraphNode.builder()
                        .id(memory.getId())
                        .label("Memory")
                        .content(memory.getText())
                        .type("memory")
                        .userId(memory.getUserId())
                        .agentId(memory.getAgentId())
                        .properties(Map.of(
                                "importance", memory.getImportance(),
                                "createdAt", memory.getCreatedAt().toString()
                        ))
                        .build();

                String memoryNodeId = graphStore.createNode(memoryNode);

                // 创建实体节点和关系
                if (memory.getEntities() != null) {
                    for (Entity entity : memory.getEntities()) {
                        // 实体节点
                        GraphNode entityNode = GraphNode.builder()
                                .id(entity.getNormalizedId())
                                .label("Entity")
                                .content(entity.getName())
                                .type(entity.getType().name())
                                .userId(memory.getUserId())
                                .properties(Map.of(
                                        "entityType", entity.getType().name(),
                                        "confidence", entity.getConfidence()
                                ))
                                .build();

                        graphStore.createNode(entityNode);

                        // 记忆-实体 关系
                        GraphEdge edge = GraphEdge.builder()
                                .id(memoryNodeId + "->" + entity.getNormalizedId())
                                .sourceId(memoryNodeId)
                                .targetId(entity.getNormalizedId())
                                .type("CONTAINS_ENTITY")
                                .weight(entity.getConfidence())
                                .build();

                        graphStore.createEdge(edge);
                    }
                }
            }

            System.out.println("[MemoryExtractionService] GraphStore保存成功");

        } catch (Exception e) {
            System.out.println("[MemoryExtractionService] GraphStore保存失败: " + e.getMessage());
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 解析实体类型字符串
     */
    private EntityType parseEntityType(String typeStr) {
        if (typeStr == null) return EntityType.TOPIC;
        try {
            return EntityType.valueOf(typeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            String lower = typeStr.toLowerCase();
            for (EntityType t : EntityType.values()) {
                if (t.name().toLowerCase().contains(lower) || t.getDisplayName().contains(typeStr)) {
                    return t;
                }
            }
            return EntityType.TOPIC;
        }
    }

    /**
     * 生成记忆唯一ID
     */
    private String generateId(String userId, String text) {
        // 使用位运算确保非负数，避免Math.abs(Integer.MIN_VALUE)返回负数
        String hash = String.valueOf((userId + text).hashCode() & 0x7FFFFFFF);
        return "mem_" + hash + "_" + System.currentTimeMillis();
    }
}
