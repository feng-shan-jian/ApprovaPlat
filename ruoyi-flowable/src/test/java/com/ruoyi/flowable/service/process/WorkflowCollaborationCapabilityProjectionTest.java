package com.ruoyi.flowable.service.process;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.config.WorkflowCollaborationProperties;
import com.ruoyi.flowable.domain.WfCollaborationMessage;
import com.ruoyi.flowable.domain.WfCollaborationOutbox;
import com.ruoyi.flowable.domain.dto.WorkflowOperationsQuery;
import com.ruoyi.flowable.domain.vo.WorkflowCollaborationMessageView;
import com.ruoyi.flowable.domain.vo.WorkflowCollaborationOutboxView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.extension.WorkflowHttpConnector;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.mapper.WfCollaborationMessageMapper;
import com.ruoyi.flowable.mapper.WfCollaborationOutboxMapper;
import com.ruoyi.flowable.runtime.WorkflowCollaborationMetrics;

/** 验证协作入站/出站能力进入正式服务视图，并且写端仍保留锁定行与 CAS 门禁。 */
class WorkflowCollaborationCapabilityProjectionTest
{
    private static final WorkflowCurrentIdentity ACTOR =
            new WorkflowCurrentIdentity("42", Set.of());

    /**
     * 验证出站服务原样消费数据库能力投影，入站服务按唯一状态判定输出完整矩阵。
     * @return void，列表结果逐行断言三个后端能力字段
     */
    @Test
    void projectsCompleteOutboundAndInboundCapabilityMatrices()
    {
        OutboxHarness outbox = outboxHarness();
        List<WfCollaborationOutbox> outboundRows = List.of(
                outboxRow("PENDING", false, true, 0),
                outboxRow("RETRYING", false, true, 0),
                outboxRow("DEAD_LETTER", true, true, 0),
                outboxRow("DELIVERING", false, false, 0),
                outboxRow("DELIVERING", false, true, 0),
                outboxRow("PROCESSED", false, false, 0),
                outboxRow("CANCELLED", false, false, 0));
        when(outbox.mapper().countList(any())).thenReturn((long) outboundRows.size());
        when(outbox.mapper().selectList(any(), anyInt(), anyInt()))
                .thenReturn(outboundRows);
        List<WorkflowCollaborationOutboxView> outboundViews = outbox.service()
                .list(query(), 1, 100).rows();
        for (int index = 0; index < outboundRows.size(); index++)
        {
            assertEquals(outboundRows.get(index).isRetryAllowed(),
                    outboundViews.get(index).retryAllowed());
            assertEquals(outboundRows.get(index).isCancelAllowed(),
                    outboundViews.get(index).cancelAllowed());
        }

        MessageHarness inbound = messageHarness();
        List<WfCollaborationMessage> inboundRows = List.of(
                messageRow("inbound-received", "RECEIVED", 0),
                messageRow("inbound-retrying", "RETRYING", 0),
                messageRow("inbound-dead", "DEAD_LETTER", 0),
                messageRow("inbound-processed", "PROCESSED", 0));
        when(inbound.mapper().countList(any())).thenReturn((long) inboundRows.size());
        when(inbound.mapper().selectList(any(), anyInt(), anyInt()))
                .thenReturn(inboundRows);
        List<WorkflowCollaborationMessageView> inboundViews = inbound.service()
                .list(query(), 1, 100).rows();
        assertFalse(inboundViews.get(0).retryAllowed());
        assertTrue(inboundViews.get(1).retryAllowed());
        assertTrue(inboundViews.get(2).retryAllowed());
        assertFalse(inboundViews.get(3).retryAllowed());
    }

