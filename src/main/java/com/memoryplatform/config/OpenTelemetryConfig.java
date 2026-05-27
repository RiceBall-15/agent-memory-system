package com.memoryplatform.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class OpenTelemetryConfig {
    
    @Value("${app.otel.endpoint:http://localhost:4318}")
    private String endpoint;
    
    @Value("${spring.application.name:agent-memory-system}")
    private String serviceName;
    
    @Bean
    public OpenTelemetry openTelemetry() {
        Resource resource = Resource.getDefault()
            .merge(Resource.builder()
                .put("service.name", serviceName)
                .put("service.version", "2.0.0")
                .build());
        
        // Span Exporter
        var spanExporter = OtlpHttpSpanExporter.builder()
            .setEndpoint(endpoint + "/v1/traces")
            .build();
        
        // Metric Exporter  
        var metricExporter = OtlpHttpMetricExporter.builder()
            .setEndpoint(endpoint + "/v1/metrics")
            .build();
        
        // Tracer Provider
        var tracerProvider = SdkTracerProvider.builder()
            .setResource(resource)
            .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
            .build();
        
        // Meter Provider
        var meterProvider = SdkMeterProvider.builder()
            .setResource(resource)
            .registerMetricReader(PeriodicMetricReader.builder(metricExporter)
                .setInterval(Duration.ofSeconds(30))
                .build())
            .build();
        
        return OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setMeterProvider(meterProvider)
            .build();
    }
    
    @Bean
    public Tracer tracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer("agent-memory-system");
    }
    
    @Bean
    public Meter meter(OpenTelemetry openTelemetry) {
        return openTelemetry.getMeter("agent-memory-system");
    }
}
