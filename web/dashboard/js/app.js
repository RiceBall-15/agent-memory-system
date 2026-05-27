/**
 * 主应用模块
 * 
 * 管理页面路由、导航切换、WebSocket连接、自动刷新
 * 初始化并协调所有模块
 * 
 * @module app
 */

const App = (() => {
    'use strict';

    // ==================== 配置 ====================

    /** 当前活动面板ID */
    let activePanel = 'overview';

    /** 系统状态 */
    let systemStatus = {
        connected: false,
        healthData: null,
        lastUpdate: null,
    };

    /** 时间更新定时器 */
    let clockTimer = null;

    /** 健康检查定时器 */
    let healthCheckTimer = null;

    /** WebSocket连接 */
    let ws = null;

    /** 是否已初始化 */
    let initialized = false;

    // ==================== 路由管理 ====================

    /**
     * 初始化Hash路由
     */
    function initRouter() {
        // 监听hash变化
        window.addEventListener('hashchange', () => {
            const hash = window.location.hash.slice(1) || 'overview';
            navigateTo(hash);
        });

        // 处理初始hash
        const initialHash = window.location.hash.slice(1) || 'overview';
        navigateTo(initialHash);
    }

    /**
     * 导航到指定面板
     * @param {string} panelId - 面板ID
     */
    function navigateTo(panelId) {
        // 验证面板ID
        const validPanels = ['overview', 'metrics', 'memories', 'system'];
        if (!validPanels.includes(panelId)) {
            panelId = 'overview';
        }

        // 更新活动面板
        activePanel = panelId;

        // 更新URL hash
        if (window.location.hash.slice(1) !== panelId) {
            window.history.replaceState(null, '', `#${panelId}`);
        }

        // 切换面板显示
        document.querySelectorAll('.panel').forEach(panel => {
            panel.classList.remove('active');
        });
        const targetPanel = document.getElementById(`panel-${panelId}`);
        if (targetPanel) {
            targetPanel.classList.add('active');
        }

        // 更新导航高亮
        document.querySelectorAll('.nav-item').forEach(item => {
            item.classList.remove('active');
        });
        const activeNav = document.querySelector(`.nav-item[data-panel="${panelId}"]`);
        if (activeNav) {
            activeNav.classList.add('active');
        }

        // 更新顶部栏标题
        const titles = {
            overview: '系统概览',
            metrics: '实时指标',
            memories: '记忆管理',
            system: '系统状态',
        };
        const topbarTitle = document.getElementById('topbar-title');
        if (topbarTitle) {
            topbarTitle.textContent = titles[panelId] || 'Dashboard';
        }

        // 触发面板特定初始化
        onPanelActivated(panelId);

        console.log(`[App] 导航到: ${panelId}`);
    }

    /**
     * 面板激活时的回调
     * @param {string} panelId - 面板ID
     */
    function onPanelActivated(panelId) {
        switch (panelId) {
            case 'overview':
                refreshOverview();
                break;
            case 'metrics':
                // 重新绘制图表以适应容器大小
                setTimeout(() => Metrics.handleResize(), 100);
                break;
            case 'memories':
                loadMemories();
                break;
            case 'system':
                refreshSystemStatus();
                break;
        }
    }

    // ==================== 导航事件 ====================

    /**
     * 绑定导航点击事件
     */
    function bindNavigation() {
        document.querySelectorAll('.nav-item[data-panel]').forEach(item => {
            item.addEventListener('click', (e) => {
                e.preventDefault();
                const panelId = item.getAttribute('data-panel');
                navigateTo(panelId);
            });
        });

        // 移动端菜单按钮
        const menuBtn = document.getElementById('menu-btn');
        const sidebar = document.getElementById('sidebar');
        const overlay = document.getElementById('sidebar-overlay');

        if (menuBtn && sidebar) {
            menuBtn.addEventListener('click', () => {
                sidebar.classList.toggle('open');
                if (overlay) overlay.classList.toggle('active');
            });
        }

        if (overlay) {
            overlay.addEventListener('click', () => {
                sidebar.classList.remove('open');
                overlay.classList.remove('active');
            });
        }
    }

    // ==================== 时钟更新 ====================

    /**
     * 启动实时时钟
     */
    function startClock() {
        updateClock();
        clockTimer = setInterval(updateClock, 1000);
    }

    /**
     * 更新时间显示
     */
    function updateClock() {
        const clockEl = document.getElementById('topbar-time');
        if (clockEl) {
            const now = new Date();
            clockEl.textContent = now.toLocaleString('zh-CN', {
                year: 'numeric',
                month: '2-digit',
                day: '2-digit',
                hour: '2-digit',
                minute: '2-digit',
                second: '2-digit',
            });
        }
    }

    // ==================== 健康检查 ====================

    /**
     * 启动健康检查
     */
    function startHealthCheck() {
        checkHealth();
        healthCheckTimer = setInterval(checkHealth, 10000); // 每10秒检查一次
    }

    /**
     * 执行健康检查
     */
    async function checkHealth() {
        try {
            const health = await API.getHealth();
            systemStatus.connected = true;
            systemStatus.healthData = health;
            systemStatus.lastUpdate = new Date();
            updateConnectionStatus(true);
        } catch (error) {
            systemStatus.connected = false;
            systemStatus.healthData = null;
            updateConnectionStatus(false);
            console.warn('[App] 健康检查失败:', error.message);
        }
    }

    /**
     * 更新连接状态UI
     * @param {boolean} connected - 是否已连接
     */
    function updateConnectionStatus(connected) {
        // 顶部栏连接状态
        const connStatus = document.getElementById('topbar-connection');
        if (connStatus) {
            connStatus.className = `topbar-connection ${connected ? 'connected' : 'disconnected'}`;
            connStatus.innerHTML = `
                <span class="status-indicator ${connected ? 'connected' : 'disconnected'}"></span>
                <span>${connected ? '已连接' : '未连接'}</span>
            `;
        }

        // 侧边栏状态
        const sideStatus = document.getElementById('sidebar-status-indicator');
        if (sideStatus) {
            sideStatus.className = `status-indicator ${connected ? 'connected' : 'disconnected'}`;
        }

        const sideStatusText = document.getElementById('sidebar-status-text');
        if (sideStatusText) {
            sideStatusText.textContent = connected ? '系统运行中' : '连接断开';
        }
    }

    // ==================== 概览面板 ====================

    /**
     * 刷新概览面板数据
     */
    async function refreshOverview() {
        // 使用模拟数据更新概览
        updateOverviewCards();
        
        // 尝试获取真实数据
        try {
            const health = await API.getHealth();
            if (health) {
                updateHealthDisplay(health);
            }
        } catch {
            // 后端不可用时使用模拟数据
        }
    }

    /**
     * 更新概览指标卡片（模拟数据）
     */
    function updateOverviewCards() {
        // 总记忆数
        const totalMemories = document.getElementById('overview-total-memories');
        if (totalMemories) {
            const count = Math.floor(Math.random() * 500) + 1200;
            totalMemories.textContent = count.toLocaleString();
        }

        // 今日请求
        const todayRequests = document.getElementById('overview-today-requests');
        if (todayRequests) {
            const count = Math.floor(Math.random() * 2000) + 3000;
            todayRequests.textContent = count.toLocaleString();
        }

        // 平均延迟
        const avgLatency = document.getElementById('overview-avg-latency');
        if (avgLatency) {
            avgLatency.textContent = (Math.random() * 30 + 30).toFixed(1) + ' ms';
        }

        // 成功率
        const successRate = document.getElementById('overview-success-rate');
        if (successRate) {
            successRate.textContent = (99 + Math.random()).toFixed(1) + '%';
        }
    }

    /**
     * 更新健康状态显示
     * @param {Object} health - 健康数据
     */
    function updateHealthDisplay(health) {
        // 更新运行时间
        const uptimeEl = document.getElementById('overview-uptime');
        if (uptimeEl && health.uptimeFormatted) {
            uptimeEl.textContent = health.uptimeFormatted;
        }

        // 更新存储状态
        if (health.stores) {
            updateStoreStatus('vector', health.stores.vector);
            updateStoreStatus('graph', health.stores.graph);
            updateStoreStatus('metadata', health.stores.metadata);
        }

        // 更新内存使用
        if (health.memory) {
            const memoryUsage = document.getElementById('overview-memory-usage');
            if (memoryUsage) {
                const pct = health.memory.maxMB > 0 
                    ? (health.memory.usedMB / health.memory.maxMB * 100).toFixed(1) 
                    : 0;
                memoryUsage.textContent = `${health.memory.usedMB}MB / ${health.memory.maxMB}MB (${pct}%)`;
            }

            // 更新内存进度条
            const memoryBar = document.getElementById('overview-memory-bar');
            if (memoryBar && health.memory.maxMB > 0) {
                const pct = (health.memory.usedMB / health.memory.maxMB * 100);
                memoryBar.style.width = `${Math.min(pct, 100)}%`;
                memoryBar.className = `progress-fill ${pct > 80 ? 'danger' : pct > 60 ? 'warning' : 'success'}`;
            }
        }
    }

    /**
     * 更新存储层状态显示
     * @param {string} store - 存储类型
     * @param {string} status - 状态 ('UP'/'DOWN'/'NOT_CONFIGURED')
     */
    function updateStoreStatus(store, status) {
        const statusMap = {
            'UP': { text: '运行中', badge: 'badge-up', icon: '✓' },
            'DOWN': { text: '不可用', badge: 'badge-down', icon: '✗' },
            'NOT_CONFIGURED': { text: '未配置', badge: 'badge-not-configured', icon: '—' },
            'DEGRADED': { text: '降级', badge: 'badge-degraded', icon: '⚠' },
        };

        const config = statusMap[status] || statusMap['NOT_CONFIGURED'];
        
        const el = document.getElementById(`overview-store-${store}`);
        if (el) {
            el.innerHTML = `
                <span class="badge ${config.badge}">
                    <span>${config.icon}</span>
                    ${config.text}
                </span>
            `;
        }
    }

    // ==================== 记忆管理面板 ====================

    /** 记忆列表分页 */
    let memoryPage = { offset: 0, limit: 20, total: 0 };

    /**
     * 加载记忆列表
     */
    async function loadMemories() {
        const container = document.getElementById('memories-list');
        if (!container) return;

        container.innerHTML = '<div class="loading-overlay"><div class="spinner"></div></div>';

        try {
            const data = await API.getMemories({
                limit: memoryPage.limit,
                offset: memoryPage.offset,
            });

            memoryPage.total = data.total || 0;

            if (!data.memories || data.memories.length === 0) {
                container.innerHTML = `
                    <div class="empty-state">
                        <div class="empty-state-icon">📝</div>
                        <div class="empty-state-text">暂无记忆数据</div>
                        <div class="text-muted">系统启动后将自动提取对话中的记忆</div>
                    </div>
                `;
                return;
            }

            container.innerHTML = data.memories.map(memory => `
                <div class="memory-item" data-id="${memory.id}">
                    <div class="memory-item-header">
                        <span class="memory-item-id">${memory.id ? memory.id.substring(0, 12) + '...' : 'N/A'}</span>
                        <button class="btn btn-danger btn-sm" onclick="App.deleteMemory('${memory.id}')">删除</button>
                    </div>
                    <div class="memory-item-text">${escapeHtml(memory.text || '')}</div>
                    <div class="memory-item-meta">
                        <span>👤 ${memory.userId || 'N/A'}</span>
                        <span>🤖 ${memory.agentId || 'N/A'}</span>
                        <span>📅 ${memory.createdAt ? new Date(memory.createdAt).toLocaleString('zh-CN') : 'N/A'}</span>
                        ${memory.importance !== undefined ? `
                            <span>重要度: 
                                <span class="importance-bar">
                                    <span class="importance-fill" style="width: ${(memory.importance * 100).toFixed(0)}%; background: ${memory.importance > 0.7 ? '#22c55e' : memory.importance > 0.4 ? '#f59e0b' : '#ef4444'};"></span>
                                </span>
                                ${(memory.importance * 100).toFixed(0)}%
                            </span>
                        ` : ''}
                    </div>
                </div>
            `).join('');

            // 更新分页信息
            updateMemoryPagination();

        } catch (error) {
            container.innerHTML = `
                <div class="empty-state">
                    <div class="empty-state-icon">⚠️</div>
                    <div class="empty-state-text">加载失败</div>
                    <div class="text-muted">${error.message || '无法连接到后端服务'}</div>
                    <button class="btn btn-primary mt-md" onclick="App.loadMemories()">重试</button>
                </div>
            `;
        }
    }

    /**
     * 更新分页控件
     */
    function updateMemoryPagination() {
        const pagination = document.getElementById('memory-pagination');
        if (!pagination) return;

        const totalPages = Math.ceil(memoryPage.total / memoryPage.limit);
        const currentPage = Math.floor(memoryPage.offset / memoryPage.limit) + 1;

        pagination.innerHTML = `
            <span class="text-muted">共 ${memoryPage.total} 条记录，第 ${currentPage}/${totalPages || 1} 页</span>
            <div class="flex gap-sm">
                <button class="btn btn-secondary btn-sm" ${memoryPage.offset === 0 ? 'disabled' : ''} 
                    onclick="App.prevMemoryPage()">上一页</button>
                <button class="btn btn-secondary btn-sm" ${currentPage >= totalPages ? 'disabled' : ''} 
                    onclick="App.nextMemoryPage()">下一页</button>
            </div>
        `;
    }

    /**
     * 上一页
     */
    function prevMemoryPage() {
        memoryPage.offset = Math.max(0, memoryPage.offset - memoryPage.limit);
        loadMemories();
    }

    /**
     * 下一页
     */
    function nextMemoryPage() {
        memoryPage.offset += memoryPage.limit;
        loadMemories();
    }

    /**
     * 搜索记忆
     */
    async function searchMemories() {
        const input = document.getElementById('memory-search-input');
        const query = input ? input.value.trim() : '';
        
        if (!query) {
            loadMemories();
            return;
        }

        const container = document.getElementById('memories-list');
        if (!container) return;

        container.innerHTML = '<div class="loading-overlay"><div class="spinner"></div></div>';

        try {
            const data = await API.searchMemories({
                text: query,
                userId: 'dashboard-user',
                topK: 20,
            });

            if (!data.results || data.results.length === 0) {
                container.innerHTML = `
                    <div class="empty-state">
                        <div class="empty-state-icon">🔍</div>
                        <div class="empty-state-text">未找到匹配结果</div>
                        <div class="text-muted">尝试使用不同的关键词</div>
                    </div>
                `;
                return;
            }

            container.innerHTML = data.results.map(result => `
                <div class="memory-item">
                    <div class="memory-item-header">
                        <span class="memory-item-id">相似度: ${((result.score || 0) * 100).toFixed(1)}%</span>
                    </div>
                    <div class="memory-item-text">${escapeHtml(result.text || result.content || '')}</div>
                    <div class="memory-item-meta">
                        <span>搜索耗时: ${data.latencyMs || 0}ms</span>
                    </div>
                </div>
            `).join('');

        } catch (error) {
            container.innerHTML = `
                <div class="empty-state">
                    <div class="empty-state-icon">⚠️</div>
                    <div class="empty-state-text">搜索失败</div>
                    <div class="text-muted">${error.message}</div>
                </div>
            `;
        }
    }

    /**
     * 删除记忆
     * @param {string} id - 记忆ID
     */
    async function deleteMemory(id) {
        if (!confirm('确定要删除这条记忆吗？')) return;

        try {
            await API.deleteMemory(id);
            showToast('记忆已删除', 'success');
            loadMemories();
        } catch (error) {
            showToast('删除失败: ' + error.message, 'error');
        }
    }

    // ==================== 系统状态面板 ====================

    /**
     * 刷新系统状态
     */
    async function refreshSystemStatus() {
        try {
            const health = await API.getHealth();
            renderSystemStatus(health);
        } catch (error) {
            const container = document.getElementById('system-status-content');
            if (container) {
                container.innerHTML = `
                    <div class="empty-state">
                        <div class="empty-state-icon">🔌</div>
                        <div class="empty-state-text">无法获取系统状态</div>
                        <div class="text-muted">${error.message || '后端服务未运行'}</div>
                        <button class="btn btn-primary mt-md" onclick="App.refreshSystemStatus()">重试</button>
                    </div>
                `;
            }
        }
    }

    /**
     * 渲染系统状态
     * @param {Object} health - 健康数据
     */
    function renderSystemStatus(health) {
        const container = document.getElementById('system-status-content');
        if (!container) return;

        const overallStatus = health.status || 'UNKNOWN';
        const statusConfig = {
            'UP': { text: '系统正常', class: 'badge-up' },
            'DOWN': { text: '系统异常', class: 'badge-down' },
            'DEGRADED': { text: '系统降级', class: 'badge-degraded' },
        };
        const config = statusConfig[overallStatus] || { text: '未知', class: 'badge-not-configured' };

        container.innerHTML = `
            <!-- 整体状态 -->
            <div class="card mb-lg">
                <div class="card-header">
                    <div>
                        <div class="card-title">整体状态</div>
                        <div class="card-subtitle">Agent Memory System</div>
                    </div>
                    <span class="badge ${config.class}">${config.text}</span>
                </div>
            </div>

            <!-- 存储层状态 -->
            <div class="card mb-lg">
                <div class="card-title mb-md">存储层状态</div>
                <div class="status-grid">
                    ${renderStoreCard('vector', '向量存储 (Milvus)', health.stores?.vector)}
                    ${renderStoreCard('graph', '图存储 (Neo4j)', health.stores?.graph)}
                    ${renderStoreCard('metadata', '元数据存储 (MySQL)', health.stores?.metadata)}
                </div>
            </div>

            <!-- 系统信息 -->
            <div class="card mb-lg">
                <div class="card-title mb-md">系统信息</div>
                <div class="status-grid">
                    <div class="status-item">
                        <div class="status-item-icon">⏱️</div>
                        <div class="status-item-info">
                            <div class="status-item-label">运行时间</div>
                            <div class="status-item-value">${health.uptimeFormatted || 'N/A'}</div>
                        </div>
                    </div>
                    <div class="status-item">
                        <div class="status-item-icon">💾</div>
                        <div class="status-item-info">
                            <div class="status-item-label">JVM内存</div>
                            <div class="status-item-value">${health.memory ? health.memory.usedMB + 'MB / ' + health.memory.maxMB + 'MB' : 'N/A'}</div>
                            ${health.memory && health.memory.maxMB > 0 ? `
                                <div class="progress-bar mt-sm">
                                    <div class="progress-fill ${(health.memory.usedMB / health.memory.maxMB * 100) > 80 ? 'danger' : 'success'}" 
                                         style="width: ${(health.memory.usedMB / health.memory.maxMB * 100).toFixed(1)}%"></div>
                                </div>
                            ` : ''}
                        </div>
                    </div>
                    ${health.runtime ? `
                        <div class="status-item">
                            <div class="status-item-icon">☕</div>
                            <div class="status-item-info">
                                <div class="status-item-label">Java版本</div>
                                <div class="status-item-value">${health.runtime.javaVersion || 'N/A'}</div>
                            </div>
                        </div>
                        <div class="status-item">
                            <div class="status-item-icon">🖥️</div>
                            <div class="status-item-info">
                                <div class="status-item-label">CPU核心数</div>
                                <div class="status-item-value">${health.runtime.availableProcessors || 'N/A'}</div>
                            </div>
                        </div>
                    ` : ''}
                </div>
            </div>

            <!-- 原始数据 -->
            <div class="card">
                <div class="card-header">
                    <div class="card-title">原始健康数据</div>
                    <button class="btn btn-secondary btn-sm" onclick="App.refreshSystemStatus()">刷新</button>
                </div>
                <div class="code-block">${JSON.stringify(health, null, 2)}</div>
            </div>
        `;
    }

    /**
     * 渲染存储层状态卡片
     */
    function renderStoreCard(store, label, status) {
        const statusMap = {
            'UP': { text: '运行中', class: 'badge-up', icon: '✓' },
            'DOWN': { text: '不可用', class: 'badge-down', icon: '✗' },
            'NOT_CONFIGURED': { text: '未配置', class: 'badge-not-configured', icon: '—' },
        };
        const config = statusMap[status] || statusMap['NOT_CONFIGURED'];

        return `
            <div class="status-item">
                <div class="status-item-icon">${store === 'vector' ? '🔢' : store === 'graph' ? '🔗' : '🗃️'}</div>
                <div class="status-item-info">
                    <div class="status-item-label">${label}</div>
                    <div class="status-item-value">
                        <span class="badge ${config.class}">${config.icon} ${config.text}</span>
                    </div>
                </div>
            </div>
        `;
    }

    // ==================== WebSocket连接 ====================

    /**
     * 初始化WebSocket连接
     */
    function initWebSocket() {
        const wsUrl = API.getBaseUrl().replace(/^http/, 'ws');
        
        try {
            ws = new WebSocket(`${wsUrl}/ws/dashboard`);
            
            ws.onopen = () => {
                console.log('[App] WebSocket已连接');
                systemStatus.connected = true;
                updateConnectionStatus(true);
            };

            ws.onmessage = (event) => {
                try {
                    const data = JSON.parse(event.data);
                    handleWebSocketMessage(data);
                } catch (e) {
                    console.warn('[App] WebSocket消息解析失败:', e.message);
                }
            };

            ws.onclose = () => {
                console.log('[App] WebSocket已断开');
                systemStatus.connected = false;
                updateConnectionStatus(false);
                // 5秒后重连
                setTimeout(initWebSocket, 5000);
            };

            ws.onerror = (error) => {
                console.warn('[App] WebSocket错误:', error);
            };
        } catch (error) {
            console.warn('[App] WebSocket初始化失败:', error.message);
        }
    }

    /**
     * 处理WebSocket消息
     * @param {Object} data - 消息数据
     */
    function handleWebSocketMessage(data) {
        if (data.type === 'metrics') {
            Metrics.updateFromBackend(data.payload);
        } else if (data.type === 'health') {
            systemStatus.healthData = data.payload;
            updateHealthDisplay(data.payload);
        } else if (data.type === 'log') {
            // 可以添加日志面板支持
        }
    }

    // ==================== Toast通知 ====================

    /**
     * 显示Toast通知
     * @param {string} message - 通知消息
     * @param {string} type - 类型 (success/error/warning)
     */
    function showToast(message, type = 'success') {
        const container = document.getElementById('toast-container');
        if (!container) return;

        const icons = {
            success: '✓',
            error: '✗',
            warning: '⚠',
        };

        const toast = document.createElement('div');
        toast.className = `toast ${type}`;
        toast.innerHTML = `
            <span>${icons[type] || '●'}</span>
            <span>${escapeHtml(message)}</span>
        `;

        container.appendChild(toast);

        // 3秒后自动移除
        setTimeout(() => {
            toast.style.animation = 'slideIn 0.3s ease reverse';
            setTimeout(() => toast.remove(), 300);
        }, 3000);
    }

    // ==================== 工具函数 ====================

    /**
     * HTML转义
     * @param {string} text - 原始文本
     * @returns {string} 转义后的文本
     */
    function escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    // ==================== 初始化 ====================

    /**
     * 应用初始化
     */
    function init() {
        if (initialized) return;
        initialized = true;

        console.log('[App] Agent Memory System Dashboard 初始化中...');

        // 绑定导航事件
        bindNavigation();

        // 初始化路由
        initRouter();

        // 启动时钟
        startClock();

        // 启动健康检查
        startHealthCheck();

        // 初始化指标模块
        Metrics.init();

        // 初始化WebSocket（可选）
        initWebSocket();

        // 监听窗口大小变化
        window.addEventListener('resize', () => {
            Metrics.handleResize();
        });

        // 键盘快捷键
        document.addEventListener('keydown', (e) => {
            // ESC关闭移动端侧边栏
            if (e.key === 'Escape') {
                const sidebar = document.getElementById('sidebar');
                const overlay = document.getElementById('sidebar-overlay');
                if (sidebar) sidebar.classList.remove('open');
                if (overlay) overlay.classList.remove('active');
            }
        });

        console.log('[App] Dashboard初始化完成');
    }

    // ==================== 公共API ====================

    return {
        init,
        navigateTo,
        loadMemories,
        searchMemories,
        deleteMemory,
        prevMemoryPage,
        nextMemoryPage,
        refreshSystemStatus,
        refreshOverview,
        showToast,
    };
})();

// 页面加载完成后初始化
if (typeof window !== 'undefined') {
    window.addEventListener('DOMContentLoaded', () => {
        App.init();
    });
}
