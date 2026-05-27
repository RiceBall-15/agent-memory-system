package com.memoryplatform.health;

import com.memoryplatform.storage.StorageFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 存储层健康检查指示器
 * <p>
 * 检查所有已注册的存储适配器（VectorStore、GraphStore、MetadataStore）的健康状态，
 * 将结果暴露到 Actuator /actuator/health 端点。
 * </p>
 *
 * @author Agent Memory Platform
 * @since 5.0
 */
@Slf4j
@Component("storageHealthIndicator")
@RequiredArgsConstructor
public class StorageHealthIndicator implements HealthIndicator {

    private final StorageFactory storageFactory;

    @Override
    public Health health() {
        try {
            Map<String, Boolean> results = storageFactory.healthCheckAll();

            // 如果所有存储都健康，返回 UP
            boolean allUp = results.values().stream().allMatch(Boolean::booleanValue);

            Health.Builder builder = allUp ? Health.up() : Health.down();
            results.forEach(builder::withDetail);

            if (!allUp) {
                log.warn("[StorageHealth] 部分存储健康检查失败: {}", results);
            }
            return builder.build();
        } catch (Exception e) {
            log.error("[StorageHealth] 存储层健康检查异常", e);
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
