/**
 * 版本管理模块
 * 
 * 功能:
 * - 显示记忆版本列表
 * - 版本对比展示
 * - 版本回滚操作
 * 
 * @module versions
 */

const VersionsModule = (() => {
    'use strict';

    // ===== API 配置 =====
    const API_BASE = '/api/v1';

    // ===== 状态 =====
    let versions = [];
    let selectedVersions = [];  // 用于对比的版本ID列表（最多2个）
    let currentMemoryId = null;

    /**
     * 初始化版本管理模块
     */
    function init() {
        console.log('[Versions] 初始化版本管理模块');
        bindEvents();
        loadVersions();
    }

    /**
     * 绑定DOM事件
     */
    function bindEvents() {
        // 记忆ID搜索
        const searchInput = document.getElementById('version-memory-search');
        if (searchInput) {
            searchInput.addEventListener('keydown', (e) => {
                if (e.key === 'Enter') {
                    const id = searchInput.value.trim();
                    if (id) {
                        currentMemoryId = id;
                        loadVersions(id);
                    }
                }
            });
        }

        const searchBtn = document.getElementById('version-search-btn');
        if (searchBtn) {
            searchBtn.addEventListener('click', () => {
                const input = document.getElementById('version-memory-search');
                if (input) {
                    const id = input.value.trim();
                    if (id) {
                        currentMemoryId = id;
                        loadVersions(id);
                    }
                }
            });
        }

        // 对比按钮
        const compareBtn = document.getElementById('version-compare-btn');
        if (compareBtn) {
            compareBtn.addEventListener('click', compareSelected);
        }
    }

    // ===== 数据加载 =====

    /**
     * 加载版本列表
     * @param {string} [memoryId] - 指定记忆ID的版本，否则加载全部
     */
    async function loadVersions(memoryId) {
        const container = document.getElementById('version-list');
        if (!container) return;

        container.innerHTML = '<div class="loading-spinner-small"></div>';

        try {
            let url = `${API_BASE}/versions`;
            if (memoryId) {
                url = `${API_BASE}/memories/${encodeURIComponent(memoryId)}/versions`;
            }

            const response = await fetch(url, {
                headers: { 'Content-Type': 'application/json' },
                signal: AbortSignal.timeout(10000),
            });

            if (!response.ok) throw new Error(`HTTP ${response.status}`);

            const data = await response.json();
            versions = Array.isArray(data) ? data : (data.versions || data.data || []);

            selectedVersions = [];
            renderVersionList();
            updateCompareButton();
        } catch (err) {
            console.error('[Versions] 加载版本列表失败:', err);
            container.innerHTML = `
                <div class="analytics-empty">
                    <div>⚠️ 加载失败</div>
                    <div class="text-muted">${err.message}</div>
                    <button class="btn btn-sm" onclick="VersionsModule.loadVersions()">重试</button>
                </div>
            `;
        }
    }

    // ===== 渲染 =====

    function renderVersionList() {
        const container = document.getElementById('version-list');
        if (!container) return;

        if (versions.length === 0) {
            container.innerHTML = `
                <div class="analytics-empty">
                    <div>📋</div>
                    <div>暂无版本记录</div>
                    <div class="text-muted">输入记忆ID查询其版本历史</div>
                </div>
            `;
            return;
        }

        container.innerHTML = `
            <table class="data-table version-table">
                <thead>
                    <tr>
                        <th style="width:40px"></th>
                        <th>版本</th>
                        <th>变更摘要</th>
                        <th>操作者</th>
                        <th>时间</th>
                        <th style="width:100px">操作</th>
                    </tr>
                </thead>
                <tbody>
                    ${versions.map(v => {
                        const id = v.id || v.version_id || '';
                        const version = v.version || v.versionNumber || '';
                        const summary = v.summary || v.description || v.changeSummary || '';
                        const operator = v.operator || v.user || v.modifiedBy || '-';
                        const timestamp = v.timestamp || v.createdAt || v.created_at || '';
                        const memoryId = v.memoryId || v.memory_id || '';
                        const isSelected = selectedVersions.includes(id);

                        return `
                            <tr class="${isSelected ? 'row-selected' : ''}">
                                <td>
                                    <input type="checkbox" class="version-checkbox" 
                                        ${isSelected ? 'checked' : ''}
                                        onchange="VersionsModule.toggleSelect('${escapeHtml(id)}')" />
                                </td>
                                <td class="cell-mono">
                                    <span class="version-tag">v${escapeHtml(version || '?')}</span>
                                </td>
                                <td>${escapeHtml(summary) || '<span class="text-muted">-</span>'}</td>
                                <td>${escapeHtml(operator)}</td>
                                <td class="cell-mono">${timestamp ? new Date(timestamp).toLocaleString('zh-CN') : '-'}</td>
                                <td>
                                    <button class="btn btn-sm btn-secondary" onclick="VersionsModule.viewVersion('${escapeHtml(id)}', '${escapeHtml(memoryId)}')" title="查看详情">👁️</button>
                                    <button class="btn btn-sm btn-primary" onclick="VersionsModule.rollbackVersion('${escapeHtml(id)}', '${escapeHtml(memoryId)}')" title="回滚">⏪</button>
                                </td>
                            </tr>
                        `;
                    }).join('')}
                </tbody>
            </table>
        `;
    }

    // ===== 版本对比 =====

    function toggleSelect(versionId) {
        const index = selectedVersions.indexOf(versionId);
        if (index >= 0) {
            selectedVersions.splice(index, 1);
        } else if (selectedVersions.length < 2) {
            selectedVersions.push(versionId);
        } else {
            showToast('最多选择2个版本进行对比', 'error');
            return;
        }
        renderVersionList();
        updateCompareButton();
    }

    function updateCompareButton() {
        const btn = document.getElementById('version-compare-btn');
        if (btn) {
            btn.disabled = selectedVersions.length !== 2;
            btn.textContent = selectedVersions.length === 2 
                ? `对比选中的 ${selectedVersions.length} 个版本` 
                : `选择版本进行对比 (${selectedVersions.length}/2)`;
        }
    }

    /**
     * 对比选中的两个版本
     */
    async function compareSelected() {
        if (selectedVersions.length !== 2) return;

        const container = document.getElementById('version-compare');
        if (!container) return;

        container.innerHTML = '<div class="loading-spinner-small"></div>';
        container.style.display = 'block';

        try {
            const [v1, v2] = await Promise.all([
                fetchVersionDetail(selectedVersions[0]),
                fetchVersionDetail(selectedVersions[1]),
            ]);

            renderCompareResult(v1, v2);
        } catch (err) {
            console.error('[Versions] 对比失败:', err);
            container.innerHTML = `<div class="analytics-empty">对比失败: ${err.message}</div>`;
        }
    }

    async function fetchVersionDetail(versionId) {
        try {
            const response = await fetch(`${API_BASE}/versions/${encodeURIComponent(versionId)}`, {
                headers: { 'Content-Type': 'application/json' },
                signal: AbortSignal.timeout(10000),
            });
            if (!response.ok) return null;
            return await response.json();
        } catch {
            return null;
        }
    }

    function renderCompareResult(v1, v2) {
        const container = document.getElementById('version-compare');
        if (!container) return;

        if (!v1 || !v2) {
            container.innerHTML = '<div class="analytics-empty">无法获取版本详情</div>';
            return;
        }

        const text1 = v1.text || v1.content || JSON.stringify(v1, null, 2);
        const text2 = v2.text || v2.content || JSON.stringify(v2, null, 2);
        const ver1 = v1.version || v1.versionNumber || '?';
        const ver2 = v2.version || v2.versionNumber || '?';

        container.innerHTML = `
            <div class="compare-header">
                <h4>版本对比</h4>
                <button class="btn btn-sm btn-secondary" onclick="document.getElementById('version-compare').style.display='none'">关闭</button>
            </div>
            <div class="compare-grid">
                <div class="compare-panel">
                    <div class="compare-panel-header">版本 ${escapeHtml(String(ver1))}</div>
                    <pre class="compare-content">${escapeHtml(text1)}</pre>
                </div>
                <div class="compare-panel">
                    <div class="compare-panel-header">版本 ${escapeHtml(String(ver2))}</div>
                    <pre class="compare-content">${escapeHtml(text2)}</pre>
                </div>
            </div>
        `;
    }

    // ===== 操作 =====

    /**
     * 查看版本详情
     */
    async function viewVersion(versionId, memoryId) {
        const container = document.getElementById('version-compare');
        if (!container) return;

        container.innerHTML = '<div class="loading-spinner-small"></div>';
        container.style.display = 'block';

        const detail = await fetchVersionDetail(versionId);
        if (!detail) {
            container.innerHTML = '<div class="analytics-empty">无法获取版本详情</div>';
            return;
        }

        const text = detail.text || detail.content || JSON.stringify(detail, null, 2);
        const ver = detail.version || detail.versionNumber || '?';

        container.innerHTML = `
            <div class="compare-header">
                <h4>版本 ${escapeHtml(String(ver))} 详情</h4>
                <button class="btn btn-sm btn-secondary" onclick="document.getElementById('version-compare').style.display='none'">关闭</button>
            </div>
            <pre class="compare-content" style="max-height:400px;overflow:auto">${escapeHtml(text)}</pre>
        `;
    }

    /**
     * 回滚到指定版本
     */
    async function rollbackVersion(versionId, memoryId) {
        if (!versionId) return;
        if (!confirm('确定要回滚到此版本吗？当前版本将被覆盖。')) return;

        try {
            const response = await fetch(`${API_BASE}/versions/${encodeURIComponent(versionId)}/rollback`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
            });

            if (!response.ok) {
                const errData = await response.json().catch(() => ({}));
                throw new Error(errData.message || `HTTP ${response.status}`);
            }

            showToast('版本回滚成功', 'success');
            // 重新加载
            if (memoryId) loadVersions(memoryId);
            else loadVersions();
        } catch (err) {
            console.error('[Versions] 回滚失败:', err);
            showToast(`回滚失败: ${err.message}`, 'error');
        }
    }

    // ===== 工具函数 =====
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
        loadVersions,
        toggleSelect,
        compareSelected,
        viewVersion,
        rollbackVersion,
    };
})();

if (typeof window !== 'undefined') {
    window.VersionsModule = VersionsModule;
}
