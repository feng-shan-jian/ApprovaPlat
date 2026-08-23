package com.ruoyi.flowable.extension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfConnectorEndpoint;
import com.ruoyi.flowable.domain.WfDeployExtensionSnapshot;
import com.ruoyi.flowable.runtime.WorkflowConnectorMetrics;
import com.ruoyi.flowable.service.model.WorkflowConnectorEndpointService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * HTTP 连接器请求正文浮点数协议边界测试。
 */
class WorkflowHttpConnectorTest
{
    private static final String BODY_VARIABLE = "payload";
    private final ObjectMapper objectMapper = JsonMapper.shared();
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicReference<byte[]> receivedBody = new AtomicReference<>();
    private HttpServer server;
    private WorkflowHttpConnector connector;

    /**
     * 为每个测试启动随机端口的本机 HTTP 接收端并创建真实连接器实例。
     * @return void，端口创建失败时测试失败
     * @throws IOException 本机监听端口创建失败
     */
    @BeforeEach
    void setUp() throws IOException
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/connector/test", this::receiveRequest);
        server.start();
        connector = new WorkflowHttpConnector(
                mock(WorkflowConnectorEndpointService.class),
                mock(WorkflowConnectorSecretResolver.class),
                mock(WorkflowConnectorMetrics.class));
    }

    /**
     * 停止当前测试的本机 HTTP 接收端，避免监听资源泄漏到其他测试。
     * @return void，无返回值
     */
    @AfterEach
    void tearDown()
    {
        server.stop(0);
    }

    /**
     * 验证有限 Double 经公开执行入口发送后仍是数值节点且数值不变。
     * @return void，请求未到达或 JSON 类型、数值变化时测试失败
     */
    @Test
    void sendsFiniteDoubleAsJsonNumber()
    {
        assertFiniteNumber(1250.75D, 1250.75D);
    }

    /**
     * 验证有限 Float 经公开执行入口发送后仍是数值节点且数值不变。
     * @return void，请求未到达或 JSON 类型、数值变化时测试失败
     */
    @Test
    void sendsFiniteFloatAsJsonNumber()
    {
        assertFiniteNumber(6.25F, 6.25D);
    }

    /**
     * 验证六种非有限浮点值在生成安全 JSON 树时失败且不会触发 HTTP 请求。
     * @param label String，参数化用例名称
     * @param value Number，待验证的 Float 或 Double 非有限值
     * @return void，异常类型、状态、提示或请求计数不符合契约时测试失败
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("nonFiniteNumbers")
    void rejectsNonFiniteNumbersBeforeHttpRequest(String label, Number value)
    {
        ServiceException exception = assertThrows(ServiceException.class,
                () -> connector.execute(execution(value), mock(WfDeployExtensionSnapshot.class), config()));

        assertThat(exception.getMessage()).as(label).contains("非有限数字");
        assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR);
        assertThat(requestCount.get()).isZero();
        assertThat(receivedBody.get()).isNull();
    }

    /**
     * 提供 Double 和 Float 的 NaN、正无穷与负无穷协议非法值。
     * @return Stream&lt;Arguments&gt;，六个独立参数化场景
     */
    private static Stream<Arguments> nonFiniteNumbers()
    {
        return Stream.of(
                Arguments.of("Double.NaN", Double.NaN),
                Arguments.of("Double.POSITIVE_INFINITY", Double.POSITIVE_INFINITY),
                Arguments.of("Double.NEGATIVE_INFINITY", Double.NEGATIVE_INFINITY),
                Arguments.of("Float.NaN", Float.NaN),
                Arguments.of("Float.POSITIVE_INFINITY", Float.POSITIVE_INFINITY),
                Arguments.of("Float.NEGATIVE_INFINITY", Float.NEGATIVE_INFINITY));
    }

    /**
     * 通过公开执行入口发送有限数字，并核对接收端观察到的真实 JSON 正文。
     * @param value Number，放入 Flowable 请求正文变量的有限浮点值
     * @param expected double，期望接收的数值
     * @return void，请求或 JSON 断言不成立时测试失败
     */
    private void assertFiniteNumber(Number value, double expected)
    {
        connector.execute(execution(value), mock(WfDeployExtensionSnapshot.class), config());

        assertThat(requestCount.get()).isEqualTo(1);
        JsonNode body = read(receivedBody.get());
        assertThat(body.get("value").isNumber()).isTrue();
        assertThat(body.get("value").doubleValue()).isEqualTo(expected);
    }

    /**
     * 创建只暴露请求正文及幂等键所需标识的 Flowable 执行上下文。
     * @param value Number，请求正文字段值
     * @return DelegateExecution，可交给连接器公开 execute(...) 入口的执行模拟
     */
    private DelegateExecution execution(Number value)
    {
        DelegateExecution execution = mock(DelegateExecution.class);
        when(execution.hasVariable(BODY_VARIABLE)).thenReturn(true);
        when(execution.getVariable(BODY_VARIABLE)).thenReturn(Map.of("value", value));
        when(execution.getProcessInstanceId()).thenReturn("process-1");
        when(execution.getId()).thenReturn("execution-1");
        when(execution.getCurrentActivityId()).thenReturn("http-task-1");
        return execution;
    }

    /**
     * 创建通过快照摘要、方法、路径和本机网络范围校验的冻结配置。
     * @return JsonNode，指向当前随机端口接收端的 POST 配置
     */
    private JsonNode config()
    {
        WfConnectorEndpoint endpoint = new WfConnectorEndpoint();
        endpoint.setEndpointId(1L);
        endpoint.setEndpointKey("local-test");
        endpoint.setEndpointName("本机测试端点");
        endpoint.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        endpoint.setAllowedMethods("POST");
        endpoint.setPathPrefix("/connector");
        endpoint.setAuthType("NONE");
        endpoint.setConnectTimeoutMs(1000);
        endpoint.setRequestTimeoutMs(5000);
        endpoint.setNetworkScope("PRIVATE");
        endpoint.setRevisionNo(1);
        endpoint.setChecksum(WorkflowConnectorEndpointService.endpointChecksum(endpoint));

        ObjectNode config = objectMapper.createObjectNode();
        config.put("endpointKey", endpoint.getEndpointKey());
        config.put("method", "POST");
        config.put("path", "/connector/test");
        config.put("bodyVariable", BODY_VARIABLE);
        ObjectNode snapshot = config.putObject("endpointSnapshot");
        snapshot.put("endpointId", endpoint.getEndpointId());
        snapshot.put("endpointName", endpoint.getEndpointName());
        snapshot.put("revisionNo", endpoint.getRevisionNo());
        snapshot.put("baseUrl", endpoint.getBaseUrl());
        snapshot.put("allowedMethods", endpoint.getAllowedMethods());
        snapshot.put("pathPrefix", endpoint.getPathPrefix());
        snapshot.put("authType", endpoint.getAuthType());
        snapshot.put("connectTimeoutMs", endpoint.getConnectTimeoutMs());
        snapshot.put("requestTimeoutMs", endpoint.getRequestTimeoutMs());
        snapshot.put("networkScope", endpoint.getNetworkScope());
        snapshot.put("checksum", endpoint.getChecksum());
        return config;
    }

    /**
     * 接收连接器的真实 HTTP 请求并保存正文，计数在读取前递增以覆盖异常请求。
     * @param exchange HttpExchange，本机测试服务收到的请求交换
     * @return void，响应固定为 204
     * @throws IOException 请求读取或响应写入失败
     */
    private void receiveRequest(HttpExchange exchange) throws IOException
    {
        requestCount.incrementAndGet();
        receivedBody.set(exchange.getRequestBody().readAllBytes());
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    /**
     * 解析本机接收端捕获的请求正文。
     * @param body byte[]，连接器发送的 JSON 字节
     * @return JsonNode，解析后的请求正文树
     */
    private JsonNode read(byte[] body)
    {
        try
        {
            return objectMapper.readTree(body);
        }
        catch (Exception exception)
        {
            throw new AssertionError("连接器发送的请求正文必须是合法 JSON", exception);
        }
    }
}
