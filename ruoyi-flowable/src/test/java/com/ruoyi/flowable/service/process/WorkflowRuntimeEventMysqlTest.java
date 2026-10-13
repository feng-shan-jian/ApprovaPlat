package com.ruoyi.flowable.service.process;

import com.ruoyi.common.testsupport.WorkflowMysqlTargetGuard;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flowable.engine.delegate.JavaDelegate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.dto.WorkflowIntegrationCredentialCreateRequest;
import com.ruoyi.flowable.domain.dto.WorkflowRuntimeEventRequest;
import com.ruoyi.flowable.domain.vo.WorkflowIntegrationCredentialSecretView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.engine.WorkflowExceptionTranslator;
import com.ruoyi.flowable.identity.WorkflowAuthenticationContext;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityCodec;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfIntegrationCredentialMapper;
import com.ruoyi.flowable.mapper.WfRuntimeEventRequestMapper;
import com.ruoyi.flowable.runtime.WorkflowCredentialRateLimitMetrics;
import com.ruoyi.flowable.runtime.WorkflowRedisAtomicOperations;
import com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport;
import com.ruoyi.flowable.testsupport.WorkflowH2SchemaMapperSupport;

/** Real engine/MySQL transactions and real Redis authentication; no vendor IO. */
@EnabledIfEnvironmentVariable(named = "WORKFLOW_MYSQL_TEST_URL", matches = ".+")
class WorkflowRuntimeEventMysqlTest
{
    private final String prefix = "runtime_event_" + UUID.randomUUID().toString().replace("-", "");
    private final Instant now = Instant.now();
    private final List<String> deployments = new ArrayList<>();
    private final List<String> requests = new ArrayList<>();
    private final List<Long> credentialIds = new ArrayList<>();
    private final List<String> redisKeys = new ArrayList<>();
    private final AtomicBoolean downstreamFailure = new AtomicBoolean();
    private WorkflowFlowableEngineTestSupport infra;
    private JdbcTemplate jdbc;
    private LettuceConnectionFactory redisFactory;
    private StringRedisTemplate redis;
    private WorkflowEngineOperations operations;
    private WorkflowIntegrationCredentialService credentials;
    private WorkflowRuntimeEventService service;

    @BeforeEach
    void setUp()
    {
        String url = System.getenv("WORKFLOW_MYSQL_TEST_URL");
        WorkflowMysqlTargetGuard.verify(url);
        String redisPort = System.getenv("WORKFLOW_REDIS_TEST_PORT");
        assertThat(redisPort).as("M02 isolated Redis is required with MySQL").isNotBlank();
        infra = WorkflowFlowableEngineTestSupport.start(new DriverManagerDataSource(url,
                System.getenv("WORKFLOW_MYSQL_TEST_USERNAME"), System.getenv("WORKFLOW_MYSQL_TEST_PASSWORD")),
                Map.of("controlledFailure", (JavaDelegate) execution -> {
                    execution.setVariable("downstreamEffect", "must rollback");
                    if (downstreamFailure.get()) throw new IllegalStateException("controlled downstream failure");
                }));
        jdbc = infra.jdbcTemplate();
        var identity = mock(WorkflowIdentityResolver.class);
        when(identity.resolveCurrentIdentity()).thenReturn(new WorkflowCurrentIdentity("1", Set.of()));
        operations = infra.transactionalProxy(new WorkflowEngineOperations(new WorkflowAuthenticationContext(
                infra.processEngine().getIdentityService(), new WorkflowIdentityCodec()),
                new WorkflowExceptionTranslator(), identity));
        var config = new RedisStandaloneConfiguration(System.getenv().getOrDefault("WORKFLOW_REDIS_TEST_HOST", "127.0.0.1"), Integer.parseInt(redisPort));
        String password = System.getenv("WORKFLOW_REDIS_TEST_PASSWORD");
        if (password != null && !password.isBlank()) config.setPassword(password);
        redisFactory = new LettuceConnectionFactory(config);
        redisFactory.afterPropertiesSet();
        redisFactory.start();
        redis = new StringRedisTemplate(redisFactory);
        redis.afterPropertiesSet();
        credentials = credentialService(now);
        service = eventService(credentials);
    }

