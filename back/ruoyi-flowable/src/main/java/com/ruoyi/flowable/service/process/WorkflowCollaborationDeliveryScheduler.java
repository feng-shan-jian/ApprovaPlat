package com.ruoyi.flowable.service.process;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.ruoyi.flowable.config.WorkflowCollaborationProperties;
import com.ruoyi.flowable.domain.WfCollaborationOutbox;
import com.ruoyi.flowable.runtime.WorkflowCollaborationMetrics;
import com.ruoyi.flowable.service.process.WorkflowCollaborationOutboxService.DeliveryOutcome;

/** 自动领取协作 outbox 并重试入站乱序/暂不可消费消息的后台 worker。 */
@Component
public class WorkflowCollaborationDeliveryScheduler
{
    private static final Logger log = LoggerFactory.getLogger(
            WorkflowCollaborationDeliveryScheduler.class);
    private final WorkflowCollaborationOutboxService outboxService;
    private final WorkflowCollaborationMessageService messageService;
    private final WorkflowCollaborationProperties properties;
    private final WorkflowCollaborationMetrics metrics;
    private final String workerId = UUID.randomUUID().toString();

    /**
     * 创建协作消息后台 worker。
     * @param outboxService WorkflowCollaborationOutboxService，出站领取和投递服务
     * @param messageService WorkflowCollaborationMessageService，入站自动重试服务
     * @param properties WorkflowCollaborationProperties，单轮批次上限
     * @param metrics WorkflowCollaborationMetrics，固定低基数失败指标
     * @return void，构造后由 Spring 调度
     */
    public WorkflowCollaborationDeliveryScheduler(WorkflowCollaborationOutboxService outboxService,
            WorkflowCollaborationMessageService messageService,
            WorkflowCollaborationProperties properties, WorkflowCollaborationMetrics metrics)
    {
        this.outboxService = outboxService;
        this.messageService = messageService;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * 每轮分别处理有界数量的出站和入站消息，单条失败保留正式状态并继续下一条。
     * @return void，调度基础设施按固定延迟再次触发
     */
    @Scheduled(
            initialDelayString = "${flowable.collaboration.worker-initial-delay:PT5S}",
            fixedDelayString = "${flowable.collaboration.worker-fixed-delay:PT1S}")
    public void deliverDueMessages()
    {
        for (int index = 0; index < properties.getBatchSize(); index++)
        {
            WfCollaborationOutbox row;
            try
            {
                row = outboxService.claimNext(workerId);
                if (row == null) break;
                DeliveryOutcome outcome = outboxService.deliver(row);
                if (outcome.success()) outboxService.completeSuccess(row, workerId, outcome);
                else outboxService.completeFailure(row, workerId, outcome);
            }
            catch (RuntimeException failure)
            {
                metrics.record("scheduler_failure");
                log.error("协作消息出站 worker 处理失败，exceptionType={}",
                        failure.getClass().getName());
            }
        }
        for (int index = 0; index < properties.getBatchSize(); index++)
        {
            try
            {
                if (!messageService.retryNextDue()) break;
            }
            catch (RuntimeException failure)
            {
                metrics.record("scheduler_failure");
                log.error("协作消息入站 worker 处理失败，exceptionType={}",
                        failure.getClass().getName());
            }
        }
    }
}
