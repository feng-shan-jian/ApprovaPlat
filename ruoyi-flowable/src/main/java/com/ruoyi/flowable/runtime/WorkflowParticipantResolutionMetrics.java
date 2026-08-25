package com.ruoyi.flowable.runtime;

import java.util.Map;
import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 累计人员规则解析失败次数，指标只使用固定错误码，禁止用户或候选集合进入标签。
 */
@Component
public class WorkflowParticipantResolutionMetrics
{
    private final Map<String, Counter> failures;

    /**
     * 注册人员解析固定失败码指标。
     *
     * @param registry MeterRegistry，应用统一 Micrometer 指标注册表
     * @return 无返回值，构造后可直接累计失败次数
     */
    public WorkflowParticipantResolutionMetrics(MeterRegistry registry)
    {
        failures = Map.of(
                "PROCESS_START_SCOPE_DENIED", counter(registry, "PROCESS_START_SCOPE_DENIED"),
                "TASK_PARTICIPANT_NO_MATCH", counter(registry, "TASK_PARTICIPANT_NO_MATCH"),
                "TASK_PARTICIPANT_RESOLUTION_FAILED",
                counter(registry, "TASK_PARTICIPANT_RESOLUTION_FAILED"));
    }

    /**
     * 累加一次固定错误码失败。
     *
     * @param errorCode String，服务对外稳定且已注册的低基数错误码
     * @return void，未知错误码视为编程错误
     */
    public void recordFailure(String errorCode)
    {
        Counter counter = failures.get(errorCode);
        if (counter == null) throw new IllegalArgumentException("未知人员解析错误码");
        counter.increment();
    }

    /**
     * 创建带固定错误码标签的人员解析失败计数器。
     *
     * @param registry MeterRegistry，应用统一指标注册表
     * @param errorCode String，固定错误码
     * @return Counter，可累计的失败计数器
     */
    private Counter counter(MeterRegistry registry, String errorCode)
    {
        return Counter.builder("workflow.participant.resolution.failures")
                .description("工作流人员规则解析失败次数")
                .tag("error_code", errorCode)
                .register(registry);
    }
}
