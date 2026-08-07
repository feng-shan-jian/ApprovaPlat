package com.ruoyi.flowable.domain.vo;

import java.time.LocalDateTime;

/**
 * 审批 SLA 执行状态回显。
 *
 * @param slaExecutionId Long，执行主键
 * @param processInstanceId String，流程实例主键
 * @param taskId String，原审批任务主键
 * @param taskDefinitionKey String，原审批节点标识
 * @param assigneeUserId String，当前办理人
 * @param status String，ACTIVE、COMPLETED 或 ESCALATED
 * @param startedAt LocalDateTime，开始时间
 * @param reminderDueAt LocalDateTime，首次提醒时间
 * @param escalationDueAt LocalDateTime，升级时间
 * @param remindersSent Integer，已提醒次数
 * @param pausedAt LocalDateTime，当前暂停时刻
 * @param pausedMillis Long，累计暂停毫秒数
 */
public record WorkflowTaskSlaExecutionView(Long slaExecutionId,
        String processInstanceId, String taskId, String taskDefinitionKey,
        String assigneeUserId, String status, LocalDateTime startedAt,
        LocalDateTime reminderDueAt, LocalDateTime escalationDueAt,
        Integer remindersSent, LocalDateTime pausedAt, Long pausedMillis) { }
