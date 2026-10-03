package com.ruoyi.flowable.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.dto.WorkflowCollaborationMessageRequest;
import com.ruoyi.flowable.engine.*;
import com.ruoyi.flowable.identity.*;
import com.ruoyi.flowable.mapper.*;
import com.ruoyi.flowable.runtime.WorkflowCollaborationMetrics;
import com.ruoyi.flowable.testsupport.*;

@EnabledIfEnvironmentVariable(named = "WORKFLOW_MYSQL_TEST_URL", matches = ".+")
class WorkflowCollaborationInboundMysqlTest
{
    private WorkflowFlowableEngineTestSupport infra;
    private WorkflowCollaborationMessageService service;
    private WorkflowCollaborationChannelService channels;
    private JdbcTemplate jdbc;
    private String deployment;
    private final long credentialId = 8_000_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L);
    private final String processKey = "rule_collab_" + UUID.randomUUID().toString().replace("-", "");
    private final List<String> messages = new CopyOnWriteArrayList<>();
    private final List<String> channelIds = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp()
    {
        String url = System.getenv("WORKFLOW_MYSQL_TEST_URL");
        assertThat(url).matches("jdbc:mysql://[^/]+/[A-Za-z0-9_]*_it(?:\\?.*)?");
        var source = new DriverManagerDataSource(url, System.getenv("WORKFLOW_MYSQL_TEST_USERNAME"),
                System.getenv("WORKFLOW_MYSQL_TEST_PASSWORD"));
        infra = WorkflowFlowableEngineTestSupport.start(source, Map.of());
        jdbc = infra.jdbcTemplate();
        var engine = infra.processEngine();
        var identities = mock(WorkflowIdentityResolver.class);
        when(identities.resolveCurrentIdentity()).thenReturn(new WorkflowCurrentIdentity("1", Set.of()));
        var operations = infra.transactionalProxy(new WorkflowEngineOperations(
                new WorkflowAuthenticationContext(engine.getIdentityService(), new WorkflowIdentityCodec()),
                new WorkflowExceptionTranslator(), identities));
        var credentials = mock(WorkflowIntegrationCredentialService.class);
        jdbc.update("insert into wf_integration_credential(credential_id,credential_name,token_prefix,token_hash,scopes,allowed_variables,rate_limit_per_minute,create_by) "
                + "values(?,'rule collab',? ,?,'MESSAGE','value',10000,'1')", credentialId,
                UUID.randomUUID().toString().replace("-", "").substring(0, 12), "a".repeat(64));
        when(credentials.authenticateAndConsume("synthetic", "MESSAGE"))
                .thenReturn(new WorkflowIntegrationCredentialService.AuthenticatedCredential(credentialId, "1", Set.of("value")));
        channels = spy(new WorkflowCollaborationChannelService(mapper(WfCollaborationChannelMapper.class)));
        service = infra.transactionalProxy(new WorkflowCollaborationMessageService(operations, credentials,
                mapper(WfCollaborationMessageMapper.class), engine.getRuntimeService(), engine.getRepositoryService(),
                channels, new WorkflowCollaborationAuditService(mapper(WfCollaborationMessageAuditMapper.class)),
                mock(WorkflowCollaborationMetrics.class)));
        deployment = engine.getRepositoryService().createDeployment().name(processKey).addString("collab.bpmn20.xml", """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="collab">
                <message id="message" name="incoming"/><process id="%s" isExecutable="true"><startEvent id="s"/>
                <intermediateCatchEvent id="one"><messageEventDefinition messageRef="message"/></intermediateCatchEvent>
                <intermediateCatchEvent id="two"><messageEventDefinition messageRef="message"/></intermediateCatchEvent>
                <endEvent id="e"/><sequenceFlow id="s1" sourceRef="s" targetRef="one"/>
                <sequenceFlow id="f12" sourceRef="one" targetRef="two"/><sequenceFlow id="f2e" sourceRef="two" targetRef="e"/>
                </process></definitions>
                """.formatted(processKey)).deploy().getId();
    }

    @AfterEach
    void close()
    {
        if (infra == null) return;
        try
        {
            if (deployment != null) infra.processEngine().getRepositoryService().deleteDeployment(deployment, true);
            for (String id : messages)
            {
                jdbc.update("delete from wf_collaboration_message_audit where message_id=? and direction='INBOUND'", id);
                jdbc.update("delete from wf_collaboration_message where message_id=?", id);
            }
            for (String id : channelIds) jdbc.update("delete from wf_collaboration_channel where channel_id=?", id);
            jdbc.update("delete from wf_integration_credential where credential_id=?", credentialId);
        }
        finally { infra.close(); }
    }

    @Test
    void newMessageReplayConflictAndOutOfOrderRetryUseEachTransactionsLockedChannel()
    {
        String correlation = start();
        var second = request(correlation, 2);
        assertThat(service.publishPointToPoint("synthetic", second).status()).isEqualTo("RETRYING");
        verify(channels, times(1)).lockInbound(processKey, "BUSINESS_KEY", correlation, "POINT_TO_POINT");
        clearInvocations(channels);
        var first = request(correlation, 1);
        assertThat(service.publishPointToPoint("synthetic", first).status()).isEqualTo("PROCESSED");
        verify(channels, times(1)).lockInbound(processKey, "BUSINESS_KEY", correlation, "POINT_TO_POINT");
        clearInvocations(channels);
        assertThat(service.publishPointToPoint("synthetic", first).status()).isEqualTo("PROCESSED");
        verifyNoInteractions(channels);
        var conflict = new WorkflowCollaborationMessageRequest(first.messageId(), "incoming", "source", processKey,
                correlation, null, 1L, Map.of("value", "changed"), 3);
        assertThatThrownBy(() -> service.publishPointToPoint("synthetic", conflict))
                .isInstanceOfSatisfying(ServiceException.class, failure ->
                        assertThat(failure.getSubCode()).isEqualTo("COLLAB_MESSAGE_IDEMPOTENCY_CONFLICT"));
        jdbc.update("update wf_collaboration_message set next_attempt_time=date_sub(current_timestamp(3),interval 1 second) where message_id=?", second.messageId());
        assertThat(service.retryNextDue()).isTrue();
        verify(channels, times(1)).lockInbound(processKey, "BUSINESS_KEY", correlation, "POINT_TO_POINT");
        assertThat(jdbc.queryForObject("select status from wf_collaboration_message where message_id=?", String.class, second.messageId())).isEqualTo("PROCESSED");
        assertThat(jdbc.queryForObject("select inbound_sequence from wf_collaboration_channel where channel_id=?", Long.class,
                channelIds.get(0))).isEqualTo(2L);
    }

    @Test
    void exhaustedInboundCanBeCompensatedWithFreshChannelFacts()
    {
        String correlation = UUID.randomUUID().toString();
        channelIds.add(WorkflowCollaborationChannelService.channelId(processKey, "BUSINESS_KEY", correlation, "POINT_TO_POINT"));
        var normal = request(correlation, 1);
        var exhausted = new WorkflowCollaborationMessageRequest(normal.messageId(), normal.messageName(), "source",
                processKey, correlation, null, 1L, normal.variables(), 1);
        assertThat(service.publishPointToPoint("synthetic", exhausted).status()).isEqualTo("DEAD_LETTER");
        infra.processEngine().getRuntimeService().startProcessInstanceByKey(processKey, correlation);
        clearInvocations(channels);
        var result = service.retry(exhausted.messageId());
        assertThat(result.status()).isEqualTo("PROCESSED");
        assertThat(result.compensationCount()).isOne();
        verify(channels, times(1)).lockInbound(processKey, "BUSINESS_KEY", correlation, "POINT_TO_POINT");
    }

    @Test
    void sameChannelSerializesWhileDifferentChannelProgresses() throws Exception
    {
        String correlation = start();
        String otherCorrelation = start();
        // 先登记两条乱序消息，使并发试验锁定既有消息和频道，避免不存在主键的间隙锁混入频道锁断言。
        var request = request(correlation, 2);
        var otherRequest = request(otherCorrelation, 2);
        assertThat(service.publishPointToPoint("synthetic", request).status()).isEqualTo("RETRYING");
        assertThat(service.publishPointToPoint("synthetic", otherRequest).status()).isEqualTo("RETRYING");
        service.publishPointToPoint("synthetic", request(correlation, 1));
        service.publishPointToPoint("synthetic", request(otherCorrelation, 1));
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(3);
        var holdFirst = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(invocation -> {
            Object channel = invocation.callRealMethod();
            if (holdFirst.getAndSet(false))
            {
                locked.countDown();
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return channel;
        }).when(channels).lockInbound(processKey, "BUSINESS_KEY", correlation, "POINT_TO_POINT");
        try
        {
            var first = workers.submit(() -> service.publishPointToPoint("synthetic", request));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var secondStarted = new CountDownLatch(1);
            var second = workers.submit(() -> {
                secondStarted.countDown();
                return service.publishPointToPoint("synthetic", request);
            });
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            var independent = workers.submit(() -> service.publishPointToPoint("synthetic", otherRequest));
            assertThat(independent.get(5, TimeUnit.SECONDS).status()).isEqualTo("PROCESSED");
            assertThatThrownBy(() -> second.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).status()).isEqualTo("PROCESSED");
            assertThat(second.get(10, TimeUnit.SECONDS).status()).isEqualTo("PROCESSED");
        }
        finally
        {
            release.countDown(); workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private String start()
    {
        String correlation = UUID.randomUUID().toString();
        infra.processEngine().getRuntimeService().startProcessInstanceByKey(processKey, correlation);
        channelIds.add(WorkflowCollaborationChannelService.channelId(processKey, "BUSINESS_KEY", correlation, "POINT_TO_POINT"));
        return correlation;
    }

    private WorkflowCollaborationMessageRequest request(String correlation, long sequence)
    {
        String id = UUID.randomUUID().toString(); messages.add(id);
        return new WorkflowCollaborationMessageRequest(id, "incoming", "source", processKey,
                correlation, null, sequence, Map.of("value", "message-" + sequence), 3);
    }

    private <T> T mapper(Class<T> type)
    {
        return WorkflowH2SchemaMapperSupport.createSpringMapper(infra.dataSource(), type.getSimpleName(), type,
                "mapper/flowable/" + type.getSimpleName() + ".xml");
    }
}