    /**
     * 验证出站服务先拒绝能力 false，再执行 revision CAS，并返回更新后的正式能力。
     * @return void，失败路径不写审计/指标，成功补偿返回 RETRYING(false,true)
     */
    @Test
    void consumesOutboundRetryProjectionBeforeCasAndReturnsFormalState()
    {
        OutboxHarness rejected = outboxHarness();
        WfCollaborationOutbox retrying = outboxRow("RETRYING", false, true, 3);
        when(rejected.mapper().selectByIdForUpdate(retrying.getMessageId()))
                .thenReturn(retrying);
        assertConflict(() -> rejected.service().compensate(retrying.getMessageId()));
        verify(rejected.mapper(), never()).compensate(anyString(), anyInt(), anyString());
        verifyNoInteractions(rejected.auditService(), rejected.metrics());

        OutboxHarness stale = outboxHarness();
        WfCollaborationOutbox staleDead = outboxRow("DEAD_LETTER", true, true, 7);
        when(stale.mapper().selectByIdForUpdate(staleDead.getMessageId()))
                .thenReturn(staleDead);
        when(stale.mapper().compensate(staleDead.getMessageId(), 7, ACTOR.userId()))
                .thenReturn(0);
        assertConflict(() -> stale.service().compensate(staleDead.getMessageId()));
        verifyNoInteractions(stale.auditService(), stale.metrics());
        assertEquals("DEAD_LETTER", staleDead.getStatus());
        assertEquals(0, staleDead.getCompensationCount());

        OutboxHarness accepted = outboxHarness();
        WfCollaborationOutbox dead = outboxRow("DEAD_LETTER", true, true, 2);
        WfCollaborationOutbox after = copyOutbox(dead, "RETRYING", false, true, 3);
        after.setCompensationCount(1);
        when(accepted.mapper().selectByIdForUpdate(dead.getMessageId())).thenReturn(dead);
        when(accepted.mapper().compensate(dead.getMessageId(), 2, ACTOR.userId()))
                .thenReturn(1);
        when(accepted.mapper().selectById(dead.getMessageId())).thenReturn(after);
        WorkflowCollaborationOutboxView result =
                accepted.service().compensate(dead.getMessageId());
        assertEquals("RETRYING", result.status());
        assertFalse(result.retryAllowed());
        assertTrue(result.cancelAllowed());
        assertEquals(1, result.compensationCount());
        verify(accepted.auditService()).record(dead.getMessageId(), "OUTBOUND", "COMPENSATE",
                "USER", ACTOR.userId(), "DEAD_LETTER", "RETRYING", 0, null,
                "管理员重新开启有界投递");
        verify(accepted.metrics()).record("compensated");
    }

    /**
     * 验证取消能力 false 和旧 revision 均失败关闭，成功取消返回 CANCELLED(false,false)。
     * @return void，失败路径不写审计/指标且不修改快照对象
     */
    @Test
    void consumesOutboundCancelProjectionBeforeCasAndReturnsFormalState()
    {
        OutboxHarness rejected = outboxHarness();
        WfCollaborationOutbox delivering = outboxRow("DELIVERING", false, false, 5);
        when(rejected.mapper().selectByIdForUpdate(delivering.getMessageId()))
                .thenReturn(delivering);
        assertConflict(() -> rejected.service().cancel(delivering.getMessageId()));
        verify(rejected.mapper(), never()).cancel(anyString(), anyInt());
        verifyNoInteractions(rejected.auditService(), rejected.metrics());

        OutboxHarness stale = outboxHarness();
        WfCollaborationOutbox stalePending = outboxRow("PENDING", false, true, 9);
        when(stale.mapper().selectByIdForUpdate(stalePending.getMessageId()))
                .thenReturn(stalePending);
        when(stale.mapper().cancel(stalePending.getMessageId(), 9)).thenReturn(0);
        assertConflict(() -> stale.service().cancel(stalePending.getMessageId()));
        verifyNoInteractions(stale.auditService(), stale.metrics());
        assertEquals("PENDING", stalePending.getStatus());
        assertEquals(0, stalePending.getCompensationCount());

        OutboxHarness accepted = outboxHarness();
        WfCollaborationOutbox expired = outboxRow("DELIVERING", false, true, 4);
        WfCollaborationOutbox cancelled = copyOutbox(
                expired, "CANCELLED", false, false, 5);
        when(accepted.mapper().selectByIdForUpdate(expired.getMessageId())).thenReturn(expired);
        when(accepted.mapper().cancel(expired.getMessageId(), 4)).thenReturn(1);
        when(accepted.mapper().selectById(expired.getMessageId())).thenReturn(cancelled);
        WorkflowCollaborationOutboxView result = accepted.service().cancel(expired.getMessageId());
        assertEquals("CANCELLED", result.status());
        assertFalse(result.retryAllowed());
        assertFalse(result.cancelAllowed());
        verify(accepted.auditService()).record(expired.getMessageId(), "OUTBOUND", "CANCEL",
                "USER", ACTOR.userId(), "DELIVERING", "CANCELLED", 0, null,
                "管理员取消未送达消息");
        verify(accepted.metrics()).record("cancelled");
    }

