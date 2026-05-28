#!/bin/bash
# =============================================================================
# 性能测试脚本 - API全面性能测试
#
# 功能:
#   - 测试CRUD操作性能
#   - 测试语义搜索性能
#   - 测试并发处理能力
#   - 输出详细性能报告
#
# 用法:
#   chmod +x scripts/performance-test.sh
#   ./scripts/performance-test.sh [BASE_URL] [ITERATIONS] [CONCURRENCY]
#
# 示例:
#   ./scripts/performance-test.sh http://localhost:8080 100 20
#   ./scripts/performance-test.sh  # 使用默认值
# =============================================================================

set -euo pipefail

# ==================== 配置 ====================

BASE_URL="${1:-http://localhost:8080}"
ITERATIONS="${2:-100}"
CONCURRENCY="${3:-20}"

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m'

# 临时文件
RESULTS_DIR=$(mktemp -d)
mkdir -p "$RESULTS_DIR"

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

print_header() {
    echo ""
    echo -e "${BOLD}╔══════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${BOLD}║            $1"
    echo -e "${BOLD}╚══════════════════════════════════════════════════════════════╝${NC}"
    echo ""
}

print_metric() {
    local label="$1"
    local value="$2"
    local unit="${3:-}"
    printf "  %-20s ${BOLD}%-15s${NC} %s\n" "$label" "$value" "$unit"
}

