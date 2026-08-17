package com.ruoyi.flowable.runtime;

import java.util.Map;
import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 累计通知 outbox 状态动作与人工催办 Redis 结果，标签集合保持固定低基数。
 */
@Component
public class WorkflowNotificationMetrics
{
    private final Map<String, Counter> deliveryTransitions;
    private final Map<String, Counter> urgeResults;

    /**
     * 注册通知投递和催办固定结果计数器。
     *
     * @param registry MeterRegistry，应用统一 Micrometer 指标注册表
     * @return 无返回值，构造后可记录通知业务结果
     */
    public WorkflowNotificationMetrics(MeterRegistry registry)
    {
        deliveryTransitions = Map.of(
                "ENQUEUE", deliveryCounter(registry, "ENQUEUE"),
                "CLAIM", deliveryCounter(registry, "CLAIM"),
                "DELIVER", deliveryCounter(registry, "DELIVER"),
                "RETRY", deliveryCounter(registry, "RETRY"),
                "DEAD_LETTER", deliveryCounter(registry, "DEAD_LETTER"),
                "CANCEL", deliveryCounter(registry, "CANCEL"),
                "COMPENSATE", deliveryCounter(registry, "COMPENSATE"));
        urgeResults = Map.of(
                "accepted", urgeCounter(registry, "accepted"),
                "cooldown_rejected", urgeCounter(registry, "cooldown_rejected"),
                "redis_unavailable", urgeCounter(registry, "redis_unavailable"));
    }

    /**
     * 累加一次 outbox 固定状态动作。
     *
     * @param action String，ENQUEUE、CLAIM、DELIVER、RETRY、DEAD_LETTER、CANCEL 或 COMPENSATE
     * @return void，未知动作视为编程错误
     */
    public void recordDeliveryTransition(String action)
    {
        Counter counter = deliveryTransitions.get(action);
        if (counter == null) throw new IllegalArgumentException("未知通知投递动作");
        counter.increment();
    }

    /**
     * 累加一次人工催办固定结果。
     *
     * @param result String，accepted、cooldown_rejected 或 redis_unavailable
     * @return void，未知结果视为编程错误
     */
    public void recordUrge(String result)
    {
        Counter counter = urgeResults.get(result);
        if (counter == null) throw new IllegalArgumentException("未知人工催办结果");
        counter.increment();
    }

    /**
     * 创建通知状态动作计数器。
     *
     * @param registry MeterRegistry，统一指标注册表
     * @param action String，固定状态动作
     * @return Counter，可累计计数器
     */
    private Counter deliveryCounter(MeterRegistry registry, String action)
    {
        return Counter.builder("workflow.notification.delivery.transitions")
                .description("通知 outbox 状态动作次数")
                .tag("action", action)
                .register(registry);
    }

    /**
     * 创建人工催办结果计数器。
     *
     * @param registry MeterRegistry，统一指标注册表
     * @param result String，固定催办结果
     * @return Counter，可累计计数器
     */
    private Counter urgeCounter(MeterRegistry registry, String result)
    {
        return Counter.builder("workflow.notification.urge.requests")
                .description("人工催办请求结果次数")
                .tag("result", result)
                .register(registry);
    }
}
