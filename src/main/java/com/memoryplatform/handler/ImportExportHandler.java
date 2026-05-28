package com.memoryplatform.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.memoryplatform.model.MetadataRecord;
import com.memoryplatform.server.HttpHandler;
import com.memoryplatform.service.ConcurrentWriteService;
import com.memoryplatform.storage.MetadataStore;
import com.memoryplatform.util.HashUtil;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 导入/导出处理器 - 支持记忆的批量导入导出和迁移
 * <p>
 * 提供以下API端点:
 * <ul>
 *   <li>{@code POST /api/memories/export} - 导出记忆（支持按用户/Agent/时间范围过滤）</li>
 *   <li>{@code POST /api/memories/import} - 导入记忆（支持三种导入策略）</li>
 *   <li>{@code GET /api/memories/export/file} - 下载导出文件（返回JSON文件）</li>
 * </ul>
 * </p>
 *
 * <h3>导入策略</h3>
 * <ul>
 *   <li>{@code skip} - 跳过重复（基于内容SHA-256哈希）</li>
 *   <li>{@code overwrite} - 覆盖已存在的记忆</li>
 *   <li>{@code merge} - 合并（更新importance取较大值，保留原始时间戳）</li>
 * </ul>
 *
 * <h3>重复检测</h3>
 * <p>基于SHA-256内容哈希（组合text + userId + agentId），存储在metadata的data字段中。</p>
 *
 * <h3>依赖服务</h3>
 * <ul>
 *   <li>{@link MetadataStore} - 元数据CRUD查询</li>
 *   <li>{@link ConcurrentWriteService} - 高并发异步写入</li>
 * </ul>
 *
 * @author MemoryPlatform
 * @version 1.0
 * @since 1.0
 */
public class ImportExportHandler implements HttpHandler {

    /** 元数据表名 */
    private static final String METADATA_TABLE = "memories";

    /** 内容哈希字段名（存储在metadata的data map中） */
    private static final String CONTENT_HASH_KEY = "content_hash";

    /** 导出最大数量限制 */
    private static final int MAX_EXPORT_LIMIT = 10000;

    /** 导入最大数量限制 */
    private static final int MAX_IMPORT_LIMIT = 5000;

    /** 默认导出数量 */
    private static final int DEFAULT_EXPORT_LIMIT = 200;

    /** 写入超时时间（秒） */
    private static final int WRITE_TIMEOUT_SECONDS = 30;

    /** 元数据存储 */
    private final MetadataStore metadataStore;

    /** 高并发写入服务（可为null） */
    private final ConcurrentWriteService writeService;

    /**
     * 构造导入/导出处理器
     *
     * @param metadataStore 元数据存储
     * @param writeService  高并发写入服务（可为null，导入时降级为直接写入）
     */
    public ImportExportHandler(MetadataStore metadataStore, ConcurrentWriteService writeService) {
        this.metadataStore = metadataStore;
        this.writeService = writeService;
        log("[ImportExportHandler] 初始化完成");
    }

    @Override
    public void handle(HttpExchange exchange, Map<String, String> pathParams) throws IOException {
        String method = getMethod(exchange);
        String path = getPath(exchange);

        log("[ImportExportHandler] 请求: " + method + " " + path);

        try {
            // GET /api/memories/export/file - 下载导出文件
            if ("GET".equals(method) && path.equals("/api/memories/export/file")) {
                handleExportFile(exchange);
                return;
            }

            // POST /api/memories/export - 导出记忆
            if ("POST".equals(method) && path.equals("/api/memories/export")) {
                handleExport(exchange);
                return;
            }

            // POST /api/memories/import - 导入记忆
            if ("POST".equals(method) && path.equals("/api/memories/import")) {
                handleImport(exchange);
                return;
            }

            errorResponse(exchange, 405, "不支持的请求: " + method + " " + path);

        } catch (Exception e) {
            logError("[ImportExportHandler] 处理请求异常: " + e.getMessage());
            e.printStackTrace();
            errorResponse(exchange, 500, "内部服务器错误: " + e.getMessage());
        }
    }

    // ==================== POST /api/memories/export ====================

