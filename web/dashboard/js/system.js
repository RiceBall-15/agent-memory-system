/**
 * 系统状态监控模块
 * 
 * 功能:
 * - 健康状态卡片: 系统状态、各存储层状态
 * - 存储层详情: VectorStore/GraphStore/MetadataStore连接状态
 * - JVM内存使用: 堆内存、非堆内存可视化
 * - 线程信息: 活跃线程数、守护线程数
 * - GC信息: GC次数、耗时
 * - 熔断器状态: 各存储的熔断器状态指示
 * - 队列深度: 写入队列实时深度
 * - 运行时间: 系统启动时间和运行时长
 * 
 * @module system
 */

const SystemModule = (() => {
    // ===== API 配置 =====
    const API_BASE = '/api/v1';
    
    // ===== 状态 =====
    let refreshTimer = null;
    let refreshInterval = 5000;  // 5秒刷新
    let gauges = {};             // 仪表盘图表实例
    let lineCharts = {};         // 折线图图表实例
    let historyData = {          // 历史数据
        memory: [],
        threads: [],
        queue: [],
    };
    const MAX_HISTORY = 60;      // 最多保留60个数据点

    /**
     * 初始化系统状态模块
     */
    function init() {
        console.log('[System] 初始化系统状态模块');
        
        // 初始化仪表盘图表
        initGauges();
        
        // 初始化折线图
        initLineCharts();
        
        // 加载初始数据
        refresh();
        
        // 启动定时刷新
        startAutoRefresh();
    }

    /**
     * 初始化仪表盘图表
     */
    function initGauges() {
        // 堆内存使用率仪表盘
        const heapCanvas = document.getElementById('gauge-heap');
        if (heapCanvas) {
            gauges.heap = new GaugeChart('gauge-heap', {
                min: 0,
                max: 100,
                unit: '%',
                label: 'Heap Memory',
                thresholds: [
                    { value: 50, color: '#2ecc71', label: 'Good' },
                    { value: 75, color: '#f39c12', label: 'Warning' },
                    { value: 90, color: '#e74c3c', label: 'Critical' },
                ],
            });
            gauges.heap.setValue(0);
        }
        
        // 非堆内存仪表盘
        const nonHeapCanvas = document.getElementById('gauge-nonheap');
        if (nonHeapCanvas) {
            gauges.nonHeap = new GaugeChart('gauge-nonheap', {
                min: 0,
                max: 256,
                unit: 'MB',
                label: 'Non-Heap Memory',
                thresholds: [
                    { value: 128, color: '#2ecc71' },
                    { value: 192, color: '#f39c12' },
                    { value: 224, color: '#e74c3c' },
                ],
            });
            gauges.nonHeap.setValue(0);
        }
        
        // 线程数仪表盘
        const threadCanvas = document.getElementById('gauge-threads');
        if (threadCanvas) {
            gauges.threads = new GaugeChart('gauge-threads', {
                min: 0,
                max: 200,
                unit: 'threads',
                label: 'Active Threads',
                thresholds: [
                    { value: 100, color: '#2ecc71' },
                    { value: 150, color: '#f39c12' },
                    { value: 180, color: '#e74c3c' },
                ],
            });
            gauges.threads.setValue(0);
        }
    }

    /**
     * 初始化折线图
     */
    function initLineCharts() {
        // 写入队列深度趋势
        const queueCanvas = document.getElementById('chart-queue');
        if (queueCanvas) {
            lineCharts.queue = new LineChart('chart-queue', {
                showLegend: false,
                showDots: false,
                maxPoints: MAX_HISTORY,
                yMin: 0,
            });
        }
        
        // GC耗时趋势
        const gcCanvas = document.getElementById('chart-gc');
        if (gcCanvas) {
            lineCharts.gc = new LineChart('chart-gc', {
                showLegend: false,
                showDots: false,
                maxPoints: MAX_HISTORY,
                yMin: 0,
            });
        }
    }

    /**
     * 刷新系统状态数据
     */
    async function refresh() {
        try {
            const [healthData, metricsData] = await Promise.all([
                fetchHealth(),
                fetchMetrics(),
            ]);
            
            if (healthData) updateHealthUI(healthData);
            if (metricsData) updateMetricsUI(metricsData);
            
        } catch (err) {
            console.error('[System] 刷新数据失败:', err);
            showError('系统状态刷新失败');
        }
    }

    /**
     * 获取健康状态
     * @returns {Promise<Object>} 健康数据
     */
    async function fetchHealth() {
        try {
            const response = await fetch(`${API_BASE}/health`);
            if (!response.ok) throw new Error(`HTTP ${response.status}`);
            const data = await response.json();
            return data;
        } catch (err) {
            console.error('[System] 获取健康状态失败:', err);
            return null;
        }
    }

    /**
     * 获取指标数据
     * @returns {Promise<Object>} 指标数据
     */
    async function fetchMetrics() {
        try {
            const response = await fetch(`${API_BASE}/metrics`);
            if (!response.ok) throw new Error(`HTTP ${response.status}`);
            const data = await response.json();
            return data;
        } catch (err) {
            console.error('[System] 获取指标数据失败:', err);
            return null;
        }
    }

    /**
     * 更新健康状态UI
     * @param {Object} data - 健康数据
     */
    function updateHealthUI(data) {
        // 整体状态指示
        const statusCard = document.getElementById('system-status');
        if (statusCard) {
            const isUp = data.status === 'UP' || data.status === 'ok';
            statusCard.className = `status-card ${isUp ? 'status-ok' : 'status-error'}`;
            statusCard.innerHTML = `
                <div class="status-icon">${isUp ? '🟢' : '🔴'}</div>
                <div class="status-info">
                    <div class="status-label">系统状态</div>
                    <div class="status-value">${data.status || 'UNKNOWN'}</div>
                </div>
            `;
        }
        
        // 各存储层状态
        const services = data.services || data.components || {};
        updateStorageStatus('vector-store', services.vectorStore || services.vector_store);
        updateStorageStatus('graph-store', services.graphStore || services.graph_store);
        updateStorageStatus('metadata-store', services.metadataStore || services.metadata_store);
        
        // 运行时间
        if (data.uptime || data.startTime) {
            updateUptime(data);
        }
        
        // 熔断器状态
        if (data.circuitBreakers || data.circuit_breakers) {
            updateCircuitBreakers(data.circuitBreakers || data.circuit_breakers);
        }
    }

    /**
     * 更新存储层状态显示
     * @param {string} elementId - 元素ID
     * @param {string|Object} status - 状态
     */
    function updateStorageStatus(elementId, status) {
        const el = document.getElementById(elementId);
        if (!el) return;
        
        let statusText, statusClass;
        
        if (typeof status === 'string') {
            statusText = status;
            statusClass = status === 'OK' ? 'ok' : status === 'DEGRADED' ? 'warn' : 'error';
        } else if (typeof status === 'object' && status !== null) {
            statusText = status.status || 'UNKNOWN';
            statusClass = status.status === 'OK' ? 'ok' : status.status === 'DEGRADED' ? 'warn' : 'error';
        } else {
            statusText = 'UNKNOWN';
            statusClass = 'error';
        }
        
        const iconMap = { ok: '✅', warn: '⚠️', error: '❌' };
        
        el.className = `storage-status storage-${statusClass}`;
        el.innerHTML = `
            <span class="storage-icon">${iconMap[statusClass]}</span>
            <span class="storage-text">${statusText}</span>
        `;
    }

    /**
     * 更新熔断器状态
     * @param {Object} breakers - 熔断器数据
     */
    function updateCircuitBreakers(breakers) {
        const container = document.getElementById('circuit-breakers');
        if (!container) return;
        
        const stateIcons = {
            CLOSED: { icon: '🟢', text: '正常', class: 'cb-closed' },
            OPEN: { icon: '🔴', text: '断开', class: 'cb-open' },
            HALF_OPEN: { icon: '🟡', text: '半开', class: 'cb-half-open' },
        };
        
        let html = '';
        Object.entries(breakers).forEach(([name, state]) => {
            const info = stateIcons[state] || stateIcons.CLOSED;
            html += `
                <div class="circuit-breaker ${info.class}">
                    <span class="cb-icon">${info.icon}</span>
                    <span class="cb-name">${name}</span>
                    <span class="cb-state">${info.text}</span>
                </div>
            `;
        });
        
        container.innerHTML = html;
    }

    /**
     * 更新运行时间显示
     * @param {Object} data - 包含启动时间的数据
     */
    function updateUptime(data) {
        const uptimeEl = document.getElementById('system-uptime');
        if (!uptimeEl) return;
        
        let startTime, uptime;
        
        if (data.startTime) {
            startTime = new Date(data.startTime);
            uptime = Date.now() - startTime.getTime();
        } else if (data.uptime) {
            uptime = data.uptime; // 毫秒
        }
        
        if (!uptime) return;
        
        const days = Math.floor(uptime / (24 * 60 * 60 * 1000));
        const hours = Math.floor((uptime % (24 * 60 * 60 * 1000)) / (60 * 60 * 1000));
        const minutes = Math.floor((uptime % (60 * 60 * 1000)) / (60 * 1000));
        
        uptimeEl.innerHTML = `
            <div class="uptime-label">运行时间</div>
            <div class="uptime-value">
                ${days > 0 ? `${days}天 ` : ''}${hours}小时 ${minutes}分钟
            </div>
            ${startTime ? `<div class="uptime-start">启动: ${startTime.toLocaleString('zh-CN')}</div>` : ''}
        `;
    }

    /**
     * 更新指标数据UI
     * @param {Object} data - 指标数据
     */
    function updateMetricsUI(data) {
        // JVM内存
        if (data.jvm || data.memory) {
            const mem = data.jvm || data.memory;
            updateMemoryGauges(mem);
        }
        
        // 线程信息
        if (data.threads) {
            updateThreadGauges(data.threads);
        }
        
        // GC信息
        if (data.gc) {
            updateGCInfo(data.gc);
        }
        
        // 写入队列
        if (data.writeQueue || data.write_queue) {
            updateQueueChart(data.writeQueue || data.write_queue);
        }
        
        // 更新历史图表
        updateHistoryCharts();
    }

    /**
     * 更新内存仪表盘
     * @param {Object} mem - 内存数据
     */
    function updateMemoryGauges(mem) {
        // 堆内存
        if (gauges.heap && mem.heapUsed !== undefined && mem.heapMax !== undefined) {
            const heapUsage = (mem.heapUsed / mem.heapMax) * 100;
            gauges.heap.setValue(heapUsage);
            
            // 更新详细信息
            updateDetailInfo('heap-detail', {
                '已用': formatBytes(mem.heapUsed),
                '最大': formatBytes(mem.heapMax),
                '使用率': heapUsage.toFixed(1) + '%',
            });
        }
        
        // 非堆内存
        if (gauges.nonHeap && mem.nonHeapUsed !== undefined) {
            const nonHeapMB = mem.nonHeapUsed / (1024 * 1024);
            gauges.nonHeap.setValue(nonHeapMB);
            
            updateDetailInfo('nonheap-detail', {
                '已用': formatBytes(mem.nonHeapUsed),
                '提交': formatBytes(mem.nonHeapCommitted || 0),
            });
        }
    }

    /**
     * 更新线程仪表盘
     * @param {Object} threads - 线程数据
     */
    function updateThreadGauges(threads) {
        if (gauges.threads && threads.active !== undefined) {
            gauges.threads.setValue(threads.active);
        }
        
        updateDetailInfo('thread-detail', {
            '活跃': threads.active || 0,
            '守护': threads.daemon || 0,
            '峰值': threads.peak || 0,
        });
    }

    /**
     * 更新GC信息
     * @param {Object} gc - GC数据
     */
    function updateGCInfo(gc) {
        const gcInfo = document.getElementById('gc-info');
        if (!gcInfo) return;
        
        let html = '<div class="gc-summary">';
        
        if (gc.collections) {
            gc.collections.forEach((collection, i) => {
                html += `
                    <div class="gc-item">
                        <div class="gc-name">${collection.name || `GC ${i + 1}`}</div>
                        <div class="gc-count">次数: ${collection.count || 0}</div>
                        <div class="gc-time">耗时: ${collection.time || 0}ms</div>
                    </div>
                `;
            });
        }
        
        // 总GC统计
        if (gc.totalCount !== undefined) {
            html += `
                <div class="gc-item gc-total">
                    <div class="gc-name">总GC</div>
                    <div class="gc-count">次数: ${gc.totalCount}</div>
                    <div class="gc-time">总耗时: ${gc.totalTime || 0}ms</div>
                </div>
            `;
        }
        
        html += '</div>';
        gcInfo.innerHTML = html;
        
        // 更新GC趋势图
        if (lineCharts.gc && gc.totalTime !== undefined) {
            const now = new Date().toLocaleTimeString('zh-CN', { 
                hour: '2-digit', 
                minute: '2-digit', 
                second: '2-digit' 
            });
            
            historyData.threads.push({ x: now, y: gc.totalTime });
            if (historyData.threads.length > MAX_HISTORY) {
                historyData.threads.shift();
            }
            
            lineCharts.gc.setData([{
                name: 'GC Time (ms)',
                data: [...historyData.threads],
            }]);
        }
    }

    /**
     * 更新队列深度图
     * @param {Object} queue - 队列数据
     */
    function updateQueueChart(queue) {
        const queueInfo = document.getElementById('queue-info');
        if (queueInfo) {
            const depth = queue.depth || queue.size || 0;
            const capacity = queue.capacity || 10000;
            const usagePercent = (depth / capacity) * 100;
            
            queueInfo.innerHTML = `
                <div class="queue-depth-value">${formatNumber(depth)}</div>
                <div class="queue-depth-bar">
                    <div class="queue-depth-fill" style="width:${Math.min(usagePercent, 100)}%;background:${
                        usagePercent > 80 ? '#e74c3c' : usagePercent > 50 ? '#f39c12' : '#2ecc71'
                    }"></div>
                </div>
                <div class="queue-capacity">容量: ${formatNumber(capacity)}</div>
            `;
        }
        
        // 更新队列深度趋势图
        if (lineCharts.queue) {
            const now = new Date().toLocaleTimeString('zh-CN', { 
                hour: '2-digit', 
                minute: '2-digit', 
                second: '2-digit' 
            });
            const depth = queue.depth || queue.size || 0;
            
            historyData.queue.push({ x: now, y: depth });
            if (historyData.queue.length > MAX_HISTORY) {
                historyData.queue.shift();
            }
            
            lineCharts.queue.setData([{
                name: 'Queue Depth',
                data: [...historyData.queue],
            }]);
        }
    }

    /**
     * 更新历史折线图
     */
    function updateHistoryCharts() {
        // 此处可扩展更多历史图表
    }

    /**
     * 更新详情信息面板
     * @param {string} elementId - 元素ID
     * @param {Object} details - 详情数据
     */
    function updateDetailInfo(elementId, details) {
        const el = document.getElementById(elementId);
        if (!el) return;
        
        let html = '<div class="detail-grid">';
        Object.entries(details).forEach(([key, value]) => {
            html += `
                <div class="detail-item">
                    <span class="detail-key">${key}</span>
                    <span class="detail-value">${value}</span>
                </div>
            `;
        });
        html += '</div>';
        el.innerHTML = html;
    }

    /**
     * 格式化字节数
     * @param {number} bytes - 字节数
     * @returns {string} 格式化后的字符串
     */
    function formatBytes(bytes) {
        if (bytes === 0) return '0 B';
        const k = 1024;
        const sizes = ['B', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
    }

    /**
     * 格式化数字 (添加千分位)
     * @param {number} num - 数字
     * @returns {string} 格式化后的字符串
     */
    function formatNumber(num) {
        return num.toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',');
    }

    /**
     * 显示错误提示
     * @param {string} message - 错误消息
     */
    function showError(message) {
        const toast = document.createElement('div');
        toast.className = 'toast toast-error';
        toast.textContent = message;
        document.body.appendChild(toast);
        
        setTimeout(() => {
            toast.classList.add('toast-fade-out');
            setTimeout(() => toast.remove(), 300);
        }, 3000);
    }

    /**
     * 启动定时刷新
     */
    function startAutoRefresh() {
        if (refreshTimer) clearInterval(refreshTimer);
        refreshTimer = setInterval(refresh, refreshInterval);
        console.log(`[System] 自动刷新已启动，间隔: ${refreshInterval}ms`);
    }

    /**
     * 停止定时刷新
     */
    function stopAutoRefresh() {
        if (refreshTimer) {
            clearInterval(refreshTimer);
            refreshTimer = null;
        }
    }

    /**
     * 设置刷新间隔
     * @param {number} interval - 刷新间隔 (ms)
     */
    function setRefreshInterval(interval) {
        refreshInterval = Math.max(1000, interval);
        stopAutoRefresh();
        startAutoRefresh();
    }

    /**
     * 手动刷新
     */
    function manualRefresh() {
        refresh();
    }

    /**
     * 销毁模块
     */
    function destroy() {
        stopAutoRefresh();
        Object.values(gauges).forEach(g => g?.destroy());
        Object.values(lineCharts).forEach(c => c?.destroy());
    }

    // ===== 公开 API =====
    return {
        init,
        refresh,
        manualRefresh,
        setRefreshInterval,
        stopAutoRefresh,
        destroy,
        fetchHealth,
        fetchMetrics,
    };
})();

// 导出到全局
window.SystemModule = SystemModule;
