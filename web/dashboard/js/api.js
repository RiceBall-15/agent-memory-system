/**
 * API调用封装模块
 * 
 * 提供与Agent Memory System后端的HTTP通信封装，
 * 包含统一的错误处理、请求拦截和响应解析。
 * 
 * @module api
 */

const API = (() => {
    'use strict';

    // ==================== 配置 ====================

    /** API基础URL */
    let BASE_URL = 'http://localhost:8080';

    /** Prometheus指标端口 */
    let METRICS_PORT = 9090;

    /** 请求超时时间(毫秒) */
    const REQUEST_TIMEOUT = 30000;

    /** 重试次数 */
    const MAX_RETRIES = 3;

    /** 重试延迟(毫秒) */
    const RETRY_DELAY = 1000;

    // ==================== 内部工具函数 ====================

    /**
     * 统一fetch请求封装
     * @param {string} url - 请求URL
     * @param {Object} options - fetch选项
     * @param {number} retries - 剩余重试次数
     * @returns {Promise<Object>} 响应数据
     */
    async function request(url, options = {}, retries = MAX_RETRIES) {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), REQUEST_TIMEOUT);

        const defaultHeaders = {
            'Content-Type': 'application/json',
        };

        const config = {
            ...options,
            headers: {
                ...defaultHeaders,
                ...options.headers,
            },
            signal: controller.signal,
        };

        try {
            const response = await fetch(url, config);
            clearTimeout(timeoutId);

            // 解析响应
            const contentType = response.headers.get('content-type');
            let data;

            if (contentType && contentType.includes('application/json')) {
                data = await response.json();
            } else {
                data = await response.text();
            }

            // 检查HTTP状态码
            if (!response.ok) {
                const errorMsg = typeof data === 'object' 
                    ? (data.message || data.error || `HTTP ${response.status}`)
                    : `HTTP ${response.status}: ${data}`;
                throw new ApiError(errorMsg, response.status, data);
            }

            return data;

        } catch (error) {
            clearTimeout(timeoutId);

            // 如果是网络错误且还有重试次数，进行重试
            if (retries > 0 && (error.name === 'AbortError' || error.name === 'TypeError')) {
                console.warn(`[API] 请求失败，${RETRY_DELAY}ms后重试 (${retries}/${MAX_RETRIES})...`);
                await sleep(RETRY_DELAY);
                return request(url, options, retries - 1);
            }

            throw error;
        }
    }

    /**
     * 模拟延迟（用于开发测试）
     * @param {number} ms - 延迟毫秒数
     * @returns {Promise<void>}
     */
    function sleep(ms) {
        return new Promise(resolve => setTimeout(resolve, ms));
    }

    /**
     * 构建查询字符串
     * @param {Object} params - 查询参数
     * @returns {string} URL查询字符串
     */
    function buildQueryString(params) {
        if (!params || Object.keys(params).length === 0) return '';
        const parts = Object.entries(params)
            .filter(([, value]) => value !== undefined && value !== null && value !== '')
            .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(value)}`);
        return parts.length > 0 ? '?' + parts.join('&') : '';
    }

    // ==================== 自定义错误类 ====================

    /**
     * API错误类
     * @param {string} message - 错误消息
     * @param {number} status - HTTP状态码
     * @param {*} data - 响应数据
     */
    class ApiError extends Error {
        constructor(message, status, data) {
            super(message);
            this.name = 'ApiError';
            this.status = status;
            this.data = data;
        }
    }

    // ==================== 公共API方法 ====================

    return {
        /**
         * 设置API基础URL
         * @param {string} url - 新的基础URL
         */
        setBaseUrl(url) {
            BASE_URL = url.replace(/\/+$/, '');
        },

        /**
         * 获取当前基础URL
         * @returns {string} 当前基础URL
         */
        getBaseUrl() {
            return BASE_URL;
        },

        /**
         * 设置指标端口
         * @param {number} port - Prometheus指标端口
         */
        setMetricsPort(port) {
            METRICS_PORT = port;
        },

        /**
         * 获取当前指标端口
         * @returns {number} 当前指标端口
         */
        getMetricsPort() {
            return METRICS_PORT;
        },

        // ==================== 记忆管理API ====================

        /**
         * 获取记忆列表
         * @param {Object} params - 查询参数
         * @param {string} params.userId - 用户ID过滤
         * @param {string} params.agentId - Agent ID过滤
         * @param {number} params.limit - 返回数量限制(默认20，最大100)
         * @param {number} params.offset - 偏移量(默认0)
         * @returns {Promise<Object>} 包含memories数组的响应
         */
        async getMemories(params = {}) {
            const queryString = buildQueryString(params);
            const url = `${BASE_URL}/api/memories${queryString}`;
            return request(url);
        },

        /**
         * 获取单条记忆
         * @param {string} id - 记忆ID
         * @returns {Promise<Object>} 记忆数据
         */
        async getMemoryById(id) {
            const url = `${BASE_URL}/api/memories/${encodeURIComponent(id)}`;
            return request(url);
        },

        /**
         * 搜索记忆（混合检索）
         * @param {Object} query - 搜索查询
         * @param {string} query.text - 搜索文本(必需)
         * @param {string} query.userId - 用户ID(必需)
         * @param {string} [query.agentId] - Agent ID(可选)
         * @param {number} [query.topK=10] - 返回结果数量
         * @param {number} [query.threshold=0.5] - 相似度阈值
         * @returns {Promise<Object>} 搜索结果
         */
        async searchMemories(query) {
            const url = `${BASE_URL}/api/search`;
            return request(url, {
                method: 'POST',
                body: JSON.stringify(query),
            });
        },

        /**
         * 向量语义搜索
         * @param {Object} query - 搜索查询（同searchMemories）
         * @returns {Promise<Object>} 搜索结果
         */
        async vectorSearch(query) {
            const url = `${BASE_URL}/api/search/vector`;
            return request(url, {
                method: 'POST',
                body: JSON.stringify(query),
            });
        },

        /**
         * 图遍历搜索
         * @param {Object} query - 搜索查询（同searchMemories）
         * @returns {Promise<Object>} 搜索结果
         */
        async graphSearch(query) {
            const url = `${BASE_URL}/api/search/graph`;
            return request(url, {
                method: 'POST',
                body: JSON.stringify(query),
            });
        },

        /**
         * 创建记忆（从对话文本提取）
         * @param {Object} data - 创建数据
         * @param {Array} data.messages - 消息列表 [{role, content}]
         * @param {string} data.userId - 用户ID
         * @param {string} [data.agentId] - Agent ID
         * @returns {Promise<Object>} 创建结果
         */
        async createMemory(data) {
            const url = `${BASE_URL}/api/memories`;
            return request(url, {
                method: 'POST',
                body: JSON.stringify(data),
            });
        },

        /**
         * 更新记忆
         * @param {string} id - 记忆ID
         * @param {Object} data - 更新数据
         * @param {string} [data.text] - 更新后的文本
         * @param {number} [data.importance] - 更新后的重要性
         * @returns {Promise<Object>} 更新结果
         */
        async updateMemory(id, data) {
            const url = `${BASE_URL}/api/memories/${encodeURIComponent(id)}`;
            return request(url, {
                method: 'PUT',
                body: JSON.stringify(data),
            });
        },

        /**
         * 删除记忆
         * @param {string} id - 记忆ID
         * @returns {Promise<Object>} 删除结果
         */
        async deleteMemory(id) {
            const url = `${BASE_URL}/api/memories/${encodeURIComponent(id)}`;
            return request(url, {
                method: 'DELETE',
            });
        },

        // ==================== 系统状态API ====================

        /**
         * 健康检查
         * @returns {Promise<Object>} 健康状态数据
         */
        async getHealth() {
            const url = `${BASE_URL}/health`;
            return request(url);
        },

        /**
         * 获取Prometheus指标（文本格式）
         * @returns {Promise<string>} Prometheus文本格式指标
         */
        async getMetrics() {
            const url = `http://localhost:${METRICS_PORT}/metrics`;
            try {
                const response = await fetch(url, {
                    signal: AbortSignal.timeout(REQUEST_TIMEOUT),
                });
                if (!response.ok) {
                    throw new ApiError(`Metrics endpoint returned ${response.status}`, response.status);
                }
                return await response.text();
            } catch (error) {
                if (error.name === 'TypeError') {
                    // 网络错误，可能是metrics端口未启动
                    console.warn('[API] Metrics端口可能未启动:', error.message);
                    return '';
                }
                throw error;
            }
        },

        /**
         * 解析Prometheus文本格式指标为对象
         * @param {string} text - Prometheus文本格式
         * @returns {Object} 解析后的指标对象 {metricName: {labels, value, timestamp}}
         */
        parsePrometheusMetrics(text) {
            if (!text) return {};
            const metrics = {};
            const lines = text.split('\n');

            for (const line of lines) {
                // 跳过空行和注释
                if (!line.trim() || line.startsWith('#')) continue;

                // 匹配格式: metric_name{label="value"} value timestamp
                const match = line.match(/^([a-zA-Z_:][a-zA-Z0-9_:]*)\{([^}]*)\}\s+([\d.e+-]+)\s*(.*)$/);
                if (match) {
                    const [, name, labelsStr, value, timestamp] = match;
                    const labels = {};
                    if (labelsStr) {
                        labelsStr.split(',').forEach(pair => {
                            const [k, v] = pair.split('=');
                            labels[k.trim()] = v.replace(/"/g, '').trim();
                        });
                    }
                    metrics[name] = { labels, value: parseFloat(value), timestamp: parseInt(timestamp) || null };
                    continue;
                }

                // 匹配无标签格式: metric_name value timestamp
                const matchSimple = line.match(/^([a-zA-Z_:][a-zA-Z0-9_:]*)\s+([\d.e+-]+)\s*(.*)$/);
                if (matchSimple) {
                    const [, name, value, timestamp] = matchSimple;
                    metrics[name] = { value: parseFloat(value), timestamp: parseInt(timestamp) || null };
                }
            }

            return metrics;
        },

        // ==================== 便捷方法 ====================

        /**
         * 快速测试API连通性
         * @returns {Promise<boolean>} 是否连通
         */
        async ping() {
            try {
                await this.getHealth();
                return true;
            } catch {
                return false;
            }
        },

        /**
         * 获取所有存储层状态
         * @returns {Promise<Object>} 各存储层状态
         */
        async getStoreStatuses() {
            const health = await this.getHealth();
            return health.stores || {};
        },

        /**
         * 获取系统运行时间
         * @returns {Promise<string>} 格式化的运行时间
         */
        async getUptime() {
            const health = await this.getHealth();
            return health.uptimeFormatted || 'unknown';
        },

        /**
         * 获取内存使用情况
         * @returns {Promise<Object>} 内存信息 {usedMB, maxMB}
         */
        async getMemoryUsage() {
            const health = await this.getHealth();
            return health.memory || { usedMB: 0, maxMB: 0 };
        },

        /**
         * 批量删除记忆
         * @param {string[]} ids - 记忆ID数组
         * @returns {Promise<Object[]>} 删除结果数组
         */
        async deleteMemories(ids) {
            const results = await Promise.allSettled(
                ids.map(id => this.deleteMemory(id))
            );
            return results.map((result, index) => ({
                id: ids[index],
                success: result.status === 'fulfilled',
                error: result.status === 'rejected' ? result.reason.message : null,
            }));
        },

        /**
         * 获取系统完整概览
         * @returns {Promise<Object>} 系统概览数据
         */
        async getSystemOverview() {
            const [health, memories] = await Promise.allSettled([
                this.getHealth(),
                this.getMemories({ limit: 1 }),
            ]);

            return {
                healthy: health.status === 'fulfilled',
                healthData: health.status === 'fulfilled' ? health.value : null,
                totalMemories: memories.status === 'fulfilled' 
                    ? (memories.value.total || 0) 
                    : 0,
                timestamp: new Date().toISOString(),
            };
        },
    };
})();

// 导出给全局使用
if (typeof window !== 'undefined') {
    window.API = API;
}
