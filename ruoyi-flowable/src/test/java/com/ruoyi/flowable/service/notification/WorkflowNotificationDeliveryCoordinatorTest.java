package com.ruoyi.flowable.service.notification;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证通知协调器只提交通道明确返回的结果，不隐藏通道程序缺陷。
 */
class WorkflowNotificationDeliveryCoordinatorTest
{
    /**
     * 验证未知运行时异常保留原始原因并进入 worker 的租约恢复边界。
     *
     * @return void，异常被改写或 outbox 被错误提交时测试失败
     */
    @Test
    void propagatesUnexpectedChannelFailureWithoutCompletingDelivery()
    {
        WorkflowNotificationOutboxService outboxService =
                mock(WorkflowNotificationOutboxService.class);
        WorkflowNotificationChannel emailChannel = mock(WorkflowNotificationChannel.class);
        WorkflowNotificationChannel smsChannel = mock(WorkflowNotificationChannel.class);
        WorkflowNotificationOutboxRecord row = new WorkflowNotificationOutboxRecord(
                1L, "delivery-key", "TASK_ARRIVED", "EMAIL", 100L,
                "recipient@example.test", "process-1", "task-1", "待审批",
                "您有新的审批任务", null, "/workflow/detail", "PENDING",
                1, 1, 1, 3, 1);
        IllegalStateException channelDefect = new IllegalStateException("channel defect");
        when(emailChannel.channel()).thenReturn("EMAIL");
        when(smsChannel.channel()).thenReturn("SMS");
        when(emailChannel.deliver(row)).thenThrow(channelDefect);
        WorkflowNotificationDeliveryCoordinator coordinator =
                new WorkflowNotificationDeliveryCoordinator(outboxService,
                        List.of(emailChannel, smsChannel));

        assertThatThrownBy(() -> coordinator.deliverClaimed(row, "worker-1"))
                .isSameAs(channelDefect);
        // 未知异常不生成普通失败结果，数据库记录继续由已提交租约的过期恢复机制接管。
        verify(outboxService, never()).completeDelivery(any(), anyString(), any());
    }
}
