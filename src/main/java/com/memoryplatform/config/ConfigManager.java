package com.memoryplatform.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 增强的配置管理器
 * <p>
 * 在原有ApplicationConfig基础上增加：
 * <ul>
 *   <li>配置热重载支持</li>
 *   <li>配置变更回调</li>
 *   <li>配置备份与恢复</li>
 *   <li>配置校验</li>
 * </ul>
 * </p>
 *
 * @author MemoryPlatform
 * @since 2.0
 */
@Slf4j
public class ConfigManager implements ConfigHotReload.ConfigChangeListener {

    /** 单例实例 */
    private static volatile ConfigManager instance;

    /** 应用配置 */
    private volatile ApplicationConfig config;

    /** 配置缓存（支持运行时动态修改） */
    private final ConcurrentHashMap<String, Object> runtimeConfig = new ConcurrentHashMap<>();

    /** 配置变更回调 */
    private final java.util.List<ConfigChangeCallback> callbacks = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 配置文件路径 */
    private final String configPath;

    /** Gson实例 */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * 私有构造函数
     *
     * @param configPath 配置文件路径
     */
    private ConfigManager(String configPath) {
        this.configPath = configPath;
        this.config = ApplicationConfig.load(configPath);

        // 注册到热重载管理器
        ConfigHotReload.getInstance().addListener(this);
    }

    /**
     * 获取单例实例
     *
     * @return ConfigManager实例
     */
    public static ConfigManager getInstance() {
        if (instance == null) {
            synchronized (ConfigManager.class) {
                if (instance == null) {
                    instance = new ConfigManager("application.json");
                }
            }
        }
        return instance;
    }

    /**
     * 获取单例实例
     *
     * @param configPath 配置文件路径
     * @return ConfigManager实例
     */
    public static ConfigManager getInstance(String configPath) {
        if (instance == null) {
            synchronized (ConfigManager.class) {
                if (instance == null) {
                    instance = new ConfigManager(configPath);
                }
            }
        }
        return instance;
    }

    /**
     * 获取应用配置
     *
     * @return ApplicationConfig实例
     */
    public ApplicationConfig getConfig() {
        return config;
    }

    /**
     * 获取运行时配置值
     *
     * @param key 配置键（支持点分隔的嵌套键，如 "server.port"）
     * @return 配置值
     */
    public Object get(String key) {
        return runtimeConfig.get(key);
    }

    /**
     * 获取运行时配置值（带默认值）
     *
     * @param key          配置键
     * @param defaultValue 默认值
     * @return 配置值
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, T defaultValue) {
        Object value = runtimeConfig.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return (T) value;
        } catch (ClassCastException e) {
            return defaultValue;
        }
    }

    /**
     * 设置运行时配置值
     *
     * @param key   配置键
     * @param value 配置值
     */
    public void set(String key, Object value) {
        Object oldValue = runtimeConfig.put(key, value);
        log.info("[ConfigManager] 运行时配置变更: {} = {} -> {}", key, oldValue, value);
        notifyCallbacks(key, value);
    }

    /**
     * 批量设置运行时配置
     *
     * @param values 配置键值对
     */
    public void setAll(Map<String, Object> values) {
        runtimeConfig.putAll(values);
        log.info("[ConfigManager] 批量设置运行时配置: {} 项", values.size());
    }

    /**
     * 获取运行时配置Map
     *
     * @return 配置Map
     */
    public Map<String, Object> getAll() {
        return new ConcurrentHashMap<>(runtimeConfig);
    }

    /**
     * 添加配置变更回调
     *
     * @param callback 回调函数
     */
    public void addCallback(ConfigChangeCallback callback) {
        callbacks.add(callback);
    }

    /**
     * 移除配置变更回调
     *
     * @param callback 回调函数
     */
    public void removeCallback(ConfigChangeCallback callback) {
        callbacks.remove(callback);
    }

