package com.memoryplatform.storage;

import com.memoryplatform.storage.adapters.MilvusVectorStore;
import com.memoryplatform.storage.adapters.Neo4jGraphStore;
import com.memoryplatform.storage.adapters.JdbcMetadataStore;

import java.util.Map;

/**
 * 存储适配器工厂类 - 统一创建和管理各存储组件的实例。
 *
 * <p>支持的类型：milvus、neo4j、mysql、postgresql。</p>
 *
 * @author MemoryPlatform
 * @since 1.0
 */
public class StorageFactory {

    /** 单例实例（volatile 保证线程可见性） */
    private static volatile StorageFactory instance;

    /** 私有构造函数 */
    private StorageFactory() {
    }

    /**
     * 获取工厂单例（双重检查锁，线程安全）。
     *
     * @return 全局唯一的 StorageFactory 实例
     */
    public static StorageFactory getInstance() {
        if (instance == null) {
            synchronized (StorageFactory.class) {
                if (instance == null) {
                    instance = new StorageFactory();
                    System.out.println("[StorageFactory] singleton created");
                }
            }
        }
        return instance;
    }

    /**
     * 根据类型创建向量存储实例。
     *
     * @param type   存储类型，目前仅支持 "milvus"
     * @param config 初始化参数（会透传给 init 方法）
     * @return 已初始化的 VectorStore 实例
     * @throws IllegalArgumentException 当 type 不被支持时
     */
    public VectorStore createVectorStore(String type, Map<String, Object> config) {
        VectorStore store;
        switch (type.toLowerCase()) {
            case "milvus":
                store = new MilvusVectorStore();
                break;
            default:
                throw new IllegalArgumentException(
                        "[StorageFactory] unsupported VectorStore type: " + type);
        }
        System.out.println("[StorageFactory] creating VectorStore [" + type + "]...");
        store.init(config);
        System.out.println("[StorageFactory] VectorStore [" + type + "] created");
        return store;
    }

    /**
     * 根据类型创建图存储实例。
     *
     * @param type   存储类型，目前仅支持 "neo4j"
     * @param config 初始化参数（会透传给 init 方法）
     * @return 已初始化的 GraphStore 实例
     * @throws IllegalArgumentException 当 type 不被支持时
     */
    public GraphStore createGraphStore(String type, Map<String, Object> config) {
        GraphStore store;
        switch (type.toLowerCase()) {
            case "neo4j":
                store = new Neo4jGraphStore();
                break;
            default:
                throw new IllegalArgumentException(
                        "[StorageFactory] unsupported GraphStore type: " + type);
        }
        System.out.println("[StorageFactory] creating GraphStore [" + type + "]...");
        store.init(config);
        System.out.println("[StorageFactory] GraphStore [" + type + "] created");
        return store;
    }

    /**
     * 根据类型创建元数据存储实例。
     *
     * @param type   存储类型，支持 "mysql" 和 "postgresql"
     * @param config 初始化参数（会透传给 init 方法）
     * @return 已初始化的 MetadataStore 实例
     * @throws IllegalArgumentException 当 type 不被支持时
     */
    public MetadataStore createMetadataStore(String type, Map<String, Object> config) {
        MetadataStore store;
        switch (type.toLowerCase()) {
            case "mysql":
            case "postgresql":
                store = new JdbcMetadataStore();
                break;
            default:
                throw new IllegalArgumentException(
                        "[StorageFactory] unsupported MetadataStore type: " + type);
        }
        System.out.println("[StorageFactory] creating MetadataStore [" + type + "]...");
        store.init(config);
        System.out.println("[StorageFactory] MetadataStore [" + type + "] created");
        return store;
    }

    /**
     * 从全局配置一次性创建所有存储实例。
     *
     * <p>每个子配置中必须包含 "type" 字段。未提供某一类配置时，该类存储不会被创建。</p>
     *
     * @param globalConfig 全局配置 Map
     * @return 包含所有已创建存储实例的 StorageBundle
     * @throws IllegalArgumentException 任意类型不被支持时抛出
     */
    public StorageBundle fromConfig(Map<String, Object> globalConfig) {
        System.out.println("[StorageFactory] creating all stores from global config...");

        VectorStore vectorStore = null;
        GraphStore graphStore = null;
        MetadataStore metadataStore = null;

        Object vsConfig = globalConfig.get("vectorStore");
        if (vsConfig instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cfg = (Map<String, Object>) vsConfig;
            String type = (String) cfg.get("type");
            if (type != null) {
                vectorStore = createVectorStore(type, cfg);
            } else {
                System.out.println("[StorageFactory] vectorStore missing type, skipped");
            }
        } else {
            System.out.println("[StorageFactory] vectorStore not configured, skipped");
        }

        Object gsConfig = globalConfig.get("graphStore");
        if (gsConfig instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cfg = (Map<String, Object>) gsConfig;
            String type = (String) cfg.get("type");
            if (type != null) {
                graphStore = createGraphStore(type, cfg);
            } else {
                System.out.println("[StorageFactory] graphStore missing type, skipped");
            }
        } else {
            System.out.println("[StorageFactory] graphStore not configured, skipped");
        }

        Object msConfig = globalConfig.get("metadataStore");
        if (msConfig instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cfg = (Map<String, Object>) msConfig;
            String type = (String) cfg.get("type");
            if (type != null) {
                metadataStore = createMetadataStore(type, cfg);
            } else {
                System.out.println("[StorageFactory] metadataStore missing type, skipped");
            }
        } else {
            System.out.println("[StorageFactory] metadataStore not configured, skipped");
        }

        System.out.println("[StorageFactory] done - VS=" + (vectorStore != null)
                + " GS=" + (graphStore != null) + " MS=" + (metadataStore != null));

        return new StorageBundle(vectorStore, graphStore, metadataStore);
    }

    public static class StorageBundle {

        private final VectorStore vectorStore;
        private final GraphStore graphStore;
        private final MetadataStore metadataStore;

        public StorageBundle(VectorStore vectorStore, GraphStore graphStore, MetadataStore metadataStore) {
            this.vectorStore = vectorStore;
            this.graphStore = graphStore;
            this.metadataStore = metadataStore;
        }

        public VectorStore getVectorStore() {
            return vectorStore;
        }

        public GraphStore getGraphStore() {
            return graphStore;
        }

        public MetadataStore getMetadataStore() {
            return metadataStore;
        }

        @Override
        public String toString() {
            return "StorageBundle{"
                    + "vectorStore=" + (vectorStore != null ? vectorStore.getClass().getSimpleName() : "null")
                    + ", graphStore=" + (graphStore != null ? graphStore.getClass().getSimpleName() : "null")
                    + ", metadataStore=" + (metadataStore != null ? metadataStore.getClass().getSimpleName() : "null")
                    + '}';
        }
    }
}
