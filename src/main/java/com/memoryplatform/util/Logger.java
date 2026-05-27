package com.memoryplatform.util;

import java.io.PrintStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 日志工具类 - 替代System.out.println提供结构化日志输出
 * <p>
 * 支持三种日志级别：
 * <ul>
 *   <li>{@code INFO}  - 常规信息，输出到System.out</li>
 *   <li>{@code WARN}  - 警告信息，输出到System.out</li>
 *   <li>{@code ERROR} - 错误信息，输出到System.err</li>
 * </ul>
 * </p>
 *
 * <h3>日志格式</h3>
 * <pre>
 * [2026-05-27 10:30:45] [INFO] 消息内容
 * [2026-05-27 10:30:45] [WARN] 警告内容
 * [2026-05-27 10:30:45] [ERROR] 错误内容
 * </pre>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 基本用法
 * Logger.info("服务启动成功");
 * Logger.warn("连接池使用率超过80%");
 * Logger.error("数据库连接失败: " + e.getMessage());
 *
 * // 带类名前缀
 * Logger.info(MemoryHandler.class, "请求处理完成");
 * Logger.error(MemoryHandler.class, "处理异常: {}", e.getMessage());
 * }</pre>
 *
 * @author MemoryPlatform
 * @version 1.0
 * @since 1.0
 */
public final class Logger {

    /** 日期时间格式: yyyy-MM-dd HH:mm:ss */
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** INFO级别输出到System.out */
    private static final PrintStream INFO_STREAM = System.out;

    /** WARN级别输出到System.out */
    private static final PrintStream WARN_STREAM = System.out;

    /** ERROR级别输出到System.err */
    private static final PrintStream ERROR_STREAM = System.err;

    /**
     * 日志级别枚举
     */
    public enum Level {
        INFO("INFO"),
        WARN("WARN"),
        ERROR("ERROR");

        private final String label;

        Level(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    // 私有构造，防止实例化
    private Logger() {
    }

    // ==================== INFO 级别 ====================

    /**
     * 输出INFO级别日志
     *
     * @param message 日志消息
     */
    public static void info(String message) {
        log(Level.INFO, null, message);
    }

    /**
     * 输出INFO级别日志（带类名前缀）
     *
     * @param clazz   调用类
     * @param message 日志消息
     */
    public static void info(Class<?> clazz, String message) {
        log(Level.INFO, clazz, message);
    }

    /**
     * 输出INFO级别日志（带类名前缀，支持占位符格式化）
     * <p>
     * 使用 {@code {}} 作为占位符，例如:
     * <pre>{@code
     * Logger.info(MemoryHandler.class, "提取到 {} 条记忆, userId={}", count, userId);
     * }</pre>
     * </p>
     *
     * @param clazz   调用类
     * @param message 日志消息模板
     * @param args    占位符参数
     */
    public static void info(Class<?> clazz, String message, Object... args) {
        log(Level.INFO, clazz, formatMessage(message, args));
    }

    // ==================== WARN 级别 ====================

    /**
     * 输出WARN级别日志
     *
     * @param message 日志消息
     */
    public static void warn(String message) {
        log(Level.WARN, null, message);
    }

    /**
     * 输出WARN级别日志（带类名前缀）
     *
     * @param clazz   调用类
     * @param message 日志消息
     */
    public static void warn(Class<?> clazz, String message) {
        log(Level.WARN, clazz, message);
    }

    /**
     * 输出WARN级别日志（带类名前缀，支持占位符格式化）
     *
     * @param clazz   调用类
     * @param message 日志消息模板
     * @param args    占位符参数
     */
    public static void warn(Class<?> clazz, String message, Object... args) {
        log(Level.WARN, clazz, formatMessage(message, args));
    }

    // ==================== ERROR 级别 ====================

    /**
     * 输出ERROR级别日志
     *
     * @param message 错误消息
     */
    public static void error(String message) {
        log(Level.ERROR, null, message);
    }

    /**
     * 输出ERROR级别日志（带类名前缀）
     *
     * @param clazz   调用类
     * @param message 错误消息
     */
    public static void error(Class<?> clazz, String message) {
        log(Level.ERROR, clazz, message);
    }

    /**
     * 输出ERROR级别日志（带类名前缀，支持占位符格式化）
     *
     * @param clazz   调用类
     * @param message 错误消息模板
     * @param args    占位符参数
     */
    public static void error(Class<?> clazz, String message, Object... args) {
        log(Level.ERROR, clazz, formatMessage(message, args));
    }

    /**
     * 输出ERROR级别日志（带异常堆栈）
     *
     * @param clazz   调用类
     * @param message 错误消息
     * @param t       异常对象
     */
    public static void error(Class<?> clazz, String message, Throwable t) {
        log(Level.ERROR, clazz, message);
        if (t != null) {
            PrintStream stream = ERROR_STREAM;
            t.printStackTrace(stream);
        }
    }

    // ==================== 通用方法 ====================

    /**
     * 输出指定级别的日志
     *
     * @param level   日志级别
     * @param message 日志消息
     */
    public static void log(Level level, String message) {
        log(level, null, message);
    }

    /**
     * 输出指定级别的日志（带类名前缀）
     *
     * @param level   日志级别
     * @param clazz   调用类
     * @param message 日志消息
     */
    public static void log(Level level, Class<?> clazz, String message) {
        String timestamp = LocalDateTime.now().format(FORMATTER);
        String tag = clazz != null ? clazz.getSimpleName() : "Application";
        String formatted = String.format("[%s] [%s] [%s] %s",
                timestamp, level.getLabel(), tag, message);

        PrintStream stream;
        switch (level) {
            case ERROR:
                stream = ERROR_STREAM;
                break;
            case WARN:
                stream = WARN_STREAM;
                break;
            default:
                stream = INFO_STREAM;
                break;
        }

        stream.println(formatted);
    }

    // ==================== 工具方法 ====================

    /**
     * 格式化消息，将 {} 占位符替换为参数值
     *
     * @param template 消息模板
     * @param args     参数列表
     * @return 格式化后的消息
     */
    private static String formatMessage(String template, Object... args) {
        if (template == null) {
            return "null";
        }
        if (args == null || args.length == 0) {
            return template;
        }

        StringBuilder sb = new StringBuilder(template);
        for (Object arg : args) {
            int idx = sb.indexOf("{}");
            if (idx < 0) break;
            String value = arg != null ? arg.toString() : "null";
            sb.replace(idx, idx + 2, value);
        }
        return sb.toString();
    }
}
