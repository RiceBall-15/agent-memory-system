#!/bin/bash
# =============================================================================
# 快速性能测试脚本 - 用于日常开发测试
# =============================================================================

set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
ITERATIONS="${2:-50}"
CONCURRENCY="${3:-10}"

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║            快速性能测试                                    ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo "  目标: $BASE_URL"
echo "  请求数: $ITERATIONS"
echo "  并发数: $CONCURRENCY"
echo ""

# 检查服务状态
echo "检查服务状态..."
if ! curl -s --connect-timeout 5 "${BASE_URL}/health" > /dev/null 2>&1; then
    echo "警告: 服务可能未启动"
fi

# 创建测试数据
echo ""
echo "=== 创建操作测试 ==="
CREATE_START=$(date +%s%N)
for i in $(seq 1 $ITERATIONS); do
    TOPIC=$(echo "机器学习 Web开发 分布式系统 数据库优化 容器技术" | tr ' ' '\n' | shuf -n 1)
    curl -s -X POST "${BASE_URL}/api/memories" \
        -H "Content-Type: application/json" \
        -d "{
            \"messages\": [{\"role\": \"user\", \"content\": \"测试${TOPIC}问题${i}\"}],
            \"userId\": \"quick-test\",
            \"agentId\": \"quick-agent\"
        }" > /dev/null &
done
wait
CREATE_END=$(date +%s%N)
CREATE_DURATION=$(( (CREATE_END - CREATE_START) / 1000000 ))
echo "创建完成: ${CREATE_DURATION}ms"

# 读取测试
echo ""
echo "=== 读取操作测试 ==="
READ_START=$(date +%s%N)
for i in $(seq 1 $ITERATIONS); do
    curl -s "${BASE_URL}/api/memories?userId=quick-test&limit=10" > /dev/null &
done
wait
READ_END=$(date +%s%N)
READ_DURATION=$(( (READ_END - READ_START) / 1000000 ))
echo "读取完成: ${READ_DURATION}ms"

# 搜索测试
echo ""
echo "=== 搜索操作测试 ==="
SEARCH_START=$(date +%s%N)
for i in $(seq 1 $ITERATIONS); do
    curl -s -X POST "${BASE_URL}/api/memories/search" \
        -H "Content-Type: application/json" \
        -d "{\"text\": \"机器学习\", \"userId\": \"quick-test\", \"topK\": 10}" > /dev/null &
done
wait
SEARCH_END=$(date +%s%N)
SEARCH_DURATION=$(( (SEARCH_END - SEARCH_START) / 1000000 ))
echo "搜索完成: ${SEARCH_DURATION}ms"

# 总结
echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                    测试结果                                ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
echo "  创建操作: ${CREATE_DURATION}ms ($(( ITERATIONS * 1000 / (CREATE_DURATION + 1) )) req/s)"
echo "  读取操作: ${READ_DURATION}ms ($(( ITERATIONS * 1000 / (READ_DURATION + 1) )) req/s)"
echo "  搜索操作: ${SEARCH_DURATION}ms ($(( ITERATIONS * 1000 / (SEARCH_DURATION + 1) )) req/s)"
echo ""
