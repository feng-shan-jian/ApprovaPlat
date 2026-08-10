package com.ruoyi.flowable.domain.vo;

import java.time.LocalDateTime;

/**
 * 当前用户 BPMN 错误或升级通知视图。
 *
 * @param notificationId Long，通知主键
 * @param auditId Long，关联运行审计主键
 * @param title String，通知标题
 * @param content String，通知正文
 * @param readStatus String，UNREAD 或 READ
 * @param createTime LocalDateTime，创建时间
 * @param readTime LocalDateTime，阅读时间
 */
public record WorkflowBpmnEventNotificationView(Long notificationId, Long auditId,
        String title, String content, String readStatus, LocalDateTime createTime,
        LocalDateTime readTime)
{
}
