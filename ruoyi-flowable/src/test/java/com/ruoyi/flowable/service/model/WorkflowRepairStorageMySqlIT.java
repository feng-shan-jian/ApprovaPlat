package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.dto.WorkflowConnectorEndpointRequest;
import com.ruoyi.flowable.domain.dto.WorkflowModelDesignDraftRequest;
import com.ruoyi.flowable.domain.vo.WorkflowModelView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.engine.WorkflowExceptionTranslator;
import com.ruoyi.flowable.extension.WorkflowConnectorSecretResolver;
import com.ruoyi.flowable.extension.WorkflowManagedConnectorSecrets;
import com.ruoyi.flowable.identity.*;
import com.ruoyi.flowable.mapper.WfConnectorEndpointMapper;
import com.ruoyi.flowable.mapper.WfIntegrationCredentialMapper;
import com.ruoyi.flowable.domain.dto.WorkflowIntegrationCredentialCreateRequest;
import com.ruoyi.flowable.service.process.WorkflowIntegrationCredentialService;
import com.ruoyi.flowable.runtime.WorkflowCredentialRateLimitMetrics;
import com.ruoyi.flowable.runtime.WorkflowRedisAtomicOperations;
import com.ruoyi.flowable.testsupport.WorkflowH2SchemaMapperSupport;
import com.ruoyi.flowable.testsupport.WorkflowMySqlITSupport;

/** 独立 MySQL 上验证正式增量 SQL、端点 Mapper、受管绑定事务与设计草稿 CAS。 */
class WorkflowRepairStorageMySqlIT
{
    private static com.mysql.cj.jdbc.MysqlDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static WorkflowEngineOperations operations;
    private static WorkflowIdentityResolver identities;
    private static WfConnectorEndpointMapper endpoints;
    private static final String ACTOR = "90912001";

    @BeforeAll static void connect() throws Exception
    {
        dataSource = WorkflowMySqlITSupport.createDataSource();
        WorkflowMySqlITSupport.verifyIsolatedBaseline(dataSource, "修复存储协议", "approvaplat_it",
                List.of("wf_connector_endpoint", "wf_model_design_draft", "wf_connector_managed_secret"));
        jdbc = new JdbcTemplate(dataSource);
        identities = mock(WorkflowIdentityResolver.class);
        when(identities.resolveCurrentIdentity()).thenReturn(new WorkflowCurrentIdentity(ACTOR, Set.of()));
        operations = WorkflowMySqlITSupport.transactionalProxy(new WorkflowEngineOperations(
                new WorkflowAuthenticationContext(mock(org.flowable.engine.IdentityService.class), new WorkflowIdentityCodec()),
                new WorkflowExceptionTranslator(), identities), new DataSourceTransactionManager(dataSource));
        endpoints = WorkflowH2SchemaMapperSupport.createSpringMapper(dataSource, "repair-storage-mysql",
                WfConnectorEndpointMapper.class, "mapper/flowable/WfConnectorEndpointMapper.xml");
    }

    @Test void managedBindingRotationRevocationAndFailedBindingAreAtomic()
    {
        byte[] master = new byte[32]; new java.security.SecureRandom().nextBytes(master);
        String key = Base64.getEncoder().encodeToString(master);
        var secrets = new WorkflowManagedConnectorSecrets(dataSource, key);
        var resolver = new WorkflowConnectorSecretResolver(secrets);
        var service = new WorkflowConnectorEndpointService(operations, endpoints, resolver);
        var binding = new WorkflowConnectorCredentialService(operations, endpoints, service, secrets);
        String endpointKey = "repair12_" + UUID.randomUUID().toString().replace("-", "");
        long endpointId = service.create(new WorkflowConnectorEndpointRequest(endpointKey, "修复受管事务验证", "http://127.0.0.1:18081",
                List.of("POST"), "/workflow", "NONE", null, null, 1000, 3000, "PRIVATE"));
        String reference = null;
        try
        {
            assertThat(service.list()).filteredOn(row -> row.endpointId() == endpointId).singleElement().satisfies(row -> assertThat(row.revisionNo()).isOne());
            long before = jdbc.queryForObject("select count(*) from wf_connector_managed_secret where update_by=?", Long.class, ACTOR);
            assertThatThrownBy(() -> binding.bind(endpointId, 1, 0, "first-test-secret", "API_KEY", "invalid header"))
                    .isInstanceOf(ServiceException.class);
            assertThat(jdbc.queryForObject("select count(*) from wf_connector_managed_secret where update_by=?", Long.class, ACTOR)).isEqualTo(before);
            assertThat(binding.state(endpointId).endpointRevision()).isOne();
            var bound = binding.bind(endpointId, 1, 0, "first-test-secret", "BEARER", null);
            reference = bound.credential().reference();
            assertThat(bound.endpointRevision()).isEqualTo(2);
            assertThat(resolver.requireSecret(reference)).isEqualTo("first-test-secret");
            assertThat(new WorkflowManagedConnectorSecrets(dataSource, key).requireSecret(reference)).isEqualTo("first-test-secret");
            assertThat(jdbc.queryForObject("select encrypted_value from wf_connector_managed_secret where secret_ref=?", String.class, reference)).doesNotContain("first-test-secret");
            assertThat(service.listOptions()).filteredOn(row -> row.endpointId() == endpointId).singleElement().satisfies(row -> assertThat(row.available()).isTrue());
            assertThatThrownBy(() -> binding.bind(endpointId, 1, 1, "stale-test-secret", "BEARER", null)).isInstanceOf(ServiceException.class);
            var rotated = binding.bind(endpointId, 2, 1, "rotated-test-secret", "BEARER", null);
            assertThat(rotated.credential().reference()).isEqualTo(reference);
            assertThat(resolver.requireSecret(reference)).isEqualTo("rotated-test-secret");
            binding.revoke(endpointId, 3, 2);
            String revoked = reference;
            assertThatThrownBy(() -> resolver.requireSecret(revoked)).isInstanceOf(ServiceException.class);
            assertThat(service.listOptions()).filteredOn(row -> row.endpointId() == endpointId).singleElement().satisfies(row -> assertThat(row.available()).isFalse());
        }
        finally
        {
            jdbc.update("delete from wf_connector_endpoint where endpoint_id=? and endpoint_key=?", endpointId, endpointKey);
            if (reference != null) jdbc.update("delete from wf_connector_managed_secret where secret_ref=? and update_by=?", reference, ACTOR);
        }
    }

