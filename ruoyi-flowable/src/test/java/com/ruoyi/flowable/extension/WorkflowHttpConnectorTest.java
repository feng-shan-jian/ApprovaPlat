package com.ruoyi.flowable.extension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfConnectorEndpoint;
import com.ruoyi.flowable.domain.WfDeployExtensionSnapshot;
import com.ruoyi.flowable.runtime.WorkflowConnectorMetrics;
import com.ruoyi.flowable.service.model.WorkflowConnectorEndpointService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 受控 HTTP 连接器的 Flowable Job、稳定幂等键和真实请求测试。
 */
class WorkflowHttpConnectorTest
{
    private final ObjectMapper objectMapper = JsonMapper.shared();
    private HttpServer server;

    /**
     * 每个真实本机 HTTP Server 测试结束后释放监听端口。
     * @return void，避免测试进程遗留监听器
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
     * 验证正文摘要参与稳定 Idempotency-Key，成功结果只写入受控流程变量并产生指标。
     * @return void，请求、幂等键、变量或指标任一漂移时测试失败
     * @throws Exception HTTP Server 或摘要计算失败
     */
    @Test
    void sendsStablePayloadBoundIdempotencyKey() throws Exception
    {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> idempotencyKey = new AtomicReference<>();
        startServer(200, requestBody, idempotencyKey, "ok");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WorkflowHttpConnector connector = connector(endpoint(),
                mock(WorkflowConnectorSecretResolver.class), registry);
        JsonNode frozen = objectMapper.readTree(connector.freezeConfig(
                authorConfig("POST", "/api/audit", "payload", "httpStatus"), true));
        DelegateExecution execution = execution(Map.of("businessId", "B-100"));

        connector.execute(execution, snapshot(), frozen);

        String body = "{\"businessId\":\"B-100\"}";
        String payloadSha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(StandardCharsets.UTF_8)));
        assertThat(requestBody).hasValue(body);
        assertThat(idempotencyKey).hasValue(WorkflowExtensionChecksum.sha256(
                "instance", "execution", "httpTask", payloadSha256));
        verify(execution).setVariable("httpStatus", 200L);
        assertThat(registry.get("workflow.connector.attempts")
                .tags("type", "http", "result", "success").counter().count())
                .isEqualTo(1.0D);
    }

    /**
     * 验证相同执行身份但不同请求载荷生成不同幂等键，禁止把不同业务请求错误折叠。
     * @return void，载荷摘要未参与键生成时测试失败
     * @throws Exception HTTP Server 或 JSON 解析失败
     */
    @Test
    void changesIdempotencyKeyWhenPayloadChanges() throws Exception
    {
        AtomicReference<String> firstKey = new AtomicReference<>();
        AtomicReference<String> currentKey = new AtomicReference<>();
        startServer(204, new AtomicReference<>(), currentKey, "");
        WorkflowHttpConnector connector = connector(endpoint(),
                mock(WorkflowConnectorSecretResolver.class), new SimpleMeterRegistry());
        JsonNode frozen = objectMapper.readTree(connector.freezeConfig(
                authorConfig("POST", "/api/audit", "payload", null), true));

        connector.execute(execution(Map.of("value", 1)), snapshot(), frozen);
        firstKey.set(currentKey.get());
        connector.execute(execution(Map.of("value", 2)), snapshot(), frozen);

        assertThat(currentKey.get()).isNotEqualTo(firstKey.get());
    }

    /**
     * 验证 HTTP 非成功状态直接抛给 Flowable Job，并记录失败指标而不持久化自建调用状态。
     * @return void，非 2xx 被吞掉或标记成功时测试失败
     * @throws Exception HTTP Server 或 JSON 解析失败
     */
    @Test
    void exposesNonSuccessStatusToFlowableJob() throws Exception
    {
        startServer(503, new AtomicReference<>(), new AtomicReference<>(), "failed");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WorkflowHttpConnector connector = connector(endpoint(),
                mock(WorkflowConnectorSecretResolver.class), registry);
        JsonNode frozen = objectMapper.readTree(connector.freezeConfig(
                authorConfig("POST", "/api/audit", null, null), true));

        assertThatThrownBy(() -> connector.execute(execution(null), snapshot(), frozen))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("非成功状态");
        assertThat(registry.get("workflow.connector.attempts")
                .tags("type", "http", "result", "failure").counter().count())
                .isEqualTo(1.0D);
    }

    /**
     * 验证 HTTP ServiceTask 必须异步，确保超时和 5xx 由 Flowable 原生重试与死信承载。
     * @return void，同步连接器能够部署时测试失败
     */
    @Test
    void rejectsSynchronousServiceTask()
    {
        WorkflowConnectorEndpointService endpointService = mock(
                WorkflowConnectorEndpointService.class);
        WorkflowHttpConnector connector = new WorkflowHttpConnector(endpointService,
                mock(WorkflowConnectorSecretResolver.class),
                new WorkflowConnectorMetrics(new SimpleMeterRegistry()));

        assertThatThrownBy(() -> connector.freezeConfig(
                authorConfig("POST", "/api/audit", null, null), false))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("必须启用进入前异步");
    }

    /**
     * 创建使用本机真实监听端口的 HTTP 连接器。
     * @param endpoint WfConnectorEndpoint，部署冻结端点
     * @param secretResolver WorkflowConnectorSecretResolver，运行密钥解析器
     * @param registry SimpleMeterRegistry，测试指标注册表
     * @return WorkflowHttpConnector，绑定真实 HTTP 出口的连接器
     */
    private WorkflowHttpConnector connector(WfConnectorEndpoint endpoint,
            WorkflowConnectorSecretResolver secretResolver, SimpleMeterRegistry registry)
    {
        WorkflowConnectorEndpointService endpointService = mock(
                WorkflowConnectorEndpointService.class);
        when(endpointService.lockEnabledForDeployment("audit-endpoint")).thenReturn(endpoint);
        return new WorkflowHttpConnector(endpointService, secretResolver,
                new WorkflowConnectorMetrics(registry));
    }

    /**
     * 创建作者 HTTP 配置。
     * @param method String，请求方法
     * @param path String，端点相对路径
     * @param bodyVariable String，可空正文变量
     * @param statusVariable String，可空状态变量
     * @return JsonNode，作者配置对象
     */
    private JsonNode authorConfig(String method, String path, String bodyVariable,
            String statusVariable)
    {
        var config = objectMapper.createObjectNode()
                .put("endpointKey", "audit-endpoint")
                .put("method", method)
                .put("path", path);
        if (bodyVariable != null) config.put("bodyVariable", bodyVariable);
        if (statusVariable != null) config.put("statusVariable", statusVariable);
        return config;
    }

    /**
     * 创建固定流程身份和可选正文变量的执行上下文。
     * @param payload Map&lt;String,Object&gt;，可空受控请求正文
     * @return DelegateExecution，连接器运行上下文
     */
    private DelegateExecution execution(Map<String, Object> payload)
    {
        DelegateExecution execution = mock(DelegateExecution.class);
        when(execution.getProcessInstanceId()).thenReturn("instance");
        when(execution.getId()).thenReturn("execution");
        when(execution.getCurrentActivityId()).thenReturn("httpTask");
        if (payload != null)
        {
            when(execution.hasVariable("payload")).thenReturn(true);
            when(execution.getVariable("payload")).thenReturn(payload);
        }
        return execution;
    }

    /**
     * 创建最小部署快照。
     * @return WfDeployExtensionSnapshot，连接器调度参数
     */
    private WfDeployExtensionSnapshot snapshot()
    {
        return new WfDeployExtensionSnapshot();
    }

    /**
     * 创建当前本机测试服务对应的端点白名单。
     * @return WfConnectorEndpoint，摘要完整的启用端点
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
     * 启动本机 HTTP 服务并捕获正文与幂等请求头。
     * @param status int，固定响应状态
     * @param requestBody AtomicReference&lt;String&gt;，正文捕获器
     * @param idempotencyKey AtomicReference&lt;String&gt;，幂等头捕获器
     * @param responseBody String，固定响应正文
     * @return void，服务启动后可由端点访问
     * @throws IOException 监听端口启动失败
     */
    private void startServer(int status, AtomicReference<String> requestBody,
            AtomicReference<String> idempotencyKey, String responseBody) throws IOException
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", exchange -> handle(exchange, status,
                requestBody, idempotencyKey, responseBody));
        server.start();
    }

    /**
     * 处理一次测试 HTTP 请求。
     * @param exchange HttpExchange，本机请求上下文
     * @param status int，固定响应状态
     * @param requestBody AtomicReference&lt;String&gt;，正文捕获器
     * @param idempotencyKey AtomicReference&lt;String&gt;，幂等头捕获器
     * @param responseBody String，固定响应正文
     * @return void，响应完成后关闭交换
     * @throws IOException 读取或响应失败
     */
    private void handle(HttpExchange exchange, int status,
            AtomicReference<String> requestBody, AtomicReference<String> idempotencyKey,
            String responseBody) throws IOException
    {
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8));
        idempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