    /**
     * 导出记忆 - 按条件过滤并返回JSON格式记忆列表
     * <p>
     * 请求体格式:
     * <pre>{@code
     * {
     *   "user_id": "xxx",        // 可选，按用户过滤
     *   "agent_id": "xxx",       // 可选，按Agent过滤
     *   "start_time": "...",     // 可选，开始时间（ISO-8601）
     *   "end_time": "...",       // 可选，结束时间（ISO-8601）
     *   "format": "json",        // 可选，输出格式（默认json）
     *   "limit": 200,            // 可选，最大导出数量
     *   "offset": 0              // 可选，偏移量
     * }
     * }</pre>
     * </p>
     *
     * @param exchange HTTP交换对象
     * @throws IOException 如果IO操作失败
     */
    private void handleExport(HttpExchange exchange) throws IOException {
        log("[ImportExportHandler] 导出记忆 - 开始");

        if (metadataStore == null) {
            errorResponse(exchange, 503, "元数据存储未配置");
            return;
        }

        // 1. 解析请求体
        String body = readBody(exchange);
        JsonObject requestJson = null;
        if (body != null && !body.isBlank()) {
            try {
                requestJson = JsonParser.parseString(body).getAsJsonObject();
            } catch (Exception e) {
                errorResponse(exchange, 400, "无效的JSON格式: " + e.getMessage());
                return;
            }
        }

        // 2. 构建过滤条件
        Map<String, Object> filters = buildFilters(requestJson);

        // 3. 解析时间范围过滤（需在内存中过滤，因为MetadataStore不支持时间范围查询）
        Instant startTime = null;
        Instant endTime = null;
        if (requestJson != null) {
            if (requestJson.has("start_time")) {
                try {
                    startTime = Instant.parse(requestJson.get("start_time").getAsString());
                } catch (DateTimeParseException e) {
                    errorResponse(exchange, 400, "无效的start_time格式，请使用ISO-8601: " + e.getMessage());
                    return;
                }
            }
            if (requestJson.has("end_time")) {
                try {
                    endTime = Instant.parse(requestJson.get("end_time").getAsString());
                } catch (DateTimeParseException e) {
                    errorResponse(exchange, 400, "无效的end_time格式，请使用ISO-8601: " + e.getMessage());
                    return;
                }
            }
        }

        // 4. 分页查询
        int limit = DEFAULT_EXPORT_LIMIT;
        int offset = 0;
        if (requestJson != null) {
            if (requestJson.has("limit")) {
                limit = Math.min(requestJson.get("limit").getAsInt(), MAX_EXPORT_LIMIT);
            }
            if (requestJson.has("offset")) {
                offset = Math.max(0, requestJson.get("offset").getAsInt());
            }
        }

        log("[ImportExportHandler] 导出过滤: " + filters + ", limit=" + limit + ", offset=" + offset);

        try {
            List<MetadataRecord> records = metadataStore.find(METADATA_TABLE, filters, limit, offset);

            // 5. 时间范围过滤
            if (startTime != null || endTime != null) {
                records = records.stream()
                        .filter(r -> {
                            Instant created = r.getCreatedAt();
                            if (created == null) return true; // 无时间戳的记录保留
                            if (startTime != null && created.isBefore(startTime)) return false;
                            if (endTime != null && created.isAfter(endTime)) return false;
                            return true;
                        })
                        .collect(Collectors.toList());
            }

            // 6. 构建导出数据
            List<Map<String, Object>> memories = new ArrayList<>();
            for (MetadataRecord record : records) {
                Map<String, Object> memoryData = buildExportRecord(record);
                memories.add(memoryData);
            }

            // 7. 构建响应
            Map<String, Object> responseData = new HashMap<>();
            responseData.put("memories", memories);
            responseData.put("count", memories.size());
            responseData.put("exported_at", Instant.now().toString());
            responseData.put("filters", filters);

            Map<String, Object> envelope = new HashMap<>();
            envelope.put("success", true);
            envelope.put("data", responseData);

            jsonResponse(exchange, 200, envelope);
            log("[ImportExportHandler] 导出完成: count=" + memories.size());

        } catch (Exception e) {
            logError("[ImportExportHandler] 导出失败: " + e.getMessage());
            errorResponse(exchange, 500, "导出失败: " + e.getMessage());
        }
    }

    // ==================== POST /api/memories/import ====================

