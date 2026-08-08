package com.ruoyi.flowable.service.notification;

import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService.DeliveryOutcome;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService.OutboxRow;

/**
 * 普通审批通知后台 worker，按数据库租约处理站内信和 SMTP 通道。
 */
@Component
@ConditionalOnProperty(prefix = "flowable.notification", name = "worker-enabled",
        havingValue = "true", matchIfMissing = true)
public class WorkflowNotificationWorker
{
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowNotificationWorker.class);
    /** 当前应用进程固定 worker 标识，重启后会生成新值并接管过期租约。 */
    private final String workerId = "notification-" + UUID.randomUUID();
    private final WorkflowNotificationService service;

    /**
     * 创建通知 worker。
     * @param service WorkflowNotificationService，领取、投递和状态提交服务
     * @return void，构造后由 Spring 调度
     */
    public WorkflowNotificationWorker(WorkflowNotificationService service)
    {
        this.service = service;
    }

    /**
     * 周期执行一个有界投递批次，单条失败不会阻塞后续消息。
     * @return void，具体失败进入 outbox 重试或死信
     */
    @Scheduled(fixedDelayString = "${flowable.notification.worker-delay:1000}")
    public void deliverBatch()
    {
        for (int index = 0; index < service.batchSize(); index++)
        {
            OutboxRow row = service.claimNext(workerId);
            if (row == null) return;
            try
            {
                if ("INBOX".equals(row.channel()))
                {
                    service.deliverInbox(row, workerId);
                }
                else
                {
                    DeliveryOutcome outcome = service.deliverEmail(row);
                    service.completeDelivery(row, workerId, outcome);
                }
            }
            catch (RuntimeException exception)
            {
                // 单条未知异常必须回写重试/死信，且日志只保留 outbox 主键，不输出正文或接收地址。
                LOGGER.error("审批通知投递发生未知异常，outboxId={}", row.outboxId(), exception);
                try
                {
                    service.completeDelivery(row, workerId, DeliveryOutcome.failure(
                            "DELIVERY_INTERNAL_ERROR", "通知投递发生内部错误", false));
                }
                catch (RuntimeException leaseException)
                {
                    // 租约已被其他节点接管时只记录冲突，下一条仍继续处理。
                    LOGGER.warn("审批通知失败状态回写未成功，outboxId={}", row.outboxId(), leaseException);
                }
            }
        }
    }
}