    @AfterEach
    void close()
    {
        try
        {
            if (infra != null)
            {
                for (String deployment : deployments) infra.processEngine().getRepositoryService().deleteDeployment(deployment, true);
                for (String request : requests) jdbc.update("delete from wf_runtime_event_request where request_id=?", request);
                for (Long credential : credentialIds) jdbc.update("delete from wf_integration_credential where credential_id=?", credential);
            }
            if (redis != null && !redisKeys.isEmpty()) redis.delete(redisKeys);
        }
        finally
        {
            if (redisFactory != null) redisFactory.destroy();
            if (infra != null) infra.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "MESSAGE", "SIGNAL", "RECEIVE" })
    void uniqueConsumptionReplayAndZeroMatchHaveDurableOutcomes(String type)
    {
        var secret = credential(List.of(type), 100);
        var absent = request(eventName(type), null, prefix + "_absent", Map.of());
        fails(() -> service.publish(secret.token(), type, absent), "RUNTIME_EVENT_INSTANCE_NOT_FOUND");
        assertLedger(absent.requestId(), "FAILED", "RUNTIME_EVENT_INSTANCE_NOT_FOUND");

        String key = deploy(type, false, false);
        var instance = infra.processEngine().getRuntimeService().startProcessInstanceByKey(key, prefix);
        var incoming = request(eventName(type), instance.getId(), null, Map.of("value", "accepted"));
        var result = service.publish(secret.token(), type, incoming);
        assertThat(result.status()).isEqualTo("PROCESSED");
        assertThat(result.matchedProcessInstanceId()).isEqualTo(instance.getId());
        assertThat(result.matchedExecutionId()).isNotBlank();
        assertThat(infra.processEngine().getRuntimeService().getVariable(instance.getId(), "value")).isEqualTo("accepted");
        assertThat(infra.processEngine().getTaskService().createTaskQuery().processInstanceId(instance.getId()).singleResult().getTaskDefinitionKey()).isEqualTo("review");
        assertThat(service.publish(secret.token(), type, incoming)).isEqualTo(result);
        fails(() -> service.publish(secret.token(), type, new WorkflowRuntimeEventRequest(incoming.requestId(),
                incoming.eventName(), instance.getId(), null, Map.of("value", "different"))), "RUNTIME_EVENT_IDEMPOTENCY_CONFLICT");
        assertThat(jdbc.queryForObject("select count(*) from wf_runtime_event_request where request_id=? and status='PROCESSED'", Integer.class, incoming.requestId())).isOne();

        var noWait = request(eventName(type), instance.getId(), null, Map.of());
        String noWaitCode = "RECEIVE".equals(type) ? "RUNTIME_EVENT_RECEIVE_NOT_FOUND" : "RUNTIME_EVENT_SUBSCRIPTION_NOT_FOUND";
        fails(() -> service.publish(secret.token(), type, noWait), noWaitCode);
        assertLedger(noWait.requestId(), "FAILED", noWaitCode);
    }

