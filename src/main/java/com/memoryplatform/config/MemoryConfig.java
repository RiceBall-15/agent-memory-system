package com.memoryplatform.config;

import com.memoryplatform.cache.LRUCache;
import com.memoryplatform.service.*;
import com.memoryplatform.storage.GraphStore;
import com.memoryplatform.storage.MetadataStore;
import com.memoryplatform.storage.StorageFactory;
import com.memoryplatform.storage.VectorStore;
import com.memoryplatform.storage.adapters.JdbcMetadataStore;
import com.memoryplatform.storage.adapters.MilvusVectorStore;
import com.memoryplatform.storage.adapters.Neo4jGraphStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import jakarta.annotation.PreDestroy;
import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Configuration
@EnableScheduling
@EnableConfigurationProperties(MemoryConfig.MilvusProperties.class,
        MemoryConfig.Neo4jProperties.class,
        MemoryConfig.MySqlProperties.class)
public class MemoryConfig {

    private StorageFactory storageFactory;

    @Value("${app.milvus.host:localhost}")
    private String milvusHost;

    @Value("${app.milvus.port:19530}")
    private int milvusPort;

    @Value("${app.milvus.collection-prefix:agent_memory_}")
    private String milvusCollectionPrefix;

    @Value("${app.milvus.index-type:IVF_FLAT}")
    private String milvusIndexType;

    @Value("${app.milvus.metric-type:COSINE}")
    private String milvusMetricType;

    @Value("${app.milvus.nlist:1024}")
    private int milvusNlist;

    @Value("${app.neo4j.uri:bolt://localhost:7687}")
    private String neo4jUri;

    @Value("${app.neo4j.username:neo4j}")
    private String neo4jUsername;

    @Value("${app.neo4j.password:password}")
    private String neo4jPassword;

    @Value("${app.neo4j.max-connection-pool-size:50}")
    private int neo4jMaxPoolSize;

    @Value("${app.mysql.jdbc-url}")
    private String mysqlJdbcUrl;

    @Value("${app.mysql.username:root}")
    private String mysqlUsername;

    @Value("${app.mysql.password:password}")
    private String mysqlPassword;

    @Value("${app.mysql.maximum-pool-size:10}")
    private int mysqlMaxPoolSize;

    @Value("${app.mysql.minimum-idle:2}")
    private int mysqlMinIdle;

    @Value("${app.mysql.idle-timeout:300000}")
    private long mysqlIdleTimeout;

    @Value("${app.mysql.connection-timeout:30000}")
    private long mysqlConnectionTimeout;

    @Value("${app.llm.base-url:http://localhost:11434}")
    private String llmBaseUrl;

    @Value("${app.llm.model:qwen2.5:7b}")
    private String llmModel;

    @Value("${app.llm.timeout:60000}")
    private long llmTimeout;

    @Bean
    public VectorStore vectorStore() {
        log.info("[MemoryConfig] 初始化Milvus向量存储: {}:{} prefix={}",
                milvusHost, milvusPort, milvusCollectionPrefix);
        try {
            MilvusVectorStore store = new MilvusVectorStore();
            Map<String, Object> config = new HashMap<>();
            config.put("host", milvusHost);
            config.put("port", milvusPort);
            config.put("collectionPrefix", milvusCollectionPrefix);
            config.put("indexType", milvusIndexType);
            config.put("metricType", milvusMetricType);
            config.put("nlist", milvusNlist);
            store.init(config);
            log.info("[MemoryConfig] Milvus向量存储初始化成功");
            // 注册到StorageFactory
            if (storageFactory != null) {
                storageFactory.registerVectorStore(store.getStoreName(), store);
            }
            return store;
        } catch (Exception e) {
            log.error("[MemoryConfig] Milvus向量存储初始化失败: {}", e.getMessage());
            return null;
        }
    }

    @Bean
    public Driver neo4jDriver() {
        log.info("[MemoryConfig] 创建Neo4j Driver: {}", neo4jUri);
        return GraphDatabase.driver(neo4jUri,
                AuthTokens.basic(neo4jUsername, neo4jPassword),
                Config.builder()
                        .withMaxConnectionPoolSize(neo4jMaxPoolSize)
                        .build());
    }

