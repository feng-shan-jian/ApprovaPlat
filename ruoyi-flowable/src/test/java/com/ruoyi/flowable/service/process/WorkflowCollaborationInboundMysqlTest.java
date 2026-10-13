package com.ruoyi.flowable.service.process;

import com.ruoyi.common.testsupport.WorkflowMysqlTargetGuard;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flowable.engine.delegate.JavaDelegate;
import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    private WfCollaborationMessageMapper messageMapper;
    private JdbcTemplate jdbc;
    private final AtomicBoolean failDownstream = new AtomicBoolean();
    private final AtomicBoolean nestedRollbackOnly = new AtomicBoolean();
    private final String effectTable = "collab_effect_" + UUID.randomUUID().toString().replace("-", "");
    private String deployment;
    private final long credentialId = 8_000_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L);
    private final String processKey = "rule_collab_" + UUID.randomUUID().toString().replace("-", "");
    private final List<String> messages = new CopyOnWriteArrayList<>();
    private final List<String> channelIds = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp()
    {
        String url = System.getenv("WORKFLOW_MYSQL_TEST_URL");
        WorkflowMysqlTargetGuard.verify(url);
        var source = new DriverManagerDataSource(url, System.getenv("WORKFLOW_MYSQL_TEST_USERNAME"),
                System.getenv("WORKFLOW_MYSQL_TEST_PASSWORD"));
        infra = WorkflowFlowableEngineTestSupport.start(source, Map.of("collaborationEffect", (JavaDelegate) execution -> {
            if (nestedRollbackOnly.get()) infra.transactionTemplate().executeWithoutResult(status -> writeBusinessEffect(execution));
            else writeBusinessEffect(execution);
        }));
        jdbc = infra.jdbcTemplate();
        jdbc.execute("create table " + effectTable + " (instance_id varchar(64) primary key)");
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
        messageMapper = spy(mapper(WfCollaborationMessageMapper.class));
        service = infra.transactionalProxy(new WorkflowCollaborationMessageService(operations, credentials,
                messageMapper, engine.getRuntimeService(), engine.getRepositoryService(),
                channels, new WorkflowCollaborationAuditService(mapper(WfCollaborationMessageAuditMapper.class)),
                mock(WorkflowCollaborationMetrics.class), infra.transactionManager()));
        deployment = engine.getRepositoryService().createDeployment().name(processKey).addString("collab.bpmn20.xml", """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="collab">
                <message id="message" name="incoming"/><process id="%s" isExecutable="true"><startEvent id="s"/>
                <intermediateCatchEvent id="one"><messageEventDefinition messageRef="message"/></intermediateCatchEvent>
                <intermediateCatchEvent id="two"><messageEventDefinition messageRef="message"/></intermediateCatchEvent>
                <serviceTask id="effect" flowable:delegateExpression="${collaborationEffect}"/>
                <endEvent id="e"/><sequenceFlow id="s1" sourceRef="s" targetRef="one"/>
                <sequenceFlow id="f1effect" sourceRef="one" targetRef="effect"/><sequenceFlow id="feffect2" sourceRef="effect" targetRef="two"/>
                <sequenceFlow id="f2e" sourceRef="two" targetRef="e"/>
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
            jdbc.execute("drop table if exists " + effectTable);
        }
        finally { infra.close(); }
    }

    @Test
    void concurrentSameNewMessageIdReplaysAfterAnyRealGapLockVictim() throws Exception
    {
        sameNewKeyRace(false);
    }

    @Test
    void concurrentDifferentPayloadForSameNewMessageIdPreservesTheWinner() throws Exception
    {
        sameNewKeyRace(true);
    }

    private void sameNewKeyRace(boolean differentPayload) throws Exception
    {
        String correlation = start();
        var first = request(correlation, 1);
        var second = differentPayload ? new WorkflowCollaborationMessageRequest(first.messageId(), first.messageName(),
                first.sourceProcessDefinitionKey(), first.targetProcessDefinitionKey(), first.correlationKey(),
                null, 1L, Map.of("value", "competing payload"), 3) : first;
        var bothMissing = new CyclicBarrier(2);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (result == null) bothMissing.await(10, TimeUnit.SECONDS);
            return result;
        }).when(messageMapper).selectByIdForUpdate(first.messageId());
        var workers = Executors.newFixedThreadPool(2);
        try
        {
            var left = workers.submit(() -> attempt(first));
            var right = workers.submit(() -> attempt(second));
            Object a = left.get(15, TimeUnit.SECONDS), b = right.get(15, TimeUnit.SECONDS);
            int successes = (a instanceof Throwable ? 0 : 1) + (b instanceof Throwable ? 0 : 1);
            assertThat(successes).isGreaterThanOrEqualTo(1);
            for (Object outcome : List.of(a, b))
            {
                if (outcome instanceof Throwable failure)
                    assertThat(new WorkflowExceptionTranslator().isRetryableConcurrencyConflict(failure))
                            .as("new-key concurrency failure must be a real gap-lock/transaction conflict: %s", failure).isTrue();
            }
            assertThat(jdbc.queryForObject("select count(*) from wf_collaboration_message where message_id=? and status='PROCESSED'", Integer.class, first.messageId())).isOne();
            assertThat(jdbc.queryForObject("select inbound_sequence from wf_collaboration_channel where channel_id=?", Long.class, channelIds.get(0))).isEqualTo(1L);
            assertThat(jdbc.queryForObject("select count(*) from " + effectTable, Integer.class)).isOne();
            assertThat(jdbc.queryForList("select action from wf_collaboration_message_audit where message_id=? order by audit_id", String.class, first.messageId())).containsExactly("RECEIVE", "DELIVER");
            var winner = a instanceof Throwable ? second : first;
            assertThat(service.publishPointToPoint("synthetic", winner).status()).isEqualTo("PROCESSED");
            if (differentPayload)
            {
                var loser = a instanceof Throwable ? first : second;
                assertThatThrownBy(() -> service.publishPointToPoint("synthetic", loser))
                        .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getSubCode()).isEqualTo("COLLAB_MESSAGE_IDEMPOTENCY_CONFLICT"));
            }
            else assertThat(service.publishPointToPoint("synthetic", second).status()).isEqualTo("PROCESSED");
        }
        finally { workers.shutdownNow(); assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void downstreamFailureRetainsRetryLedgerAndRollsBackEngineBusinessAndCursor(boolean springRollbackOnly)
    {
        nestedRollbackOnly.set(springRollbackOnly);
        String correlation = start();
        var first = request(correlation, 1);
        String instance = infra.processEngine().getRuntimeService().createProcessInstanceQuery().processInstanceBusinessKey(correlation).singleResult().getId();
        failDownstream.set(true);
        Throwable failure = catchThrowable(() -> service.publishPointToPoint("synthetic", first));
        int ledgerRows = jdbc.queryForObject("select count(*) from wf_collaboration_message where message_id=?", Integer.class, first.messageId());
        int businessRows = jdbc.queryForObject("select count(*) from " + effectTable, Integer.class);
        var ledger = jdbc.queryForList("select status,attempt_count from wf_collaboration_message where message_id=?", first.messageId());
        var cursor = jdbc.queryForList("select inbound_sequence from wf_collaboration_channel where channel_id=?", Long.class, channelIds.get(0));
        var audit = jdbc.queryForList("select action from wf_collaboration_message_audit where message_id=? order by audit_id", String.class, first.messageId());
        long waiting = infra.processEngine().getRuntimeService().createExecutionQuery().processInstanceId(instance).activityId("one").count();
        assertThat(businessRows).as("consumer failure=%s; ledger=%s; cursor=%s; audit=%s; waiting=%s", failure, ledger, cursor, audit, waiting).isZero();
        assertThat(infra.processEngine().getRuntimeService().createExecutionQuery().processInstanceId(instance).activityId("one").count()).isOne();
        assertThat(infra.processEngine().getRuntimeService().getVariables(instance)).doesNotContainKeys("value", "businessEffect");
        assertThat(failure).as("failed engine consumption must commit only RETRYING recovery; ledgerRows=%s, businessRows=%s", ledgerRows, businessRows).isNull();
        assertThat(jdbc.queryForMap("select status,attempt_count from wf_collaboration_message where message_id=?", first.messageId()))
                .containsEntry("status", "RETRYING").containsEntry("attempt_count", 1);
        assertThat(jdbc.queryForObject("select inbound_sequence from wf_collaboration_channel where channel_id=?", Long.class, channelIds.get(0))).isZero();
        assertThat(jdbc.queryForList("select action from wf_collaboration_message_audit where message_id=? order by audit_id", String.class, first.messageId())).containsExactly("RECEIVE", "RETRY");
        for (int attempt = 2; attempt <= 3; attempt++)
        {
            jdbc.update("update wf_collaboration_message set next_attempt_time=date_sub(current_timestamp(3),interval 1 second) where message_id=?", first.messageId());
            assertThat(service.retryNextDue()).isTrue();
            assertThat(jdbc.queryForObject("select attempt_count from wf_collaboration_message where message_id=?", Integer.class, first.messageId())).isEqualTo(attempt);
            assertThat(jdbc.queryForObject("select count(*) from " + effectTable, Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select inbound_sequence from wf_collaboration_channel where channel_id=?", Long.class, channelIds.get(0))).isZero();
        }
        assertThat(jdbc.queryForObject("select status from wf_collaboration_message where message_id=?", String.class, first.messageId())).isEqualTo("DEAD_LETTER");
        assertThat(service.retry(first.messageId()).status()).isEqualTo("RETRYING");
        assertThat(jdbc.queryForMap("select attempt_count,compensation_count from wf_collaboration_message where message_id=?", first.messageId()))
                .containsEntry("attempt_count", 1).containsEntry("compensation_count", 1);
        failDownstream.set(false);
        assertThat(service.retry(first.messageId()).status()).isEqualTo("PROCESSED");
        assertThat(jdbc.queryForObject("select count(*) from " + effectTable, Integer.class)).isOne();
        assertThat(infra.processEngine().getRuntimeService().createExecutionQuery().processInstanceId(instance).activityId("two").count()).isOne();
        assertThat(jdbc.queryForObject("select inbound_sequence from wf_collaboration_channel where channel_id=?", Long.class, channelIds.get(0))).isOne();
    }

    private void writeBusinessEffect(DelegateExecution execution)
    {
        jdbc.update("insert into " + effectTable + " (instance_id) values (?)", execution.getProcessInstanceId());
        execution.setVariable("businessEffect", "written");
        if (failDownstream.get()) throw new IllegalStateException("controlled collaboration downstream failure");
    }

    @Test
    void callerOwnedTransactionCannotCommitConsumedBusinessOrDetachedRecovery()
    {
        var request = request(start(), 1);
        failDownstream.set(true);
        assertThatThrownBy(() -> infra.transactionTemplate().executeWithoutResult(status -> {
            assertThatThrownBy(() -> service.publishPointToPoint("synthetic", request)).isInstanceOf(RuntimeException.class);
            // Catching the nested failure must not turn the caller's transaction
            // into a successful commit or persist a separate retry ledger.
        })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(jdbc.queryForObject("select count(*) from " + effectTable, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from wf_collaboration_message where message_id=?", Integer.class, request.messageId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from wf_collaboration_channel where channel_id=?", Integer.class, channelIds.get(0))).isZero();
    }

    @Test
    void failedRecoveryPreservesTheOriginalConsumptionFailure()
    {
        var request = request(start(), 1);
        failDownstream.set(true);
        doThrow(new IllegalStateException("controlled recovery storage failure")).when(messageMapper).markFailed(any());
        Throwable failure = catchThrowable(() -> service.publishPointToPoint("synthetic", request));
        assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessage("controlled recovery storage failure");
        assertThat(failure.getSuppressed()).hasSize(1);
        assertThat(failure.getSuppressed()[0]).hasRootCauseMessage("controlled collaboration downstream failure");
        assertThat(jdbc.queryForObject("select count(*) from " + effectTable, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from wf_collaboration_message where message_id=?", Integer.class, request.messageId())).isZero();
    }

    @Test
    void concurrentSuccessBetweenRollbackAndRecoveryIsNeverDowngraded() throws Exception
    {
        var request = request(start(), 1);
        var recoveryReached = new CountDownLatch(1);
        var resumeRecovery = new CountDownLatch(1);
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
            if (reads.incrementAndGet() == 2)
            {
                recoveryReached.countDown();
                assertThat(resumeRecovery.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return invocation.callRealMethod();
        }).when(messageMapper).selectByIdForUpdate(request.messageId());
        var worker = Executors.newSingleThreadExecutor();
        failDownstream.set(true);
        try
        {
            var first = worker.submit(() -> service.publishPointToPoint("synthetic", request));
            assertThat(recoveryReached.await(10, TimeUnit.SECONDS)).isTrue();
            failDownstream.set(false);
            assertThat(service.publishPointToPoint("synthetic", request).status()).isEqualTo("PROCESSED");
            resumeRecovery.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).status()).isEqualTo("PROCESSED");
            assertThat(jdbc.queryForObject("select count(*) from " + effectTable, Integer.class)).isOne();
            assertThat(jdbc.queryForList("select action from wf_collaboration_message_audit where message_id=? order by audit_id", String.class, request.messageId())).containsExactly("RECEIVE", "DELIVER");
        }
        finally { resumeRecovery.countDown(); worker.shutdownNow(); assertThat(worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test
    void workerKeepsItsNewTransactionWhenCallerRollsBack()
    {
        String correlation = UUID.randomUUID().toString();
        channelIds.add(WorkflowCollaborationChannelService.channelId(processKey, "BUSINESS_KEY", correlation, "POINT_TO_POINT"));
        var request = request(correlation, 1);
        assertThat(service.publishPointToPoint("synthetic", request).status()).isEqualTo("RETRYING");
        infra.processEngine().getRuntimeService().startProcessInstanceByKey(processKey, correlation);
        jdbc.update("update wf_collaboration_message set next_attempt_time=date_sub(current_timestamp(3),interval 1 second) where message_id=?", request.messageId());
        infra.transactionTemplate().executeWithoutResult(status -> {
            assertThat(service.retryNextDue()).isTrue();
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("select status from wf_collaboration_message where message_id=?", String.class, request.messageId())).isEqualTo("PROCESSED");
        assertThat(jdbc.queryForObject("select count(*) from " + effectTable, Integer.class)).isOne();
    }

    private Object attempt(WorkflowCollaborationMessageRequest request)
    {
        try { return service.publishPointToPoint("synthetic", request); }
        catch (RuntimeException failure) { return failure; }
    }

    @Test
    void sameChannelNewSequenceRaceRecoversInOrderWithoutHalfConsumption() throws Exception
    {
        String correlation = start();
        var first = request(correlation, 1);
        var second = request(correlation, 2);
        parallelNewMessages(first, second);
        // A fresh-key gap-lock victim may retry only after its failed transaction
        // has returned. The second sequence is allowed to wait for the first.
        assertThat(service.publishPointToPoint("synthetic", first).status()).isEqualTo("PROCESSED");
        assertThat(service.publishPointToPoint("synthetic", second).status()).isEqualTo("PROCESSED");
        assertThat(jdbc.queryForObject("select inbound_sequence from wf_collaboration_channel where channel_id=?", Long.class, channelIds.get(0))).isEqualTo(2L);
        assertThat(jdbc.queryForObject("select count(*) from " + effectTable, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from wf_collaboration_message where message_id in (?,?) and status='PROCESSED'", Integer.class, first.messageId(), second.messageId())).isEqualTo(2);
        assertThat(infra.processEngine().getRuntimeService().createProcessInstanceQuery().processInstanceBusinessKey(correlation).count()).isZero();
    }

    @Test
    void differentChannelsNewMessagesCanRecoverTheirIndependentGapLockVictims() throws Exception
    {
        var first = request(start(), 1);
        var second = request(start(), 1);
        parallelNewMessages(first, second);
        assertThat(service.publishPointToPoint("synthetic", first).status()).isEqualTo("PROCESSED");
        assertThat(service.publishPointToPoint("synthetic", second).status()).isEqualTo("PROCESSED");
        for (String channel : channelIds)
            assertThat(jdbc.queryForObject("select inbound_sequence from wf_collaboration_channel where channel_id=?", Long.class, channel)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from " + effectTable, Integer.class)).isEqualTo(2);
        assertThat(infra.processEngine().getRuntimeService().createExecutionQuery().activityId("two").count()).isEqualTo(2);
    }

    private void parallelNewMessages(WorkflowCollaborationMessageRequest first,
            WorkflowCollaborationMessageRequest second) throws Exception
    {
        var bothMissing = new CyclicBarrier(2);
        Set<String> newIds = Set.of(first.messageId(), second.messageId());
        var arrivals = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (result == null && newIds.contains(invocation.getArgument(0)) && arrivals.incrementAndGet() <= 2)
                bothMissing.await(10, TimeUnit.SECONDS);
            return result;
        }).when(messageMapper).selectByIdForUpdate(anyString());
        var workers = Executors.newFixedThreadPool(2);
        try
        {
            var left = workers.submit(() -> attempt(first));
            var right = workers.submit(() -> attempt(second));
            for (Object outcome : List.of(left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS)))
            {
                if (outcome instanceof Throwable failure)
                    assertThat(new WorkflowExceptionTranslator().isRetryableConcurrencyConflict(failure))
                            .as("only a real database concurrency failure is retryable: %s", failure).isTrue();
            }
        }
        finally { workers.shutdownNow(); assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
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
