package com.ruoyi.flowable.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.ruoyi.common.exception.ServiceException;

/**
 * 通知投递协调器的事务边界和稳定结果提交测试。
 */
class WorkflowNotificationDeliveryCoordinatorTest
{
    /**
     * 验证成功投递发生在事务外，并且只向 outbox 状态所有者提交一次成功结果。
     *
     * @return void，渠道进入事务或重复提交结果时测试失败
     */
    @Test
    void deliversSuccessfullyOutsideTransactionAndCompletesOnce()
    {
        Fixture fixture = fixture("EMAIL");
        when(fixture.selectedChannel().deliver(fixture.row())).thenAnswer(invocation ->
        {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return WorkflowNotificationDeliveryResult.delivered();
        });

        fixture.coordinator().deliverClaimed(fixture.row(), "worker-1");

        verifyCompletion(fixture, WorkflowNotificationDeliveryResult.delivered());
    }

    /**
     * 验证临时失败由协调器原样提交，供 outbox 状态服务进入有界重试。
     *
     * @return void，失败结果丢失、变形或重复提交时测试失败
     */
    @Test
    void submitsRetryableFailureOnce()
    {
        Fixture fixture = fixture("SMS");
        WorkflowNotificationDeliveryResult failure = WorkflowNotificationDeliveryResult.failure(
                "SMS_TIMEOUT", "短信供应商响应超时", false);
        when(fixture.selectedChannel().deliver(fixture.row())).thenReturn(failure);

        fixture.coordinator().deliverClaimed(fixture.row(), "worker-1");

        verifyCompletion(fixture, failure);
    }

    /**
     * 验证永久失败由协调器原样提交，供 outbox 状态服务直接进入死信。
     *
     * @return void，永久属性丢失或重复提交时测试失败
     */
    @Test
    void submitsPermanentFailureOnce()
    {
        Fixture fixture = fixture("INBOX");
        WorkflowNotificationDeliveryResult failure = WorkflowNotificationDeliveryResult.failure(
                "RECIPIENT_INVALID", "接收人已失效", true);
        when(fixture.selectedChannel().deliver(fixture.row())).thenReturn(failure);

        fixture.coordinator().deliverClaimed(fixture.row(), "worker-1");

        verifyCompletion(fixture, failure);
    }

    /**
     * 验证渠道未处理异常被转换为脱敏稳定结果，并且仍然完成一次状态提交。
     *
     * @return void，异常泄漏或 outbox 长期停留 DELIVERING 时测试失败
     */
    @Test
    void convertsChannelExceptionAndCompletesOnce()
    {
        Fixture fixture = fixture("EMAIL");
        when(fixture.selectedChannel().deliver(fixture.row()))
                .thenThrow(new IllegalStateException("包含外部系统敏感响应"));

        fixture.coordinator().deliverClaimed(fixture.row(), "worker-1");

        ArgumentCaptor<WorkflowNotificationDeliveryResult> result =
                ArgumentCaptor.forClass(WorkflowNotificationDeliveryResult.class);
        verify(fixture.outboxService(), times(1)).completeDelivery(
                eq(fixture.row()), eq("worker-1"), result.capture());
        assertThat(result.getValue()).isEqualTo(WorkflowNotificationDeliveryResult.failure(
                "DELIVERY_INTERNAL_ERROR", "通知投递发生内部错误", false));
    }

    /**
     * 验证协调器拒绝在活动数据库事务中调用任何渠道。
     *
     * @return void，事务保护失效或事务内产生渠道副作用时测试失败
     */
    @Test
    void rejectsChannelCallInsideActiveTransaction()
    {
        Fixture fixture = fixture("EMAIL");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try
        {
            assertThatThrownBy(() -> fixture.coordinator().deliverClaimed(
                    fixture.row(), "worker-1"))
                    .isInstanceOf(ServiceException.class)
                    .hasMessage("通知通道调用不得运行在数据库事务内");
        }
        finally
        {
            TransactionSynchronizationManager.clear();
        }

        verify(fixture.selectedChannel(), times(0)).deliver(fixture.row());
        verify(fixture.outboxService(), times(0)).completeDelivery(
                eq(fixture.row()), eq("worker-1"),
                org.mockito.ArgumentMatchers.any(WorkflowNotificationDeliveryResult.class));
    }

    /**
     * 创建完整注册三个正式通道的协调器测试夹具。
     *
     * @param selectedChannelName String，本次实际投递的 INBOX、EMAIL 或 SMS 通道
     * @return Fixture，包含协调器、状态服务、目标通道和不可变领取快照
     */
    private Fixture fixture(String selectedChannelName)
    {
        WorkflowNotificationOutboxService outboxService =
                mock(WorkflowNotificationOutboxService.class);
        WorkflowNotificationChannel inbox = channel("INBOX");
        WorkflowNotificationChannel email = channel("EMAIL");
        WorkflowNotificationChannel sms = channel("SMS");
        WorkflowNotificationDeliveryCoordinator coordinator =
                new WorkflowNotificationDeliveryCoordinator(outboxService,
                        List.of(inbox, email, sms));
        WorkflowNotificationChannel selected = switch (selectedChannelName)
        {
            case "INBOX" -> inbox;
            case "EMAIL" -> email;
            case "SMS" -> sms;
            default -> throw new IllegalArgumentException("测试通道不合法");
        };
        String deliveryTarget = "EMAIL".equals(selectedChannelName)
                ? "user@example.test" : "SMS".equals(selectedChannelName)
                        ? "13800000000" : null;
        WorkflowNotificationOutboxRecord row = new WorkflowNotificationOutboxRecord(
                9L, "idempotency-9", "TASK_ARRIVED", selectedChannelName, 7L,
                deliveryTarget, "process-1", "task-1", "标题", "正文", null,
                "/workflow/process-detail/process-1", "PENDING", 1, 1, 1, 3, 4);
        return new Fixture(coordinator, outboxService, selected, row);
    }

    /**
     * 创建带固定通道标识的 Strategy 替身。
     *
     * @param channelName String，正式通道标识
     * @return WorkflowNotificationChannel，可由 Mockito 配置投递结果的通道替身
     */
    private WorkflowNotificationChannel channel(String channelName)
    {
        WorkflowNotificationChannel channel = mock(WorkflowNotificationChannel.class);
        when(channel.channel()).thenReturn(channelName);
        return channel;
    }

    /**
     * 断言协调器只提交一次指定稳定投递结果。
     *
     * @param fixture Fixture，本次投递测试夹具
     * @param expected WorkflowNotificationDeliveryResult，预期提交结果
     * @return void，提交次数或结果不一致时测试失败
     */
    private void verifyCompletion(Fixture fixture,
            WorkflowNotificationDeliveryResult expected)
    {
        verify(fixture.outboxService(), times(1)).completeDelivery(
                fixture.row(), "worker-1", expected);
    }

    /** 协调器行为测试所需的最小依赖集合。 */
    private record Fixture(WorkflowNotificationDeliveryCoordinator coordinator,
            WorkflowNotificationOutboxService outboxService,
            WorkflowNotificationChannel selectedChannel,
            WorkflowNotificationOutboxRecord row)
    {
    }
}
