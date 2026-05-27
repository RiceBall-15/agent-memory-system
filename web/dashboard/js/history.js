/**
 * 历史数据管理模块
 *
 * 使用localStorage存储Dashboard历史指标数据。
 * - 自动聚合：每小时保存一次聚合快照
 * - 自动清理：超过24小时的数据自动删除
 * - 压缩存储：只保留小时级聚合数据
 * - 导出功能：将历史数据导出为JSON
 *
 * 存储结构 (localStorage key: 'ams_history'):
 * {
 *   snapshots: [
 *     { ts: 1234567890, requests: 50, latency: 45.2, qps: 10, errors: 2,
 *       memoryUsed: 128, memoryMax: 512, heapUsed: 96, nonheapUsed: 32 }
 *   ]
 * }
 *
 * @module history
 */
const HistoryManager = (() => {
    'use strict';

    // ==================== 配置 ====================

    /** localStorage 存储键名 */
    const STORAGE_KEY = 'ams_history';

    /** 最大保留时间 (毫秒: 24小时) */
    const MAX_RETENTION_MS = 24 * 60 * 60 * 1000;

    /** 聚合间隔 (毫秒: 1小时) */
    const AGGREGATION_INTERVAL_MS = 60 * 60 * 1000;

    /** 当前小时的累积数据缓冲区 */
    let hourlyBuffer = {
        requests: [],
        latency: [],
        qps: [],
        errors: [],
        memoryUsed: [],
        heapUsed: [],
        nonheapUsed: [],
        count: 0,
    };

    /** 是否已初始化 */
    let initialized = false;

    /** 清理定时器 */
    let cleanupTimer = null;

    // ==================== 数据读写 ====================

    /**
     * 从localStorage读取历史数据
     * @returns {Object} 历史数据对象 {snapshots: [...]}
     */
    function load() {
        try {
            const raw = localStorage.getItem(STORAGE_KEY);
            if (!raw) return { snapshots: [] };
            const data = JSON.parse(raw);
            if (!Array.isArray(data.snapshots)) {
                return { snapshots: [] };
            }
            return data;
        } catch (e) {
            console.warn('[History] 读取历史数据失败:', e.message);
            return { snapshots: [] };
        }
    }

    /**
     * 将历史数据写入localStorage
     * @param {Object} data - 历史数据对象
     */
    function save(data) {
        try {
            localStorage.setItem(STORAGE_KEY, JSON.stringify(data));
        } catch (e) {
            console.warn('[History] 写入历史数据失败:', e.message);
        }
    }

    // ==================== 数据聚合 ====================

    /**
     * 向当前小时缓冲区添加一条数据点
     * @param {Object} metrics - 当前时刻的指标数据
     */
    function record(metrics) {
        if (!metrics) return;

        hourlyBuffer.count++;

        if (metrics.requests !== undefined) hourlyBuffer.requests.push(metrics.requests);
        if (metrics.latency !== undefined) hourlyBuffer.latency.push(metrics.latency);
        if (metrics.qps !== undefined) hourlyBuffer.qps.push(metrics.qps);
        if (metrics.errors !== undefined) hourlyBuffer.errors.push(metrics.errors);
        if (metrics.memoryUsed !== undefined) hourlyBuffer.memoryUsed.push(metrics.memoryUsed);
        if (metrics.heapUsed !== undefined) hourlyBuffer.heapUsed.push(metrics.heapUsed);
        if (metrics.nonheapUsed !== undefined) hourlyBuffer.nonheapUsed.push(metrics.nonheapUsed);
    }

    /**
     * 将缓冲区数据聚合为一条快照
     * @returns {Object|null} 聚合快照，如果缓冲区为空则返回null
     */
    function flushBuffer() {
        if (hourlyBuffer.count === 0) return null;

        const avg = (arr) => arr.length > 0 ? Math.round(arr.reduce((a, b) => a + b, 0) / arr.length * 10) / 10 : 0;
        const sum = (arr) => arr.reduce((a, b) => a + b, 0);
        const last = (arr) => arr.length > 0 ? arr[arr.length - 1] : 0;

        const snapshot = {
            ts: Date.now(),
            requests: sum(hourlyBuffer.requests),
            latency: avg(hourlyBuffer.latency),
            qps: avg(hourlyBuffer.qps),
            errors: sum(hourlyBuffer.errors),
            memoryUsed: last(hourlyBuffer.memoryUsed),
            memoryMax: undefined, // 由调用者补充
            heapUsed: last(hourlyBuffer.heapUsed),
            nonheapUsed: last(hourlyBuffer.nonheapUsed),
        };

        // 重置缓冲区
        hourlyBuffer = {
            requests: [], latency: [], qps: [], errors: [],
            memoryUsed: [], heapUsed: [], nonheapUsed: [],
            count: 0,
        };

        return snapshot;
    }

    /**
     * 保存一条快照到历史数据
     * @param {Object} snapshot - 聚合快照
     */
    function saveSnapshot(snapshot) {
        if (!snapshot) return;

        const data = load();
        data.snapshots.push(snapshot);

        // 清理过期数据
        const cutoff = Date.now() - MAX_RETENTION_MS;
        data.snapshots = data.snapshots.filter(s => s.ts > cutoff);

        save(data);
    }

    /**
     * 强制将缓冲区数据刷入历史（用于页面卸载）
     */
    function forceFlush() {
        const snapshot = flushBuffer();
        if (snapshot) saveSnapshot(snapshot);
    }

    // ==================== 数据查询 ====================

    /**
     * 获取所有历史快照
     * @returns {Object[]} 快照数组
     */
    function getSnapshots() {
        return load().snapshots;
    }

    /**
     * 获取指定时间范围内的快照
     * @param {number} startMs - 起始时间戳
     * @param {number} endMs - 结束时间戳
     * @returns {Object[]}
     */
    function getSnapshotsInRange(startMs, endMs) {
        return load().snapshots.filter(s => s.ts >= startMs && s.ts <= endMs);
    }

    /**
     * 获取最近N小时的快照
     * @param {number} hours - 小时数 (默认24)
     * @returns {Object[]}
     */
    function getRecentSnapshots(hours = 24) {
        const cutoff = Date.now() - hours * 60 * 60 * 1000;
        return load().snapshots.filter(s => s.ts > cutoff);
    }

    /**
     * 获取请求量趋势数据 (用于图表)
     * @param {number} hours - 最近几小时
     * @returns {Object} {labels: string[], lines: [{name, data, color}]}
     */
    function getRequestsTrend(hours = 24) {
        const snapshots = getRecentSnapshots(hours);
        return {
            labels: snapshots.map(s => new Date(s.ts).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })),
            lines: [{
                name: '请求量',
                data: snapshots.map(s => s.requests || 0),
                color: '#6366f1',
            }],
        };
    }

    /**
     * 获取延迟趋势数据
     * @param {number} hours
     * @returns {Object}
     */
    function getLatencyTrend(hours = 24) {
        const snapshots = getRecentSnapshots(hours);
        return {
            labels: snapshots.map(s => new Date(s.ts).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })),
            lines: [
                {
                    name: '平均延迟(ms)',
                    data: snapshots.map(s => s.latency || 0),
                    color: '#22c55e',
                },
                {
                    name: 'QPS',
                    data: snapshots.map(s => s.qps || 0),
                    color: '#3b82f6',
                },
            ],
        };
    }

    /**
     * 获取内存使用分布数据 (用于柱状图)
     * @returns {Object} {labels, values, colors}
     */
    function getMemoryDistribution() {
        const snapshots = getRecentSnapshots(1);
        const latest = snapshots.length > 0 ? snapshots[snapshots.length - 1] : {};

        return {
            labels: ['Heap已用', 'NonHeap已用', '空闲Heap'],
            values: [
                latest.heapUsed || 0,
                latest.nonheapUsed || 0,
                Math.max(0, (latest.memoryMax || 512) - (latest.heapUsed || 0)),
            ],
            colors: ['#6366f1', '#a855f7', '#334155'],
        };
    }

    // ==================== 数据导出 ====================

    /**
     * 将历史数据导出为JSON并触发下载
     */
    function exportAsJSON() {
        const data = load();
        const exportData = {
            exportedAt: new Date().toISOString(),
            totalSnapshots: data.snapshots.length,
            timeRange: data.snapshots.length > 0 ? {
                from: new Date(data.snapshots[0].ts).toISOString(),
                to: new Date(data.snapshots[data.snapshots.length - 1].ts).toISOString(),
            } : null,
            snapshots: data.snapshots,
        };

        const json = JSON.stringify(exportData, null, 2);
        const blob = new Blob([json], { type: 'application/json' });
        const url = URL.createObjectURL(blob);

        const a = document.createElement('a');
        a.href = url;
        a.download = `ams-history-${new Date().toISOString().slice(0, 19).replace(/:/g, '-')}.json`;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);

        console.log(`[History] 导出了 ${data.snapshots.length} 条历史记录`);
    }

    /**
     * 获取历史数据摘要信息
     * @returns {Object}
     */
    function getSummary() {
        const data = load();
        const snapshots = data.snapshots;

        if (snapshots.length === 0) {
            return {
                totalSnapshots: 0,
                oldestTime: null,
                newestTime: null,
                totalRequests: 0,
                avgLatency: 0,
                dataSize: localStorage.getItem(STORAGE_KEY)?.length || 0,
            };
        }

        const totalRequests = snapshots.reduce((sum, s) => sum + (s.requests || 0), 0);
        const avgLatency = snapshots.reduce((sum, s) => sum + (s.latency || 0), 0) / snapshots.length;

        return {
            totalSnapshots: snapshots.length,
            oldestTime: new Date(snapshots[0].ts).toLocaleString('zh-CN'),
            newestTime: new Date(snapshots[snapshots.length - 1].ts).toLocaleString('zh-CN'),
            totalRequests: Math.round(totalRequests),
            avgLatency: Math.round(avgLatency * 10) / 10,
            dataSize: (localStorage.getItem(STORAGE_KEY)?.length || 0),
        };
    }

    // ==================== 自动清理 ====================

    /**
     * 清理过期数据
     * @returns {number} 被删除的快照数
     */
    function cleanup() {
        const data = load();
        const before = data.snapshots.length;
        const cutoff = Date.now() - MAX_RETENTION_MS;
        data.snapshots = data.snapshots.filter(s => s.ts > cutoff);
        const removed = before - data.snapshots.length;

        if (removed > 0) {
            save(data);
            console.log(`[History] 清理了 ${removed} 条过期数据`);
        }

        return removed;
    }

    /**
     * 清空所有历史数据
     */
    function clearAll() {
        localStorage.removeItem(STORAGE_KEY);
        hourlyBuffer = {
            requests: [], latency: [], qps: [], errors: [],
            memoryUsed: [], heapUsed: [], nonheapUsed: [],
            count: 0,
        };
        console.log('[History] 已清空所有历史数据');
    }

    // ==================== 初始化 ====================

    function init() {
        if (initialized) return;
        initialized = true;

        console.log('[History] 初始化历史数据管理模块');

        // 启动自动清理 (每10分钟)
        cleanupTimer = setInterval(cleanup, 10 * 60 * 1000);

        // 首次清理
        cleanup();

        // 页面卸载时刷出缓冲区
        window.addEventListener('beforeunload', forceFlush);

        console.log('[History] 初始化完成，当前快照数:', load().snapshots.length);
    }

    function destroy() {
        if (cleanupTimer) {
            clearInterval(cleanupTimer);
            cleanupTimer = null;
        }
        forceFlush();
        initialized = false;
        console.log('[History] 已销毁');
    }

    // ==================== 公共API ====================

    return {
        init,
        destroy,
        record,
        forceFlush,
        flushBuffer,
        saveSnapshot,

        getSnapshots,
        getSnapshotsInRange,
        getRecentSnapshots,

        getRequestsTrend,
        getLatencyTrend,
        getMemoryDistribution,

        exportAsJSON,
        getSummary,
        cleanup,
        clearAll,
    };
})();

if (typeof window !== 'undefined') {
    window.HistoryManager = HistoryManager;
}