    @Test void draftCompareAndSwapPreservesWinnerAndTombstone() throws Exception
    {
        String modelId = "repair12_" + UUID.randomUUID().toString().replace("-", "");
        var models = mock(WorkflowModelService.class);
        when(models.getModel(modelId)).thenReturn(new WorkflowModelView(modelId, "草稿", "draftProcess", "test", 1, 1, 2,
                null, null, null, null, null, null, false, true, true, true));
        var service = new WorkflowModelDesignDraftService(dataSource, models, new WorkflowBpmnService(null, null, null), operations, identities);
        String xml = "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\"><process id=\"draftProcess\"/></definitions>";
        try
        {
            service.save(modelId, new WorkflowModelDesignDraftRequest(1, 0, xml));
            assertThatThrownBy(() -> service.save(modelId, new WorkflowModelDesignDraftRequest(1, 0, xml))).isInstanceOf(ServiceException.class);
            var workers = Executors.newFixedThreadPool(2);
            service.save(modelId, new WorkflowModelDesignDraftRequest(1, 1, xml.replace("<process ", "<process name=\"winner\" ")));
            assertThatThrownBy(() -> service.save(modelId, new WorkflowModelDesignDraftRequest(1, 1, xml))).isInstanceOf(ServiceException.class);
            assertThat(service.read(modelId).bpmnXml()).contains("winner");
            service.discard(modelId, 2);
            assertThat(service.read(modelId).revision()).isEqualTo(3);
            assertThat(service.read(modelId).bpmnXml()).isNull();
            assertThatThrownBy(() -> service.save(modelId, new WorkflowModelDesignDraftRequest(1, 0, xml))).isInstanceOf(ServiceException.class);
            var start = new CountDownLatch(1);
            try
            {
                var attempts = java.util.stream.IntStream.range(0, 2).mapToObj(index -> workers.submit(() -> {
                    if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("并发起跑超时");
                    try
                    {
                        service.save(modelId, new WorkflowModelDesignDraftRequest(1, 3, xml.replace("<process ", "<process name=\"concurrent" + index + "\" ")));
                        return true;
                    }
                    catch (ServiceException conflict)
                    {
                        assertThat(conflict.getCode()).isEqualTo(409);
                        return false;
                    }
                })).toList();
                start.countDown();
                assertThat(List.of(attempts.get(0).get(15, TimeUnit.SECONDS), attempts.get(1).get(15, TimeUnit.SECONDS)))
                        .containsExactlyInAnyOrder(true, false);
                assertThat(service.read(modelId).revision()).isEqualTo(4);
                assertThat(service.read(modelId).bpmnXml()).contains("concurrent");
            }
            finally { workers.shutdownNow(); }
        }
        finally { jdbc.update("delete from wf_model_design_draft where model_id=? and owner_user_id=?", modelId, ACTOR); }
    }

