package com.ruoyi.flowable.extension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfConnectorEndpoint;
import com.ruoyi.flowable.domain.WfDeployExtensionSnapshot;
import com.ruoyi.flowable.domain.vo.WorkflowConnectorInvocationClaim;
import com.ruoyi.flowable.service.model.WorkflowConnectorEndpointService;
import com.ruoyi.flowable.service.process.WorkflowConnectorInvocationService;

/**
 * 受控 HTTP 连接器部署冻结、真实请求和幂等终态测试。
 */
class WorkflowHttpConnectorTest
{
    private final ObjectMapper objectMapper = JsonMapper.shared();
    private HttpServer server;

    /**
     * 每个真实本机 HTTP Server 测试结束后释放监听端口。
     * @return void，避免测试进程遗留外部副作用
     */
    @AfterEach
    void stopServer()
    {
        if (server != null)
        {
            server.stop(0);
            server = null;
        }
    }

    /**
     * 验证冻结端点、JSON 请求正文、幂等请求头和成功状态变量的真实执行闭环。
     * @return void，本机 HTTP 请求、台账和 Flowable 变量任一不一致时测试失败
     * @throws Exception HTTP Server 或 JSON 处理失败
     */
    @Test
    void executesSuccessfulRequestAgainstRealHttpServer() throws Exception
    {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> idempotency = new AtomicReference<>();
        startServer(200, requestBody, idempotency, "{\"accepted\":true}");
        WorkflowConnectorInvocationService invocationService = mock(
                WorkflowConnectorInvocationService.class);
        when(invocationService.begin(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyInt(), anyString(), anyString(), anyString()))
                .thenReturn(new WorkflowConnectorInvocationClaim(3L, "RUNNING", "claim", 1, null));
        WorkflowHttpConnector connector = connector(invocationService);
        JsonNode config = objectMapper.readTree(connector.freezeConfig(authorConfig("POST",
                "/api/audit", "payload", "httpStatus"), true));
        DelegateExecution execution = execution();
        when(execution.hasVariable("payload")).thenReturn(true);
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", "审批完成");
        payload.put("amount", 12);
        when(execution.getVariable("payload")).thenReturn(payload);

        connector.execute(execution, snapshot(), config);

        assertThat(requestBody.get()).contains("\"amount\":12", "\"message\":\"审批完成\"");
        assertThat(idempotency.get()).matches("[0-9a-f]{64}");
        verify(invocationService).success(any(WorkflowConnectorInvocationClaim.class),
                anyLong(), eq(200), anyString());
        verify(execution).setVariable("httpStatus", 200L);
    }

    /**
     * 验证非 2xx 响应落失败台账一次并让 Flowable 看到失败，不能伪造成功。
     * @return void，真实 HTTP 业务失败或重复台账写入时测试失败
     * @throws Exception HTTP Server 启停失败
     */
    @Test
    void recordsNonSuccessHttpStatusExactlyOnce() throws Exception
    {
        startServer(500, new AtomicReference<>(), new AtomicReference<>(), "failed");
        WorkflowConnectorInvocationService invocationService = mock(
                WorkflowConnectorInvocationService.class);
        when(invocationService.begin(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyInt(), anyString(), anyString(), anyString()))
                .thenReturn(new WorkflowConnectorInvocationClaim(3L, "RUNNING", "claim", 1, null));
        WorkflowHttpConnector connector = connector(invocationService);
        JsonNode config = objectMapper.readTree(connector.freezeConfig(authorConfig("POST",
                "/api/audit", null, "httpStatus"), true));

        assertThatThrownBy(() -> connector.execute(execution(), snapshot(), config))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("非成功状态");
        verify(invocationService).failure(any(WorkflowConnectorInvocationClaim.class),
                anyLong(), eq(500), eq("HTTP_STATUS"), anyString());
        verify(invocationService, never()).success(any(), anyLong(), anyInt(), anyString());
    }

