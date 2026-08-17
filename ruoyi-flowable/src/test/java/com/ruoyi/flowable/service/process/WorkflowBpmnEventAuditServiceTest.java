package com.ruoyi.flowable.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.flowable.mapper.WfBpmnEventMapper;
import com.ruoyi.flowable.service.notification.WorkflowNotificationRegistrar;
import com.ruoyi.flowable.service.notification.WorkflowSynchronousNotification;

/**
 * BPMN 事件独立审计与通知副作用边界测试。
 */
class WorkflowBpmnEventAuditServiceTest
{
    private WfBpmnEventMapper mapper;
    private WorkflowNotificationRegistrar notificationService;
    private WorkflowBpmnEventAuditService service;

    /** @return void，每个用例创建独立 Mapper 替身。 */
    @BeforeEach
    void setUp()
    {
        mapper = org.mockito.Mockito.mock(WfBpmnEventMapper.class);
        notificationService = org.mockito.Mockito.mock(WorkflowNotificationRegistrar.class);
        service = new WorkflowBpmnEventAuditService(mapper, notificationService);
        when(mapper.selectAuditId("event-key")).thenReturn(91L);
    }

    /**
     * 验证捕获成功时按冻结策略只通知有效发起人。
     * @return void，审计主键或通知调用漂移时测试失败
     */
    @Test
    void notifiesInitiatorOnlyForCapturedEvent()
    {
        Long auditId = service.record(event("CAPTURED", "boundary-error", true));

        assertThat(auditId).isEqualTo(91L);
        verify(notificationService).publishSynchronousInbox(new WorkflowSynchronousNotification(
                "BPMN_EVENT", "91", "ERROR", "1", "expense", "process-1",
                null, null, "流程业务错误：审批业务校验失败",
                "APPROVAL_BUSINESS_ERROR · CAPTURED · 库存不足",
                "/workflow/process-detail/process-1?source=own"));
    }

    /**
     * 验证未匹配事件仅保留诊断审计，不产生误导用户的业务通知。
     * @return void，未匹配事件产生通知时测试失败
     */
    @Test
    void keepsUnmatchedEventNotificationFree()
    {
        service.record(event("UNMATCHED", null, null));

        verify(notificationService, never()).publishSynchronousInbox(
                org.mockito.ArgumentMatchers.any());
    }

    /**
     * 构造字段完整的运行审计事件。
     * @param matchStatus String，CAPTURED 或 UNMATCHED
     * @param boundaryEventId String，可空匹配边界标识
     * @param interrupting Boolean，可空中断语义
     * @return WorkflowBpmnEventAuditService.RuntimeEvent，测试事件
     */
    private WorkflowBpmnEventAuditService.RuntimeEvent event(String matchStatus,
            String boundaryEventId, Boolean interrupting)
    {
        return new WorkflowBpmnEventAuditService.RuntimeEvent(
                "event-key", "deployment-1", "process-1", "definition-1",
                "expense", "execution-1", "raise-error", "SERVICE_TASK", "ERROR",
                "APPROVAL_BUSINESS_ERROR", "审批业务校验失败", "INITIATOR",
                matchStatus, boundaryEventId, interrupting, "库存不足", "1");
    }
}
