package com.ruoyi.flowable.domain.vo;

import java.time.LocalDateTime;

/**
 * 统一用户通知收件箱中的单条通知。
 *
 * @param notificationId Long，通知主键
 * @param eventType String，通知事件类型
 * @param title String，通知标题
 * @param content String，通知正文
 * @param processInstanceId String，关联流程实例主键
 * @param taskId String，关联任务主键
 * @param routePath String，服务端生成的受控跳转路径
 * @param readStatus String，UNREAD 或 READ
 * @param createTime LocalDateTime，通知创建时间
 * @param readTime LocalDateTime，通知首次阅读时间
 */
public record WorkflowNotificationItem(Long notificationId, String eventType, String title,
        String content, String processInstanceId, String taskId, String routePath,
        String readStatus, LocalDateTime createTime, LocalDateTime readTime)
{
}
