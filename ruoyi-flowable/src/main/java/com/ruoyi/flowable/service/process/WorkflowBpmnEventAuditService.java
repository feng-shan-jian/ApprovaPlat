package com.ruoyi.flowable.service.process;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.mapper.WfBpmnEventMapper;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService.SynchronousNotification;

/**
 * BPMN 错误与升级运行审计及通知的独立事务服务。
 */
@Service
public class WorkflowBpmnEventAuditService
{
    private final WfBpmnEventMapper eventMapper;
    private final WorkflowNotificationService notificationService;

    /**
     * 创建运行审计服务。
     * @param eventMapper WfBpmnEventMapper，审计和通知数据访问层
     * @param notificationService WorkflowNotificationService，统一 outbox、inbox 和投递审计服务
     * @return 无返回值，构造后由 Spring 管理
     */
    public WorkflowBpmnEventAuditService(WfBpmnEventMapper eventMapper,
            WorkflowNotificationService notificationService)
    {
        this.eventMapper = eventMapper;
        this.notificationService = notificationService;
    }

    /**
     * 在独立事务中幂等记录受控产生结果，并按冻结策略通知有效发起人。
     * @param event RuntimeEvent，字段完整的运行事件
     * @return Long，首次或重复触发对应的同一审计主键
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Long record(RuntimeEvent event)
    {
        eventMapper.insertAudit(event.idempotencyKey(), event.deploymentId(),
                event.processInstanceId(), event.processDefinitionId(), event.executionId(),
                event.sourceElementId(), event.sourceType(), event.eventType(), event.eventCode(),
                event.eventName(), event.matchStatus(), event.boundaryEventId(),
                event.interrupting(), event.messageSummary(), event.initiatorUserId());
        Long auditId = eventMapper.selectAuditId(event.idempotencyKey());
        if (auditId == null)
        {
            throw new ServiceException("BPMN 事件审计保存不完整", HttpStatus.ERROR);
        }
        if ("CAPTURED".equals(event.matchStatus())
                && "INITIATOR".equals(event.notificationPolicy())
                && event.initiatorUserId() != null && !event.initiatorUserId().isBlank())
        {
            // 未匹配事件代表部署或运行状态异常，只保留诊断审计，禁止产生误导用户的业务通知。
            String title = "流程" + ("ERROR".equals(event.eventType()) ? "业务错误" : "业务升级")
                    + "：" + event.eventName();
            String content = event.eventCode() + " · " + event.matchStatus()
                    + (event.messageSummary() == null ? "" : " · " + event.messageSummary());
            notificationService.publishSynchronousInbox(new SynchronousNotification(
                    "BPMN_EVENT", String.valueOf(auditId), event.eventType(),
                    event.initiatorUserId(), event.processDefinitionKey(),
                    event.processInstanceId(), null, null, title, content,
                    "/workflow/process-detail/" + event.processInstanceId() + "?source=own"));
        }
        return auditId;
    }

    /**
     * 运行审计不可变参数。
     *
     * @param idempotencyKey String，稳定幂等摘要
     * @param deploymentId String，部署主键
     * @param processInstanceId String，实例主键
     * @param processDefinitionId String，定义主键
     * @param processDefinitionKey String，流程定义 key
     * @param executionId String，执行主键
     * @param sourceElementId String，产生节点
     * @param sourceType String，来源类型
     * @param eventType String，ERROR 或 ESCALATION
     * @param eventCode String，稳定编码
     * @param eventName String，冻结名称
     * @param notificationPolicy String，冻结通知策略
     * @param matchStatus String，CAPTURED 或 UNMATCHED
     * @param boundaryEventId String，匹配边界标识
     * @param interrupting Boolean，中断语义
     * @param messageSummary String，脱敏摘要
     * @param initiatorUserId String，发起人用户主键
     */
    public record RuntimeEvent(String idempotencyKey, String deploymentId,
            String processInstanceId, String processDefinitionId,
            String processDefinitionKey, String executionId,
            String sourceElementId, String sourceType, String eventType, String eventCode,
            String eventName, String notificationPolicy, String matchStatus,
            String boundaryEventId, Boolean interrupting, String messageSummary,
            String initiatorUserId)
    {
    }
}
