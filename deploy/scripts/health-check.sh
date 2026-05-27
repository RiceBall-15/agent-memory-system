#!/usr/bin/env bash
# =============================================================================
# Agent Memory System - 健康检查脚本
# 用法: bash health-check.sh [--json] [--http-only] [--process-only]
# 输出: JSON格式的健康检查结果
# =============================================================================

set -uo pipefail

# ==================== 配置 ====================

HTTP_HOST="${HTTP_HOST:-127.0.0.1}"
HTTP_PORT="${HTTP_PORT:-8080}"
METRICS_PORT="${METRICS_PORT:-9090}"
HTTP_TIMEOUT="${HTTP_TIMEOUT:-5}"
SERVICE_NAME="${SERVICE_NAME:-agent-memory}"

# 颜色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# 标志
JSON_MODE=false
HTTP_ONLY=false
PROCESS_ONLY=false

# ==================== 参数解析 ====================

while [[ $# -gt 0 ]]; do
    case $1 in
        --json)         JSON_MODE=true; shift ;;
        --http-only)    HTTP_ONLY=true; shift ;;
        --process-only) PROCESS_ONLY=true; shift ;;
        --help|-h)
            echo "用法: bash $0 [选项]"
            echo ""
            echo "选项:"
            echo "  --json          输出JSON格式（默认）"
            echo "  --http-only     仅检查HTTP端点"
            echo "  --process-only  仅检查进程状态"
            echo "  --help, -h      显示帮助"
            exit 0
            ;;
        *) echo "未知参数: $1"; exit 1 ;;
    esac
done

# ==================== 检查函数 ====================

# 检查HTTP端点
check_http() {
    local url="$1"
    local label="$2"
    local timeout="$3"

    local start_time=$(date +%s%N)
    local http_code
    local response_time

    http_code=$(curl -s -o /dev/null -w "%{http_code}" \
        --connect-timeout "$timeout" \
        --max-time "$timeout" \
        "$url" 2>/dev/null) || http_code="000"

    local end_time=$(date +%s%N)
    response_time=$(( (end_time - start_time) / 1000000 ))  # 毫秒

    if [[ "$http_code" -ge 200 && "$http_code" -lt 300 ]]; then
        echo "{\"status\":\"healthy\",\"endpoint\":\"$url\",\"http_code\":$http_code,\"response_time_ms\":$response_time}"
    else
        echo "{\"status\":\"unhealthy\",\"endpoint\":\"$url\",\"http_code\":$http_code,\"response_time_ms\":$response_time}"
    fi
}

# 检查进程状态
check_process() {
    local pid_file="${PID_FILE:-/var/run/agent-memory.pid}"

    # 方法1: 检查systemd服务状态
    if command -v systemctl &>/dev/null; then
        local service_active
        service_active=$(systemctl is-active "$SERVICE_NAME" 2>/dev/null || echo "unknown")
        local service_status
        service_status=$(systemctl show "$SERVICE_NAME" --property=ActiveState --value 2>/dev/null || echo "unknown")
        local sub_state
        sub_state=$(systemctl show "$SERVICE_NAME" --property=SubState --value 2>/dev/null || echo "unknown")
        local main_pid
        main_pid=$(systemctl show "$SERVICE_NAME" --property=MainPID --value 2>/dev/null || echo "0")

        if [[ "$service_active" == "active" ]]; then
            # 获取进程内存和CPU
            local rss_kb=0
            local cpu_percent=0
            local uptime_seconds=0

            if [[ "$main_pid" -gt 0 ]] && [[ -d "/proc/$main_pid" ]]; then
                # RSS内存（KB）
                rss_kb=$(awk '/VmRSS/ {print $2}' /proc/$main_pid/status 2>/dev/null || echo "0")
                # 运行时间
                uptime_seconds=$(awk '{print int($1)}' /proc/$main_pid/stat 2>/dev/null || echo "0")
            fi

            echo "{\"status\":\"running\",\"service\":\"$SERVICE_NAME\",\"pid\":$main_pid,\"sub_state\":\"$sub_state\",\"rss_kb\":$rss_kb,\"uptime_seconds\":$uptime_seconds}"
        elif [[ "$service_active" == "activating" || "$service_active" == "reloading" ]]; then
            echo "{\"status\":\"starting\",\"service\":\"$SERVICE_NAME\",\"sub_state\":\"$sub_state\"}"
        else
            echo "{\"status\":\"stopped\",\"service\":\"$SERVICE_NAME\",\"sub_state\":\"$sub_state\"}"
        fi
        return
    fi

    # 方法2: 检查PID文件
    if [[ -f "$pid_file" ]]; then
        local pid
        pid=$(cat "$pid_file" 2>/dev/null)
        if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
            echo "{\"status\":\"running\",\"pid\":$pid,\"source\":\"pid_file\"}"
        else
            echo "{\"status\":\"stopped\",\"pid_file\":\"$pid_file\",\"source\":\"pid_file\"}"
        fi
        return
    fi

    # 方法3: 通过进程名搜索
    local pids
    pids=$(pgrep -f "agent-memory-system" 2>/dev/null || true)
    if [[ -n "$pids" ]]; then
        local pid
        pid=$(echo "$pids" | head -1)
        echo "{\"status\":\"running\",\"pid\":$pid,\"source\":\"process_name\"}"
    else
        echo "{\"status\":\"stopped\",\"source\":\"process_name\"}"
    fi
}

