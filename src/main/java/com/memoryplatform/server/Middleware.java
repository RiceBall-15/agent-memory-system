package com.memoryplatform.server;

import com.sun.net.httpserver.HttpExchange;

/**
 * 中间件接口
 * <p>
 * 中间件用于在请求处理之前或之后执行特定逻辑。
 * 例如：日志记录、CORS处理、认证验证等。
 * </p>
 * <p>
 * 中间件链的工作流程：
 * <pre>
 * 请求 -> 中间件1 -> 中间件2 -> ... -> 处理器
 * </pre>
 * </p>
 * 
 * @author MemoryPlatform
 * @version 1.0
 */
public interface Middleware {
    
    /**
     * 处理中间件逻辑
     * <p>
     * 调用 {@code next.run()} 继续执行下一个中间件或处理器。
     * 如果返回false，请求处理将终止。
     * </p>
     * 
     * @param exchange HTTP交换对象
     * @param next 下一个中间件或处理器的执行链
     * @return true表示继续执行，false表示终止
     */
    boolean handle(HttpExchange exchange, Runnable next);
    
    /**
     * 获取中间件名称（用于日志）
     * 
     * @return 中间件名称
     */
    default String getName() {
        return this.getClass().getSimpleName();
    }
    
    /**
     * 获取中间件优先级（数值越小越先执行）
     * 
     * @return 优先级数值
     */
    default int getPriority() {
        return 100;
    }
}
