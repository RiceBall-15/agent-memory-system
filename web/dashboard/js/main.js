/**
 * Dashboard主入口 - 初始化所有模块
 * 
 * @module main
 */

document.addEventListener('DOMContentLoaded', () => {
    console.log('[Dashboard] 初始化开始');
    
    // 初始化WebSocket模块
    WebSocketModule.init({
        statusElementId: 'ws-status',
    });
    
    // 初始化系统状态模块
    SystemModule.init();
    
    // 初始化记忆管理模块
    MemoryModule.init();
    
    // 订阅WebSocket实时更新
    setupRealtimeUpdates();
    
    console.log('[Dashboard] 初始化完成');
});

/**
 * 设置实时数据订阅
 */
function setupRealtimeUpdates() {
    // 监听指标更新
    WebSocketModule.subscribe('metrics', (data) => {
        console.log('[Dashboard] 收到指标更新:', data);
        // 指标更新会通过SystemModule自动处理
    });
    
    // 监听健康状态更新
    WebSocketModule.subscribe('health', (data) => {
        console.log('[Dashboard] 收到健康状态更新:', data);
        SystemModule.refresh();
    });
    
    // 监听连接状态变化
    WebSocketModule.subscribe('connection', (data) => {
        console.log('[Dashboard] 连接状态变化:', data.state);
    });
    
    // 监听错误
    WebSocketModule.subscribe('error', (data) => {
        console.error('[Dashboard] WebSocket错误:', data.message);
    });
}
