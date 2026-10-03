package com.ruoyi.flowable.service.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.config.WorkflowCollaborationProperties;
import com.ruoyi.flowable.domain.WfConnectorEndpoint;
import com.ruoyi.flowable.domain.WfCollaborationOutbox;
import com.ruoyi.flowable.extension.WorkflowHttpConnector;
import com.ruoyi.flowable.mapper.WfConnectorEndpointMapper;
import com.ruoyi.flowable.mapper.WfCollaborationOutboxMapper;
import com.ruoyi.flowable.runtime.WorkflowConnectorMetrics;
import com.ruoyi.flowable.runtime.WorkflowCollaborationMetrics;
import com.ruoyi.flowable.service.process.WorkflowCollaborationAuditService;
import com.ruoyi.flowable.service.process.WorkflowCollaborationOutboxService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 真实本机 HTTP，直接装配发送能力及各自调用者，无模型发布和 Flowable 引擎。 */
class WorkflowHttpExecutionTest
{
    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final AtomicReference<String> key = new AtomicReference<>();
    private final AtomicReference<byte[]> receivedBody = new AtomicReference<>();
    private final AtomicReference<String> responseBody = new AtomicReference<>("{\"code\":200}");
    private final AtomicInteger responseDelay = new AtomicInteger();
    private WfConnectorEndpoint endpoint;
    private WfConnectorEndpointMapper endpoints;
    private WorkflowConnectorEndpointService catalog;
    private WorkflowHttpExecution execution;

