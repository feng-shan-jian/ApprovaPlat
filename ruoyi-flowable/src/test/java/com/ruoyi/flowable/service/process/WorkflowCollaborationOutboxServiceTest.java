package com.ruoyi.flowable.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
import org.junit.jupiter.api.Test;
import com.ruoyi.flowable.config.WorkflowCollaborationProperties;
import com.ruoyi.flowable.domain.WfCollaborationOutbox;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.extension.WorkflowHttpConnector;
import com.ruoyi.flowable.mapper.WfCollaborationOutboxMapper;
import com.ruoyi.flowable.runtime.WorkflowCollaborationMetrics;

/** 协作 outbox 领取、取消和事务外投递边界测试。 */
class WorkflowCollaborationOutboxServiceTest
{
    /**
     * 验证发送方被取消后，领取事务直接提交取消状态且不会继续申请租约或网络投递。
     * @return void，取消指标、审计或领取边界漂移时测试失败
     */
    @Test
    void cancelsDeletedSourceInstanceBeforeClaimingDeliveryLease()
    {
        WfCollaborationOutboxMapper mapper = mock(WfCollaborationOutboxMapper.class);
        WorkflowCollaborationChannelService channelService = mock(WorkflowCollaborationChannelService.class);
        WorkflowCollaborationAuditService auditService = mock(WorkflowCollaborationAuditService.class);
        WorkflowHttpConnector httpConnector = mock(WorkflowHttpConnector.class);
        RepositoryService repositoryService = mock(RepositoryService.class);
        HistoryService historyService = mock(HistoryService.class);
        WorkflowEngineOperations engineOperations = mock(WorkflowEngineOperations.class);
        WorkflowCollaborationMetrics metrics = mock(WorkflowCollaborationMetrics.class);
        HistoricProcessInstanceQuery historyQuery = mock(HistoricProcessInstanceQuery.class);
        HistoricProcessInstance history = mock(HistoricProcessInstance.class);

        WfCollaborationOutbox candidate = new WfCollaborationOutbox();
        candidate.setMessageId("message-1");
        candidate.setSourceProcessInstanceId("process-1");
        candidate.setStatus("RETRYING");
        candidate.setAttemptCount(2);
        candidate.setRevisionNo(7);
        when(mapper.selectNextDueForUpdate()).thenReturn(candidate);
        when(mapper.cancel("message-1", 7)).thenReturn(1);
        when(historyService.createHistoricProcessInstanceQuery()).thenReturn(historyQuery);
        when(historyQuery.processInstanceId("process-1")).thenReturn(historyQuery);
        when(historyQuery.singleResult()).thenReturn(history);
        when(history.getDeleteReason()).thenReturn("用户取消");

        WorkflowCollaborationOutboxService service = new WorkflowCollaborationOutboxService(
                mapper, channelService, auditService, httpConnector, repositoryService,
                historyService, engineOperations, new WorkflowCollaborationProperties(), metrics);

        assertThat(service.claimNext("worker-1")).isNull();
        verify(mapper).cancel("message-1", 7);
        verify(auditService).record("message-1", "OUTBOUND", "CANCEL", "SYSTEM", "worker-1",
                "RETRYING", "CANCELLED", 2, null, "发送方流程已取消，停止后续投递");
        verify(metrics).record("cancelled");
        verify(mapper, never()).claim(anyString(), anyInt(), anyString(), anyInt());
        verify(httpConnector, never()).postFrozenJson(anyString(), anyString(),
                org.mockito.ArgumentMatchers.any(byte[].class));
    }
}
