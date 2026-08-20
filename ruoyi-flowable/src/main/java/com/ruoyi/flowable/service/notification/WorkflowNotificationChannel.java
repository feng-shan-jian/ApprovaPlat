package com.ruoyi.flowable.service.notification;

/**
 * 通知通道策略，只负责单一通道副作用，不允许修改 outbox 状态。
 */
public interface WorkflowNotificationChannel
{
    /**
     * 返回当前策略处理的稳定通道编码。
     * @return String，EMAIL 或 SMS 外部通道
     */
    String channel();

    /**
     * 执行一次已经提交领取事务的通道副作用。
     * @param row WorkflowNotificationOutboxRecord，已冻结的投递快照
     * @return WorkflowNotificationDeliveryResult，成功或稳定、脱敏的失败结果
     */
    WorkflowNotificationDeliveryResult deliver(WorkflowNotificationOutboxRecord row);
}