    @ParameterizedTest
    @ValueSource(strings = { "MESSAGE", "SIGNAL", "RECEIVE" })
    void ambiguousInstancesAndWaitingExecutionsNeverConsumeAnyCandidate(String type)
    {
        var secret = credential(List.of(type), 100);
        String key = deploy(type, false, false);
        var runtime = infra.processEngine().getRuntimeService();
        var first = runtime.startProcessInstanceByKey(key, prefix);
        var second = runtime.startProcessInstanceByKey(key, prefix);
        var ambiguous = request(eventName(type), null, prefix, Map.of("value", "forbidden"));
        fails(() -> service.publish(secret.token(), type, ambiguous), "RUNTIME_EVENT_INSTANCE_AMBIGUOUS");
        assertLedger(ambiguous.requestId(), "FAILED", "RUNTIME_EVENT_INSTANCE_AMBIGUOUS");
        assertThat(runtime.getVariables(first.getId())).doesNotContainKey("value");
        assertThat(runtime.getVariables(second.getId())).doesNotContainKey("value");
        String parallel = deploy(type, true, false);
        var instance = runtime.startProcessInstanceByKey(parallel, prefix + "_parallel");
        var duplicate = request(eventName(type), instance.getId(), null, Map.of("value", "forbidden"));
        String code = "RECEIVE".equals(type) ? "RUNTIME_EVENT_RECEIVE_AMBIGUOUS" : "RUNTIME_EVENT_SUBSCRIPTION_AMBIGUOUS";
        fails(() -> service.publish(secret.token(), type, duplicate), code);
        assertLedger(duplicate.requestId(), "FAILED", code);
        assertThat(runtime.getVariables(instance.getId())).doesNotContainKey("value");
        assertThat(infra.processEngine().getTaskService().createTaskQuery().processInstanceId(instance.getId()).count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = { "MESSAGE", "SIGNAL", "RECEIVE" })
    void downstreamFailureRollsBackConsumptionAndPersistsIndependentFailureAudit(String type)
    {
        var secret = credential(List.of(type), 100);
        String key = deploy(type, false, true);
        var runtime = infra.processEngine().getRuntimeService();
        var instance = runtime.startProcessInstanceByKey(key, prefix);
        var incoming = request(eventName(type), instance.getId(), null, Map.of("value", "must rollback"));
        downstreamFailure.set(true);
        assertThatThrownBy(() -> service.publish(secret.token(), type, incoming)).isInstanceOf(ServiceException.class);
        assertThat(jdbc.queryForObject("select status from wf_runtime_event_request where request_id=?", String.class, incoming.requestId())).isEqualTo("FAILED");
        String originalCode = jdbc.queryForObject("select result_code from wf_runtime_event_request where request_id=?", String.class, incoming.requestId());
        assertThat(originalCode).isNotBlank();
        assertThat(runtime.getVariables(instance.getId())).doesNotContainKeys("value", "downstreamEffect");
        assertThat(infra.processEngine().getTaskService().createTaskQuery().processInstanceId(instance.getId()).count()).isZero();
        assertThat(runtime.createExecutionQuery().processInstanceId(instance.getId()).activityId("wait").count()).isOne();
        downstreamFailure.set(false);
        fails(() -> service.publish(secret.token(), type, incoming), originalCode);
        var fresh = request(eventName(type), instance.getId(), null, Map.of("value", "recovered"));
        assertThat(service.publish(secret.token(), type, fresh).status()).isEqualTo("PROCESSED");
        assertLedger(incoming.requestId(), "FAILED", originalCode);
        assertThat(runtime.getVariable(instance.getId(), "value")).isEqualTo("recovered");
    }

    @Test
    void realCredentialScopeExpiryWhitelistAndRedisLimitGateTheEngine()
    {
        var secret = credential(List.of("MESSAGE"), 1);
        var scopeDenied = request("incoming", null, prefix, Map.of());
        fails(() -> service.publish(secret.token(), "SIGNAL", scopeDenied), "INTEGRATION_SCOPE_DENIED");
        assertThat(jdbc.queryForObject("select count(*) from wf_runtime_event_request where request_id=?", Integer.class, scopeDenied.requestId())).isZero();
        var deniedVariable = request("incoming", null, prefix, Map.of("forbidden", "no"));
        fails(() -> service.publish(secret.token(), "MESSAGE", deniedVariable), "RUNTIME_EVENT_VARIABLE_DENIED");
        assertLedger(deniedVariable.requestId(), "FAILED", "RUNTIME_EVENT_VARIABLE_DENIED");
        var limited = request("incoming", null, prefix, Map.of());
        fails(() -> service.publish(secret.token(), "MESSAGE", limited), "INTEGRATION_RATE_LIMITED");
        assertThat(redis.opsForValue().get(redisKeys.get(0))).isEqualTo("2");
        assertThat(redis.getExpire(redisKeys.get(0))).isBetween(1L, 60L);
        assertThat(jdbc.queryForObject("select count(*) from wf_runtime_event_request where request_id=?", Integer.class, limited.requestId())).isZero();
        var expired = eventService(credentialService(now.plusSeconds(180)));
        fails(() -> expired.publish(secret.token(), "MESSAGE", request("incoming", null, prefix, Map.of())), "INTEGRATION_TOKEN_INVALID");
        assertThat(redis.opsForValue().get(redisKeys.get(0))).isEqualTo("2");
    }

    private WorkflowIntegrationCredentialSecretView credential(List<String> scopes, int limit)
    {
        var secret = credentials.create(new WorkflowIntegrationCredentialCreateRequest(prefix, scopes, List.of("value"), limit,
                now.plusSeconds(120).atOffset(ZoneOffset.UTC)));
        credentialIds.add(secret.credentialId());
        redisKeys.add("workflow:credential:rate:" + secret.credentialId() + ":1:" + DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC).format(now));
        return secret;
    }

