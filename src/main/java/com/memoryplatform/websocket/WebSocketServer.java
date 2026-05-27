package com.memoryplatform.websocket;

// This file has been replaced by MemoryWebSocketHandler.java (Spring WebSocket).
// The old JDK HttpServer-based implementation has been removed.
//
// Migration guide:
// - WebSocketServer -> MemoryWebSocketHandler (Spring WebSocket handler)
// - webSocketServer.broadcast(msg) -> webSocketHandler.broadcast(msg)
// - webSocketServer.isRunning() -> webSocketHandler.hasActiveConnections()
// - webSocketServer.setWebSocketServer(handler) -> webSocketHandler.setWebSocketHandler(handler)
//
// @deprecated Use {@link MemoryWebSocketHandler} instead.
@Deprecated
public class WebSocketServer {
    private WebSocketServer() {
        throw new UnsupportedOperationException(
            "WebSocketServer is deprecated. Use MemoryWebSocketHandler instead.");
    }
}