    /**
     * 验证成功台账重放不再访问真实 HTTP Server，只回填已记录的状态码。
     * @return void，幂等重放再次产生外部副作用时测试失败
     * @throws Exception JSON 解析失败
     */
    @Test
    void replaysSuccessfulLedgerWithoutCallingHttpServer() throws Exception
    {
        AtomicInteger requests = new AtomicInteger();
        startServer(204, new AtomicReference<>(), new AtomicReference<>(), "");
        server.removeContext("/api");
        server.createContext("/api", exchange ->
        {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        WorkflowConnectorInvocationService invocationService = mock(
                WorkflowConnectorInvocationService.class);
        when(invocationService.begin(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyInt(), anyString(), anyString(), anyString()))
                .thenReturn(new WorkflowConnectorInvocationClaim(3L, "SUCCESS", null, 1, 204));
        WorkflowHttpConnector connector = connector(invocationService);
        JsonNode config = objectMapper.readTree(connector.freezeConfig(authorConfig("POST",
                "/api/audit", null, "httpStatus"), true));
        DelegateExecution execution = execution();

        connector.execute(execution, snapshot(), config);

        assertThat(requests).hasValue(0);
        verify(execution).setVariable("httpStatus", 204L);
        verify(invocationService, never()).success(any(), anyLong(), anyInt(), anyString());
    }

    /**
     * 验证节点路径越过端点前缀和编码目录穿越在部署阶段失败，不创建运行台账。
     * @return void，路径白名单被绕过时测试失败
     */
    @Test
    void rejectsPathOutsideFrozenEndpointPrefix() throws Exception
    {
        startServer(200, new AtomicReference<>(), new AtomicReference<>(), "ok");
        WorkflowConnectorInvocationService invocationService = mock(
                WorkflowConnectorInvocationService.class);
        WorkflowHttpConnector connector = connector(invocationService);

        assertThatThrownBy(() -> connector.freezeConfig(authorConfig("POST", "/admin", null, null), true))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("越过端点白名单");
        assertThatThrownBy(() -> connector.freezeConfig(authorConfig("POST", "/api/%2e%2e/admin",
                null, null), true))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("请求路径不合法");
        verify(invocationService, never()).begin(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyInt(), anyString(), anyString(), anyString());
    }

    /**
     * 验证真实请求超过冻结超时后写入 TIMEOUT 台账，并把失败交还 Flowable 重试。
     * @return void，超时被误记为普通 IO 或伪造成功时测试失败
     * @throws Exception HTTP Server 或 JSON 处理失败
     */
    @Test
    void recordsRequestTimeoutWithStableErrorCode() throws Exception
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", exchange ->
        {
            try
            {
                Thread.sleep(1000L);
                exchange.sendResponseHeaders(204, -1);
            }
            catch (InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
            finally
            {
                exchange.close();
            }
        });
        server.start();
        WorkflowConnectorInvocationService invocationService = runningInvocationService();
        WfConnectorEndpoint endpoint = endpoint();
        endpoint.setRequestTimeoutMs(500);
        endpoint.setChecksum(WorkflowConnectorEndpointService.endpointChecksum(endpoint));
        WorkflowHttpConnector connector = connector(invocationService, endpoint,
                mock(WorkflowConnectorSecretResolver.class));
        JsonNode config = objectMapper.readTree(connector.freezeConfig(authorConfig("POST",
                "/api/audit", null, null), true));

        assertThatThrownBy(() -> connector.execute(execution(), snapshot(), config))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("调用超时");
        verify(invocationService).failure(any(WorkflowConnectorInvocationClaim.class),
                anyLong(), eq(null), eq("TIMEOUT"), eq("request-timeout"));
        verify(invocationService, never()).success(any(), anyLong(), anyInt(), anyString());
    }

    /**
     * 验证响应正文超过 64 KiB 时关闭读取并写入独立超限错误码。
     * @return void，超限响应进入成功分支或被误记为配置校验错误时测试失败
     * @throws Exception HTTP Server 或 JSON 处理失败
     */
    @Test
    void rejectsOversizedResponseAndRecordsBoundedFailure() throws Exception
    {
        startServer(200, new AtomicReference<>(), new AtomicReference<>(),
                "x".repeat(64 * 1024 + 1));
        WorkflowConnectorInvocationService invocationService = runningInvocationService();
        WorkflowHttpConnector connector = connector(invocationService);
        JsonNode config = objectMapper.readTree(connector.freezeConfig(authorConfig("POST",
                "/api/audit", null, null), true));

        assertThatThrownBy(() -> connector.execute(execution(), snapshot(), config))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("响应正文超过大小限制");
        verify(invocationService).failure(any(WorkflowConnectorInvocationClaim.class),
                anyLong(), eq(null), eq("RESPONSE_TOO_LARGE"), eq("response-too-large"));
        verify(invocationService, never()).success(any(), anyLong(), anyInt(), anyString());
    }

    /**
     * 验证 Bearer 密钥只从外部解析器进入真实请求头，不进入冻结配置和结果摘要。
     * @return void，认证头缺失或冻结快照包含密钥正文时测试失败
     * @throws Exception HTTP Server 或 JSON 处理失败
     */
    @Test
    void appliesExternalBearerSecretWithoutPersistingSecretValue() throws Exception
    {
        AtomicReference<String> authorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", exchange ->
        {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        WorkflowConnectorInvocationService invocationService = runningInvocationService();
        WorkflowConnectorSecretResolver secretResolver = mock(
                WorkflowConnectorSecretResolver.class);
        when(secretResolver.requireSecret("WORKFLOW_CONNECTOR_SECRET_AUDIT"))
                .thenReturn("runtime-secret-value");
        WfConnectorEndpoint endpoint = endpoint();
        endpoint.setAuthType("BEARER");
        endpoint.setSecretRef("WORKFLOW_CONNECTOR_SECRET_AUDIT");
        endpoint.setChecksum(WorkflowConnectorEndpointService.endpointChecksum(endpoint));
        WorkflowHttpConnector connector = connector(invocationService, endpoint, secretResolver);
        String frozen = connector.freezeConfig(authorConfig("POST", "/api/audit", null, null),
                true);

        assertThat(frozen).contains("WORKFLOW_CONNECTOR_SECRET_AUDIT")
                .doesNotContain("runtime-secret-value");
        connector.execute(execution(), snapshot(), objectMapper.readTree(frozen));

        assertThat(authorization).hasValue("Bearer runtime-secret-value");
        verify(secretResolver).requireSecret("WORKFLOW_CONNECTOR_SECRET_AUDIT");
        verify(invocationService).success(any(WorkflowConnectorInvocationClaim.class),
                anyLong(), eq(204), anyString());
    }

    /**
     * 验证成功终态提交失败时禁止再写失败终态，避免同一领取令牌被二次终结。
     * @return void，成功提交异常触发补写 failure 时测试失败
     * @throws Exception HTTP Server 或 JSON 处理失败
     */
    @Test
    void doesNotWriteFailureAfterSuccessFinalizationStarts() throws Exception
    {
        startServer(200, new AtomicReference<>(), new AtomicReference<>(), "ok");
        WorkflowConnectorInvocationService invocationService = runningInvocationService();
        doThrow(new ServiceException("成功台账提交失败"))
                .when(invocationService).success(any(WorkflowConnectorInvocationClaim.class),
                        anyLong(), eq(200), anyString());
        WorkflowHttpConnector connector = connector(invocationService);
        JsonNode config = objectMapper.readTree(connector.freezeConfig(authorConfig("POST",
                "/api/audit", null, null), true));

        assertThatThrownBy(() -> connector.execute(execution(), snapshot(), config))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("成功台账提交失败");
        verify(invocationService, never()).failure(any(), anyLong(), any(), anyString(), anyString());
    }

    /**
     * 验证 HTTP 失败终态提交自身失败时不会再次调用 failure 覆盖原始冲突。
     * @return void，失败提交异常触发第二次 failure 时测试失败
     * @throws Exception HTTP Server 或 JSON 处理失败
     */
    @Test
    void doesNotWriteFailureTwiceWhenFailureFinalizationFails() throws Exception
    {
        startServer(500, new AtomicReference<>(), new AtomicReference<>(), "failed");
        WorkflowConnectorInvocationService invocationService = runningInvocationService();
        doThrow(new ServiceException("失败台账提交失败"))
                .when(invocationService).failure(any(WorkflowConnectorInvocationClaim.class),
                        anyLong(), eq(500), eq("HTTP_STATUS"), anyString());
        WorkflowHttpConnector connector = connector(invocationService);
        JsonNode config = objectMapper.readTree(connector.freezeConfig(authorConfig("POST",
                "/api/audit", null, null), true));

        assertThatThrownBy(() -> connector.execute(execution(), snapshot(), config))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("失败台账提交失败");
        verify(invocationService).failure(any(WorkflowConnectorInvocationClaim.class),
                anyLong(), eq(500), eq("HTTP_STATUS"), anyString());
        verify(invocationService, never()).success(any(), anyLong(), anyInt(), anyString());
    }

    /**
     * 创建使用本机真实监听端口的 HTTP 连接器及端点快照依赖。
     * @param invocationService WorkflowConnectorInvocationService，幂等台账替身
     * @return WorkflowHttpConnector，绑定测试端点服务的执行器
     */
    private WorkflowHttpConnector connector(WorkflowConnectorInvocationService invocationService)
    {
        WorkflowConnectorEndpointService endpointService = mock(WorkflowConnectorEndpointService.class);
        when(endpointService.lockEnabledForDeployment("audit-endpoint"))
                .thenReturn(endpoint());
        return new WorkflowHttpConnector(endpointService, invocationService,
                mock(WorkflowConnectorSecretResolver.class));
    }

    /**
     * 创建绑定指定端点和密钥解析器的 HTTP 连接器。
     * @param invocationService WorkflowConnectorInvocationService，幂等台账替身
     * @param endpoint WfConnectorEndpoint，部署时需要冻结的端点修订
     * @param secretResolver WorkflowConnectorSecretResolver，运行时外部密钥解析器
     * @return WorkflowHttpConnector，使用指定依赖的执行器
     */
    private WorkflowHttpConnector connector(WorkflowConnectorInvocationService invocationService,
            WfConnectorEndpoint endpoint, WorkflowConnectorSecretResolver secretResolver)
    {
        WorkflowConnectorEndpointService endpointService = mock(WorkflowConnectorEndpointService.class);
        when(endpointService.lockEnabledForDeployment("audit-endpoint")).thenReturn(endpoint);
        return new WorkflowHttpConnector(endpointService, invocationService, secretResolver);
    }

    /**
     * 创建已成功领取 RUNNING 租约的台账替身。
     * @return WorkflowConnectorInvocationService，每次 begin 返回固定领取结果
     */
    private WorkflowConnectorInvocationService runningInvocationService()
    {
        WorkflowConnectorInvocationService invocationService = mock(
                WorkflowConnectorInvocationService.class);
        when(invocationService.begin(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyInt(), anyString(), anyString(), anyString()))
                .thenReturn(new WorkflowConnectorInvocationClaim(3L, "RUNNING", "claim", 1, null));
        return invocationService;
    }

    /**
     * 创建作者配置，使用固定端点键和显式流程变量。
     * @param method String，HTTP 方法
     * @param path String，相对绝对路径
     * @param bodyVariable String，可空请求正文变量
     * @param statusVariable String，可空状态变量
     * @return JsonNode，作者配置对象
     * @throws Exception JSON 读取失败
     */
    private JsonNode authorConfig(String method, String path, String bodyVariable,
            String statusVariable) throws Exception
    {
        String body = bodyVariable == null ? "" : ",\"bodyVariable\":\"" + bodyVariable + "\"";
        String status = statusVariable == null ? "" : ",\"statusVariable\":\""
                + statusVariable + "\"";
        return objectMapper.readTree("{\"endpointKey\":\"audit-endpoint\",\"method\":\""
                + method + "\",\"path\":\"" + path + "\"" + body + status + "}");
    }

    /**
     * 创建部署运行上下文使用的最小流程快照。
     * @return WfDeployExtensionSnapshot，含稳定部署主键
     */
    private WfDeployExtensionSnapshot snapshot()
    {
        WfDeployExtensionSnapshot snapshot = new WfDeployExtensionSnapshot();
        snapshot.setDeployId("deployment");
        return snapshot;
    }

    /**
     * 创建带固定流程实例、执行和活动标识的 DelegateExecution。
     * @return DelegateExecution，真实 HTTP 执行所需上下文替身
     */
    private DelegateExecution execution()
    {
        DelegateExecution execution = mock(DelegateExecution.class);
        when(execution.getProcessInstanceId()).thenReturn("instance");
        when(execution.getId()).thenReturn("execution");
        when(execution.getCurrentActivityId()).thenReturn("httpTask");
        return execution;
    }

    /**
     * 创建指向本机 HTTP Server 的端点白名单。
     * @return WfConnectorEndpoint，完整启用端点配置
     */
    private WfConnectorEndpoint endpoint()
    {
        WfConnectorEndpoint endpoint = new WfConnectorEndpoint();
        endpoint.setEndpointId(7L);
        endpoint.setEndpointKey("audit-endpoint");
        endpoint.setEndpointName("审计回调");
        endpoint.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        endpoint.setAllowedMethods("POST");
        endpoint.setPathPrefix("/api");
        endpoint.setAuthType("NONE");
        endpoint.setConnectTimeoutMs(1000);
        endpoint.setRequestTimeoutMs(3000);
        endpoint.setNetworkScope("PRIVATE");
        endpoint.setRevisionNo(1);
        endpoint.setChecksum(WorkflowConnectorEndpointService.endpointChecksum(endpoint));
        return endpoint;
    }

    /**
     * 启动本机真实 HTTP 服务并记录请求正文和幂等请求头。
     * @param status int，响应 HTTP 状态
     * @param requestBody AtomicReference&lt;String&gt;，请求正文捕获器
     * @param idempotency AtomicReference&lt;String&gt;，幂等头捕获器
     * @param responseBody String，响应正文
     * @return void，监听器启动后端口可供端点快照使用
     * @throws IOException 监听端口启动失败
     */
    private void startServer(int status, AtomicReference<String> requestBody,
            AtomicReference<String> idempotency, String responseBody) throws IOException
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", exchange -> handle(exchange, status, requestBody,
                idempotency, responseBody));
        server.start();
    }

    /**
     * 处理一次测试 HTTP 请求并返回固定状态和正文。
     * @param exchange HttpExchange，本机服务请求上下文
     * @param status int，固定响应状态
     * @param requestBody AtomicReference&lt;String&gt;，请求正文捕获器
     * @param idempotency AtomicReference&lt;String&gt;，幂等头捕获器
     * @param responseBody String，固定响应正文
     * @return void，请求处理完成后关闭交换
     * @throws IOException 读取或写响应失败
     */
    private void handle(HttpExchange exchange, int status, AtomicReference<String> requestBody,
            AtomicReference<String> idempotency, String responseBody) throws IOException
    {
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8));
        idempotency.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
        byte[] bytes = responseBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
