package com.ruoyi.flowable.service.notification;

import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 普通审批通知后台 worker，按数据库租约处理站内信、SMTP 和短信通道。
 */
@Component
@ConditionalOnProperty(prefix = "flowable.notification", name = "worker-enabled",
        havingValue = "true", matchIfMissing = true)
public class WorkflowNotificationWorker
{
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowNotificationWorker.class);
    /** 当前应用进程固定 worker 标识，重启后会生成新值并接管过期租约。 */
    private final String workerId = "notification-" + UUID.randomUUID();
    private final WorkflowNotificationDeliveryCoordinator coordinator;

    /**
     * 创建通知 worker。
     * @param coordinator WorkflowNotificationDeliveryCoordinator，事务外投递协调入口
     * @return void，构造后由 Spring 调度
     */
    public WorkflowNotificationWorker(WorkflowNotificationDeliveryCoordinator coordinator)
    {
        this.coordinator = coordinator;
    }

    /**
     * 周期执行一个有界投递批次；领取失败中止本批，已领取记录的投递失败仍隔离处理。
     * @return void，领取异常交给下一调度周期重试，投递失败进入 outbox 重试或死信
     */
    @Scheduled(fixedDelayString = "${flowable.notification.worker-delay:1000}")
    public void deliverBatch()
    {
        for (int index = 0; index < coordinator.batchSize(); index++)
        {
            WorkflowNotificationOutboxRecord row;
            try
            {
                row = coordinator.claimNext(workerId);
            }
            catch (RuntimeException claimException)
            {
                // 领取查询总是从最低可处理行开始，继续循环只会重复命中同一毒行并占满批次槽位。
                LOGGER.error("审批通知领取发生异常，workerId={}", workerId, claimException);
                return;
            }
            if (row == null) return;
            try
            {
                // 领取事务已经提交；协调器在事务外调用 Strategy，再以独立短事务提交结果。
                coordinator.deliverClaimed(row, workerId);
            }
            catch (RuntimeException exception)
            {
                // 协调器已把通道异常转换为稳定结果；此处只可能是租约冲突或结果提交失败。
                LOGGER.warn("审批通知结果提交未成功，outboxId={}", row.outboxId(), exception);
            }
        }
    }
}
