package com.memoryplatform.health;

import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 应用信息贡献者
 * <p>
 * 将应用元数据暴露到 Actuator /actuator/info 端点，
 * 包含应用名称、版本、运行时信息等。
 * </p>
 *
 * @author Agent Memory Platform
 * @since 5.0
 */
@Component
public class AppInfoContributor implements InfoContributor {

    @Override
    public void contribute(Info.Builder builder) {
        Map<String, Object> appInfo = new HashMap<>();
        appInfo.put("name", "Agent Memory System");
        appInfo.put("version", "5.0");
        appInfo.put("runtime", System.getProperty("java.version"));
        appInfo.put("virtualThreads", Runtime.getRuntime().availableProcessors() > 1);
        builder.withDetail("app", appInfo);
    }
}
