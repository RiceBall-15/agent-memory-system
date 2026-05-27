/**
 * WebSocket实时更新模块
 * 
 * 功能:
 * - WebSocket连接管理 (自动重连)
 * - 订阅指标更新
 * - 接收实时数据并更新UI
 * - 连接状态指示
 * 
 * @module websocket
 */

const WebSocketModule = (() => {
    // ===== 配置 =====
    const CONFIG = {
        wsUrl: `ws://${window.location.host}/ws`,
        reconnectInterval: 3000,      // 重连间隔 (ms)
        maxReconnectAttempts: 10,     // 最大重连次数
        heartbeatInterval: 30000,     // 心跳间隔 (ms)
        heartbeatTimeout: 10000,      // 心跳超时 (ms)
    };

    // ===== 状态 =====
    let ws = null;                    // WebSocket 实例
    let reconnectAttempts = 0;        // 当前重连次数
    let reconnectTimer = null;        // 重连定时器
    let heartbeatTimer = null;        // 心跳定时器
    let heartbeatTimeoutTimer = null; // 心跳超时定时器
    let isConnected = false;          // 连接状态
    let subscriptions = new Map();    // 订阅者 {topic -> callback[]}
    let statusIndicator = null;       // 状态指示器元素

    /**
     * 连接状态枚举
     */
    const ConnectionState = {
        DISCONNECTED: 'disconnected',
        CONNECTING: 'connecting',
        CONNECTED: 'connected',
        RECONNECTING: 'reconnecting',
    };

    /**
     * 初始化 WebSocket 模块
     * @param {Object} options - 配置选项
     */
    function init(options = {}) {
        if (options.wsUrl) CONFIG.wsUrl = options.wsUrl;
        if (options.statusElementId) {
            statusIndicator = document.getElementById(options.statusElementId);
        }
        
        console.log('[WebSocket] 初始化模块');
        connect();
        
        // 页面卸载时关闭连接
        window.addEventListener('beforeunload', () => {
            disconnect();
        });
    }

    /**
     * 建立 WebSocket 连接
     */
    function connect() {
        if (ws && (ws.readyState === WebSocket.CONNECTING || ws.readyState === WebSocket.OPEN)) {
            console.log('[WebSocket] 已有活跃连接，跳过');
            return;
        }

        updateConnectionState(ConnectionState.CONNECTING);
        
        try {
            ws = new WebSocket(CONFIG.wsUrl);
            
            ws.onopen = handleOpen;
            ws.onmessage = handleMessage;
            ws.onerror = handleError;
            ws.onclose = handleClose;
            
            console.log('[WebSocket] 正在连接:', CONFIG.wsUrl);
        } catch (err) {
            console.error('[WebSocket] 创建连接失败:', err);
            scheduleReconnect();
        }
    }

    /**
     * 连接成功处理
     */
    function handleOpen() {
        console.log('[WebSocket] 连接成功');
        isConnected = true;
        reconnectAttempts = 0;
        
        updateConnectionState(ConnectionState.CONNECTED);
        startHeartbeat();
        
        // 通知所有订阅者连接已建立
        emit('connection', { state: 'connected' });
    }

    /**
     * 接收消息处理
     * @param {MessageEvent} event 
     */
    function handleMessage(event) {
        try {
            const message = JSON.parse(event.data);
            
            // 处理心跳响应
            if (message.type === 'pong') {
                clearTimeout(heartbeatTimeoutTimer);
                return;
            }
            
            // 处理指标更新
            if (message.type === 'metrics_update') {
                emit('metrics', message.data);
                return;
            }
            
            // 处理健康状态更新
            if (message.type === 'health_update') {
                emit('health', message.data);
                return;
            }
            
            // 处理写入队列更新
            if (message.type === 'queue_update') {
                emit('queue', message.data);
                return;
            }
            
            // 通用消息分发
            if (message.topic) {
                emit(message.topic, message.data);
            }
            
        } catch (err) {
            console.error('[WebSocket] 消息解析失败:', err);
        }
    }

    /**
     * 连接错误处理
     * @param {Event} event 
     */
    function handleError(event) {
        console.error('[WebSocket] 连接错误:', event);
        emit('error', { message: 'WebSocket连接错误' });
    }

    /**
     * 连接关闭处理
     * @param {CloseEvent} event 
     */
    function handleClose(event) {
        console.log('[WebSocket] 连接关闭, code:', event.code, 'reason:', event.reason);
        isConnected = false;
        stopHeartbeat();
        
        updateConnectionState(ConnectionState.DISCONNECTED);
        
        // 正常关闭 (1000) 不重连
        if (event.code === 1000) {
            console.log('[WebSocket] 正常关闭');
            return;
        }
        
        // 安排重连
        scheduleReconnect();
    }

    /**
     * 安排重连
     */
    function scheduleReconnect() {
        if (reconnectAttempts >= CONFIG.maxReconnectAttempts) {
            console.error('[WebSocket] 达到最大重连次数，停止重连');
            updateConnectionState(ConnectionState.DISCONNECTED);
            emit('error', { message: '连接失败，已达最大重连次数' });
            return;
        }

        reconnectAttempts++;
        updateConnectionState(ConnectionState.RECONNECTING);
        
        const delay = CONFIG.reconnectInterval * Math.min(reconnectAttempts, 5);
        console.log(`[WebSocket] ${delay}ms 后进行第 ${reconnectAttempts} 次重连`);
        
        reconnectTimer = setTimeout(() => {
            connect();
        }, delay);
    }

    /**
     * 关闭连接
     */
    function disconnect() {
        if (reconnectTimer) {
            clearTimeout(reconnectTimer);
            reconnectTimer = null;
        }
        stopHeartbeat();
        
        if (ws) {
            ws.close(1000, '主动断开');
            ws = null;
        }
        
        isConnected = false;
        updateConnectionState(ConnectionState.DISCONNECTED);
        console.log('[WebSocket] 已主动断开连接');
    }

    /**
     * 发送消息
     * @param {string} topic - 主题
     * @param {Object} data - 数据
     */
    function send(topic, data = {}) {
        if (!ws || ws.readyState !== WebSocket.OPEN) {
            console.warn('[WebSocket] 连接未就绪，消息未发送');
            return false;
        }
        
        try {
            ws.send(JSON.stringify({ topic, data, timestamp: Date.now() }));
            return true;
        } catch (err) {
            console.error('[WebSocket] 发送消息失败:', err);
            return false;
        }
    }

    /**
     * 订阅主题
     * @param {string} topic - 主题
     * @param {Function} callback - 回调函数
     * @returns {Function} 取消订阅函数
     */
    function subscribe(topic, callback) {
        if (!subscriptions.has(topic)) {
            subscriptions.set(topic, []);
        }
        subscriptions.get(topic).push(callback);
        
        console.log(`[WebSocket] 已订阅主题: ${topic}`);
        
        // 返回取消订阅函数
        return () => unsubscribe(topic, callback);
    }

    /**
     * 取消订阅
     * @param {string} topic - 主题
     * @param {Function} callback - 回调函数
     */
    function unsubscribe(topic, callback) {
        if (!subscriptions.has(topic)) return;
        
        const callbacks = subscriptions.get(topic);
        const index = callbacks.indexOf(callback);
        if (index !== -1) {
            callbacks.splice(index, 1);
        }
        
        if (callbacks.length === 0) {
            subscriptions.delete(topic);
        }
    }

    /**
     * 触发事件
     * @param {string} topic - 主题
     * @param {*} data - 数据
     */
    function emit(topic, data) {
        if (!subscriptions.has(topic)) return;
        
        const callbacks = subscriptions.get(topic);
        callbacks.forEach(callback => {
            try {
                callback(data);
            } catch (err) {
                console.error(`[WebSocket] 主题 "${topic}" 回调执行失败:`, err);
            }
        });
    }

    /**
     * 启动心跳
     */
    function startHeartbeat() {
        heartbeatTimer = setInterval(() => {
            if (ws && ws.readyState === WebSocket.OPEN) {
                send('ping');
                
                // 设置心跳超时检测
                heartbeatTimeoutTimer = setTimeout(() => {
                    console.warn('[WebSocket] 心跳超时，准备重连');
                    ws.close();
                }, CONFIG.heartbeatTimeout);
            }
        }, CONFIG.heartbeatInterval);
    }

    /**
     * 停止心跳
     */
    function stopHeartbeat() {
        if (heartbeatTimer) {
            clearInterval(heartbeatTimer);
            heartbeatTimer = null;
        }
        if (heartbeatTimeoutTimer) {
            clearTimeout(heartbeatTimeoutTimer);
            heartbeatTimeoutTimer = null;
        }
    }

    /**
     * 更新连接状态指示器
     * @param {string} state - 连接状态
     */
    function updateConnectionState(state) {
        if (!statusIndicator) return;
        
        const stateMap = {
            [ConnectionState.DISCONNECTED]: { text: '已断开', color: '#e74c3c', icon: '⭕' },
            [ConnectionState.CONNECTING]: { text: '连接中', color: '#f39c12', icon: '🔄' },
            [ConnectionState.CONNECTED]: { text: '已连接', color: '#2ecc71', icon: '🟢' },
            [ConnectionState.RECONNECTING]: { text: '重连中', color: '#f39c12', icon: '🔄' },
        };
        
        const info = stateMap[state] || stateMap[ConnectionState.DISCONNECTED];
        
        statusIndicator.innerHTML = `
            <span class="ws-status-dot" style="background:${info.color}"></span>
            <span class="ws-status-text">${info.icon} ${info.text}</span>
        `;
        statusIndicator.className = `ws-status ws-status-${state}`;
    }

    /**
     * 获取当前连接状态
     * @returns {Object} 状态信息
     */
    function getStatus() {
        return {
            connected: isConnected,
            readyState: ws ? ws.readyState : WebSocket.CLOSED,
            reconnectAttempts,
            subscriptions: Array.from(subscriptions.keys()),
        };
    }

    // ===== 公开 API =====
    return {
        init,
        connect,
        disconnect,
        send,
        subscribe,
        unsubscribe,
        getStatus,
        ConnectionState,
    };
})();

// 导出到全局
window.WebSocketModule = WebSocketModule;
