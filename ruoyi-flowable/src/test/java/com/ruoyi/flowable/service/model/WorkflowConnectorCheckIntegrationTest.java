package com.ruoyi.flowable.service.model;

import com.ruoyi.flowable.service.integration.WorkflowConnectorCheckService;
import com.ruoyi.flowable.service.integration.WorkflowConnectorEndpointService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.doAnswer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import org.flowable.engine.IdentityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfConnectorEndpoint;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.engine.WorkflowExceptionTranslator;
import com.ruoyi.flowable.service.integration.WorkflowConnectorSecretResolver;
import com.ruoyi.flowable.service.integration.WorkflowHttpExecution;
import com.ruoyi.flowable.service.integration.WorkflowManagedConnectorSecrets;
import com.ruoyi.flowable.identity.WorkflowAuthenticationContext;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityCodec;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfConnectorEndpointMapper;
import com.ruoyi.flowable.testsupport.WorkflowH2SchemaMapperSupport;
import tools.jackson.databind.json.JsonMapper;

/** Real MySQL row locks, production Mapper/credential provider, and a controlled local HTTP peer. */
@EnabledIfEnvironmentVariable(named = "WORKFLOW_MYSQL_TEST_URL", matches = ".+")
class WorkflowConnectorCheckIntegrationTest
{
    private JdbcTemplate jdbc;
    private WorkflowEngineOperations operations;
    private WorkflowManagedConnectorSecrets secrets;
    private WorkflowHttpExecution http;
    private WorkflowConnectorSecretResolver secretResolver;
    private WorkflowConnectorCheckService checks;
    private WorkflowConnectorEndpointService endpoints;
    private WfConnectorEndpoint endpoint;
    private HttpServer server;
    private String secretRef;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> token = new AtomicReference<>();
    private final AtomicReference<String> responseMode = new AtomicReference<>("ok");
    private CountDownLatch received;
    private CountDownLatch release;

