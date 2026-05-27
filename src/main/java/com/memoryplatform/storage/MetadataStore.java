package com.memoryplatform.storage;

import com.memoryplatform.model.MetadataRecord;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * 元数据存储统一接口 - 所有关系型存储适配器实现此接口
 * <p>
 * 支持SPI自动发现和Spring动态注册，实现Plugin化存储层。
 * 所有方法提供default实现，增强容错性——未实现的方法返回安全默认值而非抛出异常。
 * </p>
 *
 * @author Agent Memory Platform
 * @since 1.0
 */
@Slf4j
public interface MetadataStore {

    /**
     * 获取存储名称（用于SPI发现和注册表键）
     * <p>默认返回实现类的简单类名。</p>
     */
    default String getStoreName() {
        return getClass().getSimpleName();
    }

    /**
     * 初始化存储连接
     */
    default void init(Map<String, Object> config) {}

    /**
     * 插入一条记录
     * @param table 表名
     * @param record 元数据记录
     * @return 插入的记录ID
     */
    default String insert(String table, MetadataRecord record) {
        log.error("[MetadataStore] insert not implemented by " + getClass().getSimpleName());
        return null;
    }

    /**
     * 批量插入记录
     * @param table 表名
     * @param records 元数据记录列表
     * @return 插入的ID列表
     */
    default List<String> batchInsert(String table, List<MetadataRecord> records) {
        log.error("[MetadataStore] batchInsert not implemented by " + getClass().getSimpleName());
        return Collections.emptyList();
    }

    /**
     * 查找记录（无分页）
     * @param table 表名
     * @param filters 过滤条件
     * @return 匹配的记录列表
     */
    default List<MetadataRecord> find(String table, Map<String, Object> filters) {
        log.error("[MetadataStore] find not implemented by " + getClass().getSimpleName());
        return Collections.emptyList();
    }

    /**
     * 查找记录（带分页）
     * @param table 表名
     * @param filters 过滤条件
     * @param limit 最大返回数
     * @param offset 偏移量
     * @return 匹配的记录列表
     */
    default List<MetadataRecord> find(String table, Map<String, Object> filters, int limit, int offset) {
        // 默认委托给无分页版本，子类应覆盖以提供真正的分页
        return find(table, filters);
    }

    /**
     * 更新记录
     * @param table 表名
     * @param id 记录ID
     * @param updates 更新字段
     * @return 是否成功
     */
    default boolean update(String table, String id, Map<String, Object> updates) {
        log.error("[MetadataStore] update not implemented by " + getClass().getSimpleName());
        return false;
    }

    /**
     * 删除记录（单条）
     * @param table 表名
     * @param id 记录ID
     * @return 是否成功
     */
    default boolean delete(String table, String id) {
        log.error("[MetadataStore] delete not implemented by " + getClass().getSimpleName());
        return false;
    }

    /**
     * 删除记录（批量）
     * @param table 表名
     * @param ids 记录ID列表
     * @return 是否成功（至少删除一条）
     */
    default boolean delete(String table, List<String> ids) {
        // 默认逐条删除
        boolean anyDeleted = false;
        for (String id : ids) {
            if (delete(table, id)) {
                anyDeleted = true;
            }
        }
        return anyDeleted;
    }

    /**
     * 统计记录数
     * @param table 表名
     * @param filters 过滤条件
     * @return 记录数量
     */
    default long count(String table, Map<String, Object> filters) {
        log.error("[MetadataStore] count not implemented by " + getClass().getSimpleName());
        return 0;
    }

    /**
     * 健康检查
     * @return 是否健康
     */
    default boolean healthCheck() {
        return false;
    }
}
