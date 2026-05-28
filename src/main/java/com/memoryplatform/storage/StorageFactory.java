package com.memoryplatform.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 存储工厂 - 统一管理所有存储适配器的注册与获取
 * <p>
 * 支持三种存储适配器注册方式（优先级从高到低）：
 * <ol>
 *     <li><b>动态注册</b> - 运行时通过 register*() 方法注册</li>
 *     <li><b>Spring注入</b> - 构造器自动收集所有 Spring 管理的适配器 Bean</li>
 *     <li><b>SPI自动发现</b> - 通过 ServiceLoader 机制发现 classpath 上的适配器</li>
 * </ol>
 *
 * <h3>SPI使用示例</h3>
 * <p>将适配器实现类的全限定名写入对应的 SPI 配置文件：</p>
 * <pre>
 * META-INF/services/com.memoryplatform.storage.VectorStore
 * META-INF/services/com.memoryplatform.storage.GraphStore
 * META-INF/services/com.memoryplatform.storage.MetadataStore
 * </pre>
 *
 * <h3>动态注册示例</h3>
 * <pre>
 * // 运行时注册新的向量存储
 * storageFactory.registerVectorStore("qdrant", new QdrantVectorStore());
 *
 * // 通过名称获取
 * VectorStore store = storageFactory.getVectorStore("qdrant");
 * </pre>
 *
 * @author Agent Memory Platform
 * @since 1.0
 */
@Slf4j
@Component
public class StorageFactory {

    private final Map<String, VectorStore> vectorStores = new ConcurrentHashMap<>();
    private final Map<String, GraphStore> graphStores = new ConcurrentHashMap<>();
    private final Map<String, MetadataStore> metadataStores = new ConcurrentHashMap<>();

    /**
     * 构造器注入：自动收集 Spring 管理的存储 Bean + SPI 自动发现
     *
     * @param vsList Spring 容器中所有 VectorStore 类型的 Bean
     * @param gsList Spring 容器中所有 GraphStore 类型的 Bean
     * @param msList Spring 容器中所有 MetadataStore 类型的 Bean
     */
    public StorageFactory(
            Optional<List<VectorStore>> vsList,
            Optional<List<GraphStore>> gsList,
            Optional<List<MetadataStore>> msList) {

        // 1. 注册 Spring 管理的适配器 Bean
        vsList.ifPresent(list -> list.forEach(vs -> {
            String name = vs.getStoreName();
            vectorStores.put(name, vs);
            log.info("[StorageFactory] Spring注册VectorStore: {}", name);
        }));
        gsList.ifPresent(list -> list.forEach(gs -> {
            String name = gs.getStoreName();
            graphStores.put(name, gs);
            log.info("[StorageFactory] Spring注册GraphStore: {}", name);
        }));
        msList.ifPresent(list -> list.forEach(ms -> {
            String name = ms.getStoreName();
            metadataStores.put(name, ms);
            log.info("[StorageFactory] Spring注册MetadataStore: {}", name);
        }));

        // 2. SPI 自动发现（putIfAbsent 避免覆盖 Spring 注册的实例）
        discoverSpi(VectorStore.class, vectorStores);
        discoverSpi(GraphStore.class, graphStores);
        discoverSpi(MetadataStore.class, metadataStores);

        log.info("[StorageFactory] 初始化完成: vectorStores={}, graphStores={}, metadataStores={}",
                vectorStores.keySet(), graphStores.keySet(), metadataStores.keySet());
    }

    // ======================== VectorStore ========================

    /**
     * 按名称获取向量存储
     * @param name 存储名称
     * @return 对应的 VectorStore，不存在时返回 null
     */
    public VectorStore getVectorStore(String name) {
        return vectorStores.get(name);
    }

    /**
     * 获取默认向量存储（注册的第一个）
     * @return 默认 VectorStore，无可用存储时返回 null
     */
    public VectorStore getDefaultVectorStore() {
        return vectorStores.values().stream().findFirst().orElse(null);
    }

    /**
     * 动态注册向量存储
     * @param name 存储名称（唯一标识）
     * @param store VectorStore 实例
     */
    public void registerVectorStore(String name, VectorStore store) {
        vectorStores.put(name, store);
        log.info("[StorageFactory] 动态注册VectorStore: {} ({})", name, store.getClass().getName());
    }

    /**
     * 获取所有可用向量存储的名称
     */
    public Collection<String> availableVectorStores() {
        return Collections.unmodifiableCollection(vectorStores.keySet());
    }

