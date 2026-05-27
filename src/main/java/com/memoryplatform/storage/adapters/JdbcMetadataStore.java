package com.memoryplatform.storage.adapters;

import com.google.gson.Gson;
import com.memoryplatform.model.MetadataRecord;
import com.memoryplatform.storage.MetadataStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MySQL元数据存储适配器 - 基于HikariCP连接池实现MetadataStore接口。
 *
 * <p>功能说明:
 * <ul>
 *   <li>使用HikariCP管理MySQL数据库连接池</li>
 *   <li>所有SQL操作使用PreparedStatement防止SQL注入</li>
 *   <li>支持单条插入、批量插入、条件查询、更新、删除、计数及健康检查</li>
 *   <li>data_json字段使用Gson序列化Map为JSON字符串存储</li>
 * </ul>
 *
 * <p>配置参数（通过init方法传入）:
 * <ul>
 *   <li>url - MySQL JDBC连接URL</li>
 *   <li>username - 数据库用户名</li>
 *   <li>password - 数据库密码</li>
 *   <li>maxPoolSize - 连接池最大连接数（默认10）</li>
 * </ul>
 *
 * @author MemoryPlatform
 */
public class JdbcMetadataStore implements MetadataStore {

    /** HikariCP数据源实例 */
    private HikariDataSource dataSource;

    /** Gson实例，用于JSON序列化/反序列化 */
    private final Gson gson = new Gson();

    /** JDBC连接URL */
    private String url;

    /** 数据库用户名 */
    private String username;

    /** 数据库密码 */
    private String password;

    /** 连接池最大连接数 */
    private int maxPoolSize = 10;

    /** 标记是否已初始化 */
    private boolean initialized = false;

    /** ISO-8601时间格式化器，用于TIMESTAMP字段 */
    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    /**
     * 初始化MySQL连接池及配置参数。
     *
     * <p>从config Map中读取url、username、password、maxPoolSize参数，
     * 创建HikariDataSource连接池实例。重复调用将被忽略。</p>
     *
     * @param config 配置参数Map，包含: url, username, password, maxPoolSize
     */
    @Override
    public void init(Map<String, Object> config) {
        if (initialized) {
            System.out.println("[JdbcMetadataStore] Already initialized, skipping re-init.");
            return;
        }

        this.url = (String) config.get("url");
        this.username = (String) config.get("username");
        this.password = (String) config.get("password");

        Object maxPoolSizeObj = config.get("maxPoolSize");
        if (maxPoolSizeObj instanceof Number) {
            this.maxPoolSize = ((Number) maxPoolSizeObj).intValue();
        }

        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("[JdbcMetadataStore] 'url' config is required.");
        }

        // 创建HikariCP连接池配置
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(url);
        hikariConfig.setUsername(username);
        hikariConfig.setPassword(password);
        hikariConfig.setMaximumPoolSize(maxPoolSize);
        hikariConfig.setDriverClassName("com.mysql.cj.jdbc.Driver");
        hikariConfig.setConnectionTimeout(30000);  // 30秒连接超时
        hikariConfig.setIdleTimeout(600000);       // 10分钟空闲超时
        hikariConfig.setMaxLifetime(1800000);       // 30分钟最大生命周期
        hikariConfig.setPoolName("MemoryPlatform-HikariPool");

        this.dataSource = new HikariDataSource(hikariConfig);
        this.initialized = true;