    @BeforeEach
    void setUp() throws Exception
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/send", exchange -> {
            requests.incrementAndGet();
            method.set(exchange.getRequestMethod());
            auth.set(exchange.getRequestHeaders().getFirst("X-Integration-Token"));
            key.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            receivedBody.set(exchange.getRequestBody().readAllBytes());
            if (responseDelay.get() > 0)
            {
                try { Thread.sleep(responseDelay.get()); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        endpoint = new WfConnectorEndpoint();
        endpoint.setEndpointId(1L); endpoint.setEndpointKey("local"); endpoint.setEndpointName("local");
        endpoint.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        endpoint.setAllowedMethods("DELETE,GET,PATCH,POST,PUT"); endpoint.setPathPrefix("/send");
        endpoint.setAuthType("NONE"); endpoint.setNetworkScope("PRIVATE");
        endpoint.setConnectTimeoutMs(1000); endpoint.setRequestTimeoutMs(1000);
        endpoint.setRevisionNo(1); endpoint.setStatus("ENABLED");
        endpoints = mock(WfConnectorEndpointMapper.class);
        when(endpoints.selectEnabledByKeyForUpdate("local")).thenReturn(endpoint);
        var secrets = spy(new WorkflowConnectorSecretResolver(mock(WorkflowManagedConnectorSecrets.class), endpoints));
        // Synthetic ENV source: keep the real provider selection and runtime catalog gate.
        doReturn("synthetic-env-token").when(secrets).requireSecret("WORKFLOW_CONNECTOR_SECRET_N08");
        catalog = new WorkflowConnectorEndpointService(null, endpoints, secrets);
        execution = new WorkflowHttpExecution(secrets);
    }

    @AfterEach
    void tearDown()
    {
        if (server != null) server.stop(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "PUT", "PATCH", "DELETE"})
    void sharedExecutionPreservesEveryOrdinaryHttpMethod(String verb) throws Exception
    {
        JsonNode frozen = freeze(verb, false);
        var result = execution.send(WorkflowHttpExecution.parseFrozen(frozen), "stable-key", null);
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.body()).isEqualTo("{\"code\":200}".getBytes(StandardCharsets.UTF_8));
        assertThat(requests).hasValue(1);
        assertThat(method).hasValue(verb);
        assertThat(key).hasValue("stable-key");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void noneAndEnvironmentSnapshotsDoNotReadCurrentCatalogAfterDisable(boolean environment) throws Exception
    {
        JsonNode frozen = freeze("POST", environment);
        endpoint.setStatus("DISABLED"); endpoint.setSecretRef("MANAGED_V1_" + "f".repeat(32));
        clearInvocations(endpoints);
        execution.send(WorkflowHttpExecution.parseFrozen(frozen), "stable-key", "{}".getBytes(StandardCharsets.UTF_8));
        verifyNoInteractions(endpoints);
        assertThat(auth.get()).isEqualTo(environment ? "synthetic-env-token" : null);
        assertThat(requests).hasValue(1);
    }

    @Test
    void bpmnFailureReturnsToJobWithoutInternalRetry()
    {
        JsonNode frozen = freeze("POST", false);
        status.set(503);
        var metrics = mock(WorkflowConnectorMetrics.class);
        var adapter = new WorkflowHttpConnector(execution, metrics);
        var delegate = mock(DelegateExecution.class);
        when(delegate.getProcessInstanceId()).thenReturn("process");
        when(delegate.getId()).thenReturn("execution");
        when(delegate.getCurrentActivityId()).thenReturn("http-node");
        assertThatThrownBy(() -> adapter.execute(delegate, frozen)).isInstanceOfSatisfying(ServiceException.class,
                failure -> assertThat(failure.getSubCode()).isEqualTo("WORKFLOW_CONNECTOR_HTTP_STATUS"));
        assertThat(requests).hasValue(1);
        verify(metrics).record(eq("HTTP"), eq(false), anyLong());
        verify(delegate, never()).setVariable(anyString(), any());
    }

    @Test
    void collaborationPersistsRetryOnlyWhenItsOwnerCompletesFailure()
    {
        JsonNode frozen = freeze("POST", true);
        status.set(503);
        var mapper = mock(WfCollaborationOutboxMapper.class);
        when(mapper.markFailed(anyString(), anyString(), anyInt(), anyString(), anyLong(), any(), anyString(), anyString())).thenReturn(1);
        var outbox = new WorkflowCollaborationOutboxService(mapper, null,
                mock(WorkflowCollaborationAuditService.class), execution, null, null, null,
                new WorkflowCollaborationProperties(), mock(WorkflowCollaborationMetrics.class));
        WfCollaborationOutbox row = row(frozen);
        var outcome = outbox.deliver(row);
        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("COLLAB_OUTBOX_HTTP_STATUS");
        assertThat(requests).hasValue(1);
        verifyNoInteractions(mapper);
        outbox.completeFailure(row, "worker", outcome);
        verify(mapper).markFailed(eq(row.getMessageId()), eq("worker"), eq(1), eq("RETRYING"), eq(2L),
                eq(503), eq("COLLAB_OUTBOX_HTTP_STATUS"), anyString());
        assertThat(requests).hasValue(1);
        assertThat(auth).hasValue("synthetic-env-token");
        assertThat(key).hasValue(row.getMessageId());
    }

    @Test
    void collaborationOversizedResponseUsesItsFailureStateMachine()
    {
        JsonNode frozen = freeze("POST", true);
        responseBody.set("private-response".repeat(5000));
        var mapper = mock(WfCollaborationOutboxMapper.class);
        when(mapper.markFailed(anyString(), anyString(), anyInt(), anyString(), anyLong(), any(), anyString(), anyString())).thenReturn(1);
        var outbox = new WorkflowCollaborationOutboxService(mapper, null,
                mock(WorkflowCollaborationAuditService.class), execution, null, null, null,
                new WorkflowCollaborationProperties(), mock(WorkflowCollaborationMetrics.class));
        WfCollaborationOutbox row = row(frozen);
        var outcome = outbox.deliver(row);
        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("COLLAB_OUTBOX_RESPONSE_TOO_LARGE");
        assertThat(outcome.summary()).doesNotContain("private-response");
        outbox.completeFailure(row, "worker", outcome);
        verify(mapper).markFailed(eq(row.getMessageId()), eq("worker"), eq(1), eq("RETRYING"), eq(2L),
                isNull(), eq("COLLAB_OUTBOX_RESPONSE_TOO_LARGE"), anyString());
        row.setAttemptCount(3);
        outbox.completeFailure(row, "worker", outcome);
        verify(mapper).markFailed(eq(row.getMessageId()), eq("worker"), eq(1), eq("DEAD_LETTER"), anyLong(),
                isNull(), eq("COLLAB_OUTBOX_RESPONSE_TOO_LARGE"), anyString());
        assertThat(requests).hasValue(1);
    }

    @Test
    void requestAndResponseBoundsRemain64KiB() throws Exception
    {
        JsonNode frozen = freeze("POST", false);
        var prepared = WorkflowHttpExecution.parseFrozen(frozen);
        assertThatThrownBy(() -> execution.send(prepared, "bounded", new byte[65537]))
                .isInstanceOf(ServiceException.class).hasMessageContaining("大小限制");
        assertThat(requests).hasValue(0);
        responseBody.set("x".repeat(65537));
        assertThatThrownBy(() -> execution.send(prepared, "bounded", new byte[65536]))
                .isInstanceOf(WorkflowHttpExecution.ResponseTooLargeException.class);
        assertThat(receivedBody.get()).hasSize(65536);
        assertThat(requests).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RESPONSE_TOO_LARGE", "RESPONSE_INVALID", "HTTP_STATUS", "TIMEOUT"})
    @EnabledIfEnvironmentVariable(named = "WORKFLOW_MYSQL_TEST_URL", matches = ".+")
    void mysqlWorkerCommitsKnownFailuresAndRecoversExpiredWorkers(String failure) throws Exception
    {
        String url = System.getenv("WORKFLOW_MYSQL_TEST_URL");
        assertThat(url).matches("jdbc:mysql://[^/]+/[A-Za-z0-9_]*_it(?:\\?.*)?");
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(url,
                System.getenv("WORKFLOW_MYSQL_TEST_USERNAME"), System.getenv("WORKFLOW_MYSQL_TEST_PASSWORD"));
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
        var manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(source);
        var mapper = com.ruoyi.flowable.testsupport.WorkflowH2SchemaMapperSupport.createSpringMapper(source,
                "outbox", WfCollaborationOutboxMapper.class, "mapper/flowable/WfCollaborationOutboxMapper.xml");
        var channelMapper = com.ruoyi.flowable.testsupport.WorkflowH2SchemaMapperSupport.createSpringMapper(source,
                "outbox-channel", com.ruoyi.flowable.mapper.WfCollaborationChannelMapper.class,
                "mapper/flowable/WfCollaborationChannelMapper.xml");
        var endpointMapper = com.ruoyi.flowable.testsupport.WorkflowH2SchemaMapperSupport.createSpringMapper(source,
                "outbox-endpoint", WfConnectorEndpointMapper.class, "mapper/flowable/WfConnectorEndpointMapper.xml");
        var auditMapper = com.ruoyi.flowable.testsupport.WorkflowH2SchemaMapperSupport.createSpringMapper(source,
                "outbox-audit", com.ruoyi.flowable.mapper.WfCollaborationMessageAuditMapper.class,
                "mapper/flowable/WfCollaborationMessageAuditMapper.xml");
        var identities = mock(com.ruoyi.flowable.identity.WorkflowIdentityResolver.class);
        when(identities.resolveCurrentIdentity()).thenReturn(new com.ruoyi.flowable.identity.WorkflowCurrentIdentity("1", java.util.Set.of()));
        var operations = proxy(new com.ruoyi.flowable.engine.WorkflowEngineOperations(
                new com.ruoyi.flowable.identity.WorkflowAuthenticationContext(mock(org.flowable.engine.IdentityService.class),
                        new com.ruoyi.flowable.identity.WorkflowIdentityCodec()),
                new com.ruoyi.flowable.engine.WorkflowExceptionTranslator(), identities), manager);
        var history = mock(org.flowable.engine.HistoryService.class, RETURNS_DEEP_STUBS);
        when(history.createHistoricProcessInstanceQuery().processInstanceId(anyString()).singleResult()).thenReturn(null);
        var properties = new WorkflowCollaborationProperties(); properties.setBatchSize(1);
        var metrics = mock(WorkflowCollaborationMetrics.class);
        var outbox = proxy(new WorkflowCollaborationOutboxService(mapper, null,
                new WorkflowCollaborationAuditService(auditMapper), execution, null, history, operations, properties, metrics), manager);
        var scheduler = new com.ruoyi.flowable.service.process.WorkflowCollaborationDeliveryScheduler(outbox,
                mock(com.ruoyi.flowable.service.process.WorkflowCollaborationMessageService.class), properties, metrics);
        endpoint.setRequestTimeoutMs(500);
        endpoint.setEndpointKey("rule" + UUID.randomUUID().toString().replace("-", ""));
        endpoint.setCreateBy("1");
        when(endpoints.selectEnabledByKeyForUpdate(endpoint.getEndpointKey())).thenReturn(endpoint);
        freeze("POST", true);
        assertThat(endpointMapper.insert(endpoint)).isOne();
        endpoint.setChecksum(WorkflowConnectorEndpointService.endpointChecksum(endpoint));
        WfCollaborationOutbox row = row(freeze("POST", true));
        row.setCorrelationKey(UUID.randomUUID().toString());
        var channel = new com.ruoyi.flowable.domain.WfCollaborationChannel();
        channel.setChannelId(com.ruoyi.flowable.service.process.WorkflowCollaborationChannelService.channelId(
                "target", "BUSINESS_KEY", row.getCorrelationKey(), "COLLABORATION"));
        channel.setTargetProcessDefinitionKey("target"); channel.setCorrelationType("BUSINESS_KEY");
        channel.setCorrelationValue(row.getCorrelationKey()); channel.setDeliveryMode("COLLABORATION");
        row.setChannelId(channel.getChannelId());
        row.setSourceProcessInstanceId(UUID.randomUUID().toString());
        row.setSourceExecutionId(UUID.randomUUID().toString()); row.setSourceElementId("send");
        row.setEndpointId(endpoint.getEndpointId()); row.setEndpointRevision(1); row.setRequestPath("/send");
        row.setPayloadSha256("a".repeat(64)); row.setMaxAttempts(2);
        String followerId = UUID.randomUUID().toString();
        try
        {
            assertThat(channelMapper.insertIfAbsent(channel)).isOne();
            assertThat(mapper.insert(row)).isOne();
            switch (failure)
            {
                case "RESPONSE_TOO_LARGE" -> responseBody.set("secret".repeat(12000));
                case "RESPONSE_INVALID" -> responseBody.set("{");
                case "HTTP_STATUS" -> status.set(503);
                case "TIMEOUT" -> responseDelay.set(800);
                default -> throw new AssertionError(failure);
            }
            scheduler.deliverDueMessages();
            WfCollaborationOutbox retry = mapper.selectById(row.getMessageId());
            assertThat(retry.getStatus()).isEqualTo("RETRYING");
            assertThat(retry.getAttemptCount()).isOne();
            assertThat(retry.getRevisionNo()).isEqualTo(2);
            assertThat(retry.getLeaseOwner()).isNull(); assertThat(retry.getLeaseUntil()).isNull();
            assertThat(retry.getLastErrorCode()).isEqualTo("COLLAB_OUTBOX_" + failure);
            assertThat(retry.getLastErrorSummary()).doesNotContain("secret");
            jdbc.update("update wf_collaboration_outbox set next_attempt_time=date_sub(current_timestamp(3), interval 1 second) where message_id=?", row.getMessageId());
            scheduler.deliverDueMessages();
            var dead = mapper.selectById(row.getMessageId());
            assertThat(dead.getStatus()).isEqualTo("DEAD_LETTER");
            assertThat(dead.getAttemptCount()).isEqualTo(2); assertThat(dead.getRevisionNo()).isEqualTo(4);
            assertThat(dead.getCompleteTime()).isNotNull();
            verify(metrics, never()).record("scheduler_failure");
            assertThat(auditMapper.selectByMessageId(row.getMessageId())).hasSize(4);
            jdbc.update("insert into wf_collaboration_outbox(message_id,channel_id,sequence_no,source_process_definition_key,"
                    + "source_process_instance_id,source_execution_id,source_element_id,message_name,target_process_definition_key,"
                    + "correlation_key,endpoint_id,endpoint_revision,request_path,delivery_config_json,variables_json,payload_sha256,max_attempts,status) "
                    + "select ?,channel_id,sequence_no+1,source_process_definition_key,source_process_instance_id,concat(source_execution_id,'-next'),"
                    + "source_element_id,message_name,target_process_definition_key,correlation_key,endpoint_id,endpoint_revision,"
                    + "request_path,delivery_config_json,variables_json,payload_sha256,max_attempts,'PENDING' from wf_collaboration_outbox where message_id=?",
                    followerId, row.getMessageId());
            assertThat(outbox.claimNext("blocked-follower")).isNull();

            outbox.compensate(row.getMessageId());
            WfCollaborationOutbox abandoned = outbox.claimNext("abandoned-worker");
            assertThat(abandoned.getAttemptCount()).isOne();
            jdbc.update("update wf_collaboration_outbox set lease_until=date_sub(current_timestamp(3), interval 1 second) where message_id=?", row.getMessageId());
            WfCollaborationOutbox recovered = outbox.claimNext("replacement-worker");
            assertThat(recovered.getAttemptCount()).isEqualTo(2);
            var knownFailure = WorkflowCollaborationOutboxService.DeliveryOutcome.failure(null, "COLLAB_OUTBOX_TIMEOUT", "timeout");
            assertThatThrownBy(() -> outbox.completeFailure(abandoned, "abandoned-worker", knownFailure))
                    .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getSubCode()).isEqualTo("COLLAB_OUTBOX_LEASE_CONFLICT"));
            jdbc.update("update wf_collaboration_outbox set lease_until=date_sub(current_timestamp(3), interval 1 second) where message_id=?", row.getMessageId());
            assertThat(outbox.claimNext("final-recovery-worker")).isNull();
            assertThat(mapper.selectById(row.getMessageId()).getStatus()).isEqualTo("DEAD_LETTER");
            assertThat(mapper.selectById(row.getMessageId()).getLastErrorCode()).isEqualTo("COLLAB_OUTBOX_LEASE_EXPIRED");
            assertThat(mapper.selectById(row.getMessageId()).getAttemptCount()).isEqualTo(2);
            assertThatThrownBy(() -> outbox.completeFailure(recovered, "replacement-worker", knownFailure))
                    .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getSubCode()).isEqualTo("COLLAB_OUTBOX_LEASE_CONFLICT"));
            outbox.cancel(row.getMessageId());
            assertThat(mapper.selectById(row.getMessageId()).getStatus()).isEqualTo("CANCELLED");
            var follower = outbox.claimNext("after-cancel");
            assertThat(follower.getMessageId()).isEqualTo(followerId);
            outbox.completeSuccess(follower, "after-cancel", WorkflowCollaborationOutboxService.DeliveryOutcome.success(200));
            assertThat(mapper.selectById(followerId).getStatus()).isEqualTo("PROCESSED");
        }
        finally
        {
            jdbc.update("delete from wf_collaboration_message_audit where message_id=? and direction='OUTBOUND'", row.getMessageId());
            jdbc.update("delete from wf_collaboration_message_audit where message_id=? and direction='OUTBOUND'", followerId);
            jdbc.update("delete from wf_collaboration_outbox where message_id=?", followerId);
            jdbc.update("delete from wf_collaboration_outbox where message_id=?", row.getMessageId());
            jdbc.update("delete from wf_collaboration_channel where channel_id=?", row.getChannelId());
            jdbc.update("delete from wf_connector_endpoint where endpoint_id=?", endpoint.getEndpointId());
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(T target, org.springframework.transaction.PlatformTransactionManager manager)
    {
        var factory = new org.springframework.aop.framework.ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 65537})
    void collaborationRejectsInvalidBodyBeforeSending(int size)
    {
        JsonNode frozen = freeze("POST", true);
        byte[] body = size < 0 ? null : new byte[size];
        assertThatThrownBy(() -> execution.postFrozenJson(frozen.toString(), "bounded", body))
                .isInstanceOf(ServiceException.class).hasMessage("协作消息请求正文大小不合法");
        assertThat(requests).hasValue(0);
    }

    @Test
    void collaborationRejectsNonPostWithoutRestrictingOrdinaryHttp()
    {
        JsonNode frozen = freeze("PUT", true);
        assertThatThrownBy(() -> execution.postFrozenJson(frozen.toString(), "post-only", new byte[] {1}))
                .isInstanceOf(ServiceException.class).hasMessage("协作消息端点只允许 POST");
        assertThat(requests).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NONE", "X-Other-Token"})
    void freezingAndDeliveryRejectTheSameInvalidCollaborationAuthentication(String authentication)
    {
        freeze("POST", !"NONE".equals(authentication));
        if (!"NONE".equals(authentication)) endpoint.setApiKeyHeader(authentication);
        endpoint.setChecksum(WorkflowConnectorEndpointService.endpointChecksum(endpoint));
        var author = JsonMapper.shared().createObjectNode().put("endpointKey", "local")
                .put("method", "POST").put("path", "/send");
        String frozen = catalog.freezeConfig(author, true);
        assertThatThrownBy(() -> catalog.freezePostEndpoint("local", "/send"))
                .isInstanceOfSatisfying(ServiceException.class,
                        failure -> assertThat(failure.getSubCode()).isEqualTo("COLLAB_OUTBOX_AUTH_REQUIRED"));
        assertThatThrownBy(() -> execution.postFrozenJson(frozen, "auth", new byte[] {1}))
                .isInstanceOfSatisfying(ServiceException.class,
                        failure -> assertThat(failure.getSubCode()).isEqualTo("COLLAB_OUTBOX_AUTH_REQUIRED"));
        assertThat(requests).hasValue(0);
    }

    @Test
    void collaborationMalformedFrozenJsonKeepsItsOwnErrorCode()
    {
        assertThatThrownBy(() -> execution.postFrozenJson("{", "config", new byte[] {1}))
                .isInstanceOfSatisfying(ServiceException.class,
                        failure -> assertThat(failure.getSubCode()).isEqualTo("COLLAB_OUTBOX_CONFIG_INVALID"));
        assertThat(requests).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TIMEOUT", "IO_ERROR", "INTERRUPTED"})
    void collaborationTranslatesOneTransportFailureAndRestoresInterruption(String error) throws Exception
    {
        String frozen = freeze("POST", true).toString();
        WorkflowHttpExecution failing = spy(execution);
        Exception cause = switch (error)
        {
            case "TIMEOUT" -> new HttpTimeoutException("synthetic timeout");
            case "IO_ERROR" -> new IOException("synthetic IO failure");
            default -> new InterruptedException("synthetic interruption");
        };
        doThrow(cause).when(failing).send(any(), anyString(), any());
        try
        {
            assertThatThrownBy(() -> failing.postFrozenJson(frozen, "failure", new byte[] {1}))
                    .isInstanceOfSatisfying(ServiceException.class, failure -> {
                        assertThat(failure.getCode()).isEqualTo(500);
                        assertThat(failure.getSubCode()).isEqualTo("COLLAB_OUTBOX_" + error);
                    });
            verify(failing, times(1)).send(any(), eq("failure"), any());
            assertThat(Thread.currentThread().isInterrupted()).isEqualTo("INTERRUPTED".equals(error));
            assertThat(requests).hasValue(0);
        }
        finally
        {
            Thread.interrupted();
        }
    }

    private JsonNode freeze(String method, boolean environment)
    {
        endpoint.setAuthType(environment ? "API_KEY" : "NONE");
        endpoint.setSecretRef(environment ? "WORKFLOW_CONNECTOR_SECRET_N08" : null);
        endpoint.setApiKeyHeader(environment ? "X-Integration-Token" : null);
        endpoint.setChecksum(WorkflowConnectorEndpointService.endpointChecksum(endpoint));
        var author = JsonMapper.shared().createObjectNode().put("endpointKey", endpoint.getEndpointKey())
                .put("method", method).put("path", "/send");
        return JsonMapper.shared().readTree(catalog.freezeConfig(author, true));
    }

    private WfCollaborationOutbox row(JsonNode frozen)
    {
        var row = new WfCollaborationOutbox();
        row.setMessageId(UUID.randomUUID().toString()); row.setMessageName("test");
        row.setSourceProcessDefinitionKey("source"); row.setTargetProcessDefinitionKey("target");
        row.setCorrelationKey("correlation"); row.setSequenceNo(1L); row.setVariablesJson("{}");
        row.setMaxAttempts(3); row.setAttemptCount(1); row.setRevisionNo(1);
        row.setDeliveryConfigJson(frozen.toString());
        return row;
    }
}
