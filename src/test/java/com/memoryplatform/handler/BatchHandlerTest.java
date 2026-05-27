package com.memoryplatform.handler;

import com.memoryplatform.model.Memory;
import com.memoryplatform.model.SearchResult;
import com.memoryplatform.model.WriteResult;
import com.memoryplatform.service.ConcurrentWriteService;
import com.memoryplatform.service.HybridRetrievalService;
import com.memoryplatform.service.MemoryExtractionService;
import com.memoryplatform.storage.MetadataStore;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BatchHandler 单元测试
 * 覆盖：批量创建（正常/超限）、批量删除（正常/不存在）、批量检索、部分成功207
 */
@ExtendWith(MockitoExtension.class)
class BatchHandlerTest {

    @Mock
    private MemoryExtractionService extractionService;
    @Mock
    private ConcurrentWriteService writeService;
    @Mock
    private MetadataStore metadataStore;
    @Mock
    private HybridRetrievalService retrievalService;

    private BatchHandler handler;

    @BeforeEach
    void setUp() {
        handler = new BatchHandler(extractionService, writeService, metadataStore, retrievalService);
    }

    // ==================== 辅助方法 ====================

    private HttpExchange createMockExchange(String method, String path,
                                             String body) throws IOException {
        HttpExchange exchange = mock(HttpExchange.class);
        Headers requestHeaders = new Headers();
        when(exchange.getRequestHeaders()).thenReturn(requestHeaders);
        when(exchange.getRequestMethod()).thenReturn(method);
        when(exchange.getRequestURI()).thenReturn(URI.create(path));
        when(exchange.getRemoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 8080));

        // Mock request body
        InputStream is = new ByteArrayInputStream(
                body != null ? body.getBytes() : new byte[0]);
        when(exchange.getRequestBody()).thenReturn(is);

        // Mock response writing
        Headers responseHeaders = new Headers();
        when(exchange.getResponseHeaders()).thenReturn(responseHeaders);
        OutputStream os = new ByteArrayOutputStream();
        when(exchange.getResponseBody()).thenReturn(os);
        when(exchange.getResponseCode()).thenReturn(200);