    @Bean
    public GraphStore graphStore() {
        log.info("[MemoryConfig] 初始化Neo4j图存储: {}", neo4jUri);
        try {
            Neo4jGraphStore store = new Neo4jGraphStore();
            Map<String, Object> config = new HashMap<>();
            config.put("uri", neo4jUri);
            config.put("username", neo4jUsername);
            config.put("password", neo4jPassword);
            store.init(config);
            log.info("[MemoryConfig] Neo4j图存储初始化成功");
            // 注册到StorageFactory
            if (storageFactory != null) {
                storageFactory.registerGraphStore(store.getStoreName(), store);
            }
            return store;
        } catch (Exception e) {
            log.error("[MemoryConfig] Neo4j图存储初始化失败: {}", e.getMessage());
            return null;
        }
    }

    @Bean
    public DataSource dataSource() {
        log.info("[MemoryConfig] 初始化HikariCP数据源: {}", mysqlJdbcUrl);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(mysqlJdbcUrl);
        config.setUsername(mysqlUsername);
        config.setPassword(mysqlPassword);
        config.setMaximumPoolSize(mysqlMaxPoolSize);
        config.setMinimumIdle(mysqlMinIdle);
        config.setIdleTimeout(mysqlIdleTimeout);
        config.setConnectionTimeout(mysqlConnectionTimeout);
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        return new HikariDataSource(config);
    }

    @Bean
    public MetadataStore metadataStore(DataSource dataSource) {
        log.info("[MemoryConfig] 初始化JDBC元数据存储");
        try {
            JdbcMetadataStore store = new JdbcMetadataStore();
            Map<String, Object> config = new HashMap<>();
            config.put("dataSource", dataSource);
            store.init(config);
            log.info("[MemoryConfig] JDBC元数据存储初始化成功");
            // 注册到StorageFactory
            if (storageFactory != null) {
                storageFactory.registerMetadataStore(store.getStoreName(), store);
            }
            return store;
        } catch (Exception e) {
            log.error("[MemoryConfig] JDBC元数据存储初始化失败: {}", e.getMessage());
            return null;
        }
    }
    /**
     * 注入StorageFactory（可选，避免循环依赖）
     */
    @Autowired(required = false)
    public void setStorageFactory(StorageFactory storageFactory) {
        this.storageFactory = storageFactory;
    }

    @Bean
    public EmbeddingService embeddingService() {
        log.info("[MemoryConfig] 创建Embedding服务: type=noOp (随机向量)");
        return EmbeddingService.noOp();
    }

    @Bean
    public ConcurrentWriteService writeService(VectorStore vectorStore,
                                                GraphStore graphStore,
                                                MetadataStore metadataStore,
                                                EmbeddingService embeddingService,
                                                Executor boundedPoolExecutor,
                                                java.util.concurrent.ScheduledExecutorService scheduledExecutor) {
        log.info("[MemoryConfig] 创建高并发写入服务");
        return new ConcurrentWriteService(
                embeddingService,
                vectorStore,
                graphStore,
                metadataStore,
                boundedPoolExecutor,
                scheduledExecutor
        );
    }

    @Bean
    public HybridRetrievalService retrievalService(VectorStore vectorStore,
                                                    GraphStore graphStore,
                                                    MetadataStore metadataStore,
                                                    EmbeddingService embeddingService) {
        log.info("[MemoryConfig] 创建混合检索服务");
        return new HybridRetrievalService(vectorStore, graphStore, metadataStore, embeddingService);
    }

    @Bean
    public MemoryDeduplicationService deduplicationService(MetadataStore metadataStore,
                                                           VectorStore vectorStore) {
        log.info("[MemoryConfig] 创建记忆去重服务");
        return new MemoryDeduplicationService(metadataStore, vectorStore);
    }

    @Bean
    public MemoryTtlService ttlService(MetadataStore metadataStore) {
        log.info("[MemoryConfig] 创建TTL过期服务");
        return new MemoryTtlService(metadataStore);
    }

    @Bean
    public MemoryDecayService decayService(MetadataStore metadataStore) {
        log.info("[MemoryConfig] 创建记忆衰减服务");
        return new MemoryDecayService(metadataStore);
    }

