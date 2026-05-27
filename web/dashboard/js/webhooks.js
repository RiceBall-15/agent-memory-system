/**
 * Webhook管理模块
 * 
 * 功能:
 * - 显示所有Webhook配置
 * - 创建新Webhook（表单）
 * - 测试Webhook连接
 * - 删除Webhook
 * - 显示最近事件
 * 
 * @module webhooks
 */

const WebhooksModule = (() => {
    'use strict';

    // ===== API 配置 =====
    const API_BASE = '/api/v1';

    // ===== 状态 =====
    let webhooks = [];
    let recentEvents = [];

    /**
     * 初始化Webhook管理模块
     */
    function init() {
        console.log('[Webhooks] 初始化Webhook管理模块');
        bindEvents();
        loadWebhooks();
        loadRecentEvents();
    }

    /**
     * 绑定DOM事件
     */
    function bindEvents() {
        // 创建表单提交
        const form = document.getElementById('webhook-create-form');
        if (form) {
            form.addEventListener('submit', handleCreate);
        }

        // 创建按钮（打开模态框）
        const createBtn = document.getElementById('webhook-create-btn');
        if (createBtn) {
            createBtn.addEventListener('click', showCreateModal);
        }

        // 模态框关闭
        const modalClose = document.getElementById('webhook-modal-close');
        if (modalClose) {
            modalClose.addEventListener('click', hideCreateModal);
        }
        const modalOverlay = document.getElementById('webhook-modal-overlay');
        if (modalOverlay) {
            modalOverlay.addEventListener('click', hideCreateModal);
        }
    }

    // ===== 数据加载 =====

    /**
     * 加载Webhook列表
     */
    async function loadWebhooks() {
        const container = document.getElementById('webhook-list');
        if (!container) return;

        container.innerHTML = '<div class="loading-spinner-small"></div>';

        try {
            const response = await fetch(`${API_BASE}/webhooks`, {
                headers: { 'Content-Type': 'application/json' },
                signal: AbortSignal.timeout(10000),
            });

            if (!response.ok) throw new Error(`HTTP ${response.status}`);

            const data = await response.json();
            webhooks = Array.isArray(data) ? data : (data.webhooks || data.data || []);

            renderWebhookList();
        } catch (err) {
            console.error('[Webhooks] 加载Webhook列表失败:', err);
            container.innerHTML = `
                <div class="analytics-empty">
                    <div>⚠️ 加载失败</div>
                    <div class="text-muted">${err.message}</div>
                    <button class="btn btn-sm" onclick="WebhooksModule.loadWebhooks()">重试</button>
                </div>
            `;
        }
    }

    /**
     * 加载最近事件
     */
    async function loadRecentEvents() {
        const container = document.getElementById('webhook-events-list');
        if (!container) return;

        try {
            const response = await fetch(`${API_BASE}/webhooks/events?limit=20`, {
                headers: { 'Content-Type': 'application/json' },
                signal: AbortSignal.timeout(10000),
            });

            if (!response.ok) throw new Error(`HTTP ${response.status}`);

            const data = await response.json();
            recentEvents = Array.isArray(data) ? data : (data.events || data.data || []);

            renderRecentEvents();
        } catch (err) {
            console.warn('[Webhooks] 加载事件失败:', err.message);
            container.innerHTML = '<div class="analytics-empty">暂无事件数据</div>';
        }
    }

    // ===== 渲染 =====

    function renderWebhookList() {
        const container = document.getElementById('webhook-list');
        if (!container) return;

        if (webhooks.length === 0) {
            container.innerHTML = `
                <div class="analytics-empty">
                    <div>🔗</div>
                    <div>暂无Webhook配置</div>
                    <div class="text-muted">点击"创建Webhook"添加第一个</div>
                </div>
            `;
            return;
        }

        container.innerHTML = webhooks.map(wh => {
            const id = wh.id || wh.webhook_id || '';
            const name = wh.name || wh.title || '未命名';
            const url = wh.url || wh.endpoint || '';
            const events = wh.events || wh.eventTypes || [];
            const active = wh.active !== false;
            const createdAt = wh.createdAt || wh.created_at || '';

            return `
                <div class="webhook-item ${active ? 'active' : 'inactive'}">
                    <div class="webhook-item-header">
                        <div class="webhook-item-info">
                            <span class="webhook-status-dot ${active ? 'online' : 'offline'}"></span>
                            <span class="webhook-name">${escapeHtml(name)}</span>
                            <span class="webhook-badge">${active ? '启用' : '停用'}</span>
                        </div>
                        <div class="webhook-item-actions">
                            <button class="btn btn-sm btn-secondary" onclick="WebhooksModule.testWebhook('${escapeHtml(id)}')" title="测试">🧪</button>
                            <button class="btn btn-sm btn-danger" onclick="WebhooksModule.deleteWebhook('${escapeHtml(id)}', '${escapeHtml(name)}')" title="删除">🗑️</button>
                        </div>
                    </div>
                    <div class="webhook-item-url">${escapeHtml(url)}</div>
                    <div class="webhook-item-events">
                        ${(events.length > 0 ? events : ['all']).map(e => `<span class="webhook-event-tag">${escapeHtml(e)}</span>`).join('')}
                    </div>
                    ${createdAt ? `<div class="webhook-item-date">创建于 ${new Date(createdAt).toLocaleString('zh-CN')}</div>` : ''}
                </div>
            `;
        }).join('');
    }

    function renderRecentEvents() {
        const container = document.getElementById('webhook-events-list');
        if (!container) return;

        if (recentEvents.length === 0) {
            container.innerHTML = '<div class="analytics-empty">暂无事件记录</div>';
            return;
        }

        container.innerHTML = recentEvents.map(ev => {
            const success = ev.success !== false;
            const status = ev.status || (success ? '200' : 'Error');
            const timestamp = ev.timestamp || ev.createdAt || '';
            const eventType = ev.eventType || ev.event || ev.type || 'unknown';
            const webhookName = ev.webhookName || ev.webhook_name || '';

            return `
                <div class="webhook-event-item ${success ? 'success' : 'failed'}">
                    <span class="webhook-event-status ${success ? 'status-ok' : 'status-error'}">${status}</span>
                    <span class="webhook-event-type">${escapeHtml(eventType)}</span>
                    ${webhookName ? `<span class="webhook-event-source">${escapeHtml(webhookName)}</span>` : ''}
                    <span class="webhook-event-time">${timestamp ? new Date(timestamp).toLocaleString('zh-CN') : ''}</span>
                </div>
            `;
        }).join('');
    }

    // ===== 操作 =====

    function showCreateModal() {
        const overlay = document.getElementById('webhook-modal-overlay');
        if (overlay) overlay.classList.add('modal-visible');
    }

    function hideCreateModal() {
        const overlay = document.getElementById('webhook-modal-overlay');
        if (overlay) overlay.classList.remove('modal-visible');
        // 重置表单
        const form = document.getElementById('webhook-create-form');
        if (form) form.reset();
    }

    /**
     * 处理创建Webhook
     */
    async function handleCreate(e) {
        e.preventDefault();

        const nameInput = document.getElementById('webhook-name');
        const urlInput = document.getElementById('webhook-url');
        const eventsInput = document.getElementById('webhook-events');
        const secretInput = document.getElementById('webhook-secret');
        const submitBtn = document.getElementById('webhook-submit-btn');

        if (!nameInput || !urlInput) return;

        const name = nameInput.value.trim();
        const url = urlInput.value.trim();
        const events = eventsInput ? eventsInput.value.trim().split(',').map(s => s.trim()).filter(Boolean) : [];
        const secret = secretInput ? secretInput.value.trim() : '';

        if (!name || !url) {
            showToast('请填写名称和URL', 'error');
            return;
        }

        if (submitBtn) {
            submitBtn.disabled = true;
            submitBtn.textContent = '创建中...';
        }

        try {
            const body = { name, url, events };
            if (secret) body.secret = secret;

            const response = await fetch(`${API_BASE}/webhooks`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body),
            });

            if (!response.ok) {
                const errData = await response.json().catch(() => ({}));
                throw new Error(errData.message || `HTTP ${response.status}`);
            }

            showToast('Webhook创建成功', 'success');
            hideCreateModal();
            loadWebhooks();
        } catch (err) {
            console.error('[Webhooks] 创建失败:', err);
            showToast(`创建失败: ${err.message}`, 'error');
        } finally {
            if (submitBtn) {
                submitBtn.disabled = false;
                submitBtn.textContent = '创建';
            }
        }
    }

    /**
     * 测试Webhook
     */
    async function testWebhook(id) {
        if (!id) return;
        showToast('正在测试Webhook...', 'info');

        try {
            const response = await fetch(`${API_BASE}/webhooks/${encodeURIComponent(id)}/test`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
            });

            if (!response.ok) throw new Error(`HTTP ${response.status}`);

            const result = await response.json().catch(() => ({}));
            showToast(`测试成功: ${result.message || '连接正常'}`, 'success');
        } catch (err) {
            console.error('[Webhooks] 测试失败:', err);
            showToast(`测试失败: ${err.message}`, 'error');
        }
    }

    /**
     * 删除Webhook
     */
    async function deleteWebhook(id, name) {
        if (!id) return;
        if (!confirm(`确定要删除Webhook "${name}" 吗？此操作不可恢复。`)) return;

        try {
            const response = await fetch(`${API_BASE}/webhooks/${encodeURIComponent(id)}`, {
                method: 'DELETE',
            });

            if (!response.ok) throw new Error(`HTTP ${response.status}`);

            showToast('Webhook已删除', 'success');
            loadWebhooks();
        } catch (err) {
            console.error('[Webhooks] 删除失败:', err);
            showToast(`删除失败: ${err.message}`, 'error');
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
        // 复用已有的toast机制，如果没有则直接alert
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
        loadWebhooks,
        loadRecentEvents,
        testWebhook,
        deleteWebhook,
        showCreateModal,
        hideCreateModal,
    };
})();

if (typeof window !== 'undefined') {
    window.WebhooksModule = WebhooksModule;
}