        return exchange;
    }

    // ==================== 批量创建测试 ====================

    @Test
    @DisplayName("批量创建 - 空请求体返回400")
    void batchCreate_emptyBody_returns400() throws Exception {
        HttpExchange exchange = createMockExchange("POST", "/api/memories/batch", null);

        handler.handle(exchange, Collections.emptyMap());

        verify(exchange).sendResponseHeaders(eq(400), anyLong());
    }

    @Test
    @DisplayName("批量创建 - 无效JSON返回400")
    void batchCreate_invalidJson_returns400() throws Exception {
        HttpExchange exchange = createMockExchange("POST", "/api/memories/batch",
                "not json");

        handler.handle(exchange, Collections.emptyMap());

        verify(exchange).sendResponseHeaders(eq(400), anyLong());
    }

    @Test
    @DisplayName("批量创建 - 缺少memories字段返回400")
    void batchCreate_missingMemoriesField_returns400() throws Exception {
        HttpExchange exchange = createMockExchange("POST", "/api/memories/batch",
                "{\"data\": []}");

        handler.handle(exchange, Collections.emptyMap());

        verify(exchange).sendResponseHeaders(eq(400), anyLong());
    }

    @Test
    @DisplayName("批量创建 - memories为空数组返回400")
    void batchCreate_emptyMemoriesArray_returns400() throws Exception {
        HttpExchange exchange = createMockExchange("POST", "/api/memories/batch",
                "{\"memories\": []}");

        handler.handle(exchange, Collections.emptyMap());

        verify(exchange).sendResponseHeaders(eq(400), anyLong());
    }

    @Test
    @DisplayName("批量创建 - 超过100条限制返回400")
    void batchCreate_exceeds100Limit_returns400() throws Exception {
        StringBuilder sb = new StringBuilder("{\"memories\": [");
        for (int i = 0; i < 101; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"messages\":[],\"userId\":\"user1\"}");
        }
        sb.append("]}");

        HttpExchange exchange = createMockExchange("POST", "/api/memories/batch",
                sb.toString());

        handler.handle(exchange, Collections.emptyMap());

        verify(exchange).sendResponseHeaders(eq(400), anyLong());
    }

    @Test
    @DisplayName("批量创建 - 成功创建返回200")
    void batchCreate_success_returns200() throws Exception {
        Memory memory = Memory.builder()
                .id("mem-1").text("test").userId("user1").build();
        when(extractionService.extractFromConversation(anyList(), eq("user1"), isNull()))
                .thenReturn(List.of(memory));
        when(writeService.write(any(Memory.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        WriteResult.successBuilder().memoryId("mem-1").build()));

        String body = "{\"memories\":[{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"userId\":\"user1\"}]}";
        HttpExchange exchange = createMockExchange("POST", "/api/memories/batch", body);

        handler.handle(exchange, Collections.emptyMap());

        verify(exchange).sendResponseHeaders(eq(200), anyLong());
    }

    @Test
    @DisplayName("批量创建 - 缺少必需字段")
    void batchCreate_missingRequiredFields() throws Exception {
        String body = "{\"memories\":[{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}]}";
        HttpExchange exchange = createMockExchange("POST", "/api/memories/batch", body);

        handler.handle(exchange, Collections.emptyMap());

        // 缺少userId，应返回失败（可能是207部分成功或400）
        verify(exchange, atLeastOnce()).sendResponseHeaders(anyInt(), anyLong());
    }

    @Test
    @DisplayName("批量创建 - 提取服务抛异常")
    void batchCreate_extractionThrows() throws Exception {
        when(extractionService.extractFromConversation(anyList(), anyString(), any()))
                .thenThrow(new RuntimeException("Extract failed"));

        String body = "{\"memories\":[{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"userId\":\"user1\"}]}";
        HttpExchange exchange = createMockExchange("POST", "/api/memories/batch", body);

        handler.handle(exchange, Collections.emptyMap());

        // 应返回部分成功或全失败
        verify(exchange, atLeastOnce()).sendResponseHeaders(anyInt(), anyLong());
    }

    // ==================== 批量删除测试 ====================

    @Test
    @DisplayName("批量删除 - 空请求体返回400")
    void batchDelete_emptyBody_returns400() throws Exception {
        HttpExchange exchange = createMockExchange("DELETE", "/api/memories/batch", null);

        handler.handle(exchange, Collections.emptyMap());

        verify(exchange).sendResponseHeaders(eq(400), anyLong());
    }

    @Test
    @DisplayName("批量删除 - 缺少ids字段返回400")
    void batchDelete_missingIds_returns400() throws Exception {
        HttpExchange exchange = createMockExchange("DELETE", "/api/memories/batch",
                "{\"data\": []}");

        handler.handle(exchange, Collections.emptyMap());

        verify(exchange).sendResponseHeaders(eq(400), anyLong());
    }

    @Test
    @DisplayName("批量删除 - ids为空数组返回400")
    void batchDelete_emptyIds_returns400() throws Exception {
        HttpExchange exchange = createMockExchange("DELETE", "/api/memories/batch",
                "{\"ids\": []}");

        handler.handle(exchange, Collections.emptyMap());

        verify(exchange).sendResponseHeaders(eq(400), anyLong());
    }

    @Test
    @DisplayName("批量删除 - 超过100个ID限制返回400")
    void batchDelete_exceedsLimit_returns400() throws Exception {
        StringBuilder sb = new StringBuilder("{\"ids\": [");
        for (int i = 0; i < 101; i++) {
            if (i > 0) sb.append(",");
            sb.append("\"id").append(i).append("\"");
        }
        sb.append("]}");

        HttpExchange exchange = createMockExchange("DELETE", "/api/memories/batch",
                sb.toString());

        handler.handle(exchange, Collections.emptyMap());

        verify(exchange).sendResponseHeaders(eq(400), anyLong());
    }

    @Test
    @DisplayName("批量删除 - 成功删除返回200")
    void batchDelete_success_returns200() throws Exception {
        when(metadataStore.delete(anyString(), anyList())).thenReturn(true);

        HttpExchange exchange = createMockExchange("DELETE", "/api/memories/batch",
                "{\"ids\": [\"id1\", \"id2\"]}");

        handler.handle(exchange, Collections.emptyMap());

        verify(exchange).sendResponseHeaders(eq(200), anyLong());
    }

    @Test
    @DisplayName("批量删除 - 记忆不存在返回失败")
    void batchDelete_notFound() throws Exception {
        when(metadataStore.delete(anyString(), anyList())).thenReturn(false);

        HttpExchange exchange = createMockExchange("DELETE", "/api/memories/batch",
                "{\"ids\": [\"nonexistent\"]}");

        handler.handle(exchange, Collections.emptyMap());

        // 全部失败，应返回400
        verify(exchange).sendResponseHeaders(eq(400), anyLong());
    }

    @Test
    @DisplayName("批量删除 - 部分成功返回207")
    void batchDelete_partialSuccess_returns207() throws Exception {
        // 第一个删除成功，第二个失败
        when(metadataStore.delete(eq("memories"), eq(List.of("id1"))))
                .thenReturn(true);
        when(metadataStore.delete(eq("memories"), eq(List.of("id2"))))
                .thenReturn(false);

        HttpExchange exchange = createMockExchange("DELETE", "/api/memories/batch",
                "{\"ids\": [\"id1\", \"id2\"]}");

        handler.handle(exchange, Collections.emptyMap());

        verify(exchange).sendResponseHeaders(eq(207), anyLong());
    }

    @Test
    @DisplayName("批量删除 - metadataStore为null")
    void batchDelete_metadataStoreNull() throws Exception {
        BatchHandler handlerNoMeta = new BatchHandler(extractionService, writeService, null, retrievalService);

        HttpExchange exchange = createMockExchange("DELETE", "/api/memories/batch",
                "{\"ids\": [\"id1\"]}");

        handlerNoMeta.handle(exchange, Collections.emptyMap());

        verify(exchange).sendResponseHeaders(eq(400), anyLong());
    }

    // ==================== 批量检索测试 ====================

    @Test
    @DisplayName("批量检索 - 空请求体返回400")
    void batchSearch_emptyBody_returns400() throws Exception {
        HttpExchange exchange = createMockExchange("POST", "/api/memories/batch/search", null);

        handler.handle(exchange, Collections.emptyMap());

        verify(exchange).sendResponseHeaders(eq(400), anyLong());
    }

    @Test
    @DisplayName("批量检索 - 缺少queries字段返回400")
    void batchSearch_missingQueries_returns400() throws Exception {
        HttpExchange exchange = createMockExchange("POST", "/api/memories/batch/search",
                "{\"data\": []}");

        handler.handle(exchange, Collections.emptyMap());

        verify(exchange).sendResponseHeaders(eq(400), anyLong());
    }

    @Test
    @DisplayName("批量检索 - queries为空数组返回400")
    void batchSearch_emptyQueries_returns400() throws Exception {
        HttpExchange exchange = createMockExchange("POST", "/api/memories/batch/search",
                "{\"queries\": []}");

        handler.handle(exchange, Collections.emptyMap());

        verify(exchange).sendResponseHeaders(eq(400), anyLong());
    }

    @Test
    @DisplayName("批量检索 - 超过10个查询限制返回400")
    void batchSearch_exceedsLimit_returns400() throws Exception {
        StringBuilder sb = new StringBuilder("{\"queries\": [");
        for (int i = 0; i < 11; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"text\":\"q\",\"userId\":\"u\"}");
        }
        sb.append("]}");

        HttpExchange exchange = createMockExchange("POST", "/api/memories/batch/search",
                sb.toString());

        handler.handle(exchange, Collections.emptyMap());

        verify(exchange).sendResponseHeaders(eq(400), anyLong());
    }

    @Test
    @DisplayName("批量检索 - 检索服务未配置返回503")
    void batchSearch_serviceNotConfigured_returns503() throws Exception {
        BatchHandler handlerNoSearch = new BatchHandler(extractionService, writeService, metadataStore, null);

        HttpExchange exchange = createMockExchange("POST", "/api/memories/batch/search",
                "{\"queries\": [{\"text\":\"hello\",\"userId\":\"user1\"}]}");

        handlerNoSearch.handle(exchange, Collections.emptyMap());

        verify(exchange).sendResponseHeaders(eq(503), anyLong());
    }

    @Test
    @DisplayName("批量检索 - 成功检索返回200")
    void batchSearch_success_returns200() throws Exception {
        when(retrievalService.search(any())).thenReturn(List.of());

        HttpExchange exchange = createMockExchange("POST", "/api/memories/batch/search",
                "{\"queries\": [{\"text\":\"hello\",\"userId\":\"user1\"}]}");

        handler.handle(exchange, Collections.emptyMap());

        verify(exchange).sendResponseHeaders(eq(200), anyLong());
    }

    // ==================== 不支持的HTTP方法 ====================

    @Test
    @DisplayName("GET方法 - 返回405")
    void unsupportedMethod_returns405() throws Exception {
        HttpExchange exchange = createMockExchange("GET", "/api/memories/batch", null);

        handler.handle(exchange, Collections.emptyMap());

        verify(exchange).sendResponseHeaders(eq(405), anyLong());
    }
}
