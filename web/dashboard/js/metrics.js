/**
 * 指标可视化模块
 * 
 * 使用Canvas原生绘制实时图表，包括：
 * - 折线图（API请求量、延迟趋势）
 * - 柱状图（存储操作统计）
 * - 饼图（请求状态分布）
 * 
 * 自动每5秒刷新，保留最近60个数据点
 * 
 * @module metrics
 */

const Metrics = (() => {
    'use strict';

    // ==================== 配置 ====================

    /** 数据保留点数 */
    const MAX_POINTS = 60;

    /** 刷新间隔（毫秒） */
    const REFRESH_INTERVAL = 5000;

    /** 图表内边距 */
    const PADDING = { top: 30, right: 20, bottom: 40, left: 50 };

    // ==================== 数据存储 ====================

    /** 时间序列数据 */
    const timeSeriesData = {
        timestamps: [],
        requests: [],       // 每秒请求数
        latency: [],        // 平均延迟(ms)
        errors: [],         // 错误数
        qps: [],            // QPS
    };

    /** 柱状图数据（存储操作统计） */
    const storageData = {
        labels: ['向量写入', '图写入', '元数据写入', '向量查询', '图查询', '元数据查询'],
        values: [0, 0, 0, 0, 0, 0],
    };

    /** 饼图数据（请求状态分布） */
    const statusData = {
        labels: ['200 成功', '400 参数错误', '404 未找到', '500 服务错误', '其他'],
        values: [0, 0, 0, 0, 0],
        colors: ['#22c55e', '#f59e0b', '#3b82f6', '#ef4444', '#6b7280'],
    };

    /** 模拟指标生成器 */
    let metricsGenerator = null;

    /** 刷新定时器 */
    let refreshTimer = null;

    /** 是否已初始化 */
    let initialized = false;

    /** Canvas上下文缓存 */
    const canvasContexts = {};

    // ==================== Canvas绘图工具 ====================

    /**
     * 获取Canvas 2D上下文
     * @param {string} canvasId - Canvas元素ID
     * @returns {CanvasRenderingContext2D} 2D上下文
     */
    function getCanvasContext(canvasId) {
        if (canvasContexts[canvasId]) return canvasContexts[canvasId];
        
        const canvas = document.getElementById(canvasId);
        if (!canvas) return null;

        // 设置Canvas尺寸以匹配CSS显示大小
        const rect = canvas.getBoundingClientRect();
        const dpr = window.devicePixelRatio || 1;
        canvas.width = rect.width * dpr;
        canvas.height = rect.height * dpr;

        const ctx = canvas.getContext('2d');
        ctx.scale(dpr, dpr);
        
        canvasContexts[canvasId] = ctx;
        return ctx;
    }

    /**
     * 清除Canvas画布
     * @param {CanvasRenderingContext2D} ctx - 2D上下文
     * @param {number} width - 画布宽度
     * @param {number} height - 画布高度
     */
    function clearCanvas(ctx, width, height) {
        ctx.clearRect(0, 0, width, height);
    }

    /**
     * 绘制网格背景
     * @param {CanvasRenderingContext2D} ctx - 2D上下文
     * @param {number} width - 画布宽度
     * @param {number} height - 画布高度
     * @param {number} rows - 网格行数
     */
    function drawGrid(ctx, width, height, rows = 5) {
        ctx.strokeStyle = 'rgba(148, 163, 184, 0.1)';
        ctx.lineWidth = 1;

        const chartWidth = width - PADDING.left - PADDING.right;
        const chartHeight = height - PADDING.top - PADDING.bottom;
        const rowHeight = chartHeight / rows;

        for (let i = 0; i <= rows; i++) {
            const y = PADDING.top + i * rowHeight;
            ctx.beginPath();
            ctx.moveTo(PADDING.left, y);
            ctx.lineTo(width - PADDING.right, y);
            ctx.stroke();
        }
    }

    /**
     * 绘制Y轴标签
     * @param {CanvasRenderingContext2D} ctx - 2D上下文
     * @param {number} height - 画布高度
     * @param {number} maxVal - 最大值
     * @param {number} rows - 网格行数
     * @param {string} suffix - 后缀（如 ms, %）
     */
    function drawYAxisLabels(ctx, height, maxVal, rows = 5, suffix = '') {
        const chartHeight = height - PADDING.top - PADDING.bottom;
        const rowHeight = chartHeight / rows;

        ctx.fillStyle = '#64748b';
        ctx.font = '11px -apple-system, sans-serif';
        ctx.textAlign = 'right';
        ctx.textBaseline = 'middle';

        for (let i = 0; i <= rows; i++) {
            const y = PADDING.top + i * rowHeight;
            const value = maxVal - (maxVal / rows) * i;
            ctx.fillText(
                value >= 1000 ? (value / 1000).toFixed(1) + 'k' : Math.round(value) + suffix,
                PADDING.left - 8,
                y
            );
        }
    }

    /**
     * 绘制X轴时间标签
     * @param {CanvasRenderingContext2D} ctx - 2D上下文
     * @param {number} width - 画布宽度
     * @param {number} height - 画布高度
     * @param {number} count - 标签数量
     */
    function drawXAxisLabels(ctx, width, height, count) {
        const chartWidth = width - PADDING.left - PADDING.right;
        const step = Math.max(1, Math.floor(count / 6));

        ctx.fillStyle = '#64748b';
        ctx.font = '11px -apple-system, sans-serif';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'top';

        const now = new Date();
        for (let i = 0; i < count; i += step) {
            const x = PADDING.left + (chartWidth / (count - 1 || 1)) * i;
            const secondsAgo = (count - 1 - i) * 5;
            const time = new Date(now.getTime() - secondsAgo * 1000);
            const label = time.toLocaleTimeString('zh-CN', { 
                hour: '2-digit', 
                minute: '2-digit', 
                second: '2-digit' 
            });
            ctx.fillText(label, x, height - PADDING.bottom + 10);
        }
    }

    // ==================== 折线图绘制 ====================

    /**
     * 绘制折线图
     * @param {string} canvasId - Canvas元素ID
     * @param {number[]} data - 数据数组
     * @param {string} color - 线条颜色
     * @param {string} fillColor - 填充颜色
     * @param {number} maxOverride - 最大值覆盖（0则自动计算）
     * @param {string} suffix - Y轴后缀
     */
    function drawLineChart(canvasId, data, color, fillColor, maxOverride = 0, suffix = '') {
        const canvas = document.getElementById(canvasId);
        if (!canvas) return;

        const ctx = getCanvasContext(canvasId);
        if (!ctx) return;

        const rect = canvas.getBoundingClientRect();
        const width = rect.width;
        const height = rect.height;

        clearCanvas(ctx, width, height);
        drawGrid(ctx, width, height);

        if (data.length === 0) {
            // 空数据时显示提示
            ctx.fillStyle = '#64748b';
            ctx.font = '14px -apple-system, sans-serif';
            ctx.textAlign = 'center';
            ctx.textBaseline = 'middle';
            ctx.fillText('等待数据...', width / 2, height / 2);
            return;
        }

        const chartWidth = width - PADDING.left - PADDING.right;
        const chartHeight = height - PADDING.top - PADDING.bottom;
        const maxVal = maxOverride || Math.max(1, ...data) * 1.2;

        // 绘制Y轴标签
        drawYAxisLabels(ctx, height, maxVal, 5, suffix);

        // 绘制X轴标签
        drawXAxisLabels(ctx, width, height, data.length);

        // 计算数据点坐标
        const points = data.map((val, i) => ({
            x: PADDING.left + (chartWidth / (data.length - 1 || 1)) * i,
            y: PADDING.top + chartHeight - (val / maxVal) * chartHeight,
        }));

        // 绘制填充区域
        ctx.beginPath();
        ctx.moveTo(points[0].x, PADDING.top + chartHeight);
        for (const p of points) {
            ctx.lineTo(p.x, p.y);
        }
        ctx.lineTo(points[points.length - 1].x, PADDING.top + chartHeight);
        ctx.closePath();

        const gradient = ctx.createLinearGradient(0, PADDING.top, 0, PADDING.top + chartHeight);
        gradient.addColorStop(0, fillColor);
        gradient.addColorStop(1, 'transparent');
        ctx.fillStyle = gradient;
        ctx.fill();

        // 绘制折线
        ctx.beginPath();
        ctx.moveTo(points[0].x, points[0].y);
        for (let i = 1; i < points.length; i++) {
            ctx.lineTo(points[i].x, points[i].y);
        }
        ctx.strokeStyle = color;
        ctx.lineWidth = 2;
        ctx.lineJoin = 'round';
        ctx.lineCap = 'round';
        ctx.stroke();

        // 绘制最后一点的高亮圆点
        if (points.length > 0) {
            const last = points[points.length - 1];
            ctx.beginPath();
            ctx.arc(last.x, last.y, 4, 0, Math.PI * 2);
            ctx.fillStyle = color;
            ctx.fill();
            ctx.beginPath();
            ctx.arc(last.x, last.y, 6, 0, Math.PI * 2);
            ctx.strokeStyle = color;
            ctx.lineWidth = 1;
            ctx.globalAlpha = 0.3;
            ctx.stroke();
            ctx.globalAlpha = 1;
        }
    }

    // ==================== 柱状图绘制 ====================

    /**
     * 绘制柱状图
     * @param {string} canvasId - Canvas元素ID
     * @param {string[]} labels - 标签数组
     * @param {number[]} values - 数据数组
     */
    function drawBarChart(canvasId, labels, values) {
        const canvas = document.getElementById(canvasId);
        if (!canvas) return;

        const ctx = getCanvasContext(canvasId);
        if (!ctx) return;

        const rect = canvas.getBoundingClientRect();
        const width = rect.width;
        const height = rect.height;

        clearCanvas(ctx, width, height);
        drawGrid(ctx, width, height, 4);

        const chartWidth = width - PADDING.left - PADDING.right;
        const chartHeight = height - PADDING.top - PADDING.bottom;
        const maxVal = Math.max(1, ...values) * 1.2;

        const barCount = labels.length;
        const barGap = 12;
        const barWidth = (chartWidth - barGap * (barCount + 1)) / barCount;

        const colors = ['#6366f1', '#a855f7', '#3b82f6', '#22c55e', '#f59e0b', '#ef4444'];

        // 绘制Y轴标签
        drawYAxisLabels(ctx, height, maxVal, 4);

        // 绘制柱子和标签
        ctx.textAlign = 'center';
        ctx.textBaseline = 'top';

        for (let i = 0; i < barCount; i++) {
            const x = PADDING.left + barGap + i * (barWidth + barGap);
            const barHeight = (values[i] / maxVal) * chartHeight;
            const y = PADDING.top + chartHeight - barHeight;

            // 柱子渐变
            const gradient = ctx.createLinearGradient(x, y, x, PADDING.top + chartHeight);
            gradient.addColorStop(0, colors[i % colors.length]);
            gradient.addColorStop(1, colors[i % colors.length] + '60');

            // 绘制圆角柱子
            const radius = Math.min(4, barWidth / 2);
            ctx.beginPath();
            ctx.moveTo(x + radius, y);
            ctx.lineTo(x + barWidth - radius, y);
            ctx.quadraticCurveTo(x + barWidth, y, x + barWidth, y + radius);
            ctx.lineTo(x + barWidth, PADDING.top + chartHeight);
            ctx.lineTo(x, PADDING.top + chartHeight);
            ctx.lineTo(x, y + radius);
            ctx.quadraticCurveTo(x, y, x + radius, y);
            ctx.closePath();

            ctx.fillStyle = gradient;
            ctx.fill();

            // 数值标签
            ctx.fillStyle = '#e2e8f0';
            ctx.font = 'bold 11px -apple-system, sans-serif';
            ctx.textBaseline = 'bottom';
            ctx.fillText(values[i].toString(), x + barWidth / 2, y - 4);

            // X轴标签
            ctx.fillStyle = '#94a3b8';
            ctx.font = '10px -apple-system, sans-serif';
            ctx.textBaseline = 'top';
            // 截断过长标签
            const maxLabelLen = Math.floor(barWidth / 7);
            const label = labels[i].length > maxLabelLen 
                ? labels[i].substring(0, maxLabelLen) + '...' 
                : labels[i];
            ctx.fillText(label, x + barWidth / 2, PADDING.top + chartHeight + 8);
        }
    }

    // ==================== 饼图绘制 ====================

    /**
     * 绘制饼图（环形图）
     * @param {string} canvasId - Canvas元素ID
     * @param {string[]} labels - 标签数组
     * @param {number[]} values - 数据数组
     * @param {string[]} colors - 颜色数组
     */
    function drawPieChart(canvasId, labels, values, colors) {
        const canvas = document.getElementById(canvasId);
        if (!canvas) return;

        const ctx = getCanvasContext(canvasId);
        if (!ctx) return;

        const rect = canvas.getBoundingClientRect();
        const width = rect.width;
        const height = rect.height;

        clearCanvas(ctx, width, height);

        const total = values.reduce((sum, v) => sum + v, 0);

        if (total === 0) {
            ctx.fillStyle = '#64748b';
            ctx.font = '14px -apple-system, sans-serif';
            ctx.textAlign = 'center';
            ctx.textBaseline = 'middle';
            ctx.fillText('暂无数据', width / 2, height / 2);
            return;
        }

        const centerX = width * 0.35;
        const centerY = height / 2;
        const radius = Math.min(centerX - PADDING.left, centerY - PADDING.top) * 0.85;
        const innerRadius = radius * 0.55; // 环形图内半径

        let startAngle = -Math.PI / 2;

        // 绘制扇形
        for (let i = 0; i < values.length; i++) {
            if (values[i] === 0) continue;

            const sliceAngle = (values[i] / total) * Math.PI * 2;
            const endAngle = startAngle + sliceAngle;

            ctx.beginPath();
            ctx.arc(centerX, centerY, radius, startAngle, endAngle);
            ctx.arc(centerX, centerY, innerRadius, endAngle, startAngle, true);
            ctx.closePath();

            ctx.fillStyle = colors[i % colors.length];
            ctx.fill();

            // 扇形边框
            ctx.strokeStyle = 'rgba(15, 23, 42, 0.5)';
            ctx.lineWidth = 2;
            ctx.stroke();

            startAngle = endAngle;
        }

        // 中心文本
        ctx.fillStyle = '#e2e8f0';
        ctx.font = 'bold 20px -apple-system, sans-serif';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText(total.toString(), centerX, centerY - 8);
        ctx.fillStyle = '#64748b';
        ctx.font = '11px -apple-system, sans-serif';
        ctx.fillText('总请求', centerX, centerY + 12);

        // 图例
        const legendX = width * 0.65;
        let legendY = PADDING.top + 10;
        const legendSpacing = 24;

        ctx.textAlign = 'left';
        ctx.textBaseline = 'middle';

        for (let i = 0; i < labels.length; i++) {
            if (values[i] === 0) continue;

            // 色块
            ctx.fillStyle = colors[i % colors.length];
            ctx.beginPath();
            ctx.arc(legendX, legendY, 5, 0, Math.PI * 2);
            ctx.fill();

            // 标签
            ctx.fillStyle = '#e2e8f0';
            ctx.font = '12px -apple-system, sans-serif';
            ctx.fillText(labels[i], legendX + 12, legendY);

            // 百分比
            const pct = total > 0 ? ((values[i] / total) * 100).toFixed(1) : 0;
            ctx.fillStyle = '#94a3b8';
            ctx.font = '11px -apple-system, sans-serif';
            ctx.fillText(`${pct}%`, legendX + 12 + ctx.measureText(labels[i]).width + 8, legendY);

            legendY += legendSpacing;
        }
    }

    // ==================== 模拟数据生成 ====================

    /**
     * 生成模拟指标数据
     * 用于前端开发和演示
     */
    function generateMockMetrics() {
        const now = Date.now();
        const timestamp = new Date(now).toLocaleTimeString('zh-CN');

        // 模拟请求量波动（正弦 + 随机噪声）
        const baseRequests = 50 + Math.sin(now / 10000) * 20;
        const requests = Math.max(0, baseRequests + (Math.random() - 0.5) * 10);
        
        // 模拟延迟（基础延迟 + 波动）
        const baseLatency = 45 + Math.sin(now / 8000) * 15;
        const latency = Math.max(5, baseLatency + (Math.random() - 0.5) * 10);
        
        // 模拟错误数
        const errors = Math.floor(Math.random() * 3);
        
        // 计算QPS
        const qps = requests / 5; // 5秒采样间隔

        // 更新时间序列数据
        timeSeriesData.timestamps.push(timestamp);
        timeSeriesData.requests.push(Math.round(requests));
        timeSeriesData.latency.push(Math.round(latency * 10) / 10);
        timeSeriesData.errors.push(errors);
        timeSeriesData.qps.push(Math.round(qps * 10) / 10);

        // 保留最近MAX_POINTS个点
        Object.keys(timeSeriesData).forEach(key => {
            if (timeSeriesData[key].length > MAX_POINTS) {
                timeSeriesData[key].shift();
            }
        });

        // 更新存储操作数据（缓慢增长）
        storageData.values = storageData.values.map(v => 
            v + Math.floor(Math.random() * 3)
        );

        // 更新状态分布数据
        statusData.values[0] += Math.floor(Math.random() * 10) + 5; // 200
        statusData.values[1] += Math.random() > 0.8 ? 1 : 0;        // 400
        statusData.values[2] += Math.random() > 0.9 ? 1 : 0;        // 404
        statusData.values[3] += Math.random() > 0.95 ? 1 : 0;       // 500

        // 更新UI显示
        updateMetricCards();
    }

    /**
     * 更新指标卡片显示
     */
    function updateMetricCards() {
        const totalRequests = timeSeriesData.requests.reduce((a, b) => a + b, 0);
        const avgLatency = timeSeriesData.latency.length > 0
            ? timeSeriesData.latency.reduce((a, b) => a + b, 0) / timeSeriesData.latency.length
            : 0;
        const totalErrors = timeSeriesData.errors.reduce((a, b) => a + b, 0);
        const errorRate = totalRequests > 0 ? (totalErrors / totalRequests * 100) : 0;
        const currentQps = timeSeriesData.qps.length > 0 
            ? timeSeriesData.qps[timeSeriesData.qps.length - 1] 
            : 0;

        // 更新DOM
        setTextIfExists('metric-total-requests', totalRequests.toLocaleString());
        setTextIfExists('metric-avg-latency', avgLatency.toFixed(1) + ' ms');
        setTextIfExists('metric-error-rate', errorRate.toFixed(2) + '%');
        setTextIfExists('metric-qps', currentQps.toFixed(1));

        // 更新趋势指示
        if (timeSeriesData.requests.length >= 2) {
            const last = timeSeriesData.requests[timeSeriesData.requests.length - 1];
            const prev = timeSeriesData.requests[timeSeriesData.requests.length - 2];
            updateTrend('metric-total-requests-trend', last, prev);
        }
    }

    /**
     * 设置元素文本（安全）
     * @param {string} id - 元素ID
     * @param {string} text - 文本内容
     */
    function setTextIfExists(id, text) {
        const el = document.getElementById(id);
        if (el) el.textContent = text;
    }

    /**
     * 更新趋势指示器
     * @param {string} id - 趋势元素ID
     * @param {number} current - 当前值
     * @param {number} previous - 上一个值
     */
    function updateTrend(id, current, previous) {
        const el = document.getElementById(id);
        if (!el) return;

        if (current > previous) {
            el.className = 'metric-trend up';
            el.textContent = '↑ ' + ((current - previous) / (previous || 1) * 100).toFixed(1) + '%';
        } else if (current < previous) {
            el.className = 'metric-trend down';
            el.textContent = '↓ ' + ((previous - current) / (previous || 1) * 100).toFixed(1) + '%';
        } else {
            el.className = 'metric-trend flat';
            el.textContent = '— 0%';
        }
    }

    // ==================== 公共API ====================

    return {
        /**
         * 初始化指标模块
         * 开始定时刷新和模拟数据生成
         */
        init() {
            if (initialized) return;
            initialized = true;

            console.log('[Metrics] 初始化指标可视化模块');

            // 启动模拟数据生成（当后端不可用时）
            metricsGenerator = setInterval(() => {
                generateMockMetrics();
                this.drawAllCharts();
            }, REFRESH_INTERVAL);

            // 首次渲染
            setTimeout(() => {
                generateMockMetrics();
                this.drawAllCharts();
            }, 500);
        },

        /**
         * 销毁指标模块，停止定时器
         */
        destroy() {
            if (metricsGenerator) {
                clearInterval(metricsGenerator);
                metricsGenerator = null;
            }
            if (refreshTimer) {
                clearInterval(refreshTimer);
                refreshTimer = null;
            }
            initialized = false;
            console.log('[Metrics] 指标模块已销毁');
        },

        /**
         * 绘制所有图表
         */
        drawAllCharts() {
            this.drawLineChart();
            this.drawLatencyChart();
            this.drawStorageChart();
            this.drawStatusChart();
        },

        /**
         * 绘制请求量折线图
         */
        drawLineChart() {
            drawLineChart(
                'chart-requests',
                timeSeriesData.requests,
                '#6366f1',
                'rgba(99, 102, 241, 0.2)'
            );
        },

        /**
         * 绘制延迟折线图
         */
        drawLatencyChart() {
            drawLineChart(
                'chart-latency',
                timeSeriesData.latency,
                '#22c55e',
                'rgba(34, 197, 94, 0.2)',
                0,
                ' ms'
            );
        },

        /**
         * 绘制存储操作柱状图
         */
        drawStorageChart() {
            drawBarChart(
                'chart-storage',
                storageData.labels,
                storageData.values
            );
        },

        /**
         * 绘制请求状态饼图
         */
        drawStatusChart() {
            drawPieChart(
                'chart-status',
                statusData.labels,
                statusData.values,
                statusData.colors
            );
        },

        /**
         * 更新来自后端的真实数据
         * @param {Object} data - 指标数据
         */
        updateFromBackend(data) {
            if (data.requests !== undefined) timeSeriesData.requests.push(data.requests);
            if (data.latency !== undefined) timeSeriesData.latency.push(data.latency);
            if (data.errors !== undefined) timeSeriesData.errors.push(data.errors);
            if (data.qps !== undefined) timeSeriesData.qps.push(data.qps);
            
            if (data.storage) {
                data.storage.forEach((val, i) => {
                    if (i < storageData.values.length) storageData.values[i] = val;
                });
            }

            if (data.status) {
                data.status.forEach((val, i) => {
                    if (i < statusData.values.length) statusData.values[i] = val;
                });
            }

            // 限制数据长度
            Object.keys(timeSeriesData).forEach(key => {
                while (timeSeriesData[key].length > MAX_POINTS) {
                    timeSeriesData[key].shift();
                }
            });

            updateMetricCards();
            this.drawAllCharts();
        },

        /**
         * 重置所有数据
         */
        resetData() {
            Object.keys(timeSeriesData).forEach(key => {
                timeSeriesData[key] = [];
            });
            storageData.values = [0, 0, 0, 0, 0, 0];
            statusData.values = [0, 0, 0, 0, 0];
            updateMetricCards();
            this.drawAllCharts();
        },

        /**
         * 获取当前数据快照
         * @returns {Object} 当前所有数据
         */
        getDataSnapshot() {
            return {
                timeSeries: { ...timeSeriesData },
                storage: { ...storageData },
                status: { ...statusData },
            };
        },

        /**
         * 处理窗口大小变化，重绘图表
         */
        handleResize() {
            // 清除Canvas上下文缓存，强制重新创建
            Object.keys(canvasContexts).forEach(key => {
                delete canvasContexts[key];
            });
            this.drawAllCharts();
        },
    };
})();

// 导出给全局使用
if (typeof window !== 'undefined') {
    window.Metrics = Metrics;
}