    // ======================== GraphStore ========================

    /**
     * 按名称获取图存储
     * @param name 存储名称
     * @return 对应的 GraphStore，不存在时返回 null
     */
    public GraphStore getGraphStore(String name) {
        return graphStores.get(name);
    }

    /**
     * 获取默认图存储
     */
    public GraphStore getDefaultGraphStore() {
        return graphStores.values().stream().findFirst().orElse(null);
    }

    /**
     * 动态注册图存储
     * @param name 存储名称
     * @param store GraphStore 实例
     */
    public void registerGraphStore(String name, GraphStore store) {
        graphStores.put(name, store);
        log.info("[StorageFactory] 动态注册GraphStore: {} ({})", name, store.getClass().getName());
    }

    /**
     * 获取所有可用图存储的名称
     */
    public Collection<String> availableGraphStores() {
        return Collections.unmodifiableCollection(graphStores.keySet());
    }

    // ======================== MetadataStore ========================

    /**
     * 按名称获取元数据存储
     * @param name 存储名称
     * @return 对应的 MetadataStore，不存在时返回 null
     */
    public MetadataStore getMetadataStore(String name) {
        return metadataStores.get(name);
    }

    /**
     * 获取默认元数据存储
     */
    public MetadataStore getDefaultMetadataStore() {
        return metadataStores.values().stream().findFirst().orElse(null);
    }

    /**
     * 动态注册元数据存储
     * @param name 存储名称
     * @param store MetadataStore 实例
     */
    public void registerMetadataStore(String name, MetadataStore store) {
        metadataStores.put(name, store);
        log.info("[StorageFactory] 动态注册MetadataStore: {} ({})", name, store.getClass().getName());
    }

    /**
     * 获取所有可用元数据存储的名称
     */
    public Collection<String> availableMetadataStores() {
        return Collections.unmodifiableCollection(metadataStores.keySet());
    }

    // ======================== 批量健康检查 ========================

    /**
     * 检查所有已注册的存储是否健康
     * @return 健康状态汇总
     */
    public Map<String, Boolean> healthCheckAll() {
        Map<String, Boolean> results = new LinkedHashMap<>();
        vectorStores.forEach((name, store) ->
                results.put("vector:" + name, safeHealthCheck(store)));
        graphStores.forEach((name, store) ->
                results.put("graph:" + name, safeHealthCheck(store)));
        metadataStores.forEach((name, store) ->
                results.put("metadata:" + name, safeHealthCheck(store)));
        return results;
    }

    // ======================== SPI 发现 ========================

    /**
     * 通过 ServiceLoader 发现实现类并注册到对应的注册表
     *
     * @param type     接口类型
     * @param registry 注册表 Map
     * @param <T>      接口泛型
     */
    private <T> void discoverSpi(Class<T> type, Map<String, T> registry) {
        try {
            ServiceLoader<T> loader = ServiceLoader.load(type);
            for (T impl : loader) {
                String name;
                if (impl instanceof VectorStore vs) {
                    name = vs.getStoreName();
                } else if (impl instanceof GraphStore gs) {
                    name = gs.getStoreName();
                } else if (impl instanceof MetadataStore ms) {
                    name = ms.getStoreName();
                } else {
                    name = impl.getClass().getSimpleName();
                }
                T existing = registry.putIfAbsent(name, impl);
                if (existing == null) {
                    log.info("[StorageFactory] SPI发现并注册: {} -> {} (类型: {})",
                            name, impl.getClass().getName(), type.getSimpleName());
                } else {
                    log.debug("[StorageFactory] SPI发现 {} 但已存在Spring注册，跳过: {}",
                            name, impl.getClass().getName());
                }
            }
        } catch (Exception e) {
            log.warn("[StorageFactory] SPI加载 {} 失败: {}", type.getSimpleName(), e.getMessage());
        }
    }

    // ======================== 安全工具 ========================

    /**
     * 安全执行健康检查，捕获异常
     */
    private boolean safeHealthCheck(Object store) {
        try {
            if (store instanceof VectorStore vs) return vs.healthCheck();
            if (store instanceof GraphStore gs) return gs.healthCheck();
            if (store instanceof MetadataStore ms) return ms.healthCheck();
            return false;
        } catch (Exception e) {
            log.warn("[StorageFactory] 健康检查异常: {}", e.getMessage());
            return false;
        }
    }
}
