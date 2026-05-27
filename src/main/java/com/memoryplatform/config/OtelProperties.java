package com.memoryplatform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.otel")
public class OtelProperties {
    private String endpoint = "http://localhost:4318";
    private String serviceName = "agent-memory-system";
}
