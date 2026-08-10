package com.ruoyi.flowable.domain.vo;

import java.time.LocalDateTime;

/**
 * 审批 SLA 运行审计回显。
 *
 * @param auditId Long，审计主键
 * @param slaExecutionId Long，SLA 执行主键
 * @param processInstanceId String，流程实例主键
 * @param taskId String，原审批任务主键
 * @param taskDefinitionKey String，原审批节点标识
 * @param actionType String，CREATE、ASSIGN、REMINDER、ESCALATE、COMPLETE、PAUSE 或 RESUME
 * @param actionOrdinal Integer，重复提醒序号，非重复动作固定为零
 * @param actorUserId String，可空操作人
 * @param detail String，脱敏审计摘要
 * @param createTime LocalDateTime，动作时间
 */
public record WorkflowTaskSlaAuditView(Long auditId, Long slaExecutionId,
        String processInstanceId, String taskId, String taskDefinitionKey,
        String actionType, Integer actionOrdinal, String actorUserId,
        String detail, LocalDateTime createTime) { }
