/**
 * 审计日志模块
 * 
 * 功能:
 * - 显示最近操作日志
 * - 按用户/时间范围筛选
 * - 日志导出
 * 
 * @module audit
 */

const AuditModule = (() => {
    'use strict';

    // ===== API 配置 =====
    const API_BASE = '/api/v1';

    // ===== 状态 =====
    let logs = [];
    let currentPage = 0;
    let pageSize = 50;
    let totalCount = 0;
    let filters = {
        userId: '',
        startTime: '',
        endTime: '',
    };

    /**
     * 初始化审计日志模块
     */
    function init() {
        console.log('[Audit] 初始化审计日志模块');
        bindEvents();
        loadLogs();
    }

    /**
     * 绑定DOM事件
     */
    function bindEvents() {
        // 筛选按钮
        const filterBtn = document.getElementById('audit-filter-btn');
        if (filterBtn) {
            filterBtn.addEventListener('click', () => {
                const userIdInput = document.getElementById('audit-filter-userid');
                const startInput = document.getElementById('audit-filter-start');
                const endInput = document.getElementById('audit-filter-end');

                filters.userId = userIdInput ? userIdInput.value.trim() : '';
                filters.startTime = startInput ? startInput.value : '';
                filters.endTime = endInput ? endInput.value : '';
                currentPage = 0;
                loadLogs();
            });
        }

        // 清除筛选
        const clearBtn = document.getElementById('audit-clear-btn');
        if (clearBtn) {
            clearBtn.addEventListener('click', () => {
                filters = { userId: '', startTime: '', endTime: '' };
                const userIdInput = document.getElementById('audit-filter-userid');
                const startInput = document.getElementById('audit-filter-start');
                const endInput = document.getElementById('audit-filter-end');
                if (userIdInput) userIdInput.value = '';
                if (startInput) startInput.value = '';
                if (endInput) endInput.value = '';
                currentPage = 0;
                loadLogs();
            });
        }

        // 导出按钮
        const exportBtn = document.getElementById('audit-export-btn');
        if (exportBtn) {
            exportBtn.addEventListener('click', exportLogs);
        }

        // 分页
        const prevBtn = document.getElementById('audit-page-prev');
        const nextBtn = document.getElementById('audit-page-next');
        if (prevBtn) {
            prevBtn.addEventListener('click', () => {
                if (currentPage > 0) {
                    currentPage--;
                    loadLogs();
                }
            });
        }
        if (nextBtn) {
            nextBtn.addEventListener('click', () => {
                const maxPage = Math.ceil(totalCount / pageSize) - 1;
                if (currentPage < maxPage) {
                    currentPage++;
                    loadLogs();
                }
            });
        }
    }

    // ===== 数据加载 =====

    /**
     * 加载审计日志
     */
    async function loadLogs() {
        const container = document.getElementById('audit-log-list');
        if (!container) return;

        container.innerHTML = '<div class="loading-spinner-small"></div>';

        try {
            const params = new URLSearchParams();
            params.set('limit', pageSize.toString());
            params.set('offset', (currentPage * pageSize).toString());

            if (filters.userId) params.set('userId', filters.userId);
            if (filters.startTime) params.set('startTime', filters.startTime);
            if (filters.endTime) params.set('endTime', filters.endTime);

            const response = await fetch(`${API_BASE}/audit?${params}`, {
                headers: { 'Content-Type': 'application/json' },
                signal: AbortSignal.timeout(15000),
            });

            if (!response.ok) throw new Error(`HTTP ${response.status}`);

            const data = await response.json();
            logs = Array.isArray(data) ? data : (data.logs || data.auditLogs || data.data || []);
            totalCount = data.total || data.totalCount || logs.length;

            renderLogs();
            updatePagination();
        } catch (err) {
            console.error('[Audit] 加载审计日志失败:', err);
            container.innerHTML = `
                <div class="analytics-empty">
                    <div>⚠️ 加载失败</div>
                    <div class="text-muted">${err.message}</div>
                    <button class="btn btn-sm" onclick="AuditModule.loadLogs()">重试</button>
                </div>
            `;
        }
    }

    // ===== 渲染 =====

    function renderLogs() {
        const container = document.getElementById('audit-log-list');
        if (!container) return;

        if (logs.length === 0) {
            container.innerHTML = `
                <div class="analytics-empty">
                    <div>📝</div>
                    <div>暂无审计日志</div>
                </div>
            `;
            return;
        }

        container.innerHTML = `
            <table class="data-table audit-table">
                <thead>
                    <tr>
                        <th>时间</th>
                        <th>操作</th>
                        <th>用户</th>
                        <th>资源</th>
                        <th>详情</th>
                        <th>结果</th>
                    </tr>
                </thead>
                <tbody>
                    ${logs.map(log => {
                        const timestamp = log.timestamp || log.createdAt || log.created_at || '';
                        const action = log.action || log.operation || log.type || '-';
                        const user = log.userId || log.user || log.user_id || '-';
                        const resource = log.resource || log.target || log.resourceId || '-';
                        const detail = log.detail || log.details || log.message || '';
                        const success = log.success !== false;
                        const level = log.level || (success ? 'info' : 'error');

                        return `
                            <tr class="audit-row audit-level-${level}">
                                <td class="cell-mono cell-small">${timestamp ? formatTime(timestamp) : '-'}</td>
                                <td>
                                    <span class="audit-action-badge audit-action-${getActionClass(action)}">${escapeHtml(action)}</span>
                                </td>
                                <td>${escapeHtml(user)}</td>
                                <td class="cell-mono cell-small">${escapeHtml(String(resource).substring(0, 20))}</td>
                                <td class="cell-detail">${escapeHtml(truncate(detail, 80))}</td>
                                <td>
                                    <span class="audit-result ${success ? 'result-success' : 'result-error'}">
                                        ${success ? '✅' : '❌'}
                                    </span>
                                </td>
                            </tr>
                        `;
                    }).join('')}
                </tbody>
            </table>
        `;
    }

    function updatePagination() {
        const pageInfo = document.getElementById('audit-page-info');
        if (pageInfo) {
            const start = currentPage * pageSize + 1;
            const end = Math.min((currentPage + 1) * pageSize, totalCount);
            pageInfo.textContent = `显示 ${start}-${end} 条，共 ${totalCount} 条`;
        }

        const prevBtn = document.getElementById('audit-page-prev');
        const nextBtn = document.getElementById('audit-page-next');
        if (prevBtn) prevBtn.disabled = currentPage === 0;
        if (nextBtn) nextBtn.disabled = (currentPage + 1) * pageSize >= totalCount;
    }

    // ===== 导出 =====

    function exportLogs() {
        if (logs.length === 0) {
            showToast('暂无数据可导出', 'error');
            return;
        }

        // 生成CSV
        const headers = ['时间', '操作', '用户', '资源', '详情', '结果'];
        const rows = logs.map(log => {
            const timestamp = log.timestamp || log.createdAt || '';
            const action = log.action || log.operation || '';
            const user = log.userId || log.user || '';
            const resource = log.resource || log.target || '';
            const detail = log.detail || log.details || log.message || '';
            const success = log.success !== false;

            return [
                timestamp ? new Date(timestamp).toLocaleString('zh-CN') : '',
                action,
                user,
                resource,
                `"${String(detail).replace(/"/g, '""')}"`,
                success ? '成功' : '失败',
            ].join(',');
        });

        const csv = '\uFEFF' + headers.join(',') + '\n' + rows.join('\n');
        const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
        const url = URL.createObjectURL(blob);

        const a = document.createElement('a');
        a.href = url;
        a.download = `audit-log-${new Date().toISOString().slice(0, 10)}.csv`;
        a.click();
        URL.revokeObjectURL(url);

        showToast('审计日志已导出', 'success');
    }

    // ===== 工具函数 =====
    function formatTime(ts) {
        try {
            return new Date(ts).toLocaleString('zh-CN', {
                month: '2-digit', day: '2-digit',
                hour: '2-digit', minute: '2-digit', second: '2-digit',
            });
        } catch {
            return ts;
        }
    }

    function getActionClass(action) {
        const lower = String(action).toLowerCase();
        if (lower.includes('create') || lower.includes('add') || lower.includes('insert')) return 'create';
        if (lower.includes('update') || lower.includes('edit') || lower.includes('modify')) return 'update';
        if (lower.includes('delete') || lower.includes('remove')) return 'delete';
        if (lower.includes('search') || lower.includes('query') || lower.includes('find')) return 'query';
        if (lower.includes('login') || lower.includes('auth')) return 'auth';
        return 'other';
    }

    function truncate(str, len) {
        if (!str) return '';
        str = String(str);
        return str.length > len ? str.substring(0, len) + '...' : str;
    }

    function escapeHtml(str) {
        if (!str) return '';
        const div = document.createElement('div');
        div.textContent = str;
        return div.innerHTML;
    }

    function showToast(message, type = 'info') {
        if (typeof window.showToast === 'function') {
            window.showToast(message, type);
        } else if (typeof window.App !== 'undefined' && typeof window.App.showToast === 'function') {
            window.App.showToast(message, type);
        } else {
            console.log(`[Toast] ${message}`);
        }
    }

    // ===== 公共接口 =====
    return {
        init,
        loadLogs,
        exportLogs,
    };
})();

if (typeof window !== 'undefined') {
    window.AuditModule = AuditModule;
}