    /**
     * 导入记忆 - 支持批量导入和三种导入策略
     * <p>
     * 请求体格式:
     * <pre>{@code
     * {
     *   "memories": [
     *     {
     *       "id": "xxx",           // 可选，不提供则自动生成
     *       "text": "记忆内容",
     *       "userId": "user123",
     *       "agentId": "agent456",  // 可选
     *       "importance": 0.8,      // 可选，默认0.5
     *       "createdAt": "...",     // 可选，ISO-8601时间戳
     *       "metadata": {}          // 可选，附加元数据
     *     }
     *   ],
     *   "strategy": "skip"         // skip|overwrite|merge
     * }
     * }</pre>
     * </p>
     *
     * @param exchange HTTP交换对象
     * @throws IOException 如果IO操作失败
     */
    private void handleImport(HttpExchange exchange) throws IOException {
        log("[ImportExportHandler] 导入记忆 - 开始");

        if (metadataStore == null) {
            errorResponse(exchange, 503, "元数据存储未配置");
            return;
        }

        // 1. 读取并解析请求体
        String body = readBody(exchange);
        if (body == null || body.isBlank()) {
            errorResponse(exchange, 400, "请求体不能为空");
            return;
        }

        JsonObject requestJson;
        try {
            requestJson = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            errorResponse(exchange, 400, "无效的JSON格式: " + e.getMessage());
            return;
        }

        // 2. 验证memories数组
        if (!requestJson.has("memories") || !requestJson.get("memories").isJsonArray()) {
            errorResponse(exchange, 400, "缺少必需字段: memories (数组)");
            return;
        }

        JsonArray memoriesArray = requestJson.getAsJsonArray("memories");
        if (memoriesArray.isEmpty()) {
            errorResponse(exchange, 400, "memories数组不能为空");
            return;
        }
        if (memoriesArray.size() > MAX_IMPORT_LIMIT) {
            errorResponse(exchange, 400, "批量导入最多支持" + MAX_IMPORT_LIMIT + "条，当前: " + memoriesArray.size());
            return;
        }

        // 3. 解析导入策略
        String strategy = "skip";
        if (requestJson.has("strategy")) {
            strategy = requestJson.get("strategy").getAsString().toLowerCase();
            if (!"skip".equals(strategy) && !"overwrite".equals(strategy) && !"merge".equals(strategy)) {
                errorResponse(exchange, 400, "无效的strategy: " + strategy + "，支持: skip, overwrite, merge");
                return;
            }
        }

        log("[ImportExportHandler] 导入: " + memoriesArray.size() + " 条记忆, 策略: " + strategy);

        // 4. 执行导入
        int imported = 0;
        int skipped = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();

        for (int i = 0; i < memoriesArray.size(); i++) {
            JsonObject memoryJson = memoriesArray.get(i).getAsJsonObject();

            try {
                ImportResult result = importSingleMemory(memoryJson, strategy);
                switch (result.status) {
                    case IMPORTED:
                        imported++;
                        break;
                    case SKIPPED:
                        skipped++;
                        break;
                    case FAILED:
                        failed++;
                        if (result.errorMessage != null) {
                            errors.add("[" + i + "] " + result.errorMessage);
                        }
                        break;
                }
            } catch (Exception e) {
                failed++;
                errors.add("[" + i + "] " + e.getMessage());
                logError("[ImportExportHandler] 导入第" + i + "条失败: " + e.getMessage());
            }
        }

        // 5. 构建响应
        Map<String, Object> responseData = new HashMap<>();
        responseData.put("imported", imported);
        responseData.put("skipped", skipped);
        responseData.put("failed", failed);
        responseData.put("total", memoriesArray.size());
        responseData.put("strategy", strategy);
        if (!errors.isEmpty()) {
            responseData.put("errors", errors);
        }

        Map<String, Object> envelope = new HashMap<>();
        envelope.put("success", failed == 0);
        envelope.put("data", responseData);

        int statusCode = (failed == 0) ? 200 : (imported == 0) ? 400 : 207;
        jsonResponse(exchange, statusCode, envelope);
        log("[ImportExportHandler] 导入完成: imported=" + imported + ", skipped=" + skipped + ", failed=" + failed);
    }

    // ==================== GET /api/memories/export/file ====================

