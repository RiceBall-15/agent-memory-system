package com.memoryplatform.config;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
/**
 * 应用配置管理类 - 从 application.json 读取并解析所有配置
 *
 * <p>提供类型安全的配置访问方法，支持嵌套配置结构。
 * 所有配置项都有合理的默认值，未配置时使用默认值。</p>
 *
 * <h3>配置结构示例</h3>
 * <pre>{@code
 * {
 *   "server": {"port": 8080},
 *   "vectorStore": {"type": "milvus", "host": "localhost", "port": 19530},
 *   "graphStore": {"type": "neo4j", "uri": "bolt://localhost:7687"},
 *   "metadataStore": {"type": "mysql", "url": "jdbc:mysql://..."},
 *   "llm": {"type": "ollama", "baseUrl": "http://localhost:11434", "model": "qwen2.5"},
 *   "embedding": {"type": "local", "dimension": 1536},
 *   "circuitBreaker": {"failureThreshold": 5, "recoveryTimeout": 30000},
 *   "metrics": {"enabled": true, "port": 9090}
 * }
 * }</pre>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * ApplicationConfig config = ApplicationConfig.load();
 * int port = config.getServerPort();
 * String vectorType = config.getVectorStoreType();
 * }</pre>
 *
 * @author MemoryPlatform
 * @version 1.0
 * @since 1.0
 */
@Slf4j
public class ApplicationConfig {

    /** 默认配置文件路径 */
    private static final String DEFAULT_CONFIG_PATH = "application.json";

    /** Gson实例 */
    private static final Gson GSON = new Gson();

    /** 原始配置JSON对象 */
    private final JsonObject root;

    /** 存储配置（传递给StorageFactory的Map） */
    private Map<String, Object> storageGlobalConfig;

    /**
     * 加载默认配置文件 application.json
     *
     * @return ApplicationConfig实例
     * @throws RuntimeException 如果配置文件不存在或解析失败
     */
    public static ApplicationConfig load() {
        return load(DEFAULT_CONFIG_PATH);
    }

