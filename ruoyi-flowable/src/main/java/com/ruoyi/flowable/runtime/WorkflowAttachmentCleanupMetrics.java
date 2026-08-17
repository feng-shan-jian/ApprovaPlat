package com.ruoyi.flowable.runtime;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentCleanupResult;

/**
 * 累计附件清理执行、调度失败及单条处理结果指标。
 */
@Component
public class WorkflowAttachmentCleanupMetrics
{
    /** 已完整结束的清理轮次，固定使用 result=completed。 */
    private final Counter completedExecutions;
    /** 清理调度在轮次级发生未恢复异常的次数，固定使用 result=failed。 */
    private final Counter failedExecutions;
    /** 已完成物理删除并成功更新正式附件状态的记录数，固定使用 result=cleaned。 */
    private final Counter cleanedItems;
    /** 单条物理删除失败并进入持久化重试的附件记录数，固定使用 result=failed。 */
    private final Counter failedItems;
    /** 租约过期或被其他节点重领后放弃旧令牌回写的记录数。 */
    private final Counter leaseLostItems;

    /**
     * 创建并注册附件清理 Counter，标签集合固定以避免高基数指标。
     *
     * @param meterRegistry MeterRegistry，应用统一 Micrometer 指标注册表
     * @return 无返回值，构造后指标立即可由 Prometheus 采集
     */
    public WorkflowAttachmentCleanupMetrics(MeterRegistry meterRegistry)
    {
        this.completedExecutions = Counter.builder("workflow.attachment.cleanup.executions")
                .description("工作流附件清理调度执行次数")
                .tag("result", "completed")
                .register(meterRegistry);
        this.failedExecutions = Counter.builder("workflow.attachment.cleanup.executions")
                .description("工作流附件清理调度执行次数")
                .tag("result", "failed")
                .register(meterRegistry);
        this.cleanedItems = Counter.builder("workflow.attachment.cleanup.items")
                .description("工作流附件清理记录处理数")
                .tag("result", "cleaned")
                .register(meterRegistry);
        this.failedItems = Counter.builder("workflow.attachment.cleanup.items")
                .description("工作流附件清理记录处理数")
                .tag("result", "failed")
                .register(meterRegistry);
        this.leaseLostItems = Counter.builder("workflow.attachment.cleanup.items")
                .description("工作流附件清理记录处理数")
                .tag("result", "lease_lost")
                .register(meterRegistry);
    }

    /**
     * 记录已完成的一轮真实清理及其单条结果。
     *
     * @param result WorkflowAttachmentCleanupResult，领域服务返回的完成数和失败数
     * @return void，无返回值
     */
    public void recordCompleted(WorkflowAttachmentCleanupResult result)
    {
        completedExecutions.increment();
        if (result.cleaned() > 0)
        {
            cleanedItems.increment(result.cleaned());
        }
        if (result.failures() > 0)
        {
            failedItems.increment(result.failures());
        }
        if (result.leaseLost() > 0)
        {
            leaseLostItems.increment(result.leaseLost());
        }
    }

    /**
     * 记录批次领取、状态回写或事务级异常导致的调度失败。
     *
     * @return void，无返回值
     */
    public void recordSchedulerFailure()
    {
        failedExecutions.increment();
    }
}