    /**
     * 验证入站 RECEIVED/PROCESSED 在写端被同一能力判定拒绝且没有副作用。
     * @return void，两个拒绝状态都不执行状态更新、审计或指标
     */
    @Test
    void rejectsInboundStatesWhoseProjectedRetryCapabilityIsFalse()
    {
        for (String status : List.of("RECEIVED", "PROCESSED"))
        {
            MessageHarness harness = messageHarness();
            WfCollaborationMessage row = messageRow(
                    "inbound-rejected-" + status.toLowerCase(), status, 6);
            when(harness.mapper().selectByIdForUpdate(row.getMessageId())).thenReturn(row);
            assertConflict(() -> harness.service().retry(row.getMessageId()));
            verify(harness.mapper(), never()).markRetrying(any());
            verifyNoInteractions(harness.auditService(), harness.metrics());
            assertEquals(status, row.getStatus());
            assertEquals(6, row.getCompensationCount());
        }
    }

    /**
     * 验证 RETRYING/DEAD_LETTER 都通过唯一门禁，并由最终正式状态生成返回能力。
     * @return void，死信先执行 Mapper CAS，RETRYING 不重复执行死信更新
     */
    @Test
    void allowsInboundRetryingAndDeadLetterThroughTheSameGate()
    {
        MessageHarness retryingHarness = messageHarness();
        WfCollaborationMessage retrying = messageRow("inbound-retrying-action", "RETRYING", 2);
        WfCollaborationMessage processedRetry = copyMessage(retrying, "PROCESSED", 2);
        when(retryingHarness.mapper().selectByIdForUpdate(retrying.getMessageId()))
                .thenReturn(retrying, retrying, processedRetry);
        WorkflowCollaborationMessageView retryingResult =
                retryingHarness.service().retry(retrying.getMessageId());
        assertEquals("PROCESSED", retryingResult.status());
        assertFalse(retryingResult.retryAllowed());
        verify(retryingHarness.mapper(), never()).markRetrying(any());
        verify(retryingHarness.auditService()).record(retrying.getMessageId(), "INBOUND",
                "COMPENSATE", "USER", ACTOR.userId(), "RETRYING", "RETRYING", 0, null,
                "管理员重新开启有界消费");

        MessageHarness deadHarness = messageHarness();
        WfCollaborationMessage dead = messageRow("inbound-dead-action", "DEAD_LETTER", 3);
        WfCollaborationMessage reopened = copyMessage(dead, "RETRYING", 4);
        WfCollaborationMessage processedDead = copyMessage(dead, "PROCESSED", 4);
        when(deadHarness.mapper().selectByIdForUpdate(dead.getMessageId()))
                .thenReturn(dead, reopened, processedDead);
        when(deadHarness.mapper().markRetrying(dead)).thenReturn(1);
        WorkflowCollaborationMessageView deadResult =
                deadHarness.service().retry(dead.getMessageId());
        assertEquals("PROCESSED", deadResult.status());
        assertFalse(deadResult.retryAllowed());
        verify(deadHarness.mapper()).markRetrying(dead);
        verify(deadHarness.metrics()).record("inbound_compensated");
    }

