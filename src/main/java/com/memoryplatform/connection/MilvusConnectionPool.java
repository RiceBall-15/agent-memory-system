package com.memoryplatform.connection;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.ListCollectionsParam;

import java.util.Map;

/**
 * Milvus专用连接池实现
 *
 * <p>复用io.milvus.client.MilvusServiceClient实例，避免每次请求创建新连接的开销。</p>
 *
 * <h3>配置参数</h3>
 * <ul>
 *   <li>{@code host} - Milvus服务地址，默认 "localhost"</li>
 *   <li>{@code port} - Milvus服务端口，默认 19530</li>
 *   <li>{@code username} - 认证用户名，可选</li>
 *   <li>{@code password} - 认证密码，可选</li>
 *   <li>{@code minConnections} - 最小连接数，默认 2</li>
 *   <li>{@code maxConnections} - 最大连接数，默认 8</li>
 *   <li>{@code idleTimeoutMs} - 空闲超时（毫秒），默认 60000</li>
 * </ul>
 *
 * @author Agent Memory Platform
 */
public class MilvusConnectionPool extends AbstractConnectionPool<MilvusServiceClient> {

    /** 连接配置 */
    private final String host;
    private final int port;
    private final String username;
    private final String password;

    /** 默认最小连接数（适配2核2G环境） */
    private static final int DEFAULT_MIN_CONNECTIONS = 2;

    /** 默认最大连接数（适配2核2G环境） */
    private static final int DEFAULT_MAX_CONNECTIONS = 8;

    /** 默认空闲超时（毫秒） */
    private static final long DEFAULT_IDLE_TIMEOUT_MS = 60_000;

    /** 默认借用超时（毫秒） */
    private static final long DEFAULT_BORROW_TIMEOUT_MS = 10_000;

    // ==================== 构造函数 ====================

    /**
     * 使用配置Map创建Milvus连接池
     *
     * @param config 配置参数Map
     */
    public MilvusConnectionPool(Map<String, Object> config) {
        this(
                getConfigString(config, "host", "localhost"),
                getConfigInt(config, "port", 19530),
                getConfigString(config, "username", null),
                getConfigString(config, "password", null),
                getConfigInt(config, "minConnections", DEFAULT_MIN_CONNECTIONS),
                getConfigInt(config, "maxConnections", DEFAULT_MAX_CONNECTIONS),
                getConfigLong(config, "idleTimeoutMs", DEFAULT_IDLE_TIMEOUT_MS)
        );
    }

    /**
     * 使用详细参数创建Milvus连接池
     *
     * @param host           Milvus服务地址
     * @param port           Milvus服务端口
     * @param username       认证用户名（可为null）
     * @param password       认证密码（可为null）
     * @param minConnections 最小连接数
     * @param maxConnections 最大连接数
     * @param idleTimeoutMs  空闲超时（毫秒）
     */
    public MilvusConnectionPool(String host, int port, String username, String password,
                                 int minConnections, int maxConnections, long idleTimeoutMs) {
        super(minConnections, maxConnections, idleTimeoutMs, DEFAULT_BORROW_TIMEOUT_MS);
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
    }

    // ==================== 连接创建 ====================

    @Override
    protected MilvusServiceClient createConnection() throws Exception {
        MilvusServiceClient.Builder builder = MilvusServiceClient.builder()
                .withHost(host)
                .withPort(port);

        if (username != null && !username.isEmpty() && password != null) {
            builder.withAuthorization(username, password);
        }

        MilvusServiceClient client = builder.build();
        System.out.println("[MilvusConnectionPool] 创建连接: " + host + ":" + port);
        return client;
    }

    // ==================== 连接销毁 ====================

    @Override
    protected void destroyConnection(MilvusServiceClient connection) throws Exception {
        if (connection != null) {
            try {
                connection.close();
                System.out.println("[MilvusConnectionPool] 连接已关闭");
            } catch (Exception e) {
                System.err.println("[MilvusConnectionPool] 关闭连接异常: " + e.getMessage());
                throw e;
            }
        }
    }

    // ==================== 健康检查 ====================

    @Override
    protected boolean healthCheckImpl(MilvusServiceClient connection) {
        if (connection == null) return false;

        try {
            // 通过列出集合来验证连接是否正常
            R<RpcStatus> response = connection.listCollections(
                    ListCollectionsParam.newBuilder().build()
            );
            return response.getStatus() == R.Status.Success.getCode();
        } catch (Exception e) {
            System.err.println("[MilvusConnectionPool] 健康检查失败: " + e.getMessage());
            return false;
        }
    }

    // ==================== 配置工具方法 ====================

    private static String getConfigString(Map<String, Object> config, String key, String defaultValue) {
        if (config == null || !config.containsKey(key)) return defaultValue;
        Object value = config.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    private static int getConfigInt(Map<String, Object> config, String key, int defaultValue) {
        if (config == null || !config.containsKey(key)) return defaultValue;
        Object value = config.get(key);
        if (value instanceof Number) return ((Number) value).intValue();
        try { return Integer.parseInt(value.toString()); } catch (NumberFormatException e) { return defaultValue; }
    }

    private static long getConfigLong(Map<String, Object> config, String key, long defaultValue) {
        if (config == null || !config.containsKey(key)) return defaultValue;
        Object value = config.get(key);
        if (value instanceof Number) return ((Number) value).longValue();
        try { return Long.parseLong(value.toString()); } catch (NumberFormatException e) { return defaultValue; }
    }
}