        System.out.println("[JdbcMetadataStore] Initialized successfully. URL: " + url
                + ", maxPoolSize: " + maxPoolSize);
    }

    /**
     * 插入一条元数据记录到指定表。
     *
     * <p>SQL: INSERT INTO {table} (id, user_id, agent_id, content, importance,
     * data_json, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)</p>
     *
     * @param table  目标表名
     * @param record 元数据记录对象
     * @return 插入记录的ID
     * @throws RuntimeException 如果插入失败
     */
    @Override
    public String insert(String table, MetadataRecord record) {
        if (!initialized) {
            throw new IllegalStateException("[JdbcMetadataStore] Not initialized. Call init() first.");
        }

        String sql = "INSERT INTO " + escapeTableName(table)
                + " (id, user_id, agent_id, content, importance, data_json, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

        String dataJson = gson.toJson(record.getData() != null ? record.getData() : new HashMap<>());
        String createdAt = record.getCreatedAt() != null
                ? ISO_FORMATTER.format(record.getCreatedAt())
                : ISO_FORMATTER.format(Instant.now());
        String updatedAt = record.getUpdatedAt() != null
                ? ISO_FORMATTER.format(record.getUpdatedAt())
                : ISO_FORMATTER.format(Instant.now());

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, record.getId());
            ps.setString(2, record.getUserId());
            ps.setString(3, record.getAgentId());
            ps.setString(4, record.getContent());
            ps.setDouble(5, record.getImportance());
            ps.setString(6, dataJson);
            ps.setString(7, createdAt);
            ps.setString(8, updatedAt);

            int affected = ps.executeUpdate();
            if (affected == 0) {
                throw new SQLException("Insert returned 0 affected rows for id: " + record.getId());
            }

            System.out.println("[JdbcMetadataStore] Inserted record id=" + record.getId()
                    + " into table=" + table);
            return record.getId();

        } catch (SQLException e) {
            System.err.println("[JdbcMetadataStore] Insert failed for table=" + table
                    + ", id=" + record.getId() + ", error=" + e.getMessage());
            throw new RuntimeException("[JdbcMetadataStore] Insert failed: " + e.getMessage(), e);
        }
    }

    /**
     * 批量插入多条元数据记录到指定表。
     *
     * <p>使用PreparedStatement的addBatch()和executeBatch()提高批量写入性能。
     * 在事务内执行所有插入操作，任意一条失败则整体回滚。</p>
     *
     * @param table   目标表名
     * @param records 元数据记录列表
     * @return 所有插入记录的ID列表
     * @throws RuntimeException 如果批量插入失败
     */
    @Override
    public List<String> batchInsert(String table, List<MetadataRecord> records) {
        if (!initialized) {
            throw new IllegalStateException("[JdbcMetadataStore] Not initialized. Call init() first.");
        }

        if (records == null || records.isEmpty()) {
            System.out.println("[JdbcMetadataStore] batchInsert called with empty records, returning.");
            return new ArrayList<>();
        }

        String sql = "INSERT INTO " + escapeTableName(table)
                + " (id, user_id, agent_id, content, importance, data_json, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

        List<String> ids = new ArrayList<>();

        try (Connection conn = dataSource.getConnection()) {
            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (MetadataRecord record : records) {
                    String dataJson = gson.toJson(record.getData() != null
                            ? record.getData() : new HashMap<>());
                    String createdAt = record.getCreatedAt() != null
                            ? ISO_FORMATTER.format(record.getCreatedAt())
                            : ISO_FORMATTER.format(Instant.now());
                    String updatedAt = record.getUpdatedAt() != null
                            ? ISO_FORMATTER.format(record.getUpdatedAt())
                            : ISO_FORMATTER.format(Instant.now());

                    ps.setString(1, record.getId());
                    ps.setString(2, record.getUserId());
                    ps.setString(3, record.getAgentId());
                    ps.setString(4, record.getContent());
                    ps.setDouble(5, record.getImportance());
                    ps.setString(6, dataJson);
                    ps.setString(7, createdAt);
                    ps.setString(8, updatedAt);

                    ps.addBatch();
                    ids.add(record.getId());
                }

                int[] results = ps.executeBatch();
                conn.commit();

                int successCount = 0;
                for (int r : results) {
                    if (r >= 0) successCount++;
                }

                System.out.println("[JdbcMetadataStore] batchInsert completed for table=" + table
                        + ", total=" + records.size() + ", success=" + successCount);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(originalAutoCommit);
            }

        } catch (SQLException e) {
            System.err.println("[JdbcMetadataStore] batchInsert failed for table=" + table
                    + ", error=" + e.getMessage());
            throw new RuntimeException("[JdbcMetadataStore] batchInsert failed: " + e.getMessage(), e);
        }

        return ids;
    }

    /**
     * 查询元数据记录，支持动态WHERE过滤条件、分页及按创建时间降序排列。
     *
     * <p>支持的过滤键: user_id, agent_id。过滤值为null或空字符串时跳过该条件。</p>
     *
     * @param table   目标表名
     * @param filters 过滤条件Map，key为列名（如user_id, agent_id）
     * @param limit   返回的最大记录数
     * @param offset  跳过的记录数
     * @return 符合条件的元数据记录列表
     * @throws RuntimeException 如果查询失败
     */
    @Override
    public List<MetadataRecord> find(String table, Map<String, Object> filters,
                                     int limit, int offset) {
        if (!initialized) {
            throw new IllegalStateException("[JdbcMetadataStore] Not initialized. Call init() first.");
        }

        List<MetadataRecord> results = new ArrayList<>();

        // 构建WHERE子句
        StringBuilder whereClause = new StringBuilder();
        List<Object> params = new ArrayList<>();

        if (filters != null) {
            // 支持user_id过滤
            if (filters.containsKey("user_id") && filters.get("user_id") != null
                    && !filters.get("user_id").toString().isEmpty()) {
                whereClause.append(" AND user_id = ?");
                params.add(filters.get("user_id").toString());
            }
            // 支持agent_id过滤
            if (filters.containsKey("agent_id") && filters.get("agent_id") != null
                    && !filters.get("agent_id").toString().isEmpty()) {
                whereClause.append(" AND agent_id = ?");
                params.add(filters.get("agent_id").toString());
            }
        }

        String sql = "SELECT id, user_id, agent_id, content, importance, data_json, "
                + "created_at, updated_at FROM " + escapeTableName(table);

        if (whereClause.length() > 0) {
            sql += " WHERE 1=1" + whereClause.toString();
        }

        sql += " ORDER BY created_at DESC LIMIT ? OFFSET ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            int paramIndex = 1;
            for (Object param : params) {
                ps.setObject(paramIndex++, param);
            }
            ps.setInt(paramIndex++, limit);
            ps.setInt(paramIndex, offset);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapResultSetToRecord(rs));
                }
            }

            System.out.println("[JdbcMetadataStore] find query on table=" + table
                    + " returned " + results.size() + " records.");
            return results;

        } catch (SQLException e) {
            System.err.println("[JdbcMetadataStore] find failed for table=" + table
                    + ", error=" + e.getMessage());
            throw new RuntimeException("[JdbcMetadataStore] find failed: " + e.getMessage(), e);
        }
    }

    /**
     * 更新指定ID的元数据记录。
     *
     * <p>仅更新updates Map中提供的字段，使用SET子句动态构建更新SQL。
     * 同时自动更新updated_at字段为当前时间。</p>
     *
     * @param table   目标表名
     * @param id      记录ID
     * @param updates 更新字段Map（key为列名，value为新值）
     * @return 更新是否成功（true: 有记录被更新; false: 无匹配记录或更新失败）
     * @throws RuntimeException 如果更新过程出错
     */
    @Override
    public boolean update(String table, String id, Map<String, Object> updates) {
        if (!initialized) {
            throw new IllegalStateException("[JdbcMetadataStore] Not initialized. Call init() first.");
        }

        if (updates == null || updates.isEmpty()) {
            System.out.println("[JdbcMetadataStore] update called with empty updates, skipping.");
            return false;
        }

        // 允许更新的列名白名单，防止SQL注入
        java.util.Set<String> allowedColumns = java.util.Set.of(
                "user_id", "agent_id", "content", "importance", "data_json"
        );

        StringBuilder setClause = new StringBuilder();
        List<Object> params = new ArrayList<>();

        for (Map.Entry<String, Object> entry : updates.entrySet()) {
            String column = entry.getKey();
            if (!allowedColumns.contains(column)) {
                System.out.println("[JdbcMetadataStore] Skipping disallowed column: " + column);
                continue;
            }
            if (setClause.length() > 0) {
                setClause.append(", ");
            }
            setClause.append(column).append(" = ?");

            // 如果是data字段，序列化为JSON
            if ("data_json".equals(column) && entry.getValue() instanceof Map) {
                params.add(gson.toJson(entry.getValue()));
            } else {
                params.add(entry.getValue());
            }
        }

        if (setClause.length() == 0) {
            System.out.println("[JdbcMetadataStore] No valid columns to update.");
            return false;
        }

        // 自动更新updated_at
        setClause.append(", updated_at = ?");
        params.add(ISO_FORMATTER.format(Instant.now()));

        // WHERE条件
        params.add(id);

        String sql = "UPDATE " + escapeTableName(table)
                + " SET " + setClause.toString()
                + " WHERE id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            int paramIndex = 1;
            for (Object param : params) {
                ps.setObject(paramIndex++, param);
            }

            int affected = ps.executeUpdate();
            System.out.println("[JdbcMetadataStore] update id=" + id + " on table=" + table
                    + " affected " + affected + " row(s).");
            return affected > 0;

        } catch (SQLException e) {
            System.err.println("[JdbcMetadataStore] update failed for table=" + table
                    + ", id=" + id + ", error=" + e.getMessage());
            throw new RuntimeException("[JdbcMetadataStore] update failed: " + e.getMessage(), e);
        }
    }

    /**
     * 批量删除指定ID列表的元数据记录。
     *
     * <p>使用IN子句进行批量删除，在事务内执行。</p>
     *
     * @param table 目标表名
     * @param ids   待删除的记录ID列表
     * @return 删除是否成功（true: 至少删除了一条; false: 无匹配记录）
     * @throws RuntimeException 如果删除过程出错
     */
    @Override
    public boolean delete(String table, List<String> ids) {
        if (!initialized) {
            throw new IllegalStateException("[JdbcMetadataStore] Not initialized. Call init() first.");
        }

        if (ids == null || ids.isEmpty()) {
            System.out.println("[JdbcMetadataStore] delete called with empty ids, skipping.");
            return false;
        }

        StringBuilder sql = new StringBuilder("DELETE FROM " + escapeTableName(table) + " WHERE id IN (");
        for (int i = 0; i < ids.size(); i++) {
            sql.append("?");
            if (i < ids.size() - 1) {
                sql.append(", ");
            }
        }
        sql.append(")");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {

            for (int i = 0; i < ids.size(); i++) {
                ps.setString(i + 1, ids.get(i));
            }

            int affected = ps.executeUpdate();
            System.out.println("[JdbcMetadataStore] delete on table=" + table
                    + " removed " + affected + " row(s).");
            return affected > 0;

        } catch (SQLException e) {
            System.err.println("[JdbcMetadataStore] delete failed for table=" + table
                    + ", ids=" + ids + ", error=" + e.getMessage());
            throw new RuntimeException("[JdbcMetadataStore] delete failed: " + e.getMessage(), e);
        }
    }

    /**
     * 统计指定表中符合过滤条件的记录总数。
     *
     * @param table   目标表名
     * @param filters 过滤条件Map
     * @return 符合条件的记录数量
     * @throws RuntimeException 如果计数查询失败
     */
    @Override
    public long count(String table, Map<String, Object> filters) {
        if (!initialized) {
            throw new IllegalStateException("[JdbcMetadataStore] Not initialized. Call init() first.");
        }

        StringBuilder whereClause = new StringBuilder();
        List<Object> params = new ArrayList<>();

        if (filters != null) {
            if (filters.containsKey("user_id") && filters.get("user_id") != null
                    && !filters.get("user_id").toString().isEmpty()) {
                whereClause.append(" AND user_id = ?");
                params.add(filters.get("user_id").toString());
            }
            if (filters.containsKey("agent_id") && filters.get("agent_id") != null
                    && !filters.get("agent_id").toString().isEmpty()) {
                whereClause.append(" AND agent_id = ?");
                params.add(filters.get("agent_id").toString());
            }
        }

        String sql = "SELECT COUNT(*) FROM " + escapeTableName(table);
        if (whereClause.length() > 0) {
            sql += " WHERE 1=1" + whereClause.toString();
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            int paramIndex = 1;
            for (Object param : params) {
                ps.setObject(paramIndex++, param);
            }

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long count = rs.getLong(1);
                    System.out.println("[JdbcMetadataStore] count on table=" + table + " = " + count);
                    return count;
                }
            }

            return 0;

        } catch (SQLException e) {
            System.err.println("[JdbcMetadataStore] count failed for table=" + table
                    + ", error=" + e.getMessage());
            throw new RuntimeException("[JdbcMetadataStore] count failed: " + e.getMessage(), e);
        }
    }

    /**
     * 健康检查 - 尝试从连接池获取一个连接并执行ping操作。
     *
     * @return true: 连接池正常; false: 连接池异常或未初始化
     */
    @Override
    public boolean healthCheck() {
        if (!initialized || dataSource == null) {
            System.out.println("[JdbcMetadataStore] healthCheck: not initialized.");
            return false;
        }

        try (Connection conn = dataSource.getConnection()) {
            if (conn.isValid(5)) {
                System.out.println("[JdbcMetadataStore] healthCheck: OK.");
                return true;
            } else {
                System.out.println("[JdbcMetadataStore] healthCheck: connection invalid.");
                return false;
            }
        } catch (SQLException e) {
            System.err.println("[JdbcMetadataStore] healthCheck failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * 关闭连接池，释放所有数据库资源。
     *
     * <p>在应用关闭或不再需要此存储适配器时调用。</p>
     */
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            initialized = false;
            System.out.println("[JdbcMetadataStore] DataSource closed.");
        }
    }

    /**
     * 获取连接池统计信息。
     *
     * @return 包含连接池状态的Map
     */
    public Map<String, Object> getPoolStats() {
        Map<String, Object> stats = new HashMap<>();
        if (dataSource != null) {
            stats.put("activeConnections", dataSource.getHikariPoolMXBean() != null
                    ? dataSource.getHikariPoolMXBean().getActiveConnections() : 0);
            stats.put("idleConnections", dataSource.getHikariPoolMXBean() != null
                    ? dataSource.getHikariPoolMXBean().getIdleConnections() : 0);
            stats.put("totalConnections", dataSource.getHikariPoolMXBean() != null
                    ? dataSource.getHikariPoolMXBean().getTotalConnections() : 0);
            stats.put("threadsAwaiting", dataSource.getHikariPoolMXBean() != null
                    ? dataSource.getHikariPoolMXBean().getThreadsAwaitingConnection() : 0);
        }
        return stats;
    }

    /**
     * 将ResultSet当前行映射为MetadataRecord对象。
     *
     * @param rs 数据库结果集，已定位到有效行
     * @return 映射后的MetadataRecord对象
     * @throws SQLException 如果读取结果集字段出错
     */
    private MetadataRecord mapResultSetToRecord(ResultSet rs) throws SQLException {
        MetadataRecord record = new MetadataRecord();
        record.setId(rs.getString("id"));
        record.setUserId(rs.getString("user_id"));
        record.setAgentId(rs.getString("agent_id"));
        record.setContent(rs.getString("content"));
        record.setImportance(rs.getDouble("importance"));

        // 反序列化data_json字段
        String dataJson = rs.getString("data_json");
        if (dataJson != null && !dataJson.isEmpty()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = gson.fromJson(dataJson, Map.class);
                record.setData(data);
            } catch (Exception e) {
                System.err.println("[JdbcMetadataStore] Failed to parse data_json: " + e.getMessage());
                record.setData(new HashMap<>());
            }
        } else {
            record.setData(new HashMap<>());
        }

        // 解析时间戳
        String createdAtStr = rs.getString("created_at");
        if (createdAtStr != null) {
            try {
                record.setCreatedAt(Instant.parse(createdAtStr));
            } catch (Exception e) {
                // 尝试其他格式解析
                record.setCreatedAt(Instant.now());
            }
        }

        String updatedAtStr = rs.getString("updated_at");
        if (updatedAtStr != null) {
            try {
                record.setUpdatedAt(Instant.parse(updatedAtStr));
            } catch (Exception e) {
                record.setUpdatedAt(Instant.now());
            }
        }

        return record;
    }

    /**
     * 对表名进行安全转义，防止表名注入。
     * 仅允许字母、数字、下划线，其余字符替换为下划线。
     *
     * @param tableName 原始表名
     * @return 转义后的安全表名
     */
    private String escapeTableName(String tableName) {
        if (tableName == null || tableName.isEmpty()) {
            throw new IllegalArgumentException("[JdbcMetadataStore] Table name cannot be null or empty.");
        }
        // 仅保留安全字符
        return tableName.replaceAll("[^a-zA-Z0-9_]", "_");
    }
}
