package com.memoryplatform.config;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 配置热重载管理器
 * <p>
 * 提供配置文件变更监听和热重载功能，支持：
 * <ul>
 *   <li>文件系统监控</li>
 *   <li>配置变更通知</li>
 *   <li>自动重载配置</li>
 * </ul>
 * </p>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * ConfigHotReload manager = ConfigHotReload.getInstance();
 * manager.startWatching("/path/to/config.json");
 * manager.addListener(new ConfigChangeListener() {
 *     public void onConfigChanged(String path, String eventType) {
 *         System.out.println("配置已变更: " + eventType);
 *     }
 * });
 * }</pre>
 *
 * @author MemoryPlatform
 * @since 2.0
 */
@Slf4j
public class ConfigHotReload {

    /** 单例实例 */
    private static volatile ConfigHotReload instance;

    /** 监听器列表 */
    private final List<ConfigChangeListener> listeners = new CopyOnWriteArrayList<>();

    /** 监控线程池 */
    private final ExecutorService watchExecutor;

    /** 监控的文件路径 */
    private volatile Path watchedPath;

    /** 监控是否活跃 */
    private volatile boolean running;

    /** 上次修改时间 */
    private volatile long lastModified = 0;

    /** 上次的配置哈希（用于检测实际内容变更） */
    private volatile String lastConfigHash;

    /**
     * 私有构造函数
     */
    private ConfigHotReload() {
        this.watchExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "config-watcher");
            t.setDaemon(true);
            return t;
        });
        this.running = false;
    }

    /**
     * 获取单例实例
     *
     * @return ConfigHotReload实例
     */
    public static ConfigHotReload getInstance() {
        if (instance == null) {
            synchronized (ConfigHotReload.class) {
                if (instance == null) {
                    instance = new ConfigHotReload();
                }
            }
        }
        return instance;
    }

    /**
     * 开始监控配置文件
     *
     * @param configPath 配置文件路径
     */
    public void startWatching(String configPath) {
        if (running) {
            log.warn("[ConfigHotReload] 已经在监控中，忽略重复启动");
            return;
        }

        try {
            // 尝试从classpath加载路径
            java.net.URL resource = ConfigHotReload.class.getClassLoader().getResource(configPath);
            if (resource != null && "file".equals(resource.getProtocol())) {
                watchedPath = Paths.get(resource.toURI());
            } else {
                watchedPath = Paths.get(configPath);
            }

            if (!Files.exists(watchedPath)) {
                log.warn("[ConfigHotReload] 配置文件不存在: {}, 将监控父目录", configPath);
                watchedPath = watchedPath.getParent();
            }

            // 记录初始状态
            lastModified = getLastModified();
            lastConfigHash = calculateHash();

            running = true;
            watchExecutor.submit(this::watchLoop);

            log.info("[ConfigHotReload] 开始监控配置: {}", watchedPath);
        } catch (Exception e) {
            log.error("[ConfigHotReload] 启动监控失败: {}", e.getMessage());
        }
    }

    /**
     * 停止监控
     */
    public void stopWatching() {
        running = false;
        watchExecutor.shutdown();
        try {
            if (!watchExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                watchExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            watchExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("[ConfigHotReload] 已停止监控");
    }

    /**
     * 添加配置变更监听器
     *
     * @param listener 监听器
     */
    public void addListener(ConfigChangeListener listener) {
        listeners.add(listener);
        log.debug("[ConfigHotReload] 添加监听器: {}", listener.getClass().getSimpleName());
    }

    /**
     * 移除配置变更监听器
     *
     * @param listener 监听器
     */
    public void removeListener(ConfigChangeListener listener) {
        listeners.remove(listener);
        log.debug("[ConfigHotReload] 移除监听器: {}", listener.getClass().getSimpleName());
    }

    /**
     * 手动触发配置重载
     *
     * @param configPath 配置文件路径
     */
    public void reload(String configPath) {
        log.info("[ConfigHotReload] 手动触发配置重载: {}", configPath);
        notifyListeners(configPath, "manual");
    }

    /**
     * 监控主循环
     */
    private void watchLoop() {
        while (running) {
            try {
                Thread.sleep(1000); // 每秒检查一次

                long currentModified = getLastModified();
                if (currentModified > lastModified) {
                    // 文件修改时间变更，等待写入完成
                    Thread.sleep(500);
                    currentModified = getLastModified();

                    if (currentModified > lastModified) {
                        String currentHash = calculateHash();
                        if (currentHash != null && !currentHash.equals(lastConfigHash)) {
                            log.info("[ConfigHotReload] 检测到配置变更");
                            lastModified = currentModified;
                            lastConfigHash = currentHash;
                            notifyListeners(watchedPath.toString(), "modified");
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("[ConfigHotReload] 监控异常: {}", e.getMessage());
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * 获取最后修改时间
     */
    private long getLastModified() {
        try {
            if (Files.isRegularFile(watchedPath)) {
                return Files.getLastModifiedTime(watchedPath).toMillis();
            }
        } catch (IOException e) {
            log.debug("[ConfigHotReload] 获取修改时间失败: {}", e.getMessage());
        }
        return 0;
    }

    /**
     * 计算配置文件哈希
     */
    private String calculateHash() {
        try {
            if (Files.isRegularFile(watchedPath)) {
                byte[] bytes = Files.readAllBytes(watchedPath);
                return String.valueOf(java.util.Arrays.hashCode(bytes));
            }
        } catch (IOException e) {
            log.debug("[ConfigHotReload] 计算哈希失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 通知所有监听器
     */
    private void notifyListeners(String path, String eventType) {
        for (ConfigChangeListener listener : listeners) {
            try {
                listener.onConfigChanged(path, eventType);
            } catch (Exception e) {
                log.error("[ConfigHotReload] 通知监听器失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 配置变更监听器接口
     */
    public interface ConfigChangeListener {
        /**
         * 配置变更时回调
         *
         * @param path      配置文件路径
         * @param eventType 事件类型（created/modified/deleted）
         */
        void onConfigChanged(String path, String eventType);
    }

    /**
     * 配置变更事件
     */
    public static class ConfigChangeEvent {
        private final String path;
        private final String eventType;
        private final long timestamp;

        public ConfigChangeEvent(String path, String eventType) {
            this.path = path;
            this.eventType = eventType;
            this.timestamp = System.currentTimeMillis();
        }

        public String getPath() { return path; }
        public String getEventType() { return eventType; }
        public long getTimestamp() { return timestamp; }
    }
}
