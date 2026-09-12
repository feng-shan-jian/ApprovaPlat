package com.ruoyi.flowable.service.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Collectors;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import com.ruoyi.flowable.config.WorkflowDataRetentionProperties;
import com.ruoyi.flowable.runtime.WorkflowDataRetentionMetrics;

/**
 * 工作流固定数据保留域和危险配置边界测试。
 */
class WorkflowDataRetentionContractTest
{
    /**
     * 验证十二个固定域及其指标标签保持唯一，轮次快照采用审计类默认保留期。
     * @return void，固定域数量、标签或默认保留期漂移时测试失败
     */
    @Test
    void exposesTwelveUniqueDomainsAndRoundRetentionDefault()
    {
        WorkflowDataRetentionProperties properties = new WorkflowDataRetentionProperties();

        assertThat(WorkflowDataRetentionDomain.values()).hasSize(12);
        assertThat(Arrays.stream(WorkflowDataRetentionDomain.values())
                .map(WorkflowDataRetentionDomain::metricTag)
                .collect(Collectors.toSet())).hasSize(12);
        assertThat(WorkflowDataRetentionDomain.MULTI_INSTANCE_ROUND.metricTag())
                .isEqualTo("multi_instance_round");
        assertThat(properties.getMultiInstanceRoundRetention()).isEqualTo(Duration.ofDays(180));
    }

    /**
     * 验证轮次保留期和批次上限拒绝会造成即时删除或无界锁定的危险配置。
     * @return void，非法边界被接受时测试失败
     */
    @Test
    void rejectsUnsafeRoundRetentionBoundaries()
    {
        WorkflowDataRetentionProperties properties = new WorkflowDataRetentionProperties();

        assertThatThrownBy(() -> properties.setMultiInstanceRoundRetention(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setMultiInstanceRoundRetention(Duration.ofDays(3651)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setBatchSize(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setBatchSize(5001))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 验证指标组件为新增轮次域预注册固定结果、耗时和最老年龄指标。
     * @return void，轮次域指标缺失或标签基数发生漂移时测试失败
     */
    @Test
    void metricsPreRegisterMultiInstanceRoundDomain()
    {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new WorkflowDataRetentionMetrics(registry);

        assertThat(registry.find("workflow.data.retention.items")
                .tag("domain", "multi_instance_round").counters()).hasSize(4);
        assertThat(registry.find("workflow.data.retention.duration")
                .tag("domain", "multi_instance_round").timers()).hasSize(1);
        assertThat(registry.find("workflow.data.retention.oldest.age.seconds")
                .tag("domain", "multi_instance_round").gauges()).hasSize(1);
    }
}