    /**
     * 加载指定路径的配置文件
     *
     * @param path classpath下的配置文件路径
     * @return ApplicationConfig实例
     * @throws RuntimeException 如果配置文件不存在或解析失败
     */
    public static ApplicationConfig load(String path) {
        log.info("[ApplicationConfig] 加载配置文件: " + path)

        try (InputStream is = ApplicationConfig.class.getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                log.info("[ApplicationConfig] 配置文件未找到，使用默认配置: " + path)
                return createDefault();
            }
            try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                log.info("[ApplicationConfig] 配置文件加载成功")
                return new ApplicationConfig(json);
            }
        } catch (Exception e) {
            log.error("[ApplicationConfig] 配置文件加载失败: " + e.getMessage());
            log.info("[ApplicationConfig] 使用默认配置")
            return createDefault();
        }
    }

    /**
     * 创建默认配置
     *
     * @return 使用默认值的ApplicationConfig实例
     */
    public static ApplicationConfig createDefault() {
        JsonObject json = new JsonObject();

        // server
        JsonObject server = new JsonObject();
        server.addProperty("port", 8080);
        server.addProperty("host", "0.0.0.0");
        server.addProperty("threadCount", 10);
        server.addProperty("timeoutSeconds", 30);
        json.add("server", server);

        // vectorStore
        JsonObject vectorStore = new JsonObject();
        vectorStore.addProperty("type", "milvus");
        vectorStore.addProperty("host", "localhost");
        vectorStore.addProperty("port", 19530);
        vectorStore.addProperty("collection", "memories");
        vectorStore.addProperty("dimension", 1536);
        json.add("vectorStore", vectorStore);

        // graphStore
        JsonObject graphStore = new JsonObject();
        graphStore.addProperty("type", "neo4j");
        graphStore.addProperty("uri", "bolt://localhost:7687");
        graphStore.addProperty("user", "neo4j");
        graphStore.addProperty("password", "password");
        json.add("graphStore", graphStore);

        // metadataStore
        JsonObject metadataStore = new JsonObject();
        metadataStore.addProperty("type", "mysql");
        metadataStore.addProperty("url", "jdbc:mysql://localhost:3306/memory_platform");
        metadataStore.addProperty("user", "root");
        metadataStore.addProperty("password", "password");
        json.add("metadataStore", metadataStore);

        // llm
        JsonObject llm = new JsonObject();
        llm.addProperty("type", "ollama");
        llm.addProperty("baseUrl", "http://localhost:11434");
        llm.addProperty("model", "qwen2.5:7b");
        llm.addProperty("apiKey", "");
        llm.addProperty("temperature", 0.3);
        llm.addProperty("maxTokens", 2048);
        json.add("llm", llm);

        // embedding
        JsonObject embedding = new JsonObject();
        embedding.addProperty("type", "local");
        embedding.addProperty("dimension", 1536);
        json.add("embedding", embedding);

        // circuitBreaker
        JsonObject cb = new JsonObject();
        cb.addProperty("failureThreshold", 5);
        cb.addProperty("recoveryTimeout", 30000);
        cb.addProperty("successThreshold", 3);
        json.add("circuitBreaker", cb);

        // metrics
        JsonObject metrics = new JsonObject();
        metrics.addProperty("enabled", true);
        metrics.addProperty("port", 9090);
        json.add("metrics", metrics);

        // auth
        JsonObject auth = new JsonObject();
        auth.addProperty("enabled", false);
        auth.addProperty("apiKey", "");
        json.add("auth", auth);

        log.info("[ApplicationConfig] 使用默认配置")
        return new ApplicationConfig(json);
    }

    /**
     * 构造函数
     *
     * @param root 配置JSON根对象
     */
    private ApplicationConfig(JsonObject root) {
        this.root = root;
    }

    // ==================== Server 配置 ====================

    /**
     * 获取HTTP服务器端口
     *
     * @return 端口号，默认8080
     */
    public int getServerPort() {
        return getNestedInt("server", "port", 8080);
    }

    /**
     * 获取HTTP服务器监听地址
     *
     * @return 监听地址，默认 "0.0.0.0"
     */
    public String getServerHost() {
        return getNestedString("server", "host", "0.0.0.0");
    }

    /**
     * 获取HTTP服务器线程池大小
     *
     * @return 线程数，默认10
     */
    public int getServerThreadCount() {
        return getNestedInt("server", "threadCount", 10);
    }

    /**
     * 获取HTTP服务器超时时间（秒）
     *
     * @return 超时秒数，默认30
     */
    public int getServerTimeoutSeconds() {
        return getNestedInt("server", "timeoutSeconds", 30);
    }

    // ==================== VectorStore 配置 ====================

    /**
     * 获取向量存储类型
     *
     * @return 存储类型，默认 "milvus"
     */
    public String getVectorStoreType() {
        return getNestedString("vectorStore", "type", "milvus");
    }

    /**
     * 获取向量存储主机
     *
     * @return 主机地址，默认 "localhost"
     */
    public String getVectorStoreHost() {
        return getNestedString("vectorStore", "host", "localhost");
    }

    /**
     * 获取向量存储端口
     *
     * @return 端口号，默认19530
     */
    public int getVectorStorePort() {
        return getNestedInt("vectorStore", "port", 19530);
    }

    /**
     * 获取向量存储集合名称
     *
     * @return 集合名，默认 "memories"
     */
    public String getVectorStoreCollection() {
        return getNestedString("vectorStore", "collection", "memories");
    }

    /**
     * 获取向量维度
     *
     * @return 维度数，默认1536
     */
    public int getVectorStoreDimension() {
        return getNestedInt("vectorStore", "dimension", 1536);
    }

    // ==================== GraphStore 配置 ====================

    /**
     * 获取图存储类型
     *
     * @return 存储类型，默认 "neo4j"
     */
    public String getGraphStoreType() {
        return getNestedString("graphStore", "type", "neo4j");
    }

    /**
     * 获取图存储URI
     *
     * @return URI字符串，默认 "bolt://localhost:7687"
     */
    public String getGraphStoreUri() {
        return getNestedString("graphStore", "uri", "bolt://localhost:7687");
    }

    /**
     * 获取图存储用户名
     *
     * @return 用户名，默认 "neo4j"
     */
    public String getGraphStoreUser() {
        return getNestedString("graphStore", "user", "neo4j");
    }

    /**
     * 获取图存储密码
     *
     * @return 密码，默认 "password"
     */
    public String getGraphStorePassword() {
        return getNestedString("graphStore", "password", "password");
    }

    // ==================== MetadataStore 配置 ====================

    /**
     * 获取元数据存储类型
     *
     * @return 存储类型，默认 "mysql"
     */
    public String getMetadataStoreType() {
        return getNestedString("metadataStore", "type", "mysql");
    }

    /**
     * 获取元数据存储JDBC URL
     *
     * @return JDBC URL
     */
    public String getMetadataStoreUrl() {
        return getNestedString("metadataStore", "url", "jdbc:mysql://localhost:3306/memory_platform");
    }

    /**
     * 获取元数据存储用户名
     *
     * @return 用户名，默认 "root"
     */
    public String getMetadataStoreUser() {
        return getNestedString("metadataStore", "user", "root");
    }

    /**
     * 获取元数据存储密码
     *
     * @return 密码，默认 "password"
     */
    public String getMetadataStorePassword() {
        return getNestedString("metadataStore", "password", "password");
    }

    // ==================== LLM 配置 ====================

    /**
     * 获取LLM类型 (ollama/openai)
     *
     * @return LLM类型，默认 "ollama"
     */
    public String getLlmType() {
        return getNestedString("llm", "type", "ollama");
    }

    /**
     * 获取LLM基础URL
     *
     * @return URL字符串，默认 "http://localhost:11434"
     */
    public String getLlmBaseUrl() {
        return getNestedString("llm", "baseUrl", "http://localhost:11434");
    }

    /**
     * 获取LLM模型名称
     *
     * @return 模型名称，默认 "qwen2.5:7b"
     */
    public String getLlmModel() {
        return getNestedString("llm", "model", "qwen2.5:7b");
    }

    /**
     * 获取LLM API Key
     *
     * @return API Key，默认为空字符串
     */
    public String getLlmApiKey() {
        return getNestedString("llm", "apiKey", "");
    }

    /**
     * 获取LLM温度参数
     *
     * @return 温度值，默认0.3
     */
    public double getLlmTemperature() {
        return getNestedDouble("llm", "temperature", 0.3);
    }

    /**
     * 获取LLM最大Token数
     *
     * @return 最大Token数，默认2048
     */
    public int getLlmMaxTokens() {
        return getNestedInt("llm", "maxTokens", 2048);
    }

    // ==================== Embedding 配置 ====================

    /**
     * 获取Embedding类型 (local/ollama/openai)
     *
     * @return Embedding类型，默认 "local"
     */
    public String getEmbeddingType() {
        return getNestedString("embedding", "type", "local");
    }

    /**
     * 获取Embedding维度
     *
     * @return 维度数，默认1536
     */
    public int getEmbeddingDimension() {
        return getNestedInt("embedding", "dimension", 1536);
    }

    // ==================== CircuitBreaker 配置 ====================

    /**
     * 获取熔断器失败阈值
     *
     * @return 失败阈值，默认5
     */
    public int getCircuitFailureThreshold() {
        return getNestedInt("circuitBreaker", "failureThreshold", 5);
    }

    /**
     * 获取熔断器恢复超时时间（毫秒）
     *
     * @return 超时毫秒数，默认30000
     */
    public long getCircuitRecoveryTimeout() {
        return getNestedLong("circuitBreaker", "recoveryTimeout", 30000L);
    }

    /**
     * 获取熔断器成功阈值
     *
     * @return 成功阈值，默认3
     */
    public int getCircuitSuccessThreshold() {
        return getNestedInt("circuitBreaker", "successThreshold", 3);
    }

    // ==================== Metrics 配置 ====================

    /**
     * 检查Prometheus指标是否启用
     *
     * @return 是否启用，默认true
     */
    public boolean isMetricsEnabled() {
        return getNestedBoolean("metrics", "enabled", true);
    }

    /**
     * 获取Prometheus指标服务器端口
     *
     * @return 端口号，默认9090
     */
    public int getMetricsPort() {
        return getNestedInt("metrics", "port", 9090);
    }

    // ==================== Auth 配置 ====================

    /**
     * 检查认证是否启用
     *
     * @return 是否启用，默认false
     */
    public boolean isAuthEnabled() {
        return getNestedBoolean("auth", "enabled", false);
    }

    /**
     * 获取API Key
     *
     * @return API Key，默认空字符串
     */
    public String getAuthApiKey() {
        return getNestedString("auth", "apiKey", "");
    }

    // ==================== Admin 配置 ====================

    /**
     * 获取管理员Token
     *
     * @return 管理员token，默认空字符串
     */
    public String getAdminToken() {
        return getNestedString("admin", "token", "");
    }

    // ==================== Storage Factory Config ====================

    /**
     * 获取传递给StorageFactory的全局配置Map
     * <p>
     * 将JSON配置转换为Map结构，供StorageFactory.fromConfig()使用。
     * 结果会被缓存以避免重复转换。
     * </p>
     *
     * @return 存储配置Map
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getStorageGlobalConfig() {
        if (storageGlobalConfig == null) {
            storageGlobalConfig = GSON.fromJson(root, Map.class);
        }
        return storageGlobalConfig;
    }

    // ==================== 打印配置摘要 ====================

    /**
     * 打印配置摘要到控制台
     */
    public void printSummary() {
        log.info("┌─────────────────────────────────────────────────────┐")
        log.info("│              Application Configuration              │")
        log.info("├─────────────────────────────────────────────────────┤")
        log.info(String.format("│  Server:        %-34s │%n", getServerHost() + ":" + getServerPort()));
        log.info(String.format("│  Threads:       %-34d │%n", getServerThreadCount()));
        log.info(String.format("│  VectorStore:   %-34s │%n", getVectorStoreType() + " @ " + getVectorStoreHost() + ":" + getVectorStorePort()));
        log.info(String.format("│  GraphStore:    %-34s │%n", getGraphStoreType() + " @ " + getGraphStoreUri()));
        log.info(String.format("│  MetadataStore: %-34s │%n", getMetadataStoreType() + " @ " + getMetadataStoreUrl()));
        log.info(String.format("│  LLM:           %-34s │%n", getLlmType() + " / " + getLlmModel()));
        log.info(String.format("│  Embedding:     %-34s │%n", getEmbeddingType() + " dim=" + getEmbeddingDimension()));
        log.info(String.format("│  Metrics:       %-34s │%n", (isMetricsEnabled() ? "port=" + getMetricsPort() : "disabled")));
        log.info(String.format("│  Auth:          %-34s │%n", (isAuthEnabled() ? "enabled" : "disabled")));
        log.info("└─────────────────────────────────────────────────────┘")
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 获取嵌套JsonObject
     *
     * @param section 一级键
     * @return JsonObject，不存在时返回空对象
     */
    private JsonObject getSection(String section) {
        if (root.has(section) && root.get(section).isJsonObject()) {
            return root.getAsJsonObject(section);
        }
        return new JsonObject();
    }

    /**
     * 获取嵌套字符串值
     *
     * @param section      一级键
     * @param key          二级键
     * @param defaultValue 默认值
     * @return 字符串值
     */
    private String getNestedString(String section, String key, String defaultValue) {
        JsonObject obj = getSection(section);
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return defaultValue;
    }

    /**
     * 获取嵌套整数值
     *
     * @param section      一级键
     * @param key          二级键
     * @param defaultValue 默认值
     * @return 整数值
     */
    private int getNestedInt(String section, String key, int defaultValue) {
        JsonObject obj = getSection(section);
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsInt();
        }
        return defaultValue;
    }

    /**
     * 获取嵌套长整数值
     *
     * @param section      一级键
     * @param key          二级键
     * @param defaultValue 默认值
     * @return 长整数值
     */
    private long getNestedLong(String section, String key, long defaultValue) {
        JsonObject obj = getSection(section);
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsLong();
        }
        return defaultValue;
    }

    /**
     * 获取嵌套双精度值
     *
     * @param section      一级键
     * @param key          二级键
     * @param defaultValue 默认值
     * @return 双精度值
     */
    private double getNestedDouble(String section, String key, double defaultValue) {
        JsonObject obj = getSection(section);
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsDouble();
        }
        return defaultValue;
    }

    /**
     * 获取嵌套布尔值
     *
     * @param section      一级键
     * @param key          二级键
     * @param defaultValue 默认值
     * @return 布尔值
     */
    private boolean getNestedBoolean(String section, String key, boolean defaultValue) {
        JsonObject obj = getSection(section);
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsBoolean();
        }
        return defaultValue;
    }

    /**
     * 获取原始JsonObject（用于高级自定义）
     *
     * @return 配置根对象
     */
    public JsonObject getRoot() {
        return root;
    }
}
