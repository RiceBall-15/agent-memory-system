#!/bin/bash
# =============================================================================
# 负载测试脚本 - 使用curl进行压力测试
# 
# 功能:
#   - 10个并发请求，每个请求创建10条记忆
#   - 统计成功/失败数量和平均响应时间
#
# 用法:
#   chmod +x scripts/load-test.sh
#   ./scripts/load-test.sh [BASE_URL] [CONCURRENT] [REQUESTS_PER_WORKER]
#
# 示例:
#   ./scripts/load-test.sh http://localhost:8080 10 10
#   ./scripts/load-test.sh  # 使用默认值
# =============================================================================

set -euo pipefail

# ==================== 配置 ====================

BASE_URL="${1:-http://localhost:8080}"
CONCURRENT="${2:-10}"
REQUESTS_PER_WORKER="${3:-10}"
TOTAL_REQUESTS=$((CONCURRENT * REQUESTS_PER_WORKER))

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m' # No Color

# 临时文件
RESULTS_DIR=$(mktemp -d)
WORKER_LOG="$RESULTS_DIR/worker.log"
SUMMARY_FILE="$RESULTS_DIR/summary.csv"
TIMING_DIR="$RESULTS_DIR/timings"

mkdir -p "$TIMING_DIR"

# 清理函数
cleanup() {
    rm -rf "$RESULTS_DIR"
}
trap cleanup EXIT

# ==================== 工具函数 ====================

log_info() {
    echo -e "${CYAN}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[OK]${NC} $1"
}

