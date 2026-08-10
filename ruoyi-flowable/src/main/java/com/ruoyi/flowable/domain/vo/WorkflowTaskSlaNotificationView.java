package com.ruoyi.flowable.domain.vo;

import java.time.LocalDateTime;

/**
 * 当前用户审批 SLA 通知回显。
 *
 * @param notificationId Long，通知主键
 * @param auditId Long，关联审计主键
 * @param processInstanceId String，流程实例主键
 * @param taskId String，原审批任务主键
 * @param actionType String，REMINDER 或 ESCALATE
 * @param title String，通知标题
 * @param content String，脱敏正文
 * @param readStatus String，UNREAD 或 READ
 * @param createTime LocalDateTime，创建时间
 * @param readTime LocalDateTime，首次阅读时间
 */
public record WorkflowTaskSlaNotificationView(Long notificationId, Long auditId,
        String processInstanceId, String taskId, String actionType, String title,
        String content, String readStatus, LocalDateTime createTime,
        LocalDateTime readTime) { }