# 生成测试数据
generate_memory_payload() {
    local index=$1
    local topics=("机器学习" "Web开发" "分布式系统" "数据库优化" "容器技术" "微服务" "云原生" "网络安全" "人工智能" "系统设计")
    local topic=${topics[$((index % ${#topics[@]}))]}

    cat <<EOF
{
    "messages": [
        {"role": "user", "content": "我在${topic}方面有${index}个问题"},
        {"role": "assistant", "content": "好的，请问关于${topic}的具体问题"},
        {"role": "user", "content": "我想了解${topic}的最佳实践"}
    ],
    "userId": "perf-test-user-$((index % 10))",
    "agentId": "perf-test-agent"
}
EOF
}

# ==================== 健康检查 ====================

check_health() {
    log_info "检查服务健康状态..."

    local response
    response=$(curl -s -w "\n%{http_code}" --connect-timeout 5 --max-time 10 "${BASE_URL}/health" 2>/dev/null || echo -e "\n000")
    local http_code=$(echo "$response" | tail -1)

    if [[ "$http_code" == "200" ]]; then
        log_success "服务健康检查通过"
        return 0
    else
        log_warn "服务健康检查失败 (HTTP $http_code)"
        return 1
    fi
}

# ==================== 单次请求测试 ====================

single_request_test() {
    local url="$1"
    local method="${2:-GET}"
    local data="${3:-}"
    local label="$4"
    local index="$5"

    local tmpfile
    tmpfile=$(mktemp)
    local timing_file="$RESULTS_DIR/timing_${label}.csv"

    if [[ "$index" -eq 0 ]]; then
        echo "index,http_code,time_total_ms,time_connect_ms,time_ttfb_ms,size_bytes" > "$timing_file"
    fi

    local curl_args=(-s -o "$tmpfile" -w "%{http_code},%{time_total},%{time_connect},%{time_starttransfer},%{size_download}")
    curl_args+=(-X "$method" --connect-timeout 10 --max-time 30)

    if [[ -n "$data" ]]; then
        curl_args+=(-H "Content-Type: application/json" -d "$data")
    fi

    local result
    result=$(curl "${curl_args[@]}" "$url" 2>/dev/null || echo "000,0,0,0,0")

    IFS=',' read -r code time_total time_connect time_ttfb size <<< "$result"

    local time_total_ms=$(echo "$time_total * 1000" | bc 2>/dev/null || echo "0")
    local time_connect_ms=$(echo "$time_connect * 1000" | bc 2>/dev/null || echo "0")
    local time_ttfb_ms=$(echo "$time_ttfb * 1000" | bc 2>/dev/null || echo "0")

    echo "${index},${code},${time_total_ms},${time_connect_ms},${time_ttfb_ms},${size}" >> "$timing_file"
    rm -f "$tmpfile"
}

# ==================== 并发测试 ====================

concurrent_test() {
    local test_name="$1"
    local test_func="$2"

    log_info "开始并发测试: $test_name (并发=$CONCURRENCY, 总数=$ITERATIONS)"

    local start_time=$(date +%s%N)

    # 启动并发任务
    local pids=()
    for i in $(seq 0 $((ITERATIONS - 1))); do
        $test_func "$i" &
        pids+=($!)

        # 控制并发数
        if [[ ${#pids[@]} -ge $CONCURRENCY ]]; then
            wait "${pids[0]}" 2>/dev/null || true
            pids=("${pids[@]:1}")
        fi
    done

    # 等待所有任务完成
    for pid in "${pids[@]}"; do
        wait "$pid" 2>/dev/null || true
    done

    local end_time=$(date +%s%N)
    local duration_ms=$(( (end_time - start_time) / 1000000 ))

    echo "$duration_ms"
}

# ==================== 分析结果 ====================

analyze_results() {
    local label="$1"
    local timing_file="$RESULTS_DIR/timing_${label}.csv"

    if [[ ! -f "$timing_file" ]]; then
        echo "无数据"
        return
    fi

    local data_lines=$(tail -n +2 "$timing_file" | wc -l)
    if [[ "$data_lines" -eq 0 ]]; then
        echo "无数据"
        return
    fi

    # 使用awk计算统计
    tail -n +2 "$timing_file" | awk -F',' '
    BEGIN {
        count=0; sum=0; min=999999; max=0; success=0
    }
    {
        count++
        val = $3 + 0
        sum += val
        if (val < min) min = val
        if (val > max) max = val
        if ($2 >= 200 && $2 < 300) success++
        vals[count] = val
    }
    END {
        if (count == 0) {
            print "0,0,0,0,0,0,0"
            exit
        }
        avg = sum / count

        # 排序获取百分位数
        for (i = 1; i <= count; i++) {
            for (j = i+1; j <= count; j++) {
                if (vals[i] > vals[j]) {
                    tmp = vals[i]
                    vals[i] = vals[j]
                    vals[j] = tmp
                }
            }
        }

        p50_idx = int(count * 0.5)
        p95_idx = int(count * 0.95)
        p99_idx = int(count * 0.99)

        p50 = vals[p50_idx > 0 ? p50_idx : 1]
        p95 = vals[p95_idx > 0 ? p95_idx : 1]
        p99 = vals[p99_idx > 0 ? p99_idx : 1]

        printf "%d,%.2f,%.2f,%.2f,%.2f,%.2f,%.1f\n", count, avg, min, max, p50, p95, p99
    }'
}

# ==================== 测试函数 ====================

test_create_memory() {
    local index=$1
    local payload=$(generate_memory_payload "$index")
    single_request_test "${BASE_URL}/api/memories" "POST" "$payload" "create" "$index"
}

test_get_memory() {
    local index=$1
    # 先创建再获取
    local payload=$(generate_memory_payload "$index")
    local response=$(curl -s -X POST -H "Content-Type: application/json" -d "$payload" "${BASE_URL}/api/memories" 2>/dev/null)
    local id=$(echo "$response" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

    if [[ -n "$id" ]]; then
        single_request_test "${BASE_URL}/api/memories/${id}" "GET" "" "get" "$index"
    else
        echo "${index},000,0,0,0,0" >> "$RESULTS_DIR/timing_get.csv"
    fi
}

test_search_memory() {
    local index=$1
    local queries=("机器学习" "Web开发" "分布式系统" "数据库优化" "容器技术")
    local query=${queries[$((index % ${#queries[@]}))]}

    local payload="{\"text\": \"${query}\", \"userId\": \"perf-test-user-$((index % 10))\", \"topK\": 10}"
    single_request_test "${BASE_URL}/api/memories/search" "POST" "$payload" "search" "$index"
}

test_list_memories() {
    local index=$1
    single_request_test "${BASE_URL}/api/memories?userId=perf-test-user-$((index % 10))&limit=20&offset=0" "GET" "" "list" "$index"
}

# ==================== 主测试流程 ====================

echo ""
echo -e "${BOLD}╔══════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║          Agent Memory System - 性能测试                     ║${NC}"
echo -e "${BOLD}╚══════════════════════════════════════════════════════════════╝${NC}"
echo ""
print_metric "目标地址" "$BASE_URL"
print_metric "迭代次数" "$ITERATIONS"
print_metric "并发数" "$CONCURRENCY"
echo ""

# 健康检查
if ! check_health; then
    log_warn "服务可能未启动，继续执行测试..."
fi

echo ""
TOTAL_START=$(date +%s%N)

# ==================== 1. 创建操作测试 ====================

print_header "1. 创建记忆性能测试"

CREATE_DURATION=$(concurrent_test "create" "test_create_memory")
CREATE_STATS=$(analyze_results "create")
IFS=',' read -r C_COUNT C_AVG C_MIN C_MAX C_P50 C_P95 C_P99 <<< "$CREATE_STATS"

C_QPS=0
if [[ "$CREATE_DURATION" -gt 0 ]]; then
    C_QPS=$(echo "scale=1; $C_COUNT * 1000 / $CREATE_DURATION" | bc 2>/dev/null || echo "0")
fi

print_metric "请求数" "$C_COUNT"
print_metric "耗时" "${CREATE_DURATION}ms"
print_metric "QPS" "$C_QPS" "req/s"
print_metric "平均延迟" "${C_AVG}ms"
print_metric "P50延迟" "${C_P50}ms"
print_metric "P95延迟" "${C_P95}ms"
print_metric "P99延迟" "${C_P99}ms"
print_metric "最小延迟" "${C_MIN}ms"
print_metric "最大延迟" "${C_MAX}ms"

# ==================== 2. 读取操作测试 ====================

print_header "2. 读取记忆性能测试"

GET_DURATION=$(concurrent_test "get" "test_get_memory")
GET_STATS=$(analyze_results "get")
IFS=',' read -r G_COUNT G_AVG G_MIN G_MAX G_P50 G_P95 G_P99 <<< "$GET_STATS"

G_QPS=0
if [[ "$GET_DURATION" -gt 0 ]]; then
    G_QPS=$(echo "scale=1; $G_COUNT * 1000 / $GET_DURATION" | bc 2>/dev/null || echo "0")
fi

print_metric "请求数" "$G_COUNT"
print_metric "耗时" "${GET_DURATION}ms"
print_metric "QPS" "$G_QPS" "req/s"
print_metric "平均延迟" "${G_AVG}ms"
print_metric "P50延迟" "${G_P50}ms"
print_metric "P95延迟" "${G_P95}ms"
print_metric "P99延迟" "${G_P99}ms"

# ==================== 3. 搜索操作测试 ====================

print_header "3. 语义搜索性能测试"

SEARCH_DURATION=$(concurrent_test "search" "test_search_memory")
SEARCH_STATS=$(analyze_results "search")
IFS=',' read -r S_COUNT S_AVG S_MIN S_MAX S_P50 S_P95 S_P99 <<< "$SEARCH_STATS"

S_QPS=0
if [[ "$SEARCH_DURATION" -gt 0 ]]; then
    S_QPS=$(echo "scale=1; $S_COUNT * 1000 / $SEARCH_DURATION" | bc 2>/dev/null || echo "0")
fi

print_metric "请求数" "$S_COUNT"
print_metric "耗时" "${SEARCH_DURATION}ms"
print_metric "QPS" "$S_QPS" "req/s"
print_metric "平均延迟" "${S_AVG}ms"
print_metric "P50延迟" "${S_P50}ms"
print_metric "P95延迟" "${S_P95}ms"
print_metric "P99延迟" "${S_P99}ms"

# ==================== 4. 列表查询测试 ====================

print_header "4. 列表查询性能测试"

LIST_DURATION=$(concurrent_test "list" "test_list_memories")
LIST_STATS=$(analyze_results "list")
IFS=',' read -r L_COUNT L_AVG L_MIN L_MAX L_P50 L_P95 L_P99 <<< "$LIST_STATS"

L_QPS=0
if [[ "$LIST_DURATION" -gt 0 ]]; then
    L_QPS=$(echo "scale=1; $L_COUNT * 1000 / $LIST_DURATION" | bc 2>/dev/null || echo "0")
fi

print_metric "请求数" "$L_COUNT"
print_metric "耗时" "${LIST_DURATION}ms"
print_metric "QPS" "$L_QPS" "req/s"
print_metric "平均延迟" "${L_AVG}ms"
print_metric "P50延迟" "${L_P50}ms"
print_metric "P95延迟" "${L_P95}ms"
print_metric "P99延迟" "${L_P99}ms"

# ==================== 总结报告 ====================

TOTAL_END=$(date +%s%N)
TOTAL_DURATION=$(( (TOTAL_END - TOTAL_START) / 1000000 ))

TOTAL_REQUESTS=$((C_COUNT + G_COUNT + S_COUNT + L_COUNT))
TOTAL_QPS=0
if [[ "$TOTAL_DURATION" -gt 0 ]]; then
    TOTAL_QPS=$(echo "scale=1; $TOTAL_REQUESTS * 1000 / $TOTAL_DURATION" | bc 2>/dev/null || echo "0")
fi

echo ""
print_header "性能测试总结"

echo -e "${BOLD}┌─────────────────────────────────────────────────────┐${NC}"
echo -e "${BOLD}│                  测试结果汇总                       │${NC}"
echo -e "${BOLD}├─────────────────────────────────────────────────────┤${NC}"
print_metric "总请求数" "$TOTAL_REQUESTS"
print_metric "总耗时" "${TOTAL_DURATION}ms"
print_metric "总QPS" "$TOTAL_QPS" "req/s"
echo -e "${BOLD}├─────────────────────────────────────────────────────┤${NC}"
echo -e "${BOLD}│                 各操作对比                          │${NC}"
echo -e "${BOLD}├──────────────┬──────────┬──────────┬───────────────┤${NC}"
printf "${BOLD}│ %-12s │ %-8s │ %-8s │ %-13s │${NC}\n" "操作" "QPS" "平均延迟" "P99延迟"
echo -e "${BOLD}├──────────────┼──────────┼──────────┼───────────────┤${NC}"
printf "│ %-12s │ %-8s │ %-8s │ %-13s │\n" "创建" "$C_QPS" "${C_AVG}ms" "${C_P99}ms"
printf "│ %-12s │ %-8s │ %-8s │ %-13s │\n" "读取" "$G_QPS" "${G_AVG}ms" "${G_P99}ms"
printf "│ %-12s │ %-8s │ %-8s │ %-13s │\n" "搜索" "$S_QPS" "${S_AVG}ms" "${S_P99}ms"
printf "│ %-12s │ %-8s │ %-8s │ %-13s │\n" "列表" "$L_QPS" "${L_AVG}ms" "${L_P99}ms"
echo -e "${BOLD}└──────────────┴──────────┴──────────┴───────────────┘${NC}"

# ==================== 性能评估 ====================

echo ""
log_info "性能评估:"
PASS=true

# QPS阈值检查
if [[ $(echo "$TOTAL_QPS < 10" | bc -l 2>/dev/null || echo "0") -eq 1 ]]; then
    log_warn "总QPS ($TOTAL_QPS) 较低，建议检查系统配置"
fi

# P99延迟检查
for p99 in $C_P99 $G_P99 $S_P99 $L_P99; do
    if [[ $(echo "$p99 > 5000" | bc -l 2>/dev/null || echo "0") -eq 1 ]]; then
        log_error "P99延迟 ($p99 ms) 超过阈值 5000ms"
        PASS=false
    fi
done

if [[ "$PASS" == "true" ]]; then
    echo ""
    echo -e "${GREEN}${BOLD}✓ 性能测试通过${NC}"
else
    echo ""
    echo -e "${RED}${BOLD}✗ 性能测试未通过${NC}"
fi

echo ""
