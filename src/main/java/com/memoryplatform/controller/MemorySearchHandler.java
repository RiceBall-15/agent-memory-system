package com.memoryplatform.controller;

import com.memoryplatform.dto.ApiResponse;
import com.memoryplatform.dto.SearchRequest;
import com.memoryplatform.model.Memory;
import com.memoryplatform.model.MemoryType;
import com.memoryplatform.model.MetadataRecord;
import com.memoryplatform.model.SearchQuery;
import com.memoryplatform.model.SearchResult;
import com.memoryplatform.service.HybridRetrievalService;
import com.memoryplatform.storage.MetadataStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 记忆搜索操作处理器
 * <p>
 * 处理记忆的搜索和列表查询操作：
 * <ul>
 *   <li>GET /api/memories - 搜索/列表查询记忆（支持语义搜索和列表查询）</li>
 *   <li>POST /api/memories/search - 语义搜索记忆</li>
 * </ul>
 * </p>
 *
 * @author MemoryPlatform
 * @since 2.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemorySearchHandler {

    private static final String METADATA_TABLE = "memories";

    private final HybridRetrievalService retrievalService;
    private final MetadataStore metadataStore;

    // ==================== 搜索/列表查询记忆 ====================

    /**
     * 搜索/列表查询记忆
     * <p>
     * 支持两种模式：
     * <ul>
     *   <li>语义搜索：提供searchText参数时，使用混合检索服务进行语义搜索</li>
     *   <li>列表查询：不提供searchText时，按userId/agentId过滤列表</li>
     * </ul>
     * </p>
     *
     * @param userId     用户ID（必需）
     * @param agentId    智能体ID（可选）
     * @param searchText 搜索文本（可选，提供时触发语义搜索）
     * @param memoryType 记忆类型过滤（可选）
     * @param limit      返回数量（默认20，最大100）
     * @param offset     偏移量（默认0）
     * @return 记忆列表
     */
    public ResponseEntity<ApiResponse<Map<String, Object>>> listMemories(
            String userId, String agentId, String searchText,
            MemoryType memoryType, int limit, int offset) {

        log.info("[MemorySearchHandler] 搜索/列表查询: userId={}, agentId={}, memoryType={}, searchText={}, limit={}, offset={}",
                userId, agentId, memoryType, searchText, limit, offset);

        try {
            // 限制参数范围
            limit = Math.min(Math.max(limit, 1), 100);
            offset = Math.max(offset, 0);

            Map<String, Object> result;

            if (searchText != null && !searchText.isBlank()) {
                // 语义搜索模式
                result = executeSemanticSearch(userId, agentId, searchText, limit, offset, memoryType);
            } else {
                // 列表查询模式
                result = executeListQuery(userId, agentId, limit, offset, memoryType);
            }

            log.info("[MemorySearchHandler] 查询完成: total={}, returned={}",
                    result.get("total"), ((List<?>) result.get("memories")).size());
            return ResponseEntity.ok(ApiResponse.ok(result));
        } catch (Exception e) {
            log.error("[MemorySearchHandler] 查询异常: userId={}", userId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.internalError("查询失败: " + e.getMessage()));
        }
    }

    // ==================== 语义搜索记忆 ====================

    /**
     * 语义搜索记忆（POST方式，支持复杂搜索条件）
     *
     * @param request 搜索请求
     * @return 搜索结果
     */
    public ResponseEntity<ApiResponse<Map<String, Object>>> searchMemories(SearchRequest request) {
        log.info("[MemorySearchHandler] 语义搜索: userId={}, text={}", request.getUserId(), request.getText());

        try {
            Map<String, Object> result = executeSemanticSearch(
                    request.getUserId(), request.getAgentId(), request.getText(),
                    request.getTopK(), 0, request.getMemoryType());

            log.info("[MemorySearchHandler] 搜索完成: 返回{}条结果", ((List<?>) result.get("memories")).size());
            return ResponseEntity.ok(ApiResponse.ok(result));
        } catch (Exception e) {
            log.error("[MemorySearchHandler] 搜索异常: userId={}", request.getUserId(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.internalError("搜索失败: " + e.getMessage()));
        }
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 执行语义搜索
     *
     * @param userId     用户ID
     * @param agentId    智能体ID
     * @param searchText 搜索文本
     * @param topK       返回数量
     * @param offset     偏移量
     * @param memoryType 记忆类型过滤（可选）
     * @return 搜索结果Map
     */
    private Map<String, Object> executeSemanticSearch(String userId, String agentId,
                                                       String searchText, int topK, int offset,
                                                       MemoryType memoryType) {
        SearchQuery query = SearchQuery.builder()
                .text(searchText)
                .userId(userId)
                .agentId(agentId)
                .topK(topK)
                .threshold(0.3) // 降低阈值以获取更多结果
                .memoryType(memoryType)
                .build();

        List<SearchResult> searchResults = retrievalService.search(query);

        // 应用偏移量
        if (offset > 0 && offset < searchResults.size()) {
            searchResults = searchResults.subList(offset, searchResults.size());
        } else if (offset >= searchResults.size()) {
            searchResults = Collections.emptyList();
        }

        // 转换为Memory对象
        List<Memory> memories = searchResults.stream()
                .map(this::buildMemoryFromSearchResult)
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("memories", memories);
        result.put("total", searchResults.size());
        result.put("searchText", searchText);
        result.put("userId", userId);
        result.put("agentId", agentId);

        return result;
    }

    /**
     * 执行列表查询
     *
     * @param userId     用户ID
     * @param agentId    智能体ID
     * @param limit      返回数量
     * @param offset     偏移量
     * @param memoryType 记忆类型过滤（可选）
     * @return 查询结果Map
     */
    private Map<String, Object> executeListQuery(String userId, String agentId,
                                                  int limit, int offset,
                                                  MemoryType memoryType) {
        // 构建过滤条件
        Map<String, Object> filters = new HashMap<>();
        filters.put("user_id", userId);
        if (agentId != null && !agentId.isBlank()) {
            filters.put("agent_id", agentId);
        }

        // 查询总数
        long totalCount = metadataStore.count(METADATA_TABLE, filters);

        // 查询记录（带分页）
        List<MetadataRecord> records = metadataStore.find(METADATA_TABLE, filters, limit, offset);

        if (records == null) {
            records = Collections.emptyList();
        }

        // 转换为Memory对象
        List<Memory> memories = records.stream()
                .map(MemoryCrudHandler::buildMemoryFromRecord)
                .collect(Collectors.toList());

        // 应用memoryType过滤
        if (memoryType != null) {
            memories = memories.stream()
                    .filter(m -> memoryType.equals(m.getMemoryType()))
                    .collect(Collectors.toList());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("memories", memories);
        result.put("total", totalCount);
        result.put("limit", limit);
        result.put("offset", offset);
        result.put("userId", userId);
        result.put("agentId", agentId);

        return result;
    }

    /**
     * 从SearchResult构建Memory对象
     *
     * @param result 搜索结果
     * @return Memory对象
     */
    private Memory buildMemoryFromSearchResult(SearchResult result) {
        Memory.MemoryBuilder builder = Memory.builder()
                .id(result.getId())
                .text(result.getText())
                .importance(0.5)
                .status("ACTIVE")
                .memoryType(MemoryType.DEFAULT);

        // 从metadata提取额外信息
        if (result.getMetadata() != null) {
            Map<String, Object> meta = result.getMetadata();
            if (meta.containsKey("userId")) {
                builder.userId(meta.get("userId").toString());
            }
            if (meta.containsKey("agentId")) {
                builder.agentId(meta.get("agentId").toString());
            }
            if (meta.containsKey("importance")) {
                builder.importance(((Number) meta.get("importance")).doubleValue());
            }
            if (meta.containsKey("memoryType")) {
                try {
                    builder.memoryType(MemoryType.valueOf(meta.get("memoryType").toString()));
                } catch (Exception ignored) {
                    // 保持默认值
                }
            }
            if (meta.containsKey("createdAt")) {
                try {
                    builder.createdAt(java.time.Instant.parse(meta.get("createdAt").toString()));
                } catch (Exception ignored) {}
            }
            if (meta.containsKey("updatedAt")) {
                try {
                    builder.updatedAt(java.time.Instant.parse(meta.get("updatedAt").toString()));
                } catch (Exception ignored) {}
            }
        }

        return builder.build();
    }
}
