package com.ruoyi.flowable.runtime;

import java.util.Map;
import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/** Participant/MessageFlow 投递结果的固定低基数指标。 */
@Component
public class WorkflowCollaborationMetrics
{
    private final Map<String, Counter> counters;

    /**
     * 注册固定 action 集合，禁止消息名、实例或关联键成为指标标签。
     * @param registry MeterRegistry，应用统一指标注册表
     * @return void，构造后可直接累计
     */
    public WorkflowCollaborationMetrics(MeterRegistry registry)
    {
        counters = Map.ofEntries(
                Map.entry("enqueued", counter(registry, "enqueued")),
                Map.entry("processed", counter(registry, "processed")),
                Map.entry("retry", counter(registry, "retry")),
                Map.entry("dead_letter", counter(registry, "dead_letter")),
                Map.entry("compensated", counter(registry, "compensated")),
                Map.entry("cancelled", counter(registry, "cancelled")),
                Map.entry("inbound_received", counter(registry, "inbound_received")),
                Map.entry("inbound_processed", counter(registry, "inbound_processed")),
                Map.entry("inbound_retry", counter(registry, "inbound_retry")),
                Map.entry("inbound_dead_letter", counter(registry, "inbound_dead_letter")),
                Map.entry("inbound_compensated", counter(registry, "inbound_compensated")),
                Map.entry("scheduler_failure", counter(registry, "scheduler_failure")));
    }

    /**
     * 累加一次固定动作。
     * @param action String，构造器注册的低基数动作
     * @return void，未知动作视为编程错误
     */
    public void record(String action)
    {
        Counter counter = counters.get(action);
        if (counter == null) throw new IllegalArgumentException("未知协作消息指标动作");
        counter.increment();
    }

    /**
     * 创建带固定 action 标签的计数器。
     * @param registry MeterRegistry，统一注册表
     * @param action String，固定动作
     * @return Counter，可累计计数器
     */
    private Counter counter(MeterRegistry registry, String action)
    {
        return Counter.builder("workflow.collaboration.messages")
                .description("Participant/MessageFlow reliable delivery actions")
                .tag("action", action)
                .register(registry);
    }
}