    @BeforeEach
    void setUp() throws Exception
    {
        String url = System.getenv("WORKFLOW_MYSQL_TEST_URL");
        assertThat(url).matches("jdbc:mysql://[^/]+/[A-Za-z0-9_]*_it(?:\\?.*)?");
        var source = new DriverManagerDataSource(url, System.getenv("WORKFLOW_MYSQL_TEST_USERNAME"),
                System.getenv("WORKFLOW_MYSQL_TEST_PASSWORD"));
        jdbc = new JdbcTemplate(source);
        var resolver = mock(WorkflowIdentityResolver.class);
        when(resolver.resolveCurrentIdentity()).thenReturn(new WorkflowCurrentIdentity("1", Set.of()));
        var target = new WorkflowEngineOperations(new WorkflowAuthenticationContext(
                mock(IdentityService.class), new WorkflowIdentityCodec()), new WorkflowExceptionTranslator(), resolver);
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()));
        operations = (WorkflowEngineOperations) proxy.getProxy();
        var mapper = WorkflowH2SchemaMapperSupport.createSpringMapper(source, "connector-check",
                WfConnectorEndpointMapper.class, "mapper/flowable/WfConnectorEndpointMapper.xml");
        secrets = new WorkflowManagedConnectorSecrets(source, Base64.getEncoder().encodeToString(new byte[32]));
        secretRef = secrets.create("synthetic-original-token", "connector-check-test").reference();
        secretResolver = spy(new WorkflowConnectorSecretResolver(secrets, mapper));
        endpoints = spy(new WorkflowConnectorEndpointService(operations, mapper, secretResolver));
        http = new WorkflowHttpExecution(secretResolver);
        checks = new WorkflowConnectorCheckService(operations, mapper, http, endpoints);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/workflow/runtime-event/auth-check", exchange -> {
            requests.incrementAndGet();
            token.set(exchange.getRequestHeaders().getFirst("X-Integration-Token"));
            String nonce = JsonMapper.shared().readTree(exchange.getRequestBody()).path("nonce").asText();
            if (received != null) received.countDown();
            try
            {
                if (release != null && !release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("HTTP gate timed out");
            }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new java.io.IOException(exception); }
            String mode = responseMode.get();
            String response = switch (mode)
            {
                case "oversize" -> "x".repeat(65537);
                case "invalid-json" -> "{";
                default -> "{\"code\":" + (mode.equals("scope") ? "403" : "200")
                        + ",\"data\":{\"authenticated\":" + !mode.equals("unauthenticated")
                        + ",\"protocol\":\"" + (mode.equals("protocol") ? "OTHER" : "APPROVA_MESSAGE_AUTH_V1")
                        + "\",\"nonce\":\"" + (mode.equals("nonce") ? "wrong" : nonce) + "\"}}";
            };
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(mode.equals("status") ? 403 : 200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        endpoint = new WfConnectorEndpoint();
        endpoint.setEndpointKey("check_" + UUID.randomUUID().toString().replace("-", ""));
        endpoint.setEndpointName("connector-check-test");
        endpoint.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        endpoint.setAllowedMethods("POST"); endpoint.setPathPrefix("/workflow/runtime-event");
        endpoint.setAuthType("API_KEY"); endpoint.setApiKeyHeader("X-Integration-Token");
        endpoint.setSecretRef(secretRef); endpoint.setConnectTimeoutMs(1000); endpoint.setRequestTimeoutMs(15000);
        endpoint.setNetworkScope("PRIVATE"); endpoint.setRevisionNo(1); endpoint.setStatus("ENABLED");
        endpoint.setChecksum(WorkflowConnectorEndpointService.endpointChecksum(endpoint));
        endpoint.setCreateBy("connector-check-test");
        mapper.insert(endpoint);
    }

    @AfterEach
    void tearDown()
    {
        if (release != null) release.countDown();
        if (server != null) server.stop(0);
        if (endpoint != null && endpoint.getEndpointId() != null)
            jdbc.update("delete from wf_connector_endpoint where endpoint_id=?", endpoint.getEndpointId());
        if (secretRef != null) jdbc.update("delete from wf_connector_managed_secret where secret_ref=?", secretRef);
    }

    @Test
    void slowHttpKeepsBindingLockedUntilResponse() throws Exception
    {
        received = new CountDownLatch(1); release = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try
        {
            var check = workers.submit(() -> checks.check(endpoint.getEndpointId()));
            assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
            var update = workers.submit(() -> jdbc.update("update wf_connector_endpoint set update_by=? where endpoint_id=?",
                    "concurrent-check-test", endpoint.getEndpointId()));
            assertThatThrownBy(() -> update.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            assertThat(check.isDone()).isFalse();
            release.countDown();
            assertThat(check.get(5, TimeUnit.SECONDS).networkAuthenticated()).isTrue();
            assertThat(update.get(5, TimeUnit.SECONDS)).isOne();
        }
        finally
        {
            release.countDown(); workers.shutdown();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void rebindingCannotCommitBetweenRuntimeValidationAndSending() throws Exception
    {
        var checked = new CountDownLatch(1);
        var sendGate = new CountDownLatch(1);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            checked.countDown();
            if (!sendGate.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("send gate timed out");
            return null;
        }).when(secretResolver).requireManagedRuntimeEndpoint(endpoint.getEndpointId(), secretRef);
        var workers = Executors.newFixedThreadPool(2);
        try
        {
            var check = workers.submit(() -> checks.check(endpoint.getEndpointId()));
            assertThat(checked.await(5, TimeUnit.SECONDS)).isTrue();
            var rebind = workers.submit(() -> jdbc.update("update wf_connector_endpoint set secret_ref=? where endpoint_id=?",
                    "MANAGED_V1_" + "f".repeat(32), endpoint.getEndpointId()));
            boolean reboundBeforeSend;
            try
            {
                rebind.get(200, TimeUnit.MILLISECONDS);
                reboundBeforeSend = true;
            }
            catch (java.util.concurrent.TimeoutException expected) { reboundBeforeSend = false; }
            finally { sendGate.countDown(); }
            assertThat(check.get(5, TimeUnit.SECONDS).networkAuthenticated()).isTrue();
            assertThat(token).hasValue("synthetic-original-token");
            assertThat(rebind.get(5, TimeUnit.SECONDS)).isOne();
            assertThat(reboundBeforeSend)
                    .as("binding remains stable until the authenticated request has been sent").isFalse();
        }
        finally
        {
            sendGate.countDown(); workers.shutdown();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void revokedCredentialIsRejectedAfterFreezeBeforeSend()
    {
        String frozen = freeze();
        secrets.revoke(secretRef, 1, "connector-check-test");
        assertThatThrownBy(() -> send(frozen)).isInstanceOf(ServiceException.class)
                .extracting("subCode").isEqualTo("CONNECTOR_SECRET_UNAVAILABLE");
        assertThat(requests).hasValue(0);
    }

    @Test
    void ReboundOrDisabledEndpointIsRejectedBeforeSend()
    {
        String frozen = freeze();
        jdbc.update("update wf_connector_endpoint set secret_ref=? where endpoint_id=?",
                "MANAGED_V1_" + "f".repeat(32), endpoint.getEndpointId());
        assertThatThrownBy(() -> send(frozen)).isInstanceOf(ServiceException.class).hasMessageContaining("绑定已变更");
        jdbc.update("update wf_connector_endpoint set secret_ref=?,status='DISABLED' where endpoint_id=?", secretRef, endpoint.getEndpointId());
        assertThatThrownBy(() -> send(frozen)).isInstanceOf(ServiceException.class).hasMessageContaining("停用");
        assertThat(requests).hasValue(0);
    }

    @Test
    void rotatedCredentialIsReadAtSendTime() throws Exception
    {
        String frozen = freeze();
        secrets.rotate(secretRef, 1, "synthetic-rotated-token", "connector-check-test");
        send(frozen);
        assertThat(token).hasValue("synthetic-rotated-token");
    }

    @Test
    void invalidAuthenticationResponsesFailWithoutCreatingBusinessInstances()
    {
        long before = jdbc.queryForObject("select count(*) from ACT_RU_EXECUTION", Long.class);
        for (String mode : Set.of("status", "scope", "protocol", "nonce", "unauthenticated", "invalid-json"))
        {
            responseMode.set(mode);
            assertThatThrownBy(() -> checks.check(endpoint.getEndpointId())).isInstanceOf(ServiceException.class)
                    .extracting("subCode").isEqualTo("CONNECTOR_AUTH_CHECK_FAILED");
        }
        assertThat(jdbc.queryForObject("select count(*) from ACT_RU_EXECUTION", Long.class)).isEqualTo(before);
    }

    @Test
    void boundedHttpResponseIsStillConsumedAndRejected()
    {
        responseMode.set("oversize");
        assertThatThrownBy(() -> checks.check(endpoint.getEndpointId())).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("响应");
    }

    private String freeze()
    {
        return operations.writeAsCurrentUser(actor -> endpoints.freezePostEndpoint(endpoint.getEndpointKey(), "/workflow/runtime-event/auth-check"));
    }

    private void send(String frozen) throws Exception
    {
        http.send(WorkflowHttpExecution.parseFrozen(JsonMapper.shared().readTree(frozen)), "synthetic-auth-check", "{\"nonce\":\"synthetic-nonce\"}".getBytes(StandardCharsets.UTF_8));
    }
}
