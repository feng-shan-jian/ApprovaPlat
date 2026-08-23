package com.ruoyi.flowable.service.retention;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.ruoyi.flowable.runtime.WorkflowDataRetentionMetrics;

/**
 * 工作流数据生命周期统一调度入口，只负责编排领域批处理和指标，不访问业务表。
 */
@Component
public class WorkflowDataRetentionCoordinator
{
    /** 十二个固定父领域清理器，按枚举顺序执行以保持运行证据稳定。 */
    private final List<WorkflowDataRetentionCleaner> cleaners;
    private final WorkflowDataRetentionMetrics metrics;
    private final Clock clock;

    /**
     * 创建统一数据保留协调器并校验每个固定数据域恰有一个消费者。
     * @param cleaners List&lt;WorkflowDataRetentionCleaner&gt;，各领域独立批处理 Bean
     * @param metrics WorkflowDataRetentionMetrics，固定低基数运行指标
     * @return 无返回值，缺失或重复数据域时拒绝应用启动
     */
    @Autowired
    public WorkflowDataRetentionCoordinator(List<WorkflowDataRetentionCleaner> cleaners,
            WorkflowDataRetentionMetrics metrics)
    {
        this(cleaners, metrics, Clock.systemDefaultZone());
    }

    /**
     * 创建可注入时钟的协调器，供确定性单元测试使用。
     * @param cleaners List&lt;WorkflowDataRetentionCleaner&gt;，各领域独立批处理 Bean
     * @param metrics WorkflowDataRetentionMetrics，固定低基数运行指标
     * @param clock Clock，统一生成每轮业务时间的时钟
     * @return 无返回值，构造时完成领域所有权校验
     */
    WorkflowDataRetentionCoordinator(List<WorkflowDataRetentionCleaner> cleaners,
            WorkflowDataRetentionMetrics metrics, Clock clock)
    {
        this.cleaners = cleaners.stream()
                .sorted(Comparator.comparingInt(cleaner -> cleaner.domain().ordinal()))
                .toList();
        this.metrics = metrics;
        this.clock = clock;
        validateDomainOwnership(this.cleaners);
    }

    /**
     * 按配置周期执行全部数据域；单域失败不会阻止后续域，整轮最终准确失败。
     * @return void，无返回值；任一领域失败时抛出带 suppressed 原因的聚合异常
     */
    @Scheduled(
            initialDelayString = "${flowable.data-retention.initial-delay:PT5M}",
            fixedDelayString = "${flowable.data-retention.fixed-delay:PT1H}")
    public void runScheduledBatch()
    {
        LocalDateTime executionTime = LocalDateTime.now(clock);
        List<RuntimeException> failures = new ArrayList<>();
        for (WorkflowDataRetentionCleaner cleaner : cleaners)
        {
            long startNanos = System.nanoTime();
            try
            {
                WorkflowDataRetentionBatchResult result = cleaner.cleanBatch(executionTime);
                metrics.recordCompleted(result, System.nanoTime() - startNanos, executionTime);
            }
            catch (RuntimeException failure)
            {
                metrics.recordDomainFailure(cleaner.domain(), System.nanoTime() - startNanos);
                failures.add(failure);
            }
        }
        if (!failures.isEmpty())
        {
            metrics.recordExecutionFailed();
            IllegalStateException aggregate = new IllegalStateException(
                    "工作流统一数据保留任务存在失败数据域: " + failures.size());
            failures.forEach(aggregate::addSuppressed);
            throw aggregate;
        }
        metrics.recordExecutionCompleted();
    }

    /**
     * 校验固定数据域的唯一消费者，避免漏配后形成无主持久化数据。
     * @param configuredCleaners List&lt;WorkflowDataRetentionCleaner&gt;，已排序领域清理器
     * @return void，无返回值；重复或缺失数据域时抛出 IllegalStateException
     */
    private void validateDomainOwnership(List<WorkflowDataRetentionCleaner> configuredCleaners)
    {
        EnumSet<WorkflowDataRetentionDomain> domains = EnumSet.noneOf(WorkflowDataRetentionDomain.class);
        for (WorkflowDataRetentionCleaner cleaner : configuredCleaners)
        {
            if (!domains.add(cleaner.domain()))
            {
                throw new IllegalStateException("工作流数据保留域重复配置: " + cleaner.domain());
            }
        }
        EnumSet<WorkflowDataRetentionDomain> missing = EnumSet.allOf(WorkflowDataRetentionDomain.class);
        missing.removeAll(domains);
        if (!missing.isEmpty())
        {
            throw new IllegalStateException("工作流数据保留域缺少消费者: " + missing);
        }
    }
}