    @Bean
    public MemorySharingService sharingService(MetadataStore metadataStore) {
        log.info("[MemoryConfig] 创建记忆共享服务");
        return new MemorySharingService(metadataStore);
    }

    @Bean
    public MemoryCompressionService compressionService(MetadataStore metadataStore,
                                                       VectorStore vectorStore,
                                                       EmbeddingService embeddingService) {
        log.info("[MemoryConfig] 创建记忆压缩服务");
        return new MemoryCompressionService(metadataStore, vectorStore, embeddingService);
    }

    @Bean
    public MemoryIndexService indexService(MetadataStore metadataStore,
                                            VectorStore vectorStore,
                                            EmbeddingService embeddingService) {
        log.info("[MemoryConfig] 创建索引优化服务");
        return new MemoryIndexService(metadataStore, vectorStore, embeddingService);
    }

    @Bean
    public MemoryContextService contextService(HybridRetrievalService retrievalService,
                                                MetadataStore metadataStore) {
        log.info("[MemoryConfig] 创建记忆上下文服务");
        return new MemoryContextService(retrievalService, metadataStore);
    }

    @Bean
    public MemoryVersionService versionService() {
        log.info("[MemoryConfig] 创建版本管理服务");
        return new MemoryVersionService();
    }

    @Bean
    public AuditLogService auditLogService() {
        log.info("[MemoryConfig] 创建审计日志服务");
        return new AuditLogService();
    }

    @Bean
    public LRUCache<String, Object> metadataCache() {
        log.info("[MemoryConfig] 创建LRU缓存: maxSize=500, ttl=5min");
        return new LRUCache<>(500, 300_000);
    }

    @PreDestroy
    public void destroy() {
        log.info("[MemoryConfig] 清理存储资源...");
    }

    @ConfigurationProperties(prefix = "app.milvus")
    public static class MilvusProperties {
        private String host = "localhost";
        private int port = 19530;
        private String collectionPrefix = "agent_memory_";
        private String indexType = "IVF_FLAT";
        private String metricType = "COSINE";
        private int nlist = 1024;

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getCollectionPrefix() { return collectionPrefix; }
        public void setCollectionPrefix(String prefix) { this.collectionPrefix = prefix; }
        public String getIndexType() { return indexType; }
        public void setIndexType(String type) { this.indexType = type; }
        public String getMetricType() { return metricType; }
        public void setMetricType(String type) { this.metricType = type; }
        public int getNlist() { return nlist; }
        public void setNlist(int nlist) { this.nlist = nlist; }
    }

    @ConfigurationProperties(prefix = "app.neo4j")
    public static class Neo4jProperties {
        private String uri = "bolt://localhost:7687";
        private String username = "neo4j";
        private String password = "password";
        private int maxConnectionPoolSize = 50;

        public String getUri() { return uri; }
        public void setUri(String uri) { this.uri = uri; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public int getMaxConnectionPoolSize() { return maxConnectionPoolSize; }
        public void setMaxConnectionPoolSize(int size) { this.maxConnectionPoolSize = size; }
    }

    @ConfigurationProperties(prefix = "app.mysql")
    public static class MySqlProperties {
        private String jdbcUrl;
        private String username;
        private String password;
        private int maximumPoolSize = 10;
        private int minimumIdle = 2;
        private long idleTimeout = 300_000;
        private long connectionTimeout = 30_000;

        public String getJdbcUrl() { return jdbcUrl; }
        public void setJdbcUrl(String url) { this.jdbcUrl = url; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public int getMaximumPoolSize() { return maximumPoolSize; }
        public void setMaximumPoolSize(int size) { this.maximumPoolSize = size; }
        public int getMinimumIdle() { return minimumIdle; }
        public void setMinimumIdle(int min) { this.minimumIdle = min; }
        public long getIdleTimeout() { return idleTimeout; }
        public void setIdleTimeout(long timeout) { this.idleTimeout = timeout; }
        public long getConnectionTimeout() { return connectionTimeout; }
        public void setConnectionTimeout(long timeout) { this.connectionTimeout = timeout; }
    }
}