log_error() {
    echo -e "${RED}[FAIL]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

# 生成对话消息的JSON
generate_payload() {
    local index=$1
    local topics=("机器学习" "Web开发" "数据分析" "系统设计" "分布式系统"
                  "云计算" "微服务" "容器化" "网络安全" "数据库优化")
    local topic=${topics[$((index % ${#topics[@]}))]}

    cat <<EOF
{
    "messages": [
        {"role": "user", "content": "我在${topic}方面有一些问题想讨论"},
        {"role": "assistant", "content": "好的，请问关于${topic}的具体问题是什么？"},
        {"role": "user", "content": "我想了解${topic}的最佳实践，这是第${index}次讨论"}
    ],
    "userId": "loadtest-user-$((index % CONCURRENT))",
    "agentId": "loadtest-agent"
}
EOF
}

# 单个工作线程
worker() {
    local worker_id=$1
    local worker_log="$RESULTS_DIR/worker_${worker_id}.log"
    local timing_file="$TIMING_DIR/worker_${worker_id}.csv"

    echo "worker_id,request_id,status_code,time_total_ms,time_connect_ms,time_starttransfer_ms,size_bytes" > "$timing_file"

    for i in $(seq 1 "$REQUESTS_PER_WORKER"); do
        local request_id=$((worker_id * REQUESTS_PER_WORKER + i))
        local payload
        payload=$(generate_payload "$request_id")

        local tmpfile
        tmpfile=$(mktemp)

        # 使用curl发送请求并收集计时信息
        local http_code
        http_code=$(curl -s -o "$tmpfile" -w "%{http_code},%{time_total},%{time_connect},%{time_starttransfer},%{size_download}" \
            -X POST \
            -H "Content-Type: application/json" \
            -d "$payload" \
            --connect-timeout 10 \
            --max-time 30 \
            "${BASE_URL}/api/memories" 2>/dev/null || echo "000,0,0,0,0")

        # 解析curl输出
        IFS=',' read -r code time_total time_connect time_starttransfer size <<< "$http_code"

        # 转换为毫秒
        local time_total_ms=$(echo "$time_total * 1000" | bc 2>/dev/null || echo "0")
        local time_connect_ms=$(echo "$time_connect * 1000" | bc 2>/dev/null || echo "0")
        local time_starttransfer_ms=$(echo "$time_starttransfer * 1000" | bc 2>/dev/null || echo "0")

        # 记录结果
        echo "${worker_id},${request_id},${code},${time_total_ms},${time_connect_ms},${time_starttransfer_ms},${size}" >> "$timing_file"

        rm -f "$tmpfile"
    done
}

# ==================== 健康检查 ====================

check_health() {
    log_info "检查服务健康状态: ${BASE_URL}/health"

    local response
    local http_code
    response=$(curl -s -w "\n%{http_code}" --connect-timeout 5 --max-time 10 "${BASE_URL}/health" 2>/dev/null || echo -e "\n000")
    http_code=$(echo "$response" | tail -1)
    local body=$(echo "$response" | head -n -1)

    if [[ "$http_code" == "200" ]]; then
        log_success "服务健康检查通过 (HTTP $http_code)"
        echo -e "  响应: ${body:0:200}"
        return 0
    else
        log_error "服务健康检查失败 (HTTP $http_code)"
        echo -e "  响应: ${body:0:200}"
        return 1
    fi
}

# ==================== 主流程 ====================

echo ""
echo -e "${BOLD}╔══════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║    Agent Memory System - 负载测试           ║${NC}"
echo -e "${BOLD}╚══════════════════════════════════════════════╝${NC}"
echo ""
echo -e "  目标地址:     ${CYAN}${BASE_URL}${NC}"
echo -e "  并发数:       ${CYAN}${CONCURRENT}${NC}"
echo -e "  每线程请求数: ${CYAN}${REQUESTS_PER_WORKER}${NC}"
echo -e "  总请求数:     ${CYAN}${TOTAL_REQUESTS}${NC}"
echo ""

# 健康检查
if ! check_health; then
    log_warn "服务可能未启动，继续执行测试..."
fi

echo ""

# ==================== 执行负载测试 ====================

log_info "开始负载测试..."
echo ""

WORKER_START_TIME=$(date +%s%N)

# 启动所有工作线程
worker_pids=()
for w in $(seq 0 $((CONCURRENT - 1))); do
    worker "$w" &
    worker_pids+=($!)
done

# 等待所有工作线程完成
FAILED_WORKERS=0
for pid in "${worker_pids[@]}"; do
    if ! wait "$pid"; then
        FAILED_WORKERS=$((FAILED_WORKERS + 1))
    fi
done

WORKER_END_TIME=$(date +%s%N)
TOTAL_DURATION_MS=$(( (WORKER_END_TIME - WORKER_START_TIME) / 1000000 ))

echo ""

# ==================== 统计结果 ====================

log_info "统计测试结果..."

# 合并所有worker的timing数据
echo "worker_id,request_id,status_code,time_total_ms,time_connect_ms,time_starttransfer_ms,size_bytes" > "$SUMMARY_FILE"
for f in "$TIMING_DIR"/worker_*.csv; do
    tail -n +2 "$f" >> "$SUMMARY_FILE" 2>/dev/null || true
done

# 计算统计信息
TOTAL_LINES=$(tail -n +2 "$SUMMARY_FILE" | wc -l | tr -d ' ')

if [[ "$TOTAL_LINES" -eq 0 ]]; then
    log_error "没有收集到任何测试结果"
    exit 1
fi

# 成功数 (HTTP 2xx)
SUCCESS_COUNT=$(tail -n +2 "$SUMMARY_FILE" | awk -F',' '$3 >= 200 && $3 < 300 {count++} END {print count+0}')

# 失败数
FAIL_COUNT=$((TOTAL_LINES - SUCCESS_COUNT))

# 错误码分布
echo "$TOTAL_LINES" > "$RESULTS_DIR/total.txt"
echo "$SUCCESS_COUNT" > "$RESULTS_DIR/success.txt"
echo "$FAIL_COUNT" > "$RESULTS_DIR/fail.txt"

# 响应时间统计 (使用awk)
STATS=$(tail -n +2 "$SUMMARY_FILE" | awk -F',' '
    NR == 1 {
        min = $4; max = $4; sum = 0; sum_sq = 0;
        # 用于P50/P95/P99的数组
    }
    {
        val = $4 + 0;
        sum += val;
        sum_sq += val * val;
        count++;
        if (val < min) min = val;
        if (val > max) max = val;
        vals[count] = val;
    }
    END {
        if (count == 0) {
            print "0,0,0,0,0,0";
            exit;
        }
        avg = sum / count;
        variance = (sum_sq / count) - (avg * avg);
        stddev = (variance > 0) ? sqrt(variance) : 0;
        
        # 简单排序取百分位
        # 用asorti的方式不太方便，用近似法
        printf "%.3f,%.3f,%.3f,%.3f,%.3f,%.3f", avg, min, max, stddev, sum, count;
    }
')

IFS=',' read -r AVG_MS MIN_MS MAX_MS STDDEV_MS TOTAL_TIME_MS _ <<< "$STATS"

# 计算P50/P95/P99 (使用sort + head)
SORTED_TIMES=$(tail -n +2 "$SUMMARY_FILE" | awk -F',' '{print $4}' | sort -n)
P50_INDEX=$(echo "$TOTAL_LINES * 50 / 100" | bc 2>/dev/null || echo "1")
P95_INDEX=$(echo "$TOTAL_LINES * 95 / 100" | bc 2>/dev/null || echo "1")
P99_INDEX=$(echo "$TOTAL_LINES * 99 / 100" | bc 2>/dev/null || echo "1")

P50_MS=$(echo "$SORTED_TIMES" | sed -n "${P50_INDEX}p")
P95_MS=$(echo "$SORTED_TIMES" | sed -n "${P95_INDEX}p")
P99_MS=$(echo "$SORTED_TIMES" | sed -n "${P99_INDEX}p")

# 默认值处理
P50_MS=${P50_MS:-0}
P95_MS=${P95_MS:-0}
P99_MS=${P99_MS:-0}

# 吞吐量
THROUGHPUT=$(echo "scale=1; $TOTAL_LINES * 1000 / $TOTAL_DURATION_MS" | bc 2>/dev/null || echo "0")

# 错误码分布
ERROR_DIST=$(tail -n +2 "$SUMMARY_FILE" | awk -F',' '
    $3 != "200" && $3 != "201" {
        codes[$3]++
    }
    END {
        for (code in codes) {
            printf "    HTTP %s: %d次\n", code, codes[code];
        }
    }
')

# ==================== 输出报告 ====================

echo ""
echo -e "${BOLD}╔══════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║              测试结果报告                    ║${NC}"
echo -e "${BOLD}╚══════════════════════════════════════════════╝${NC}"
echo ""
echo -e "  ${BOLD}总请求数:${NC}     $TOTAL_LINES"
echo -e "  ${BOLD}成功数:${NC}       ${GREEN}${SUCCESS_COUNT}${NC}"
echo -e "  ${BOLD}失败数:${NC}       ${RED}${FAIL_COUNT}${NC}"
echo -e "  ${BOLD}成功率:${NC}       $(echo "scale=1; $SUCCESS_COUNT * 100 / $TOTAL_LINES" | bc 2>/dev/null || echo "0")%"
echo ""
echo -e "  ${BOLD}总耗时:${NC}       ${TOTAL_DURATION_MS} ms"
echo -e "  ${BOLD}吞吐量:${NC}       ${THROUGHPUT} req/s"
echo ""
echo -e "  ${BOLD}响应时间 (ms):${NC}"
echo -e "    平均:         ${AVG_MS}"
echo -e "    最小:         ${MIN_MS}"
echo -e "    最大:         ${MAX_MS}"
echo -e "    P50:          ${P50_MS}"
echo -e "    P95:          ${P95_MS}"
echo -e "    P99:          ${P99_MS}"
echo -e "    标准差:       ${STDDEV_MS}"

if [[ -n "$ERROR_DIST" ]]; then
    echo ""
    echo -e "  ${BOLD}错误分布:${NC}"
    echo -e "$ERROR_DIST"
fi

if [[ "$FAILED_WORKERS" -gt 0 ]]; then
    echo ""
    log_warn "有 ${FAILED_WORKERS} 个工作线程异常退出"
fi

echo ""

# ==================== 质量检查 ====================

PASS=true

SUCCESS_RATE=$(echo "scale=0; $SUCCESS_COUNT * 100 / $TOTAL_LINES" | bc 2>/dev/null || echo "0")
if [[ "$SUCCESS_RATE" -lt 95 ]]; then
    log_error "成功率 ${SUCCESS_RATE}% 低于阈值 95%"
    PASS=false
fi

AVG_INT=$(echo "$AVG_MS" | cut -d'.' -f1)
if [[ "${AVG_INT:-0}" -gt 2000 ]]; then
    log_error "平均响应时间 ${AVG_MS}ms 超过阈值 2000ms"
    PASS=false
fi

if [[ "$PASS" == "true" ]]; then
    echo -e "${GREEN}${BOLD}✓ 负载测试通过${NC}"
else
    echo -e "${RED}${BOLD}✗ 负载测试未通过${NC}"
fi

echo ""
