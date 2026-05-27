/**
 * 记忆管理模块
 * 
 * 功能:
 * - 记忆列表展示 (表格形式)
 * - 支持分页 (limit/offset)
 * - 支持按userId/agentId筛选
 * - 搜索功能: 输入文本，调用POST /api/v1/memories/search
 * - 创建记忆: 表单输入对话文本，调用POST /api/v1/memories
 * - 删除记忆: 确认后调用DELETE /api/v1/memories/{id}
 * - 记忆详情: 点击展开显示完整内容、实体、时间戳
 * - 搜索结果高亮显示匹配文本
 * - 加载状态和错误提示
 * 
 * @module memory
 */

const MemoryModule = (() => {
    // ===== API 配置 =====
    const API_BASE = '/api/v1';
    
    // ===== 状态 =====
    let currentPage = 0;            // 当前页码 (从0开始)
    let pageSize = 20;             // 每页大小
    let totalCount = 0;            // 总记录数
    let currentFilter = {          // 当前筛选条件
        userId: '',
        agentId: '',
    };
    let searchQuery = '';          // 当前搜索关键词
    let isSearchMode = false;      // 是否在搜索模式
    let expandedRows = new Set();  // 展开的行ID
    let memories = [];             // 当前加载的记忆列表

    /**
     * 初始化记忆管理模块
     */
    function init() {
        console.log('[Memory] 初始化记忆管理模块');
        
        // 绑定事件
        bindEvents();
        
        // 加载初始数据
        loadMemories();
    }

    /**
     * 绑定DOM事件
     */
    function bindEvents() {
        // 搜索输入框
        const searchInput = document.getElementById('memory-search-input');
        if (searchInput) {
            searchInput.addEventListener('input', debounce((e) => {
                searchQuery = e.target.value.trim();
                if (searchQuery.length > 0) {
                    performSearch(searchQuery);
                } else {
                    exitSearchMode();
                }
            }, 300));
            
            searchInput.addEventListener('keydown', (e) => {
                if (e.key === 'Enter') {
                    searchQuery = e.target.value.trim();
                    if (searchQuery) {
                        performSearch(searchQuery);
                    }
                }
            });
        }
        
        // 搜索按钮
        const searchBtn = document.getElementById('memory-search-btn');
        if (searchBtn) {
            searchBtn.addEventListener('click', () => {
                const input = document.getElementById('memory-search-input');
                if (input) {
                    searchQuery = input.value.trim();
                    if (searchQuery) {
                        performSearch(searchQuery);
                    }
                }
            });
        }
        
        // 清除搜索按钮
        const clearBtn = document.getElementById('memory-search-clear');
        if (clearBtn) {
            clearBtn.addEventListener('click', () => {
                exitSearchMode();
            });
        }
        
        // 筛选器
        const userIdInput = document.getElementById('filter-userid');
        const agentIdInput = document.getElementById('filter-agentid');
        const filterBtn = document.getElementById('filter-apply-btn');
        
        if (userIdInput) {
            userIdInput.addEventListener('change', (e) => {
                currentFilter.userId = e.target.value.trim();
            });
        }
        
        if (agentIdInput) {
            agentIdInput.addEventListener('change', (e) => {
                currentFilter.agentId = e.target.value.trim();
            });
        }
        
        if (filterBtn) {
            filterBtn.addEventListener('click', () => {
                currentPage = 0;
                loadMemories();
            });
        }
        
        // 分页按钮
        const prevBtn = document.getElementById('page-prev');
        const nextBtn = document.getElementById('page-next');
        
        if (prevBtn) {
            prevBtn.addEventListener('click', () => {
                if (currentPage > 0) {
                    currentPage--;
                    loadMemories();
                }
            });
        }
        
        if (nextBtn) {
            nextBtn.addEventListener('click', () => {
                const maxPage = Math.ceil(totalCount / pageSize) - 1;
                if (currentPage < maxPage) {
                    currentPage++;
                    loadMemories();
                }
            });
        }
        
        // 创建记忆按钮
        const createBtn = document.getElementById('create-memory-btn');
        if (createBtn) {
            createBtn.addEventListener('click', showCreateModal);
        }
        
        // 创建记忆表单
        const createForm = document.getElementById('create-memory-form');
        if (createForm) {
            createForm.addEventListener('submit', handleCreateMemory);
        }
        
        // 关闭模态框
        const modalClose = document.getElementById('modal-close');
        if (modalClose) {
            modalClose.addEventListener('click', hideCreateModal);
        }
        
        const modalOverlay = document.getElementById('modal-overlay');
        if (modalOverlay) {
            modalOverlay.addEventListener('click', hideCreateModal);
        }
    }

    /**
     * 加载记忆列表
     */
    async function loadMemories() {
        showLoading(true);
        
        try {
            const params = new URLSearchParams();
            params.set('limit', pageSize.toString());
            params.set('offset', (currentPage * pageSize).toString());
            
            if (currentFilter.userId) {
                params.set('userId', currentFilter.userId);
            }
            if (currentFilter.agentId) {
                params.set('agentId', currentFilter.agentId);
            }
            
            const response = await fetch(`${API_BASE}/users/${currentFilter.userId || 'default'}/memories?${params}`);
            
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}: ${response.statusText}`);
            }
            
            const result = await response.json();
            
            // 兼容不同的响应格式
            if (Array.isArray(result)) {
                memories = result;
                totalCount = result.length;
            } else if (result.data) {
                memories = Array.isArray(result.data) ? result.data : result.data.memories || [];
                totalCount = result.data.total || result.total || memories.length;
            } else if (result.memories) {
                memories = result.memories;
                totalCount = result.total || memories.length;
            } else {
                memories = [];
                totalCount = 0;
            }
            
            renderMemoryTable();
            updatePagination();
            
        } catch (err) {
            console.error('[Memory] 加载记忆列表失败:', err);
            showError(`加载失败: ${err.message}`);
        } finally {
            showLoading(false);
        }
    }

    /**
     * 执行搜索
     * @param {string} query - 搜索关键词
     */
    async function performSearch(query) {
        showLoading(true);
        isSearchMode = true;
        
        try {
            const body = {
                query: query,
                topK: 20,
                threshold: 0.3,
            };
            
            if (currentFilter.userId) body.userId = currentFilter.userId;
            if (currentFilter.agentId) body.agentId = currentFilter.agentId;
            
            const response = await fetch(`${API_BASE}/memories/search`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body),
            });
            
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}: ${response.statusText}`);
            }
            
            const result = await response.json();
            
            // 兼容不同的响应格式
            if (result.data && result.data.memories) {
                memories = result.data.memories;
            } else if (result.memories) {
                memories = result.memories;
            } else if (Array.isArray(result)) {
                memories = result;
            } else {
                memories = [];
            }
            
            totalCount = memories.length;
            
            // 显示搜索模式UI
            showSearchModeUI(query);
            renderMemoryTable(true); // true = 搜索模式
            updatePagination();
            
        } catch (err) {
            console.error('[Memory] 搜索失败:', err);
            showError(`搜索失败: ${err.message}`);
        } finally {
            showLoading(false);
        }
    }

    /**
     * 退出搜索模式
     */
    function exitSearchMode() {
        isSearchMode = false;
        searchQuery = '';
        expandedRows.clear();
        
        const searchInput = document.getElementById('memory-search-input');
        if (searchInput) searchInput.value = '';
        
        const searchModeIndicator = document.getElementById('search-mode-indicator');
        if (searchModeIndicator) searchModeIndicator.style.display = 'none';
        
        loadMemories();
    }

    /**
     * 渲染记忆表格
     * @param {boolean} isSearch - 是否为搜索结果
     */
    function renderMemoryTable(isSearch = false) {
        const tbody = document.getElementById('memory-table-body');
        if (!tbody) return;
        
        if (memories.length === 0) {
            tbody.innerHTML = `
                <tr class="empty-row">
                    <td colspan="6" class="empty-cell">
                        <div class="empty-state">
                            <div class="empty-icon">📭</div>
                            <div class="empty-text">${isSearch ? '未找到匹配的记忆' : '暂无记忆数据'}</div>
                        </div>
                    </td>
                </tr>
            `;
            return;
        }
        
        let html = '';
        
        memories.forEach((memory, index) => {
            const id = memory.id || memory.memory_id || memory.memoryId || `mem_${index}`;
            const content = memory.content || memory.text || '';
            const userId = memory.userId || memory.user_id || '-';
            const agentId = memory.agentId || memory.agent_id || '-';
            const score = memory.score || memory.relevance || null;
            const entities = memory.entities || [];
            const timestamp = memory.timestamp || memory.createdAt || memory.created_at || memory.updatedAt || '';
            const isExpanded = expandedRows.has(id);
            
            // 高亮搜索关键词
            let displayContent = escapeHtml(content);
            if (isSearch && searchQuery) {
                displayContent = highlightText(displayContent, searchQuery);
            }
            
            // 截断长文本
            const truncatedContent = displayContent.length > 100 
                ? displayContent.substring(0, 100) + '...' 
                : displayContent;
            
            html += `
                <tr class="memory-row ${isExpanded ? 'expanded' : ''}" 
                    data-id="${escapeHtml(id)}" 
                    onclick="MemoryModule.toggleExpand('${escapeHtml(id)}')">
                    <td class="cell-id">
                        <span class="id-text" title="${escapeHtml(id)}">${escapeHtml(id.substring(0, 12))}...</span>
                    </td>
                    <td class="cell-content">
                        <div class="content-text">${isExpanded ? displayContent : truncatedContent}</div>
                    </td>
                    <td class="cell-user">${escapeHtml(userId)}</td>
                    <td class="cell-agent">${escapeHtml(agentId)}</td>
                    <td class="cell-score">${score !== null ? score.toFixed(3) : '-'}</td>
                    <td class="cell-actions">
                        <button class="btn-icon btn-delete" 
                                onclick="event.stopPropagation(); MemoryModule.deleteMemory('${escapeHtml(id)}')"
                                title="删除">
                            🗑️
                        </button>
                    </td>
                </tr>
            `;
            
            // 展开详情行
            if (isExpanded) {
                html += `
                    <tr class="detail-row">
                        <td colspan="6">
                            <div class="memory-detail">
                                <div class="detail-section">
                                    <h4>完整内容</h4>
                                    <div class="detail-content">${displayContent}</div>
                                </div>
                                ${entities.length > 0 ? `
                                    <div class="detail-section">
                                        <h4>实体</h4>
                                        <div class="entity-list">
                                            ${entities.map(e => `
                                                <span class="entity-tag">
                                                    <span class="entity-name">${escapeHtml(e.name || e.text || '')}</span>
                                                    <span class="entity-type">${escapeHtml(e.type || e.category || '')}</span>
                                                </span>
                                            `).join('')}
                                        </div>
                                    </div>
                                ` : ''}
                                <div class="detail-section">
                                    <h4>元数据</h4>
                                    <div class="detail-meta">
                                        <div class="meta-item">
                                            <span class="meta-key">ID:</span>
                                            <span class="meta-value">${escapeHtml(id)}</span>
                                        </div>
                                        <div class="meta-item">
                                            <span class="meta-key">用户:</span>
                                            <span class="meta-value">${escapeHtml(userId)}</span>
                                        </div>
                                        <div class="meta-item">
                                            <span class="meta-key">代理:</span>
                                            <span class="meta-value">${escapeHtml(agentId)}</span>
                                        </div>
                                        ${timestamp ? `
                                            <div class="meta-item">
                                                <span class="meta-key">时间:</span>
                                                <span class="meta-value">${formatTimestamp(timestamp)}</span>
                                            </div>
                                        ` : ''}
                                        ${score !== null ? `
                                            <div class="meta-item">
                                                <span class="meta-key">相关度:</span>
                                                <span class="meta-value">${(score * 100).toFixed(1)}%</span>
                                            </div>
                                        ` : ''}
                                    </div>
                                </div>
                            </div>
                        </td>
                    </tr>
                `;
            }
        });
        
        tbody.innerHTML = html;
    }

    /**
     * 切换行展开/折叠
     * @param {string} id - 记忆ID
     */
    function toggleExpand(id) {
        if (expandedRows.has(id)) {
            expandedRows.delete(id);
        } else {
            expandedRows.add(id);
        }
        renderMemoryTable(isSearchMode);
    }

    /**
     * 创建记忆
     * @param {Event} e - 表单提交事件
     */
    async function handleCreateMemory(e) {
        e.preventDefault();
        
        const messagesInput = document.getElementById('create-messages');
        const userIdInput = document.getElementById('create-userid');
        const agentIdInput = document.getElementById('create-agentid');
        const submitBtn = document.getElementById('create-submit-btn');
        
        if (!messagesInput) return;
        
        const messagesText = messagesInput.value.trim();
        if (!messagesText) {
            showError('请输入对话内容');
            return;
        }
        
        // 解析对话文本为消息数组
        const messages = parseMessages(messagesText);
        
        if (messages.length === 0) {
            showError('无法解析对话内容');
            return;
        }
        
        const body = {
            messages: messages,
        };
        
        const userId = userIdInput?.value?.trim();
        const agentId = agentIdInput?.value?.trim();
        
        if (userId) body.userId = userId;
        if (agentId) body.agentId = agentId;
        
        // 禁用按钮
        if (submitBtn) {
            submitBtn.disabled = true;
            submitBtn.textContent = '创建中...';
        }
        
        try {
            const response = await fetch(`${API_BASE}/memories`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body),
            });
            
            if (!response.ok) {
                const errorData = await response.json().catch(() => ({}));
                throw new Error(errorData.message || `HTTP ${response.status}`);
            }
            
            const result = await response.json();
            
            // 成功提示
            showSuccess('记忆创建成功');
            
            // 关闭模态框
            hideCreateModal();
            
            // 重新加载列表
            loadMemories();
            
        } catch (err) {
            console.error('[Memory] 创建记忆失败:', err);
            showError(`创建失败: ${err.message}`);
        } finally {
            if (submitBtn) {
                submitBtn.disabled = false;
                submitBtn.textContent = '创建';
            }
        }
    }

    /**
     * 删除记忆
     * @param {string} id - 记忆ID
     */
    async function deleteMemory(id) {
        if (!confirm(`确定要删除记忆 "${id}" 吗？此操作不可撤销。`)) {
            return;
        }
        
        try {
            const response = await fetch(`${API_BASE}/memories/${id}`, {
                method: 'DELETE',
            });
            
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}: ${response.statusText}`);
            }
            
            showSuccess('记忆已删除');
            
            // 从展开列表中移除
            expandedRows.delete(id);
            
            // 重新加载
            loadMemories();
            
        } catch (err) {
            console.error('[Memory] 删除记忆失败:', err);
            showError(`删除失败: ${err.message}`);
        }
    }

    /**
     * 显示创建模态框
     */
    function showCreateModal() {
        const modal = document.getElementById('create-modal');
        if (modal) {
            modal.classList.add('modal-visible');
            
            // 聚焦到文本输入框
            setTimeout(() => {
                const messagesInput = document.getElementById('create-messages');
                if (messagesInput) messagesInput.focus();
            }, 100);
        }
    }

    /**
     * 隐藏创建模态框
     */
    function hideCreateModal() {
        const modal = document.getElementById('create-modal');
        if (modal) {
            modal.classList.remove('modal-visible');
            
            // 清空表单
            const form = document.getElementById('create-memory-form');
            if (form) form.reset();
        }
    }

    /**
     * 显示搜索模式UI
     * @param {string} query - 搜索关键词
     */
    function showSearchModeUI(query) {
        const indicator = document.getElementById('search-mode-indicator');
        if (indicator) {
            indicator.style.display = 'flex';
            indicator.innerHTML = `
                <span class="search-info">搜索: "${escapeHtml(query)}"</span>
                <span class="search-count">找到 ${memories.length} 条结果</span>
            `;
        }
    }

    /**
     * 更新分页信息
     */
    function updatePagination() {
        const pageInfo = document.getElementById('page-info');
        const prevBtn = document.getElementById('page-prev');
        const nextBtn = document.getElementById('page-next');
        
        if (pageInfo) {
            const start = currentPage * pageSize + 1;
            const end = Math.min((currentPage + 1) * pageSize, totalCount);
            pageInfo.textContent = `${start}-${end} / ${totalCount}`;
        }
        
        if (prevBtn) {
            prevBtn.disabled = currentPage === 0;
        }
        
        if (nextBtn) {
            const maxPage = Math.ceil(totalCount / pageSize) - 1;
            nextBtn.disabled = currentPage >= maxPage;
        }
    }

    /**
     * 显示/隐藏加载状态
     * @param {boolean} show - 是否显示
     */
    function showLoading(show) {
        const spinner = document.getElementById('loading-spinner');
        if (spinner) {
            spinner.style.display = show ? 'flex' : 'none';
        }
    }

    /**
     * 解析对话文本为消息数组
     * 支持格式:
     *   user: 你好
     *   assistant: 你好！有什么可以帮你的？
     * 
     * @param {string} text - 对话文本
     * @returns {Array} 消息数组
     */
    function parseMessages(text) {
        const lines = text.split('\n').filter(line => line.trim());
        const messages = [];
        
        for (const line of lines) {
            const trimmed = line.trim();
            
            // 尝试匹配 "role: content" 格式
            const match = trimmed.match(/^(user|assistant|system)\s*[:：]\s*(.+)/i);
            if (match) {
                messages.push({
                    role: match[1].toLowerCase(),
                    content: match[2].trim(),
                });
                continue;
            }
            
            // 没有角色前缀，交替分配 user/assistant
            if (messages.length > 0 && messages[messages.length - 1].role === 'user') {
                messages.push({ role: 'assistant', content: trimmed });
            } else {
                messages.push({ role: 'user', content: trimmed });
            }
        }
        
        return messages;
    }

    /**
     * 高亮搜索关键词
     * @param {string} text - 文本
     * @param {string} query - 关键词
     * @returns {string} 高亮后的HTML
     */
    function highlightText(text, query) {
        if (!query) return text;
        
        // 转义正则特殊字符
        const escaped = query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        const regex = new RegExp(`(${escaped})`, 'gi');
        
        return text.replace(regex, '<mark class="search-highlight">$1</mark>');
    }

    /**
     * 转义HTML特殊字符
     * @param {string} str - 原始字符串
     * @returns {string} 转义后的字符串
     */
    function escapeHtml(str) {
        if (!str) return '';
        const div = document.createElement('div');
        div.textContent = str;
        return div.innerHTML;
    }

    /**
     * 格式化时间戳
     * @param {string|number} timestamp - 时间戳
     * @returns {string} 格式化后的时间字符串
     */
    function formatTimestamp(timestamp) {
        try {
            let date;
            if (typeof timestamp === 'number') {
                date = new Date(timestamp);
            } else if (typeof timestamp === 'string') {
                date = new Date(timestamp);
            } else {
                return String(timestamp);
            }
            
            if (isNaN(date.getTime())) {
                return String(timestamp);
            }
            
            return date.toLocaleString('zh-CN', {
                year: 'numeric',
                month: '2-digit',
                day: '2-digit',
                hour: '2-digit',
                minute: '2-digit',
                second: '2-digit',
            });
        } catch (err) {
            return String(timestamp);
        }
    }

    /**
     * 防抖函数
     * @param {Function} func - 要防抖的函数
     * @param {number} wait - 等待时间 (ms)
     * @returns {Function} 防抖后的函数
     */
    function debounce(func, wait) {
        let timeout;
        return function executedFunction(...args) {
            const later = () => {
                clearTimeout(timeout);
                func(...args);
            };
            clearTimeout(timeout);
            timeout = setTimeout(later, wait);
        };
    }

    /**
     * 显示成功提示
     * @param {string} message - 消息
     */
    function showSuccess(message) {
        const toast = document.createElement('div');
        toast.className = 'toast toast-success';
        toast.textContent = message;
        document.body.appendChild(toast);
        
        setTimeout(() => {
            toast.classList.add('toast-fade-out');
            setTimeout(() => toast.remove(), 300);
        }, 3000);
    }

    /**
     * 显示错误提示
     * @param {string} message - 消息
     */
    function showError(message) {
        const toast = document.createElement('div');
        toast.className = 'toast toast-error';
        toast.textContent = message;
        document.body.appendChild(toast);
        
        setTimeout(() => {
            toast.classList.add('toast-fade-out');
            setTimeout(() => toast.remove(), 300);
        }, 4000);
    }

    /**
     * 销毁模块
     */
    function destroy() {
        expandedRows.clear();
        memories = [];
    }

    // ===== 公开 API =====
    return {
        init,
        loadMemories,
        performSearch,
        exitSearchMode,
        toggleExpand,
        deleteMemory,
        showCreateModal,
        hideCreateModal,
        destroy,
    };
})();

// 导出到全局
window.MemoryModule = MemoryModule;
