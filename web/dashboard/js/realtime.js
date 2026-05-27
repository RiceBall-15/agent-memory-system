/**
 * WebSocket实时连接管理模块
 *
 * 功能:
 * - 自动重连 (指数退避: 1s → 2s → 4s → 8s → 16s → 30s)
 * - 消息队列 (断连时缓存消息，重连后发送)
 * - 心跳维持 (每30秒ping，10秒超时判定断连)
 * - 连接状态指示器 (connected / disconnected / reconnecting)
 * - 订阅模式 (按topic分发消息)
 *
 * @module realtime
 */
const RealtimeModule = (() => {
    'use strict';

    // ==================== 配置 ====================

    /** WebSocket URL 路径 */
    const WS_PATH = '/ws/dashboard';

    /** 心跳间隔 (毫秒) */
    const HEARTBEAT_INTERVAL = 30000;

    /** 心跳超时 (毫秒) */
    const HEARTBEAT_TIMEOUT = 10000;

    /** 最大重连延迟 (毫秒) */
    const MAX_RECONNECT_DELAY = 30000;

    /** 最小重连延迟 (毫秒) */
    const MIN_RECONNECT_DELAY = 1000;

    /** 最大消息队列长度 */
    const MAX_QUEUE_SIZE = 100;

    // ==================== 状态 ====================

    /** WebSocket实例 */
    let ws = null;

    /** 连接状态: 'disconnected' | 'connecting' | 'connected' | 'reconnecting' */
    let state = 'disconnected';

    /** 当前重连延迟 (用于指数退避) */
    let reconnectDelay = MIN_RECONNECT_DELAY;

    /** 重连定时器 */
    let reconnectTimer = null;

    /** 心跳定时器 */
    let heartbeatTimer = null;

    /** 心跳超时定时器 */
    let heartbeatTimeoutTimer = null;

    /** 待发送消息队列 */
    let messageQueue = [];

    /** 订阅者 {topic: [callback1, callback2, ...]} */
    let subscribers = {};

    /** 全局消息回调 */
    let onMessageCallback = null;

    /** 连接状态变更回调 */
    let onStateChangeCallback = null;

    /** 是否已初始化 */
    let initialized = false;

    /** 是否正在主动关闭 (区分正常断开和异常断开) */
    let intentionalClose = false;

    // ==================== 连接管理 ====================

    /**
     * 建立WebSocket连接
     */
    function connect() {
        if (ws && (ws.readyState === WebSocket.CONNECTING || ws.readyState === WebSocket.OPEN)) {
            return; // 已在连接中或已连接
        }

        intentionalClose = false;
        setState('connecting');

        try {
            const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
            const url = `${protocol}//${window.location.host}${WS_PATH}`;

            console.log(`[Realtime] 正在连接: ${url}`);
            ws = new WebSocket(url);

            ws.onopen = handleOpen;
            ws.onmessage = handleMessage;
            ws.onclose = handleClose;
            ws.onerror = handleError;

        } catch (error) {
            console.error('[Realtime] 连接创建失败:', error.message);
            scheduleReconnect();
        }
    }

    /**
     * 主动断开连接
     */
    function disconnect() {
        intentionalClose = true;
        clearTimers();
        if (ws) {
            ws.close(1000, 'Client disconnect');
            ws = null;
        }
        setState('disconnected');
        console.log('[Realtime] 已断开连接');
    }

    /**
     * 处理连接打开
     */
    function handleOpen() {
        console.log('[Realtime] WebSocket已连接');
        reconnectDelay = MIN_RECONNECT_DELAY; // 重置退避
        setState('connected');
        startHeartbeat();
        flushQueue();
    }

    /**
     * 处理收到消息
     * @param {MessageEvent} event
     */
    function handleMessage(event) {
        try {
            const data = JSON.parse(event.data);

            // 心跳响应，重置超时
            if (data.type === 'pong') {
                clearTimeout(heartbeatTimeoutTimer);
                return;
            }

            // 分发消息到订阅者
            dispatchMessage(data);

        } catch (error) {
            console.warn('[Realtime] 消息解析失败:', error.message);
        }
    }

    /**
     * 处理连接关闭
     * @param {CloseEvent} event
     */
    function handleClose(event) {
        console.log(`[Realtime] 连接关闭: code=${event.code} reason=${event.reason}`);
        ws = null;
        stopHeartbeat();

        if (!intentionalClose) {
            scheduleReconnect();
        } else {
            setState('disconnected');
        }
    }

    /**
     * 处理连接错误
     * @param {Event} error
     */
    function handleError(error) {
        console.warn('[Realtime] WebSocket错误:', error);
        // handleClose会被触发，由handleClose处理重连
    }

    // ==================== 重连机制 ====================

    /**
     * 计划重连 (指数退避)
     */
    function scheduleReconnect() {
        if (intentionalClose) return;

        setState('reconnecting');

        console.log(`[Realtime] ${Math.round(reconnectDelay / 1000)}秒后重连...`);
        reconnectTimer = setTimeout(() => {
            connect();
        }, reconnectDelay);

        // 指数退避
        reconnectDelay = Math.min(reconnectDelay * 2, MAX_RECONNECT_DELAY);
    }

    // ==================== 心跳 ====================

    /**
     * 启动心跳
     */
    function startHeartbeat() {
        stopHeartbeat();
        heartbeatTimer = setInterval(() => {
            if (ws && ws.readyState === WebSocket.OPEN) {
                send({ type: 'ping' });

                // 设置超时检测
                heartbeatTimeoutTimer = setTimeout(() => {
                    console.warn('[Realtime] 心跳超时，强制重连');
                    ws.close(4000, 'Heartbeat timeout');
                }, HEARTBEAT_TIMEOUT);
            }
        }, HEARTBEAT_INTERVAL);
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

    // ==================== 消息队列 ====================

    /**
     * 发送消息
     * @param {Object} data - 消息数据
     * @returns {boolean} 是否发送成功
     */
    function send(data) {
        if (ws && ws.readyState === WebSocket.OPEN) {
            try {
                ws.send(JSON.stringify(data));
                return true;
            } catch (error) {
                console.warn('[Realtime] 发送失败:', error.message);
                addToQueue(data);
                return false;
            }
        } else {
            addToQueue(data);
            return false;
        }
    }

    /**
     * 将消息加入队列
     * @param {Object} data - 消息数据
     */
    function addToQueue(data) {
        if (messageQueue.length >= MAX_QUEUE_SIZE) {
            messageQueue.shift(); // 丢弃最旧的消息
        }
        messageQueue.push(data);
    }

    /**
     * 发送队列中的所有消息
     */
    function flushQueue() {
        let sent = 0;
        while (messageQueue.length > 0) {
            const data = messageQueue[0];
            if (ws && ws.readyState === WebSocket.OPEN) {
                try {
                    ws.send(JSON.stringify(data));
                    messageQueue.shift();
                    sent++;
                } catch {
                    break;
                }
            } else {
                break;
            }
        }
        if (sent > 0) {
            console.log(`[Realtime] 发送了 ${sent} 条队列消息`);
        }
    }

    // ==================== 订阅/分发 ====================

    /**
     * 订阅指定topic的消息
     * @param {string} topic - 消息主题 (如 'metrics', 'health')
     * @param {Function} callback - 回调函数 (data) => void
     * @returns {Function} 取消订阅函数
     */
    function subscribe(topic, callback) {
        if (!subscribers[topic]) {
            subscribers[topic] = [];
        }
        subscribers[topic].push(callback);

        console.log(`[Realtime] 订阅: ${topic}`);

        // 返回取消订阅函数
        return () => {
            unsubscribe(topic, callback);
        };
    }

    /**
     * 取消订阅
     * @param {string} topic
     * @param {Function} callback
     */
    function unsubscribe(topic, callback) {
        if (subscribers[topic]) {
            subscribers[topic] = subscribers[topic].filter(cb => cb !== callback);
            if (subscribers[topic].length === 0) {
                delete subscribers[topic];
            }
        }
    }

    /**
     * 分发消息到订阅者
     * @param {Object} data - 消息数据
     */
    function dispatchMessage(data) {
        // 全局回调
        if (onMessageCallback) {
            try {
                onMessageCallback(data);
            } catch (e) {
                console.warn('[Realtime] 全局消息回调异常:', e);
            }
        }

        // 按topic分发
        const topic = data.type || 'unknown';
        if (subscribers[topic]) {
            subscribers[topic].forEach(callback => {
                try {
                    callback(data.payload || data);
                } catch (e) {
                    console.warn(`[Realtime] 订阅者回调异常 [${topic}]:`, e);
                }
            });
        }

        // 通配符订阅 '*'
        if (subscribers['*']) {
            subscribers['*'].forEach(callback => {
                try {
                    callback(data);
                } catch (e) {
                    console.warn('[Realtime] 通配符订阅者回调异常:', e);
                }
            });
        }
    }

    // ==================== 状态管理 ====================

    /**
     * 设置连接状态
     * @param {string} newState - 新状态
     */
    function setState(newState) {
        if (state === newState) return;
        const oldState = state;
        state = newState;
        console.log(`[Realtime] 状态变更: ${oldState} → ${newState}`);

        if (onStateChangeCallback) {
            try {
                onStateChangeCallback(newState, oldState);
            } catch (e) {
                console.warn('[Realtime] 状态回调异常:', e);
            }
        }

        updateStatusIndicator(newState);
    }

    /**
     * 更新连接状态指示器UI
     * @param {string} status - 连接状态
     */
    function updateStatusIndicator(status) {
        // 顶部导航栏状态
        const wsStatusEl = document.getElementById('ws-status');
        if (wsStatusEl) {
            const configs = {
                connected: {
                    class: 'ws-status ws-status-connected',
                    dotStyle: 'background:#22c55e',
                    text: '🟢 已连接',
                },
                disconnected: {
                    class: 'ws-status ws-status-disconnected',
                    dotStyle: 'background:#e74c3c',
                    text: '🔴 未连接',
                },
                connecting: {
                    class: 'ws-status ws-status-connecting',
                    dotStyle: 'background:#f59e0b',
                    text: '🟡 连接中...',
                },
                reconnecting: {
                    class: 'ws-status ws-status-reconnecting',
                    dotStyle: 'background:#f59e0b',
                    text: '🟡 重连中...',
                },
            };

            const config = configs[status] || configs.disconnected;
            wsStatusEl.className = config.class;
            wsStatusEl.innerHTML = `
                <span class="ws-status-dot" style="${config.dotStyle}"></span>
                <span class="ws-status-text">${config.text}</span>
            `;
        }

        // 侧边栏状态指示器 (兼容app.js)
        const sideIndicator = document.getElementById('sidebar-status-indicator');
        if (sideIndicator) {
            sideIndicator.className = `status-indicator ${status === 'connected' ? 'connected' : 'disconnected'}`;
        }

        const sideText = document.getElementById('sidebar-status-text');
        if (sideText) {
            sideText.textContent = status === 'connected' ? '系统运行中' : '连接断开';
        }
    }

    // ==================== 工具函数 ====================

    function clearTimers() {
        if (reconnectTimer) {
            clearTimeout(reconnectTimer);
            reconnectTimer = null;
        }
        stopHeartbeat();
    }

    // ==================== 初始化 ====================

    function init() {
        if (initialized) return;
        initialized = true;

        console.log('[Realtime] 初始化WebSocket实时连接模块');

        // 首次连接
        connect();

        // 监听页面可见性变化 (切换tab时重连)
        document.addEventListener('visibilitychange', () => {
            if (!document.hidden && state === 'disconnected' && !intentionalClose) {
                console.log('[Realtime] 页面恢复可见，尝试重连');
                connect();
            }
        });
    }

    function destroy() {
        disconnect();
        subscribers = {};
        messageQueue = [];
        onMessageCallback = null;
        onStateChangeCallback = null;
        initialized = false;
        console.log('[Realtime] 已销毁');
    }

    // ==================== 公共API ====================

    return {
        init,
        destroy,
        connect,
        disconnect,
        send,
        subscribe,
        unsubscribe,

        /** 设置全局消息回调 */
        onMessage(callback) { onMessageCallback = callback; },

        /** 设置连接状态变更回调 */
        onStateChange(callback) { onStateChangeCallback = callback; },

        /** 获取当前连接状态 */
        getState() { return state; },

        /** 获取队列长度 */
        getQueueSize() { return messageQueue.length; },

        /** 获取订阅者统计 */
        getSubscriberCount() {
            return Object.values(subscribers).reduce((sum, arr) => sum + arr.length, 0);
        },
    };
})();

if (typeof window !== 'undefined') {
    window.RealtimeModule = RealtimeModule;
}
