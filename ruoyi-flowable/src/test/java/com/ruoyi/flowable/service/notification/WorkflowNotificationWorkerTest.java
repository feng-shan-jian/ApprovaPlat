package com.ruoyi.flowable.service.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService.DeliveryOutcome;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService.OutboxRow;

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
        WorkflowNotificationService service = mock(WorkflowNotificationService.class);
        when(service.batchSize()).thenReturn(10);
        when(service.claimNext(anyString()))
                .thenThrow(new IllegalStateException("永久损坏的最低领取记录"));
        WorkflowNotificationWorker worker = new WorkflowNotificationWorker(service);

        worker.deliverBatch();

        verify(service, times(1)).claimNext(anyString());
        verify(service, never()).deliverInbox(any(OutboxRow.class), anyString());
        verify(service, never()).deliverEmail(any(OutboxRow.class), anyString());
    }

    /**
     * 验证一条已领取通知投递异常后，worker 仍继续领取并投递当前批次的下一条记录。
     * @return void，投递异常错误中止批次或未回写失败状态时测试失败
     */
    @Test
    void continuesAfterIsolatedDeliveryFailure()
    {
        WorkflowNotificationService service = mock(WorkflowNotificationService.class);
        OutboxRow failed = outbox(9L, "task-1");
        OutboxRow next = outbox(10L, "task-2");
        when(service.batchSize()).thenReturn(3);
        when(service.claimNext(anyString())).thenReturn(failed, next, null);
        doThrow(new IllegalStateException("单条站内投递异常"))
                .when(service).deliverInbox(eq(failed), anyString());
        WorkflowNotificationWorker worker = new WorkflowNotificationWorker(service);

        worker.deliverBatch();

        verify(service, times(3)).claimNext(anyString());
        verify(service).deliverInbox(eq(failed), anyString());
        verify(service).completeDelivery(eq(failed), anyString(), any(DeliveryOutcome.class));
        verify(service).deliverInbox(eq(next), anyString());
    }

    /**
     * 验证邮件通道只调用包含租约锁、SMTP 和结果回写的原子服务边界。
     * @return void，worker 再次拆分邮件结果回写时测试失败
     */
    @Test
    void delegatesEmailToTransactionalDeliveryBoundary()
    {
        WorkflowNotificationService service = mock(WorkflowNotificationService.class);
        OutboxRow email = outbox(11L, "task-email", "EMAIL");
        when(service.batchSize()).thenReturn(2);
        when(service.claimNext(anyString())).thenReturn(email, (OutboxRow) null);
        WorkflowNotificationWorker worker = new WorkflowNotificationWorker(service);

        worker.deliverBatch();

        verify(service).deliverEmail(eq(email), anyString());
        verify(service, never()).completeDelivery(eq(email), anyString(), any(DeliveryOutcome.class));
    }

    /**
     * 验证短信通道只调用包含租约锁、供应商发送和结果回写的原子服务边界。
     * @return void，worker 拆分短信投递步骤或进入其他通道时测试失败
     */
    @Test
    void delegatesSmsToTransactionalDeliveryBoundary()
    {
        WorkflowNotificationService service = mock(WorkflowNotificationService.class);
        OutboxRow sms = outbox(12L, "task-sms", "SMS");
        when(service.batchSize()).thenReturn(2);
        when(service.claimNext(anyString())).thenReturn(sms, (OutboxRow) null);
        WorkflowNotificationWorker worker = new WorkflowNotificationWorker(service);

        worker.deliverBatch();

        verify(service).deliverSms(eq(sms), anyString());
        verify(service, never()).completeDelivery(eq(sms), anyString(), any(DeliveryOutcome.class));
    }

    /**
     * 创建站内通道 worker 测试记录。
     * @param outboxId long，测试 outbox 主键
     * @param taskId String，测试任务主键
     * @return OutboxRow，可直接进入站内投递分支的不可变记录
     */
    private OutboxRow outbox(long outboxId, String taskId)
    {
        return outbox(outboxId, taskId, "INBOX");
    }

    /**
     * 创建指定通道的 worker 测试记录。
     * @param outboxId long，测试 outbox 主键
     * @param taskId String，测试任务主键
     * @param channel String，INBOX、EMAIL 或 SMS
     * @return OutboxRow，可进入对应 worker 投递分支的不可变记录
     */
    private OutboxRow outbox(long outboxId, String taskId, String channel)
    {
        return new OutboxRow(outboxId, "key-" + outboxId, "TASK_ARRIVED", channel, 7L,
                "instance-1", taskId, "标题", "正文", null, "/workflow/process-detail/instance-1",
                "PENDING", 1, 1, 1, 6, 1);
    }
}
