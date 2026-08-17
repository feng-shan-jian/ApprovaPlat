package com.ruoyi.flowable.service.notification;

/**
 * Worker 在短事务领取后持有的不可变通知快照。
 *
 * @param outboxId long，通知 outbox 主键
 * @param idempotencyKey String，跨重试稳定幂等键
 * @param eventType String，通知业务事件类型
 * @param channel String，INBOX、EMAIL 或 SMS
 * @param recipientUserId long，接收用户主键
 * @param deliveryTarget String，可空；领取时冻结的邮箱或手机号
 * @param processInstanceId String，可空流程实例主键
 * @param taskId String，可空任务主键
 * @param title String，通知标题
 * @param content String，通知正文
 * @param smsTemplateId String，可空短信模板主键
 * @param routePath String，可空站内路由
 * @param previousStatus String，领取前状态
 * @param deliveryCycle int，管理员补偿周期
 * @param attemptCount int，当前周期尝试次数
 * @param totalAttemptCount int，累计尝试次数
 * @param maxAttempts int，当前周期最大尝试次数
 * @param revision int，领取成功后的并发版本
 */
public record WorkflowNotificationOutboxRecord(long outboxId, String idempotencyKey,
        String eventType, String channel, long recipientUserId, String deliveryTarget,
        String processInstanceId, String taskId, String title, String content,
        String smsTemplateId, String routePath, String previousStatus, int deliveryCycle,
        int attemptCount, int totalAttemptCount, int maxAttempts, int revision)
{
}
