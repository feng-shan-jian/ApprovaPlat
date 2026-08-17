package com.ruoyi.flowable.runtime;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import com.ruoyi.flowable.service.retention.WorkflowDataRetentionBatchResult;
import com.ruoyi.flowable.service.retention.WorkflowDataRetentionDomain;

/**
 * 统一记录各数据域清理数量、耗时和最老终态年龄，所有标签均为固定低基数。
 */
@Component
public class WorkflowDataRetentionMetrics
{
    private final Counter completedExecutions;
    private final Counter failedExecutions;
    private final Map<WorkflowDataRetentionDomain, Counter> scannedItems = new EnumMap<>(WorkflowDataRetentionDomain.class);
    private final Map<WorkflowDataRetentionDomain, Counter> claimedItems = new EnumMap<>(WorkflowDataRetentionDomain.class);
    private final Map<WorkflowDataRetentionDomain, Counter> deletedItems = new EnumMap<>(WorkflowDataRetentionDomain.class);
    private final Map<WorkflowDataRetentionDomain, Counter> failedItems = new EnumMap<>(WorkflowDataRetentionDomain.class);
    private final Map<WorkflowDataRetentionDomain, Timer> durations = new EnumMap<>(WorkflowDataRetentionDomain.class);
    private final Map<WorkflowDataRetentionDomain, AtomicLong> oldestAges = new EnumMap<>(WorkflowDataRetentionDomain.class);

    /**
     * 预注册固定数据域和结果标签的 Micrometer 指标，禁止业务主键进入标签。
     * @param registry MeterRegistry，应用统一指标注册表
     * @return 无返回值，构造后指标可直接采集
     */
    public WorkflowDataRetentionMetrics(MeterRegistry registry)
    {
        completedExecutions = executionCounter(registry, "completed");
        failedExecutions = executionCounter(registry, "failed");
        for (WorkflowDataRetentionDomain domain : WorkflowDataRetentionDomain.values())
        {
            scannedItems.put(domain, itemCounter(registry, domain, "scanned"));
            claimedItems.put(domain, itemCounter(registry, domain, "claimed"));
            deletedItems.put(domain, itemCounter(registry, domain, "deleted"));
            failedItems.put(domain, itemCounter(registry, domain, "failed"));
            durations.put(domain, Timer.builder("workflow.data.retention.duration")
                    .description("工作流数据保留单域批处理耗时")
                    .tag("domain", domain.metricTag()).register(registry));
            AtomicLong oldestAge = new AtomicLong(0L);
            oldestAges.put(domain, oldestAge);
            Gauge.builder("workflow.data.retention.oldest.age.seconds", oldestAge, AtomicLong::get)
                    .description("工作流数据保留单域最老终态记录年龄")
                    .tag("domain", domain.metricTag()).register(registry);
        }
    }

    /**
     * 记录成功提交的领域批次及清理后最老终态年龄。
     * @param result WorkflowDataRetentionBatchResult，领域返回的真实数量
     * @param elapsedNanos long，协调器测得的单域耗时纳秒
     * @param executionTime LocalDateTime，本轮统一业务时间
     * @return void，无返回值
     */
    public void recordCompleted(WorkflowDataRetentionBatchResult result,
            long elapsedNanos, LocalDateTime executionTime)
    {
        increment(scannedItems.get(result.domain()), result.scanned());
        increment(claimedItems.get(result.domain()), result.claimed());
        increment(deletedItems.get(result.domain()), result.deleted());
        increment(failedItems.get(result.domain()), result.failed());
        durations.get(result.domain()).record(elapsedNanos, TimeUnit.NANOSECONDS);
        oldestAges.get(result.domain()).set(ageSeconds(executionTime, result.oldestPendingTime()));
    }

    /**
     * 记录单域事务级失败，失败批次回滚后不虚报扫描、领取或删除成功数。
     * @param domain WorkflowDataRetentionDomain，发生失败的固定数据域
     * @param elapsedNanos long，失败前实际耗时纳秒
     * @return void，无返回值
     */
    public void recordDomainFailure(WorkflowDataRetentionDomain domain, long elapsedNanos)
    {
        failedItems.get(domain).increment();
        durations.get(domain).record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    /** 记录整轮所有数据域均成功结束。 @return void，无返回值 */
    public void recordExecutionCompleted() { completedExecutions.increment(); }
    /** 记录整轮至少一个数据域失败。 @return void，无返回值 */
    public void recordExecutionFailed() { failedExecutions.increment(); }

    /** 创建执行轮次 Counter。 @param registry MeterRegistry，指标注册表 @param result String，固定结果 @return Counter，已注册计数器 */
    private Counter executionCounter(MeterRegistry registry, String result)
    {
        return Counter.builder("workflow.data.retention.executions")
                .description("工作流统一数据保留执行次数")
                .tag("result", result).register(registry);
    }

    /** 创建单域处理 Counter。 @param registry MeterRegistry，指标注册表 @param domain WorkflowDataRetentionDomain，固定域 @param result String，固定结果 @return Counter，已注册计数器 */
    private Counter itemCounter(MeterRegistry registry,
            WorkflowDataRetentionDomain domain, String result)
    {
        return Counter.builder("workflow.data.retention.items")
                .description("工作流数据保留单域记录处理数")
                .tags("domain", domain.metricTag(), "result", result).register(registry);
    }

    /** 仅累计正数结果。 @param counter Counter，目标计数器 @param count int，本轮数量 @return void */
    private void increment(Counter counter, int count)
    {
        if (count > 0)
        {
            counter.increment(count);
        }
    }

    /** 计算最老终态年龄。 @param executionTime LocalDateTime，本轮时间 @param oldestPendingTime LocalDateTime，最老时间 @return long，非负秒数 */
    private long ageSeconds(LocalDateTime executionTime, LocalDateTime oldestPendingTime)
    {
        if (oldestPendingTime == null)
        {
            return 0L;
        }
        return Math.max(0L, Duration.between(oldestPendingTime, executionTime).getSeconds());
    }
}
