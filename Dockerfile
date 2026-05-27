# =============================================================================
# Agent Memory System - Dockerfile
# 多阶段构建，最终镜像基于 Eclipse Temurin JRE 17
# =============================================================================

# --- 阶段1: 构建 ---
FROM eclipse-temurin:17-jdk AS builder

WORKDIR /build

# 先复制 pom.xml 利用 Docker 层缓存
COPY pom.xml .
RUN mvn dependency:go-offline -B

# 复制源码并打包
COPY src ./src
RUN mvn clean package -DskipTests -B

# --- 阶段2: 运行 ---
FROM eclipse-temurin:17-jre

# 元数据
LABEL maintainer="MemoryPlatform"
LABEL description="Agent Memory System - 企业级Agent记忆中台"
LABEL version="1.0.0"

# 设置时区
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 创建非 root 用户
RUN groupadd -r memoryapp && useradd -r -g memoryapp memoryapp

WORKDIR /app

# 从构建阶段复制 JAR
COPY --from=builder /build/target/agent-memory-system-1.0.0.jar app.jar

# 复制配置目录
COPY config/ ./config/

# 创建数据目录
RUN mkdir -p /app/data /app/logs && \
    chown -R memoryapp:memoryapp /app

# 切换到非 root 用户
USER memoryapp

# 暴露端口
# 8080 - HTTP API 服务
# 9090 - Prometheus 指标
EXPOSE 8080 9090

# JVM 优化参数
ENV JAVA_OPTS="-XX:+UseG1GC \
  -XX:MaxRAMPercentage=75.0 \
  -XX:InitialRAMPercentage=50.0 \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/app/logs/heapdump.hprof \
  -Djava.security.egd=file:/dev/./urandom \
  -Dfile.encoding=UTF-8 \
  -Duser.timezone=Asia/Shanghai"

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --retries=3 --start-period=30s \
  CMD curl -f http://localhost:8080/health || exit 1

# 启动命令
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
