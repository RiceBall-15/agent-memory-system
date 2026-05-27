#!/usr/bin/env bash
# =============================================================================
# Agent Memory System - 一键部署脚本
# 用法: sudo bash setup.sh [--prefix /opt/agent-memory-system] [--user agent-memory]
# =============================================================================

set -euo pipefail

# ==================== 配置 ====================

# 默认安装路径
INSTALL_DIR="${INSTALL_DIR:-/opt/agent-memory-system}"
# 默认运行用户
RUN_USER="${RUN_USER:-agent-memory}"
# JAR文件名
JAR_NAME="agent-memory-system-1.0.0.jar"
# 项目源码根目录（相对于脚本位置）
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# systemd服务文件
SERVICE_FILE="$(cd "$SCRIPT_DIR/.." && pwd)/systemd/agent-memory.service"
# Nginx配置文件
NGINX_CONF="$(cd "$SCRIPT_DIR/.." && pwd)/nginx/agent-memory.conf"

# 颜色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# ==================== 工具函数 ====================

log_info()    { echo -e "${BLUE}[INFO]${NC}    $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warn()    { echo -e "${YELLOW}[WARN]${NC}    $1"; }
log_error()   { echo -e "${RED}[ERROR]${NC}   $1"; }

check_root() {
    if [[ $EUID -ne 0 ]]; then
        log_error "此脚本需要 root 权限运行"
        echo "请使用: sudo bash $0"
        exit 1
    fi
}

# ==================== 环境检查 ====================

check_java() {
    log_info "检查 Java 环境..."

    # 检查 JAVA_HOME
    if [[ -n "${JAVA_HOME:-}" ]]; then
        JAVA_CMD="$JAVA_HOME/bin/java"
    else
        JAVA_CMD="java"
    fi

    # 检查 java 命令是否存在
    if ! command -v "$JAVA_CMD" &>/dev/null && ! command -v java &>/dev/null; then
        log_error "未找到 Java 安装"
        echo ""
        echo "请安装 Java 17+:"
        echo "  Ubuntu/Debian: sudo apt install openjdk-17-jdk"
        echo "  CentOS/RHEL:   sudo yum install java-17-openjdk"
        echo "  Arch:          sudo pacman -S jdk17-openjdk"
        exit 1
    fi

    # 获取Java版本
    JAVA_VERSION=$("$JAVA_CMD" -version 2>&1 | head -1 | awk -F '"' '{print $2}')
    JAVA_MAJOR=$(echo "$JAVA_VERSION" | cut -d. -f1)

    if [[ "$JAVA_MAJOR" -lt 17 ]]; then
        log_error "需要 Java 17+, 当前版本: Java $JAVA_VERSION"
        exit 1
    fi

    log_success "Java 版本: $JAVA_VERSION ✓"
}

check_maven() {
    log_info "检查 Maven 环境..."

    if ! command -v mvn &>/dev/null; then
        log_warn "未找到 Maven，将跳过构建步骤"
        log_warn "请安装 Maven 3.8+: sudo apt install maven"
        return 1
    fi

    MVN_VERSION=$(mvn --version 2>&1 | head -1 | awk '{print $3}')
    log_success "Maven 版本: $MVN_VERSION ✓"
    return 0
}

check_docker() {
    log_info "检查 Docker 环境..."

    if ! command -v docker &>/dev/null; then
        log_warn "未找到 Docker，存储服务将需要手动启动"
        return 1
    fi

    if ! docker info &>/dev/null 2>&1; then
        log_warn "Docker 守护进程未运行或当前用户无权限"
        return 1
    fi

    log_success "Docker 已安装 ✓"
    return 0
}

# ==================== 构建 ====================

build_project() {
    log_info "开始构建项目..."

    cd "$PROJECT_ROOT"

    if check_maven; then
        log_info "使用 Maven 构建..."
        mvn clean package -DskipTests -q
    else
        log_warn "Maven 不可用，检查是否已有构建产物..."
    fi

    # 检查JAR是否存在
    JAR_PATH="$PROJECT_ROOT/target/$JAR_NAME"
    if [[ ! -f "$JAR_PATH" ]]; then
        log_error "未找到 JAR 文件: $JAR_PATH"
        log_error "请先构建项目: mvn clean package -DskipTests"
        exit 1
    fi

    log_success "构建完成: $JAR_PATH"
}

# ==================== 安装 ====================

create_user() {
    log_info "创建运行用户: $RUN_USER"

    if id "$RUN_USER" &>/dev/null; then
        log_info "用户 $RUN_USER 已存在"
    else
        useradd --system --no-create-home --shell /usr/sbin/nologin "$RUN_USER"
        log_success "用户 $RUN_USER 创建成功"
    fi
}

install_app() {
    log_info "安装应用到 $INSTALL_DIR..."

    # 创建安装目录
    mkdir -p "$INSTALL_DIR"
    mkdir -p "$INSTALL_DIR/logs"
    mkdir -p "$INSTALL_DIR/config"

    # 复制JAR文件
    cp "$JAR_PATH" "$INSTALL_DIR/$JAR_NAME"
    log_success "JAR 文件已复制"

    # 复制配置文件（如果存在）
    if [[ -f "$PROJECT_ROOT/application.json" ]]; then
        cp "$PROJECT_ROOT/application.json" "$INSTALL_DIR/config/"
        log_success "配置文件已复制"
    fi

    # 复制Web Dashboard
    if [[ -d "$PROJECT_ROOT/web" ]]; then
        cp -r "$PROJECT_ROOT/web" "$INSTALL_DIR/"
        log_success "Dashboard 已复制"
    fi

    # 设置权限
    chown -R "$RUN_USER:$RUN_USER" "$INSTALL_DIR"
    chmod 755 "$INSTALL_DIR"
    chmod 644 "$INSTALL_DIR/$JAR_NAME"
    chmod -R 755 "$INSTALL_DIR/logs"
    chmod -R 755 "$INSTALL_DIR/config"

    log_success "应用安装完成"
}

install_systemd() {
    log_info "配置 systemd 服务..."

    if [[ -f "$SERVICE_FILE" ]]; then
        cp "$SERVICE_FILE" /etc/systemd/system/agent-memory.service
        systemctl daemon-reload
        systemctl enable agent-memory
        log_success "systemd 服务已安装并启用"
    else
        log_warn "未找到 systemd 服务文件: $SERVICE_FILE"
        log_warn "请手动创建服务文件"
    fi
}

install_nginx() {
    log_info "配置 Nginx..."

    if ! command -v nginx &>/dev/null; then
        log_warn "Nginx 未安装，跳过配置"
        log_warn "安装 Nginx: sudo apt install nginx"
        return
    fi

    if [[ -f "$NGINX_CONF" ]]; then
        cp "$NGINX_CONF" /etc/nginx/conf.d/agent-memory.conf

        # 测试Nginx配置
        if nginx -t &>/dev/null; then
            systemctl reload nginx
            log_success "Nginx 配置已安装并重载"
        else
            log_error "Nginx 配置测试失败"
            log_warn "请检查配置: nginx -t"
        fi
    fi
}

# ==================== 启动 ====================

start_services() {
    log_info "启动服务..."

    # 启动Docker存储服务（如果Docker可用）
    if check_docker; then
        cd "$PROJECT_ROOT"
        if [[ -f "docker-compose.yml" ]]; then
            log_info "启动 Docker 存储服务..."
            docker compose up -d
            log_success "Docker 存储服务已启动"
            sleep 10  # 等待存储服务就绪
        fi
    fi

    # 启动应用服务
    if systemctl list-unit-files | grep -q agent-memory; then
        log_info "启动 Agent Memory System..."
        systemctl start agent-memory
        sleep 5

        if systemctl is-active --quiet agent-memory; then
            log_success "Agent Memory System 启动成功"
        else
            log_error "Agent Memory System 启动失败"
            log_warn "查看日志: journalctl -u agent-memory -n 50"
            systemctl status agent-memory --no-pager || true
        fi
    else
        log_warn "systemd 服务未安装，请手动启动"
        log_info "手动启动: java -jar $INSTALL_DIR/$JAR_NAME"
    fi
}

# ==================== 验证 ====================

verify_deployment() {
    log_info "验证部署..."

    # 检查HTTP端点
    sleep 3
    if curl -sf http://localhost:8080/health &>/dev/null; then
        log_success "HTTP 端点正常: http://localhost:8080/health"
    else
        log_warn "HTTP 端点暂不可用，请稍后重试"
    fi

    # 检查Metrics端点
    if curl -sf http://localhost:9090/metrics &>/dev/null; then
        log_success "Metrics 端点正常: http://localhost:9090/metrics"
    else
        log_warn "Metrics 端点暂不可用"
    fi

    # 检查Dashboard
    if curl -sf http://localhost:8080/dashboard/ &>/dev/null; then
        log_success "Dashboard 可访问: http://localhost:8080/dashboard/"
    else
        log_warn "Dashboard 暂不可访问"
    fi
}

# ==================== 打印总结 ====================

print_summary() {
    echo ""
    echo "============================================"
    echo "  Agent Memory System 部署完成"
    echo "============================================"
    echo ""
    echo "  安装路径:  $INSTALL_DIR"
    echo "  运行用户:  $RUN_USER"
    echo "  HTTP API:  http://localhost:8080"
    echo "  Dashboard: http://localhost:8080/dashboard/"
    echo "  Metrics:   http://localhost:9090/metrics"
    echo "  Health:    http://localhost:8080/health"
    echo ""
    echo "  常用命令:"
    echo "    启动服务:  systemctl start agent-memory"
    echo "    停止服务:  systemctl stop agent-memory"
    echo "    查看状态:  systemctl status agent-memory"
    echo "    查看日志:  journalctl -u agent-memory -f"
    echo ""
    echo "  故障排查:"
    echo "    查看日志:  journalctl -u agent-memory -n 100"
    echo "    手动启动:  java -jar $INSTALL_DIR/$JAR_NAME"
    echo "    健康检查:  bash $SCRIPT_DIR/health-check.sh"
    echo ""
}

# ==================== 主流程 ====================

main() {
    echo ""
    echo "╔═══════════════════════════════════════════════════════════╗"
    echo "║     Agent Memory System - 一键部署脚本                  ║"
    echo "╚═══════════════════════════════════════════════════════════╝"
    echo ""

    # 解析命令行参数
    while [[ $# -gt 0 ]]; do
        case $1 in
            --prefix)  INSTALL_DIR="$2"; shift 2 ;;
            --user)    RUN_USER="$2"; shift 2 ;;
            --help|-h)
                echo "用法: sudo bash $0 [选项]"
                echo ""
                echo "选项:"
                echo "  --prefix DIR   安装目录 (默认: /opt/agent-memory-system)"
                echo "  --user USER    运行用户 (默认: agent-memory)"
                echo "  --help, -h     显示帮助"
                exit 0
                ;;
            *) log_error "未知参数: $1"; exit 1 ;;
        esac
    done

    check_root

    log_info "开始部署流程..."
    echo ""

    # 1. 环境检查
    log_info "===== 阶段 1/6: 环境检查 ====="
    check_java
    check_docker || true
    echo ""

    # 2. 构建
    log_info "===== 阶段 2/6: 构建项目 ====="
    build_project
    echo ""

    # 3. 创建用户
    log_info "===== 阶段 3/6: 创建系统用户 ====="
    create_user
    echo ""

    # 4. 安装
    log_info "===== 阶段 4/6: 安装应用 ====="
    install_app
    echo ""

    # 5. 配置服务
    log_info "===== 阶段 5/6: 配置系统服务 ====="
    install_systemd
    install_nginx
    echo ""

    # 6. 启动
    log_info "===== 阶段 6/6: 启动服务 ====="
    start_services
    echo ""

    # 验证
    verify_deployment

    # 打印总结
    print_summary
}

main "$@"