    private WorkflowIntegrationCredentialService credentialService(Instant instant)
    {
        return new WorkflowIntegrationCredentialService(operations, mapper(WfIntegrationCredentialMapper.class),
                new WorkflowRedisAtomicOperations(redis), mock(WorkflowCredentialRateLimitMetrics.class),
                new SecureRandom(), Clock.fixed(instant, ZoneOffset.UTC));
    }

    private WorkflowRuntimeEventService eventService(WorkflowIntegrationCredentialService credentialService)
    {
        var mapper = mapper(WfRuntimeEventRequestMapper.class);
        return new WorkflowRuntimeEventService(operations, credentialService,
                infra.transactionalProxy(new WorkflowRuntimeEventAuditService(mapper)), mapper,
                infra.processEngine().getRuntimeService(), infra.processEngine().getRepositoryService());
    }

    private WorkflowRuntimeEventRequest request(String name, String instance, String business, Map<String, Object> variables)
    {
        String id = UUID.randomUUID().toString(); requests.add(id);
        return new WorkflowRuntimeEventRequest(id, name, instance, business, variables);
    }

    private String eventName(String type) { return "RECEIVE".equals(type) ? "wait" : "incoming"; }

    private String deploy(String type, boolean multiple, boolean failing)
    {
        String key = prefix + "_" + deployments.size();
        String declaration = "RECEIVE".equals(type) ? "" : "<" + type.toLowerCase() + " id=\"event\" name=\"incoming\"/>";
        String waiter = "RECEIVE".equals(type)
                ? "<receiveTask id=\"wait\">" + (multiple ? "<multiInstanceLoopCharacteristics isSequential=\"false\"><loopCardinality>2</loopCardinality></multiInstanceLoopCharacteristics>" : "") + "</receiveTask>"
                : "<intermediateCatchEvent id=\"wait\"><" + type.toLowerCase() + "EventDefinition " + type.toLowerCase() + "Ref=\"event\"/></intermediateCatchEvent>";
        String flow;
        if (multiple && !"RECEIVE".equals(type))
        {
            flow = "<parallelGateway id=\"split\"/><sequenceFlow id=\"start_split\" sourceRef=\"start\" targetRef=\"split\"/>"
                    + waiter + waiter.replace("id=\"wait\"", "id=\"other\"")
                    + "<sequenceFlow id=\"split_wait\" sourceRef=\"split\" targetRef=\"wait\"/><sequenceFlow id=\"split_other\" sourceRef=\"split\" targetRef=\"other\"/>"
                    + "<sequenceFlow id=\"other_review\" sourceRef=\"other\" targetRef=\"review\"/>";
        }
        else flow = waiter + "<sequenceFlow id=\"start_wait\" sourceRef=\"start\" targetRef=\"wait\"/>";
        String next = failing ? "<serviceTask id=\"effect\" flowable:delegateExpression=\"${controlledFailure}\"/><sequenceFlow id=\"wait_effect\" sourceRef=\"wait\" targetRef=\"effect\"/><sequenceFlow id=\"effect_review\" sourceRef=\"effect\" targetRef=\"review\"/>"
                : "<sequenceFlow id=\"wait_review\" sourceRef=\"wait\" targetRef=\"review\"/>";
        String xml = "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" xmlns:flowable=\"http://flowable.org/bpmn\" targetNamespace=\"runtime-events\">"
                + declaration + "<process id=\"" + key + "\" isExecutable=\"true\"><startEvent id=\"start\"/>" + flow + next
                + "<userTask id=\"review\"/><endEvent id=\"end\"/><sequenceFlow id=\"finish\" sourceRef=\"review\" targetRef=\"end\"/></process></definitions>";
        deployments.add(infra.processEngine().getRepositoryService().createDeployment().addString(key + ".bpmn20.xml", xml).deploy().getId());
        return key;
    }

    private void assertLedger(String request, String status, String code)
    {
        assertThat(jdbc.queryForMap("select status,result_code from wf_runtime_event_request where request_id=?", request))
                .containsEntry("status", status).containsEntry("result_code", code);
    }

    private void fails(Runnable action, String code)
    {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getSubCode()).isEqualTo(code));
    }

    private <T> T mapper(Class<T> type)
    {
        return WorkflowH2SchemaMapperSupport.createSpringMapper(infra.dataSource(), type.getSimpleName(), type, "mapper/flowable/" + type.getSimpleName() + ".xml");
    }
}