# 检查端口监听
check_port() {
    local port="$1"
    local port_name="$2"

    local listening=false
    local pid=0

    if command -v ss &>/dev/null; then
        local ss_output
        ss_output=$(ss -tlnp "sport = :$port" 2>/dev/null || true)
        if [[ -n "$ss_output" ]] && echo "$ss_output" | grep -q ":$port"; then
            listening=true
            pid=$(echo "$ss_output" | grep -oP 'pid=\K[0-9]+' | head -1 || echo "0")
        fi
    elif command -v netstat &>/dev/null; then
        local netstat_output
        netstat_output=$(netstat -tlnp 2>/dev/null | grep ":$port" || true)
        if [[ -n "$netstat_output" ]]; then
            listening=true
            pid=$(echo "$netstat_output" | grep -oP 'pid/\K[0-9]+' | head -1 || echo "0")
        fi
    fi

    if $listening; then
        echo "{\"status\":\"listening\",\"port\":$port,\"name\":\"$port_name\",\"pid\":$pid}"
    else
        echo "{\"status\":\"not_listening\",\"port\":$port,\"name\":\"$port_name\"}"
    fi
}

# 检查磁盘空间
check_disk() {
    local path="${1:-/opt/agent-memory-system}"
    local disk_info

    if [[ -d "$path" ]]; then
        disk_info=$(df -BM "$path" | tail -1 | awk '{print "{\"total_mbytes\":$2,\"used_mbytes\":$3,\"available_mbytes\":$4,\"use_percent\":\"$5\"}"}')
        echo "{\"path\":\"$path\",$disk_info}"
    else
        echo "{\"path\":\"$path\",\"error\":\"directory_not_found\"}"
    fi
}

# ==================== 主流程 ====================

main() {
    local overall_status="healthy"
    local timestamp
    timestamp=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

    # 收集所有检查结果
    local http_health=""
    local metrics_health=""
    local process_health=""
    local http_port_health=""
    local metrics_port_health=""
    local disk_health=""

    # HTTP健康检查
    if ! $PROCESS_ONLY; then
        http_health=$(check_http "http://$HTTP_HOST:$HTTP_PORT/health" "http_health" "$HTTP_TIMEOUT")
        metrics_health=$(check_http "http://$HTTP_HOST:$METRICS_PORT/metrics" "metrics_health" "$HTTP_TIMEOUT")

        # 端口检查
        http_port_health=$(check_port "$HTTP_PORT" "http")
        metrics_port_health=$(check_port "$METRICS_PORT" "metrics")
    fi

    # 进程检查
    if ! $HTTP_ONLY; then
        process_health=$(check_process)
    fi

    # 磁盘检查
    disk_health=$(check_disk "/opt/agent-memory-system")

    # 判断总体状态
    if ! $HTTP_ONLY; then
        local proc_status
        proc_status=$(echo "$process_health" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
        if [[ "$proc_status" != "running" ]]; then
            overall_status="critical"
        fi
    fi

    if ! $PROCESS_ONLY; then
        local http_status
        http_status=$(echo "$http_health" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
        if [[ "$http_status" != "healthy" ]]; then
            if [[ "$overall_status" == "healthy" ]]; then
                overall_status="degraded"
            fi
        fi
    fi

    # ==================== 输出 ====================

    if $JSON_MODE || ! $JSON_MODE; then
        # JSON格式输出
        cat <<EOF
{
  "timestamp": "$timestamp",
  "overall_status": "$overall_status",
  "checks": {
    "http_health": $http_health,
    "metrics_health": $metrics_health,
    "process": $process_health,
    "ports": {
      "http": $http_port_health,
      "metrics": $metrics_port_health
    },
    "disk": $disk_health
  },
  "config": {
    "http_endpoint": "http://$HTTP_HOST:$HTTP_PORT",
    "metrics_endpoint": "http://$HTTP_HOST:$METRICS_PORT",
    "service_name": "$SERVICE_NAME"
  }
}
EOF
    fi

    # 彩色状态输出
    if [[ "$overall_status" == "healthy" ]]; then
        echo -e "\n${GREEN}✓ 系统健康${NC}" >&2
    elif [[ "$overall_status" == "degraded" ]]; then
        echo -e "\n${YELLOW}⚠ 系统降级${NC}" >&2
    else
        echo -e "\n${RED}✗ 系统异常${NC}" >&2
    fi

    # 退出码
    case "$overall_status" in
        healthy)  exit 0 ;;
        degraded) exit 1 ;;
        *)        exit 2 ;;
    esac
}

main