    /**
     * 下载导出文件 - 以JSON文件形式下载记忆数据
     * <p>
     * 查询参数:
     * <ul>
     *   <li>{@code user_id} - 用户ID过滤</li>
     *   <li>{@code agent_id} - Agent ID过滤</li>
     *   <li>{@code format} - 输出格式（默认json）</li>
     * </ul>
     * </p>
     *
     * @param exchange HTTP交换对象
     * @throws IOException 如果IO操作失败
     */
    private void handleExportFile(HttpExchange exchange) throws IOException {
        log("[ImportExportHandler] 下载导出文件 - 开始");

        if (metadataStore == null) {
            errorResponse(exchange, 503, "元数据存储未配置");
            return;
        }

        // 1. 解析查询参数
        Map<String, String> queryParams = getQueryParams(exchange);
        Map<String, Object> filters = new HashMap<>();
        if (queryParams.containsKey("user_id") && !queryParams.get("user_id").isBlank()) {
            filters.put("userId", queryParams.get("user_id"));
        }
        if (queryParams.containsKey("agent_id") && !queryParams.get("agent_id").isBlank()) {
            filters.put("agentId", queryParams.get("agent_id"));
        }

        String format = queryParams.getOrDefault("format", "json");

        try {
            // 2. 查询所有匹配记录（分页遍历）
            List<MetadataRecord> allRecords = new ArrayList<>();
            int pageSize = 100;
            int offset = 0;
            long totalCount;

            do {
                List<MetadataRecord> page = metadataStore.find(METADATA_TABLE, filters, pageSize, offset);
                allRecords.addAll(page);
                totalCount = metadataStore.count(METADATA_TABLE, filters);
                offset += pageSize;
            } while (offset < totalCount && offset < MAX_EXPORT_LIMIT);

            // 3. 构建导出JSON
            List<Map<String, Object>> memories = new ArrayList<>();
            for (MetadataRecord record : allRecords) {
                memories.add(buildExportRecord(record));
            }

            JsonObject exportData = new JsonObject();
            exportData.addProperty("version", "1.0");
            exportData.addProperty("exported_at", Instant.now().toString());
            exportData.addProperty("count", memories.size());

            JsonArray memoriesJson = GSON.toJsonTree(memories).getAsJsonArray();
            exportData.add("memories", memoriesJson);

            // 4. 设置文件下载响应头
            String timestamp = Instant.now().toString().replace(":", "-").replace("T", "_").substring(0, 19);
            String filename = "memories_export_" + timestamp + ".json";

            byte[] jsonBytes = exportData.toString().getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
            exchange.getResponseHeaders().set("Content-Length", String.valueOf(jsonBytes.length));

            exchange.sendResponseHeaders(200, jsonBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(jsonBytes);
            }

            log("[ImportExportHandler] 下载导出文件完成: filename=" + filename + ", count=" + memories.size());

        } catch (Exception e) {
            logError("[ImportExportHandler] 下载导出文件失败: " + e.getMessage());
            errorResponse(exchange, 500, "下载导出文件失败: " + e.getMessage());
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 导入单条记忆
     *
     * @param memoryJson 记忆JSON对象
     * @param strategy   导入策略
     * @return 导入结果
     */
    private ImportResult importSingleMemory(JsonObject memoryJson, String strategy) throws Exception {
        // 1. 解析必需字段
        if (!memoryJson.has("text") || memoryJson.get("text").getAsString().isBlank()) {
            return ImportResult.failed("缺少text字段");
        }
        if (!memoryJson.has("userId") || memoryJson.get("userId").getAsString().isBlank()) {
            return ImportResult.failed("缺少userId字段");
        }

        String text = memoryJson.get("text").getAsString();
        String userId = memoryJson.get("userId").getAsString();
        String agentId = memoryJson.has("agentId") ? memoryJson.get("agentId").getAsString() : null;
        double importance = memoryJson.has("importance") ? memoryJson.get("importance").getAsDouble() : 0.5;

        // 2. 计算内容哈希
        String contentHash = HashUtil.memoryContentHash(text, userId, agentId);

        // 3. 查找是否已存在相同哈希的记录
        MetadataRecord existingRecord = findExistingByHash(contentHash);

        // 4. 根据策略处理
        if (existingRecord != null) {
            switch (strategy) {
                case "skip":
                    log("[ImportExportHandler] 跳过重复记忆: hash=" + contentHash.substring(0, 8) + "...");
                    return ImportResult.skipped();

                case "overwrite":
                    // 用新数据覆盖
                    return overwriteMemory(existingRecord, memoryJson, contentHash);

                case "merge":
                    // 合并：importance取较大值，保留原始时间戳
                    return mergeMemory(existingRecord, memoryJson, contentHash, importance);

                default:
                    return ImportResult.failed("未知策略: " + strategy);
            }
        }

        // 5. 新记忆 - 插入
        MetadataRecord record = buildImportRecord(memoryJson, contentHash);
        String id = metadataStore.insert(METADATA_TABLE, record);

        if (id != null) {
            log("[ImportExportHandler] 导入新记忆: id=" + id);
            return ImportResult.imported();
        } else {
            return ImportResult.failed("插入失败");
        }
    }

    /**
     * 覆盖已有记忆
     */
    private ImportResult overwriteMemory(MetadataRecord existing, JsonObject memoryJson,
                                          String contentHash) throws Exception {
        Map<String, Object> updates = new HashMap<>();
        updates.put("content", memoryJson.get("text").getAsString());
        updates.put("importance", memoryJson.has("importance") ? memoryJson.get("importance").getAsDouble() : 0.5);
        updates.put("userId", memoryJson.get("userId").getAsString());
        if (memoryJson.has("agentId")) {
            updates.put("agentId", memoryJson.get("agentId").getAsString());
        }
        updates.put("updatedAt", Instant.now().toString());

        // 更新content_hash
        Map<String, Object> data = existing.getData() != null ? new HashMap<>(existing.getData()) : new HashMap<>();
        data.put(CONTENT_HASH_KEY, contentHash);
        updates.put("data", data);

        boolean success = metadataStore.update(METADATA_TABLE, existing.getId(), updates);

        if (success) {
            log("[ImportExportHandler] 覆盖记忆: id=" + existing.getId());
            return ImportResult.imported();
        } else {
            return ImportResult.failed("覆盖更新失败: " + existing.getId());
        }
    }

    /**
     * 合并已有记忆
     */
    private ImportResult mergeMemory(MetadataRecord existing, JsonObject memoryJson,
                                      String contentHash, double newImportance) throws Exception {
        Map<String, Object> updates = new HashMap<>();

        // importance取较大值
        double existingImportance = existing.getImportance();
        double mergedImportance = Math.max(existingImportance, newImportance);
        updates.put("importance", mergedImportance);

        // 更新内容（新内容覆盖旧内容）
        updates.put("content", memoryJson.get("text").getAsString());

        // 更新agentId（如果新的不为空）
        if (memoryJson.has("agentId")) {
            updates.put("agentId", memoryJson.get("agentId").getAsString());
        }

        updates.put("updatedAt", Instant.now().toString());

        // 更新content_hash
        Map<String, Object> data = existing.getData() != null ? new HashMap<>(existing.getData()) : new HashMap<>();
        data.put(CONTENT_HASH_KEY, contentHash);
        updates.put("data", data);

        boolean success = metadataStore.update(METADATA_TABLE, existing.getId(), updates);

        if (success) {
            log("[ImportExportHandler] 合并记忆: id=" + existing.getId() + ", importance=" + mergedImportance);
            return ImportResult.imported();
        } else {
            return ImportResult.failed("合并更新失败: " + existing.getId());
        }
    }

    /**
     * 根据内容哈希查找已存在的记录
     * <p>
     * 遍历所有记录检查data.content_hash字段。
     * 对于大数据量场景，建议在存储层建立content_hash索引。
     * </p>
     */
    private MetadataRecord findExistingByHash(String contentHash) {
        if (metadataStore == null || contentHash == null) {
            return null;
        }

        try {
            // 查询所有记录，检查content_hash
            // 为了性能，限制单次扫描范围
            int pageSize = 100;
            int offset = 0;
            int maxScan = 1000; // 最多扫描1000条

            while (offset < maxScan) {
                List<MetadataRecord> records = metadataStore.find(METADATA_TABLE, new HashMap<>(), pageSize, offset);
                if (records.isEmpty()) break;

                for (MetadataRecord record : records) {
                    if (record.getData() != null) {
                        Object hashObj = record.getData().get(CONTENT_HASH_KEY);
                        if (hashObj != null && contentHash.equals(hashObj.toString())) {
                            return record;
                        }
                    }
                }

                offset += pageSize;
            }
        } catch (Exception e) {
            logError("[ImportExportHandler] 查找已有记录失败: " + e.getMessage());
        }

        return null;
    }

    /**
     * 构建导入用的MetadataRecord
     */
    private MetadataRecord buildImportRecord(JsonObject memoryJson, String contentHash) {
        MetadataRecord record = new MetadataRecord();

        // 使用提供的ID或生成新的
        String id = memoryJson.has("id") ? memoryJson.get("id").getAsString() : UUID.randomUUID().toString();
        record.setId(id);
        record.setTable(METADATA_TABLE);
        record.setUserId(memoryJson.get("userId").getAsString());
        record.setAgentId(memoryJson.has("agentId") ? memoryJson.get("agentId").getAsString() : null);
        record.setContent(memoryJson.get("text").getAsString());
        record.setImportance(memoryJson.has("importance") ? memoryJson.get("importance").getAsDouble() : 0.5);

        // 设置时间戳
        if (memoryJson.has("createdAt")) {
            try {
                record.setCreatedAt(Instant.parse(memoryJson.get("createdAt").getAsString()));
            } catch (DateTimeParseException e) {
                record.setCreatedAt(Instant.now());
            }
        } else {
            record.setCreatedAt(Instant.now());
        }
        record.setUpdatedAt(Instant.now());

        // 存储元数据和内容哈希
        Map<String, Object> data = new HashMap<>();
        data.put(CONTENT_HASH_KEY, contentHash);
        if (memoryJson.has("metadata") && memoryJson.get("metadata").isJsonObject()) {
            JsonObject metaObj = memoryJson.getAsJsonObject("metadata");
            for (Map.Entry<String, JsonElement> entry : metaObj.entrySet()) {
                data.put(entry.getKey(), entry.getValue());
            }
        }
        record.setData(data);

        return record;
    }

    /**
     * 构建导出记录Map
     */
    private Map<String, Object> buildExportRecord(MetadataRecord record) {
        Map<String, Object> memoryData = new HashMap<>();
        memoryData.put("id", record.getId());
        memoryData.put("text", record.getContent());
        memoryData.put("userId", record.getUserId());
        memoryData.put("agentId", record.getAgentId());
        memoryData.put("importance", record.getImportance());
        memoryData.put("createdAt", record.getCreatedAt() != null ? record.getCreatedAt().toString() : null);
        memoryData.put("updatedAt", record.getUpdatedAt() != null ? record.getUpdatedAt().toString() : null);

        if (record.getData() != null) {
            // 排除content_hash，不导出内部哈希字段
            Map<String, Object> metadata = record.getData().entrySet().stream()
                    .filter(e -> !CONTENT_HASH_KEY.equals(e.getKey()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            if (!metadata.isEmpty()) {
                memoryData.put("metadata", metadata);
            }
        }

        return memoryData;
    }

    /**
     * 构建过滤条件
     */
    private Map<String, Object> buildFilters(JsonObject requestJson) {
        Map<String, Object> filters = new HashMap<>();
        if (requestJson == null) return filters;

        if (requestJson.has("user_id")) {
            String userId = requestJson.get("user_id").getAsString();
            if (userId != null && !userId.isBlank()) {
                filters.put("userId", userId);
            }
        }
        if (requestJson.has("agent_id")) {
            String agentId = requestJson.get("agent_id").getAsString();
            if (agentId != null && !agentId.isBlank()) {
                filters.put("agentId", agentId);
            }
        }
        return filters;
    }

    // ==================== 导入结果 ====================

    /**
     * 导入结果枚举
     */
    private enum ImportStatus {
        IMPORTED, SKIPPED, FAILED
    }

    /**
     * 导入结果
     */
    private static class ImportResult {
        final ImportStatus status;
        final String errorMessage;

        private ImportResult(ImportStatus status, String errorMessage) {
            this.status = status;
            this.errorMessage = errorMessage;
        }

        static ImportResult imported() {
            return new ImportResult(ImportStatus.IMPORTED, null);
        }

        static ImportResult skipped() {
            return new ImportResult(ImportStatus.SKIPPED, null);
        }

        static ImportResult failed(String errorMessage) {
            return new ImportResult(ImportStatus.FAILED, errorMessage);
        }
    }
}
