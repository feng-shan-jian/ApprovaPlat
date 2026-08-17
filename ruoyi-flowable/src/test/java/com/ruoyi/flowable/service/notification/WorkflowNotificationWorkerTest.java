package com.ruoyi.flowable.service.notification;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

/**
 * 通知 worker 单条领取和投递失败隔离测试。
 */
class WorkflowNotificationWorkerTest
{
    /**
     * 验证永久领取异常立即中止当前批次，不会反复占用剩余批次槽位。
     * @return void，同一批次重复调用 claimNext 或进入投递时测试失败
     */
    @Test
    void stopsBatchAfterPermanentClaimFailure()
    {
        WorkflowNotificationDeliveryCoordinator coordinator =
                mock(WorkflowNotificationDeliveryCoordinator.class);
        when(coordinator.batchSize()).thenReturn(10);
        when(coordinator.claimNext(anyString()))
                .thenThrow(new IllegalStateException("永久损坏的最低领取记录"));
        WorkflowNotificationWorker worker = new WorkflowNotificationWorker(coordinator);

        worker.deliverBatch();

        verify(coordinator, times(1)).claimNext(anyString());
        verify(coordinator, never()).deliverClaimed(
                org.mockito.ArgumentMatchers.any(WorkflowNotificationOutboxRecord.class), anyString());
    }

    /**
     * 验证一条已领取通知投递异常后，worker 仍继续领取并投递当前批次的下一条记录。
     * @return void，投递异常错误中止批次或未回写失败状态时测试失败
     */
    @Test
    void continuesAfterIsolatedDeliveryFailure()
    {
        WorkflowNotificationDeliveryCoordinator coordinator =
                mock(WorkflowNotificationDeliveryCoordinator.class);
        WorkflowNotificationOutboxRecord failed = outbox(9L, "task-1");
        WorkflowNotificationOutboxRecord next = outbox(10L, "task-2");
        when(coordinator.batchSize()).thenReturn(3);
        when(coordinator.claimNext(anyString())).thenReturn(failed, next, null);
        doThrow(new IllegalStateException("单条站内投递异常"))
                .when(coordinator).deliverClaimed(eq(failed), anyString());
        WorkflowNotificationWorker worker = new WorkflowNotificationWorker(coordinator);

        worker.deliverBatch();

        verify(coordinator, times(3)).claimNext(anyString());
        verify(coordinator).deliverClaimed(eq(failed), anyString());
        verify(coordinator).deliverClaimed(eq(next), anyString());
    }

    /**
     * 验证邮件通道只调用包含租约锁、SMTP 和结果回写的原子服务边界。
     * @return void，worker 再次拆分邮件结果回写时测试失败
     */
    @Test
    void delegatesEmailToTransactionFreeCoordinator()
    {
        WorkflowNotificationDeliveryCoordinator coordinator =
                mock(WorkflowNotificationDeliveryCoordinator.class);
        WorkflowNotificationOutboxRecord email = outbox(11L, "task-email", "EMAIL");
        when(coordinator.batchSize()).thenReturn(2);
        when(coordinator.claimNext(anyString()))
                .thenReturn(email, (WorkflowNotificationOutboxRecord) null);
        WorkflowNotificationWorker worker = new WorkflowNotificationWorker(coordinator);

        worker.deliverBatch();

        verify(coordinator).deliverClaimed(eq(email), anyString());
    }

    /**
     * 验证短信通道只调用包含租约锁、供应商发送和结果回写的原子服务边界。
     * @return void，worker 拆分短信投递步骤或进入其他通道时测试失败
     */
    @Test
    void delegatesSmsToTransactionFreeCoordinator()
    {
        WorkflowNotificationDeliveryCoordinator coordinator =
                mock(WorkflowNotificationDeliveryCoordinator.class);
        WorkflowNotificationOutboxRecord sms = outbox(12L, "task-sms", "SMS");
        when(coordinator.batchSize()).thenReturn(2);
        when(coordinator.claimNext(anyString()))
                .thenReturn(sms, (WorkflowNotificationOutboxRecord) null);
        WorkflowNotificationWorker worker = new WorkflowNotificationWorker(coordinator);

        worker.deliverBatch();

        verify(coordinator).deliverClaimed(eq(sms), anyString());
    }

    /**
     * 创建站内通道 worker 测试记录。
     * @param outboxId long，测试 outbox 主键
     * @param taskId String，测试任务主键
     * @return WorkflowNotificationOutboxRecord，可直接进入站内投递分支的不可变记录
     */
    private WorkflowNotificationOutboxRecord outbox(long outboxId, String taskId)
    {
        return outbox(outboxId, taskId, "INBOX");
    }

    /**
     * 创建指定通道的 worker 测试记录。
     * @param outboxId long，测试 outbox 主键
     * @param taskId String，测试任务主键
     * @param channel String，INBOX、EMAIL 或 SMS
     * @return WorkflowNotificationOutboxRecord，可进入对应 worker 投递分支的不可变记录
     */
    private WorkflowNotificationOutboxRecord outbox(long outboxId, String taskId, String channel)
    {
        String deliveryTarget = "EMAIL".equals(channel) ? "user@example.test"
                : "SMS".equals(channel) ? "13800000000" : null;
        return new WorkflowNotificationOutboxRecord(outboxId, "key-" + outboxId,
                "TASK_ARRIVED", channel, 7L, deliveryTarget,
                "instance-1", taskId, "标题", "正文", null, "/workflow/process-detail/instance-1",
                "PENDING", 1, 1, 1, 6, 1);
    }
}
