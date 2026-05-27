package com.memoryplatform.storage;

import com.memoryplatform.model.MetadataRecord;
import java.util.List;
import java.util.Map;

/**
 * 元数据存储统一接口 - 所有关系数据库适配器实现此接口
 */
public interface MetadataStore {

    /**
     * 初始化存储连接
     */
    default void init(Map<String, Object> config) {}

    /**
     * 插入记录
     * @param table 表名
     * @param record 记录
     * @return 记录ID
     */
    String insert(String table, MetadataRecord record);

    /**
     * 批量插入
     * @param table 表名
     * @param records 记录列表
     * @return ID列表
     */
    List<String> batchInsert(String table, List<MetadataRecord> records);

    /**
     * 查询
     * @param table 表名
     * @param filters 过滤条件
     * @param limit 返回数量
     * @param offset 偏移量
     * @return 记录列表
     */
    List<MetadataRecord> find(String table, Map<String, Object> filters, 
                             int limit, int offset);

    /**
     * 更新
     * @param table 表名
     * @param id 记录ID
     * @param updates 更新内容
     * @return 是否成功
     */
    boolean update(String table, String id, Map<String, Object> updates);

    /**
     * 删除
     * @param table 表名
     * @param ids ID列表
     * @return 是否成功
     */
    boolean delete(String table, List<String> ids);

    /**
     * 计数
     * @param table 表名
     * @param filters 过滤条件
     * @return 数量
     */
    long count(String table, Map<String, Object> filters);

    /**
     * 健康检查
     * @return 是否健康
     */
    boolean healthCheck();
}