    /**
     * 备份当前配置
     *
     * @return 备份文件路径
     * @throws IOException 如果备份失败
     */
    public Path backupConfig() throws IOException {
        Path source = Paths.get(configPath);
        if (!Files.exists(source)) {
            throw new IOException("配置文件不存在: " + configPath);
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String backupName = "application_" + timestamp + ".json.bak";
        Path backupPath = source.resolveSibling(backupName);

        Files.copy(source, backupPath, StandardCopyOption.REPLACE_EXISTING);
        log.info("[ConfigManager] 配置已备份: {}", backupPath);
        return backupPath;
    }

    /**
     * 从备份恢复配置
     *
     * @param backupPath 备份文件路径
     * @throws IOException 如果恢复失败
     */
    public void restoreConfig(Path backupPath) throws IOException {
        if (!Files.exists(backupPath)) {
            throw new IOException("备份文件不存在: " + backupPath);
        }

        // 先备份当前配置
        backupConfig();

        Path target = Paths.get(configPath);
        Files.copy(backupPath, target, StandardCopyOption.REPLACE_EXISTING);
        log.info("[ConfigManager] 配置已从备份恢复: {}", backupPath);

        // 重新加载配置
        reloadConfig();
    }

    /**
     * 重新加载配置
     */
    public void reloadConfig() {
        log.info("[ConfigManager] 重新加载配置...");
        try {
            ApplicationConfig newConfig = ApplicationConfig.load(configPath);
            this.config = newConfig;
            log.info("[ConfigManager] 配置重载成功");
            notifyCallbacks("config", "reload");
        } catch (Exception e) {
            log.error("[ConfigManager] 配置重载失败: {}", e.getMessage());
        }
    }

    /**
     * 验证配置
     *
     * @return 验证结果
     */
    public ValidationResult validateConfig() {
        ValidationResult result = new ValidationResult();

        // 验证端口
        int port = config.getServerPort();
        if (port < 1 || port > 65535) {
            result.addError("server.port", "端口号必须在1-65535之间");
        }

        // 验证向量存储配置
        if (config.getVectorStoreDimension() <= 0) {
            result.addError("vectorStore.dimension", "向量维度必须大于0");
        }

        // 验证LLM配置
        if (config.getLlmMaxTokens() <= 0) {
            result.addError("llm.maxTokens", "最大Token数必须大于0");
        }

        double temperature = config.getLlmTemperature();
        if (temperature < 0 || temperature > 2) {
            result.addError("llm.temperature", "温度参数必须在0-2之间");
        }

        // 验证熔断器配置
        if (config.getCircuitFailureThreshold() <= 0) {
            result.addError("circuitBreaker.failureThreshold", "失败阈值必须大于0");
        }

        if (config.getCircuitRecoveryTimeout() <= 0) {
            result.addError("circuitBreaker.recoveryTimeout", "恢复超时必须大于0");
        }

        return result;
    }

    /**
     * 重置为单例模式（用于测试）
     */
    public static void reset() {
        synchronized (ConfigManager.class) {
            instance = null;
        }
    }

    // ==================== ConfigChangeListener 实现 ====================

    @Override
    public void onConfigChanged(String path, String eventType) {
        log.info("[ConfigManager] 检测到配置文件变更: {} ({})", path, eventType);
        reloadConfig();
    }

    // ==================== 内部方法 ====================

    /**
     * 通知回调
     */
    private void notifyCallbacks(String key, Object value) {
        for (ConfigChangeCallback callback : callbacks) {
            try {
                callback.onConfigChanged(key, value);
            } catch (Exception e) {
                log.error("[ConfigManager] 执行回调失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 配置变更回调接口
     */
    public interface ConfigChangeCallback {
        /**
         * 配置变更时回调
         *
         * @param key   配置键
         * @param value 新值
         */
        void onConfigChanged(String key, Object value);
    }

    /**
     * 配置验证结果
     */
    public static class ValidationResult {
        private final java.util.List<String> errors = new java.util.ArrayList<>();

        public void addError(String field, String message) {
            errors.add(field + ": " + message);
        }

        public boolean isValid() {
            return errors.isEmpty();
        }

        public java.util.List<String> getErrors() {
            return new java.util.ArrayList<>(errors);
        }

        public String getErrorMessage() {
            return String.join("; ", errors);
        }
    }
}
