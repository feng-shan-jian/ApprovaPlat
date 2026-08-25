package com.ruoyi.flowable.runtime;

import java.util.Map;
import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 集成凭据 Redis 限流结果的固定低基数指标。
 */
@Component
public class WorkflowCredentialRateLimitMetrics
{
    private final Map<String, Counter> counters;

    /**
     * 注册允许、超限和 Redis 不可用三类固定结果，禁止凭据主键进入指标标签。
     * @param registry MeterRegistry，应用统一 Micrometer 注册表
     * @return void，构造后可直接累计认证限流结果
     */
    public WorkflowCredentialRateLimitMetrics(MeterRegistry registry)
    {
        counters = Map.of(
                "allowed", counter(registry, "allowed"),
                "limited", counter(registry, "limited"),
                "unavailable", counter(registry, "unavailable"));
    }

    /**
     * 累加一次固定限流结果。
     * @param result String，只允许 allowed、limited 或 unavailable
     * @return void，未知结果视为服务端编程错误
     */
    public void record(String result)
    {
        Counter counter = counters.get(result);
        if (counter == null)
        {
            throw new IllegalArgumentException("未知集成凭据限流结果");
        }
        counter.increment();
    }

    /**
     * 创建固定结果标签的计数器。
     * @param registry MeterRegistry，应用统一指标注册表
     * @param result String，固定结果标签
     * @return Counter，可累计计数器
     */
    private Counter counter(MeterRegistry registry, String result)
    {
        return Counter.builder("workflow.credential.rate.limit")
                .description("Integration credential Redis rate limit results")
                .tag("result", result)
                .register(registry);
    }
}
