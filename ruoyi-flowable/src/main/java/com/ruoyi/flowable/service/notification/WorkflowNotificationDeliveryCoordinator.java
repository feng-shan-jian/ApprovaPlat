package com.ruoyi.flowable.service.notification;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

/**
 * 通知投递协调器，在短事务领取和短事务结果提交之间执行事务外通道副作用。
 */
@Service
public class WorkflowNotificationDeliveryCoordinator
{
    private final WorkflowNotificationOutboxService outboxService;
    private final Map<String, WorkflowNotificationChannel> channels;

    /**
     * 创建通知投递协调器。
     * @param outboxService WorkflowNotificationOutboxService，outbox 状态唯一所有者
     * @param channelStrategies List&lt;WorkflowNotificationChannel&gt;，三个正式通知通道
     * @return void，构造后由 Spring 管理
     */
    public WorkflowNotificationDeliveryCoordinator(WorkflowNotificationOutboxService outboxService,
            List<WorkflowNotificationChannel> channelStrategies)
    {
        this.outboxService = outboxService;
        LinkedHashMap<String, WorkflowNotificationChannel> indexed = new LinkedHashMap<>();
        for (WorkflowNotificationChannel strategy : channelStrategies)
        {
            WorkflowNotificationChannel previous = indexed.put(strategy.channel(), strategy);
            if (previous != null)
            {
                throw new IllegalStateException("通知通道策略重复: " + strategy.channel());
            }
        }
        if (!indexed.keySet().equals(java.util.Set.of("INBOX", "EMAIL", "SMS")))
        {
            throw new IllegalStateException("通知通道策略必须完整注册 INBOX、EMAIL 和 SMS");
        }
        this.channels = Map.copyOf(indexed);
    }

    /**
     * 返回 worker 单轮有界批次大小。
     * @return int，正式通知批次配置
     */
    public int batchSize()
    {
        return outboxService.batchSize();
    }

    /**
     * 使用独立短事务领取一条到期通知。
     * @param workerId String，当前节点 worker 标识
     * @return WorkflowNotificationOutboxRecord，可空领取快照
     */
    public WorkflowNotificationOutboxRecord claimNext(String workerId)
    {
        return outboxService.claimNext(workerId);
    }

    /**
     * 在数据库事务外执行通道副作用，并在随后短事务提交稳定结果。
     * @param row WorkflowNotificationOutboxRecord，claimNext 已提交的领取快照
     * @param workerId String，租约持有者
     * @return void，未知通道或租约漂移时抛出稳定异常
     */
    public void deliverClaimed(WorkflowNotificationOutboxRecord row, String workerId)
    {
        if (TransactionSynchronizationManager.isActualTransactionActive())
        {
            throw new ServiceException("通知通道调用不得运行在数据库事务内", HttpStatus.ERROR);
        }
        WorkflowNotificationChannel channel = channels.get(row.channel());
        if (channel == null)
        {
            throw new ServiceException("通知 outbox 包含不支持的通道", HttpStatus.ERROR);
        }
        WorkflowNotificationDeliveryResult result;
        try
        {
            result = channel.deliver(row);
        }
        catch (RuntimeException exception)
        {
            result = WorkflowNotificationDeliveryResult.failure(
                    "DELIVERY_INTERNAL_ERROR", "通知投递发生内部错误", false);
        }
        outboxService.completeDelivery(row, workerId, result);
    }
}