    /**
     * 验证入站死信 revision CAS 失败时不产生审计、指标、补偿次数或状态修改。
     * @return void，失败写保留调用前对象快照
     */
    @Test
    void keepsInboundStateAndSideEffectsUnchangedWhenDeadLetterCasFails()
    {
        MessageHarness harness = messageHarness();
        WfCollaborationMessage dead = messageRow("inbound-stale-dead", "DEAD_LETTER", 8);
        dead.setRevisionNo(12);
        when(harness.mapper().selectByIdForUpdate(dead.getMessageId())).thenReturn(dead);
        when(harness.mapper().markRetrying(dead)).thenReturn(0);
        assertConflict(() -> harness.service().retry(dead.getMessageId()));
        verifyNoInteractions(harness.auditService(), harness.metrics());
        assertEquals("DEAD_LETTER", dead.getStatus());
        assertEquals(8, dead.getCompensationCount());
        assertEquals(12, dead.getRevisionNo());
    }

    /**
     * 创建只执行回调本身的工作流引擎边界 mock，保留服务公开入口而不绕过业务代码。
     * @return WorkflowEngineOperations，支持 read 和 writeAsCurrentUser 的同步执行器
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private WorkflowEngineOperations engineOperations()
    {
        WorkflowEngineOperations operations = mock(WorkflowEngineOperations.class);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get())
                .when(operations).read(any(Supplier.class));
        doAnswer(invocation -> ((Function<WorkflowCurrentIdentity, ?>)
                invocation.getArgument(0)).apply(ACTOR))
                .when(operations).writeAsCurrentUser(any(Function.class));
        return operations;
    }

    /**
     * 创建出站服务及可验证的 Mapper、审计和指标协作者。
     * @return OutboxHarness，当前测试独占的出站服务夹具
     */
    private OutboxHarness outboxHarness()
    {
        WfCollaborationOutboxMapper mapper = mock(WfCollaborationOutboxMapper.class);
        WorkflowCollaborationAuditService audit = mock(WorkflowCollaborationAuditService.class);
        WorkflowCollaborationMetrics metrics = mock(WorkflowCollaborationMetrics.class);
        WorkflowCollaborationOutboxService service = new WorkflowCollaborationOutboxService(
                mapper, mock(WorkflowCollaborationChannelService.class), audit,
                mock(WorkflowHttpConnector.class), mock(RepositoryService.class),
                mock(HistoryService.class), engineOperations(),
                mock(WorkflowCollaborationProperties.class), metrics);
        return new OutboxHarness(service, mapper, audit, metrics);
    }

    /**
     * 创建入站服务及可验证的 Mapper、审计和指标协作者。
     * @return MessageHarness，当前测试独占的入站服务夹具
     */
    private MessageHarness messageHarness()
    {
        WfCollaborationMessageMapper mapper = mock(WfCollaborationMessageMapper.class);
        WorkflowCollaborationAuditService audit = mock(WorkflowCollaborationAuditService.class);
        WorkflowCollaborationMetrics metrics = mock(WorkflowCollaborationMetrics.class);
        WorkflowCollaborationMessageService service = new WorkflowCollaborationMessageService(
                engineOperations(), mock(WorkflowIntegrationCredentialService.class), mapper,
                mock(RuntimeService.class), mock(RepositoryService.class),
                mock(WorkflowCollaborationChannelService.class), audit, metrics);
        return new MessageHarness(service, mapper, audit, metrics);
    }

    /**
     * 创建具备指定数据库投影能力的出站行。
     * @param status String，正式出站状态
     * @param retryAllowed boolean，数据库投影的补偿能力
     * @param cancelAllowed boolean，数据库投影的取消能力
     * @param revision int，当前 revision
     * @return WfCollaborationOutbox，服务测试使用的锁定行快照
     */
    private WfCollaborationOutbox outboxRow(String status, boolean retryAllowed,
            boolean cancelAllowed, int revision)
    {
        WfCollaborationOutbox row = new WfCollaborationOutbox();
        row.setMessageId(UUID.randomUUID().toString());
        row.setMessageName("CapabilityMessage");
        row.setSourceProcessDefinitionKey("source");
        row.setSourceProcessInstanceId("source-instance");
        row.setTargetProcessDefinitionKey("target");
        row.setCorrelationKey("business-key");
        row.setSequenceNo(1L);
        row.setStatus(status);
        row.setRetryAllowed(retryAllowed);
        row.setCancelAllowed(cancelAllowed);
        row.setAttemptCount(0);
        row.setMaxAttempts(5);
        row.setCompensationCount(0);
        row.setRevisionNo(revision);
        return row;
    }