    @Test void integrationCreationAndManagedBindingCommitOrRollBackTogether() throws Exception
    {
        var credentials = WorkflowH2SchemaMapperSupport.createSpringMapper(dataSource, "repair-integration-mysql",
                WfIntegrationCredentialMapper.class, "mapper/flowable/WfIntegrationCredentialMapper.xml");
        var integration = new WorkflowIntegrationCredentialService(operations, credentials,
                mock(WorkflowRedisAtomicOperations.class), mock(WorkflowCredentialRateLimitMetrics.class));
        var secrets = new WorkflowManagedConnectorSecrets(dataSource, Base64.getEncoder().encodeToString(new byte[32]));
        var endpointService = new WorkflowConnectorEndpointService(operations, endpoints, new WorkflowConnectorSecretResolver(secrets));
        var connector = new WorkflowConnectorCredentialService(operations, endpoints, endpointService, secrets);
        var binding = new WorkflowIntegrationCredentialBindingService(operations, integration, connector);
        var receiverToken = new java.util.concurrent.atomic.AtomicReference<String>();
        var wrongNonce = new java.util.concurrent.atomic.AtomicBoolean();
        var received = new java.util.concurrent.atomic.AtomicInteger();
        var receiver = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/workflow/runtime-event/auth-check", exchange -> {
            received.incrementAndGet();
            var body = tools.jackson.databind.json.JsonMapper.shared().readTree(exchange.getRequestBody());
            boolean valid = java.util.Objects.equals(receiverToken.get(), exchange.getRequestHeaders().getFirst("X-Integration-Token"));
            String nonce = wrongNonce.get() ? "wrong" : body.path("nonce").asText();
            byte[] response = ("{\"code\":" + (valid ? 200 : 401) + ",\"data\":{\"protocol\":\"APPROVA_MESSAGE_AUTH_V1\",\"authenticated\":" + valid + ",\"nonce\":\"" + nonce + "\"}}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        receiver.start();
        String key = "repair12_bind_" + UUID.randomUUID().toString().replace("-", "");
        long endpointId = endpointService.create(new WorkflowConnectorEndpointRequest(key, "集成原子绑定验证", "http://127.0.0.1:" + receiver.getAddress().getPort(),
                List.of("POST"), "/workflow", "NONE", null, null, 1000, 3000, "PRIVATE"));
        String reference = null;
        var request = new WorkflowIntegrationCredentialCreateRequest(key, List.of("MESSAGE"), List.of("amount"), 60, null);
        try
        {
            var missingMaster = new WorkflowConnectorCredentialService(operations, endpoints, endpointService,
                    new WorkflowManagedConnectorSecrets(dataSource, ""));
            assertThatThrownBy(() -> new WorkflowIntegrationCredentialBindingService(operations, integration, missingMaster)
                    .create(endpointId, 1, 0, request)).isInstanceOf(ServiceException.class);
            assertThat(jdbc.queryForObject("select count(*) from wf_integration_credential where credential_name=?", Integer.class, key)).isZero();
            assertThat(connector.state(endpointId).endpointRevision()).isOne();
            var bound = binding.create(endpointId, 1, 0, request);
            reference = bound.endpoint().credential().reference();
            String token = secrets.requireSecret(reference);
            receiverToken.set(token);
            var http = new com.ruoyi.flowable.extension.WorkflowHttpConnector(endpointService, new WorkflowConnectorSecretResolver(secrets),
                    mock(com.ruoyi.flowable.runtime.WorkflowConnectorMetrics.class));
            var checks = new WorkflowConnectorCheckService(operations, endpoints, http);
            assertThat(checks.check(endpointId).networkAuthenticated()).isTrue();
            assertThat(received).hasValue(1);
            wrongNonce.set(true);
            assertThatThrownBy(() -> checks.check(endpointId)).isInstanceOf(ServiceException.class).hasMessageContaining("消息认证检查未通过");
            wrongNonce.set(false);
            receiverToken.set("expired-test-token");
            assertThatThrownBy(() -> checks.check(endpointId)).isInstanceOf(ServiceException.class).hasMessageContaining("消息认证检查未通过");
            assertThat(token).isNotBlank();
            assertThat(bound.toString()).doesNotContain(token);
            assertThat(jdbc.queryForObject("select token_hash from wf_integration_credential where credential_id=?", String.class, bound.credentialId())).isNotEqualTo(token);
            assertThatThrownBy(() -> binding.create(endpointId, 1, 0, request)).isInstanceOf(ServiceException.class);
            assertThat(jdbc.queryForObject("select count(*) from wf_integration_credential where credential_name=?", Integer.class, key)).isOne();
            assertThat(connector.state(endpointId).endpointRevision()).isEqualTo(2);
        }
        finally
        {
            receiver.stop(0);
            jdbc.update("delete from wf_integration_credential where credential_name=? and create_by=?", key, ACTOR);
            jdbc.update("delete from wf_connector_endpoint where endpoint_id=? and endpoint_key=?", endpointId, key);
            if (reference != null) jdbc.update("delete from wf_connector_managed_secret where secret_ref=? and update_by=?", reference, ACTOR);
        }
    }
}
