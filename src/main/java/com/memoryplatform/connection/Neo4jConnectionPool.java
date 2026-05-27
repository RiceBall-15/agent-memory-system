package com.memoryplatform.connection;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;

import java.util.Map;

import lombok.extern.slf4j.Slf4j;
/**
 * Neo4j专用连接池实现
 *
 * <p>管理Neo4j Driver实例池。Neo4j Driver本身内置连接池机制，
 * 这里对Driver实例进行池化管理，适用于多数据源或Driver重建场景。</p>
 *
 * <h3>配置参数</h3>
 * <ul>
 *   <li>{@code uri} - Neo4j连接URI，默认 "bolt://localhost:7687"</li>
 *   <li>{@code username} - 认证用户名，默认 "neo4j"</li>
 *   <li>{@code password} - 认证密码</li>
 *   <li>{@code minConnections} - 最小连接数，默认 2</li>
 *   <li>{@code maxConnections} - 最大连接数，默认 8</li>
 *   <li>{@code idleTimeoutMs} - 空闲超时（毫秒），默认 60000</li>
 * </ul>
 *
 * @author Agent Memory Platform
 */
@Slf4j
public class Neo4jConnectionPool extends AbstractConnectionPool<Driver> {

    /** 连接配置 */
    private final String uri;
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
     * 使用配置Map创建Neo4j连接池
     *
     * @param config 配置参数Map
     */
    public Neo4jConnectionPool(Map<String, Object> config) {
        this(
                getConfigString(config, "uri", "bolt://localhost:7687"),
                getConfigString(config, "username", "neo4j"),
                getConfigString(config, "password", ""),
                getConfigInt(config, "minConnections", DEFAULT_MIN_CONNECTIONS),
                getConfigInt(config, "maxConnections", DEFAULT_MAX_CONNECTIONS),
                getConfigLong(config, "idleTimeoutMs", DEFAULT_IDLE_TIMEOUT_MS)
        );
    }

    /**
     * 使用详细参数创建Neo4j连接池
     *
     * @param uri            Neo4j连接URI
     * @param username       认证用户名
     * @param password       认证密码
     * @param minConnections 最小连接数
     * @param maxConnections 最大连接数
     * @param idleTimeoutMs  空闲超时（毫秒）
     */
    public Neo4jConnectionPool(String uri, String username, String password,
                                int minConnections, int maxConnections, long idleTimeoutMs) {
        super(minConnections, maxConnections, idleTimeoutMs, DEFAULT_BORROW_TIMEOUT_MS);
        this.uri = uri;
        this.username = username;
        this.password = password;
    }

    // ==================== 连接创建 ====================

    @Override
    protected Driver createConnection() throws Exception {
        Driver driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));

        // 验证连接
        try (Session session = driver.session()) {
            session.run("RETURN 1 AS test");
        }

        log.info("[Neo4jConnectionPool] 创建连接: " + uri)
        return driver;
    }

    // ==================== 连接销毁 ====================

    @Override
    protected void destroyConnection(Driver connection) throws Exception {
        if (connection != null) {
            try {
                connection.close();
                log.info("[Neo4jConnectionPool] 连接已关闭")
            } catch (Exception e) {
                log.error("[Neo4jConnectionPool] 关闭连接异常: " + e.getMessage());
                throw e;
            }
        }
    }

    // ==================== 健康检查 ====================

    @Override
    protected boolean healthCheckImpl(Driver connection) {
        if (connection == null) return false;

        try {
            try (Session session = connection.session()) {
                session.run("RETURN 1 AS test");
            }
            return true;
        } catch (Exception e) {
            log.error("[Neo4jConnectionPool] 健康检查失败: " + e.getMessage());
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