    /**
     * 复制出站业务字段并替换更新后的状态、能力和 revision。
     * @param source WfCollaborationOutbox，更新前行
     * @param status String，更新后正式状态
     * @param retryAllowed boolean，更新后补偿能力
     * @param cancelAllowed boolean，更新后取消能力
     * @param revision int，更新后 revision
     * @return WfCollaborationOutbox，模拟 Mapper 重新查询的正式结果
     */
    private WfCollaborationOutbox copyOutbox(WfCollaborationOutbox source, String status,
            boolean retryAllowed, boolean cancelAllowed, int revision)
    {
        WfCollaborationOutbox copy = outboxRow(status, retryAllowed, cancelAllowed, revision);
        copy.setMessageId(source.getMessageId());
        copy.setCompensationCount(source.getCompensationCount());
        return copy;
    }

    /**
     * 创建具备稳定幂等字段的入站行。
     * @param messageId String，测试消息主键
     * @param status String，正式入站状态
     * @param compensationCount int，人工补偿次数
     * @return WfCollaborationMessage，服务测试使用的锁定行快照
     */
    private WfCollaborationMessage messageRow(String messageId, String status,
            int compensationCount)
    {
        WfCollaborationMessage row = new WfCollaborationMessage();
        row.setMessageId(messageId);
        row.setMessageName("CapabilityMessage");
        row.setSourceProcessDefinitionKey("source");
        row.setTargetProcessDefinitionKey("target");
        row.setCorrelationKey("business-key");
        row.setChannelId("channel");
        row.setSequenceNo(1L);
        row.setVariablesJson("{}");
        row.setPayloadSha256("0".repeat(64));
        row.setStatus(status);
        row.setAttemptCount(0);
        row.setMaxAttempts(5);
        row.setCompensationCount(compensationCount);
        row.setRevisionNo(0);
        return row;
    }

    /**
     * 复制入站幂等字段并替换更新后的状态和补偿次数。
     * @param source WfCollaborationMessage，更新前行
     * @param status String，更新后正式状态
     * @param compensationCount int，更新后补偿次数
     * @return WfCollaborationMessage，模拟 Mapper 重新查询结果
     */
    private WfCollaborationMessage copyMessage(WfCollaborationMessage source,
            String status, int compensationCount)
    {
        WfCollaborationMessage copy = messageRow(source.getMessageId(), status,
                compensationCount);
        copy.setRevisionNo(source.getRevisionNo() == null ? 0 : source.getRevisionNo() + 1);
        return copy;
    }

    /**
     * 创建无筛选条件的协作列表查询。
     * @return WorkflowOperationsQuery.Collaboration，全部状态查询
     */
    private WorkflowOperationsQuery.Collaboration query()
    {
        return new WorkflowOperationsQuery.Collaboration(null, null, null, null);
    }

    /**
     * 执行动作并断言稳定 409 冲突。
     * @param action Runnable，预期被能力或 CAS 门禁拒绝的服务动作
     * @return void，仅执行异常断言
     */
    private void assertConflict(Runnable action)
    {
        ServiceException exception = assertThrows(ServiceException.class, action::run);
        assertEquals(HttpStatus.CONFLICT, exception.getCode());
    }

    /** 出站服务、Mapper、审计和指标测试夹具。 */
    private record OutboxHarness(WorkflowCollaborationOutboxService service,
            WfCollaborationOutboxMapper mapper,
            WorkflowCollaborationAuditService auditService,
            WorkflowCollaborationMetrics metrics)
    {
    }

    /** 入站服务、Mapper、审计和指标测试夹具。 */
    private record MessageHarness(WorkflowCollaborationMessageService service,
            WfCollaborationMessageMapper mapper,
            WorkflowCollaborationAuditService auditService,
            WorkflowCollaborationMetrics metrics)
    {
    }
}
