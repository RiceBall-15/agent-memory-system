/**
 * 分析数据展示模块
 * 
 * 功能:
 * - 调用API获取分析总览、时间线、分类统计、标签云、质量分析
 * - 自动每30秒刷新数据
 * - 绘制时间线图表（Canvas原生绘制）
 * - 标签云展示（CSS动态字号）
 * - 分类统计柱状图
 * 
 * @module analytics
 */

const AnalyticsModule = (() => {
    'use strict';

    // ===== API 配置 =====
    const API_BASE = '/api/v1';

    // ===== 状态 =====
    let refreshTimer = null;
    let refreshInterval = 30000;  // 30秒刷新
    let lastData = null;

    /**
     * 初始化分析模块
     */
    function init() {
        console.log('[Analytics] 初始化分析模块');
        loadAll();
        startAutoRefresh();
    }

    /**
     * 启动自动刷新
     */
    function startAutoRefresh() {
        if (refreshTimer) clearInterval(refreshTimer);
        refreshTimer = setInterval(loadAll, refreshInterval);
    }

    /**
     * 停止自动刷新
     */
    function stopAutoRefresh() {
        if (refreshTimer) {
            clearInterval(refreshTimer);
            refreshTimer = null;
        }
    }

    /**
     * 加载所有分析数据
     */
    async function loadAll() {
        try {
            const [overview, timeline, categories, tags, quality] = await Promise.allSettled([
                fetchData(`${API_BASE}/analytics/overview`),
                fetchData(`${API_BASE}/analytics/timeline`),
                fetchData(`${API_BASE}/analytics/categories`),
                fetchData(`${API_BASE}/analytics/tags`),
                fetchData(`${API_BASE}/analytics/quality`),
            ]);

            lastData = {
                overview: overview.status === 'fulfilled' ? overview.value : null,
                timeline: timeline.status === 'fulfilled' ? timeline.value : null,
                categories: categories.status === 'fulfilled' ? categories.value : null,
                tags: tags.status === 'fulfilled' ? tags.value : null,
                quality: quality.status === 'fulfilled' ? quality.value : null,
            };

            renderOverview(lastData.overview);
            renderTimeline(lastData.timeline);
            renderCategories(lastData.categories);
            renderTags(lastData.tags);
            renderQuality(lastData.quality);
        } catch (err) {
            console.error('[Analytics] 加载分析数据失败:', err);
        }
    }

    /**
     * 获取API数据
     * @param {string} url - 请求URL
     * @returns {Promise<Object|null>} 响应数据
     */
    async function fetchData(url) {
        try {
            const response = await fetch(url, {
                headers: { 'Content-Type': 'application/json' },
                signal: AbortSignal.timeout(10000),
            });
            if (!response.ok) return null;
            return await response.json();
        } catch (err) {
            console.warn(`[Analytics] 请求失败: ${url}`, err.message);
            return null;
        }
    }

    // ===== 渲染: 总览 =====
    function renderOverview(data) {
        const container = document.getElementById('analytics-overview-cards');
        if (!container) return;

        if (!data) {
            container.innerHTML = renderPlaceholderCards();
            return;
        }

        const cards = [
            { icon: '📊', label: '总记忆数', value: formatNumber(data.totalMemories || data.total || 0), color: 'var(--accent-blue)' },
            { icon: '📅', label: '今日新增', value: formatNumber(data.todayNew || data.newToday || 0), color: 'var(--accent-green)' },
            { icon: '🔍', label: '搜索次数', value: formatNumber(data.totalSearches || data.searchCount || 0), color: 'var(--accent-purple)' },
            { icon: '⭐', label: '平均质量分', value: (data.avgQuality || data.averageQuality || 0).toFixed(2), color: 'var(--accent-orange)' },
        ];

        container.innerHTML = cards.map(c => `
            <div class="analytics-stat-card">
                <div class="analytics-stat-icon" style="background:${c.color}20;color:${c.color}">${c.icon}</div>
                <div class="analytics-stat-info">
                    <div class="analytics-stat-label">${c.label}</div>
                    <div class="analytics-stat-value">${c.value}</div>
                </div>
            </div>
        `).join('');
    }

    // ===== 渲染: 时间线 =====
    function renderTimeline(data) {
        const container = document.getElementById('analytics-timeline-chart');
        if (!container) return;

        if (!data || !data.dataPoints || data.dataPoints.length === 0) {
            container.innerHTML = '<div class="analytics-empty">暂无时间线数据</div>';
            return;
        }

        // 使用Canvas绘制
        const canvas = container.querySelector('canvas') || document.createElement('canvas');
        if (!canvas.parentNode) {
            container.innerHTML = '';
            container.appendChild(canvas);
        }
        canvas.width = container.clientWidth * (window.devicePixelRatio || 1);
        canvas.height = 200 * (window.devicePixelRatio || 1);
        canvas.style.width = '100%';
        canvas.style.height = '200px';

        const ctx = canvas.getContext('2d');
        ctx.scale(window.devicePixelRatio || 1, window.devicePixelRatio || 1);

        const width = container.clientWidth;
        const height = 200;
        const padding = { top: 20, right: 20, bottom: 30, left: 50 };
        const chartW = width - padding.left - padding.right;
        const chartH = height - padding.top - padding.bottom;

        ctx.clearRect(0, 0, width, height);

        const points = data.dataPoints;
        const maxVal = Math.max(1, ...points.map(p => p.value || p.count || 0)) * 1.2;

        // 网格
        ctx.strokeStyle = 'rgba(148,163,184,0.1)';
        ctx.lineWidth = 1;
        for (let i = 0; i <= 4; i++) {
            const y = padding.top + (chartH / 4) * i;
            ctx.beginPath(); ctx.moveTo(padding.left, y); ctx.lineTo(width - padding.right, y); ctx.stroke();
        }

        // Y轴标签
        ctx.fillStyle = '#64748b';
        ctx.font = '11px -apple-system, sans-serif';
        ctx.textAlign = 'right';
        for (let i = 0; i <= 4; i++) {
            const y = padding.top + (chartH / 4) * i;
            const val = maxVal - (maxVal / 4) * i;
            ctx.fillText(Math.round(val).toString(), padding.left - 8, y + 4);
        }

        // 数据线
        if (points.length > 1) {
            const xStep = chartW / (points.length - 1);

            // 填充区域
            ctx.beginPath();
            ctx.moveTo(padding.left, padding.top + chartH);
            points.forEach((p, i) => {
                const x = padding.left + i * xStep;
                const y = padding.top + chartH - ((p.value || p.count || 0) / maxVal) * chartH;
                ctx.lineTo(x, y);
            });
            ctx.lineTo(padding.left + (points.length - 1) * xStep, padding.top + chartH);
            ctx.closePath();
            const grad = ctx.createLinearGradient(0, padding.top, 0, padding.top + chartH);
            grad.addColorStop(0, 'rgba(99,102,241,0.3)');
            grad.addColorStop(1, 'transparent');
            ctx.fillStyle = grad;
            ctx.fill();

            // 折线
            ctx.beginPath();
            points.forEach((p, i) => {
                const x = padding.left + i * xStep;
                const y = padding.top + chartH - ((p.value || p.count || 0) / maxVal) * chartH;
                if (i === 0) ctx.moveTo(x, y);
                else ctx.lineTo(x, y);
            });
            ctx.strokeStyle = '#6366f1';
            ctx.lineWidth = 2;
            ctx.lineJoin = 'round';
            ctx.stroke();
        }

        // X轴标签
        if (points.length > 0) {
            ctx.fillStyle = '#64748b';
            ctx.font = '10px -apple-system, sans-serif';
            ctx.textAlign = 'center';
            const step = Math.max(1, Math.floor(points.length / 6));
            points.forEach((p, i) => {
                if (i % step !== 0 && i !== points.length - 1) return;
                const x = padding.left + (chartW / Math.max(1, points.length - 1)) * i;
                const label = p.label || p.time || p.timestamp || '';
                const shortLabel = label.length > 5 ? label.substring(label.length - 5) : label;
                ctx.fillText(shortLabel, x, height - 8);
            });
        }
    }

    // ===== 渲染: 分类统计 =====
    function renderCategories(data) {
        const container = document.getElementById('analytics-categories');
        if (!container) return;

        if (!data || !data.categories || data.categories.length === 0) {
            container.innerHTML = '<div class="analytics-empty">暂无分类数据</div>';
            return;
        }

        const categories = data.categories;
        const maxCount = Math.max(1, ...categories.map(c => c.count || 0));

        container.innerHTML = categories.map(c => {
            const pct = ((c.count || 0) / maxCount * 100).toFixed(1);
            return `
                <div class="analytics-category-row">
                    <span class="analytics-category-name">${escapeHtml(c.name || c.category || '未知')}</span>
                    <div class="analytics-category-bar">
                        <div class="analytics-category-fill" style="width:${pct}%"></div>
                    </div>
                    <span class="analytics-category-count">${c.count || 0}</span>
                </div>
            `;
        }).join('');
    }

    // ===== 渲染: 标签云 =====
    function renderTags(data) {
        const container = document.getElementById('analytics-tags-cloud');
        if (!container) return;

        if (!data || !data.tags || data.tags.length === 0) {
            container.innerHTML = '<div class="analytics-empty">暂无标签数据</div>';
            return;
        }

        const tags = data.tags;
        const maxCount = Math.max(1, ...tags.map(t => t.count || t.weight || 1));
        const colors = ['#6366f1', '#a855f7', '#3b82f6', '#22c55e', '#f59e0b', '#ef4444', '#ec4899'];

        container.innerHTML = tags.map((t, i) => {
            const weight = (t.count || t.weight || 1) / maxCount;
            const fontSize = (0.7 + weight * 1.5).toFixed(2);
            const color = colors[i % colors.length];
            const opacity = (0.5 + weight * 0.5).toFixed(2);
            return `<span class="analytics-tag" style="font-size:${fontSize}rem;color:${color};opacity:${opacity}" title="${t.count || t.weight || 1} 次">${escapeHtml(t.name || t.tag || '')}</span>`;
        }).join('');
    }

    // ===== 渲染: 质量分析 =====
    function renderQuality(data) {
        const container = document.getElementById('analytics-quality');
        if (!container) return;

        if (!data) {
            container.innerHTML = '<div class="analytics-empty">暂无质量分析数据</div>';
            return;
        }

        const metrics = [
            { label: '完整性', value: data.completeness || 0, icon: '📝' },
            { label: '准确性', value: data.accuracy || 0, icon: '🎯' },
            { label: '时效性', value: data.freshness || 0, icon: '⏰' },
            { label: '相关性', value: data.relevance || 0, icon: '🔗' },
        ];

        container.innerHTML = metrics.map(m => {
            const pct = Math.round((m.value || 0) * 100);
            const color = pct >= 80 ? 'var(--accent-green)' : pct >= 50 ? 'var(--accent-orange)' : 'var(--accent-red)';
            return `
                <div class="analytics-quality-item">
                    <div class="analytics-quality-header">
                        <span>${m.icon} ${m.label}</span>
                        <span class="analytics-quality-score" style="color:${color}">${pct}%</span>
                    </div>
                    <div class="analytics-quality-bar">
                        <div class="analytics-quality-fill" style="width:${pct}%;background:${color}"></div>
                    </div>
                </div>
            `;
        }).join('');
    }

    // ===== 工具函数 =====
    function formatNumber(n) {
        if (typeof n !== 'number') return '0';
        if (n >= 1000000) return (n / 1000000).toFixed(1) + 'M';
        if (n >= 1000) return (n / 1000).toFixed(1) + 'K';
        return n.toLocaleString();
    }

    function escapeHtml(str) {
        if (!str) return '';
        const div = document.createElement('div');
        div.textContent = str;
        return div.innerHTML;
    }

    function renderPlaceholderCards() {
        const placeholders = [
            { icon: '📊', label: '总记忆数', value: '--' },
            { icon: '📅', label: '今日新增', value: '--' },
            { icon: '🔍', label: '搜索次数', value: '--' },
            { icon: '⭐', label: '平均质量分', value: '--' },
        ];
        return placeholders.map(c => `
            <div class="analytics-stat-card">
                <div class="analytics-stat-icon">${c.icon}</div>
                <div class="analytics-stat-info">
                    <div class="analytics-stat-label">${c.label}</div>
                    <div class="analytics-stat-value">${c.value}</div>
                </div>
            </div>
        `).join('');
    }

    // ===== 公共接口 =====
    return {
        init,
        loadAll,
        stopAutoRefresh,
        getLastData: () => lastData,
    };
})();

if (typeof window !== 'undefined') {
    window.AnalyticsModule = AnalyticsModule;
}
