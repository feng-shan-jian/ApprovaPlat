package com.ruoyi.flowable.service.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import com.ruoyi.flowable.config.WorkflowDataRetentionProperties;

class WorkflowDataRetentionContractTest
{
    /**
     * 验证批次、保留期和调度周期拒绝危险边界配置。
     * @return void，零保留期或无界批次被接受时测试失败
     */
    @Test
    void propertiesRejectUnsafeBoundaries()
    {
        WorkflowDataRetentionProperties properties = new WorkflowDataRetentionProperties();

        assertThatThrownBy(() -> properties.setBatchSize(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setBatchSize(5001)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setRuntimeEventRetention(java.time.Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setFixedDelay(java.time.Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(properties.getNotificationOutboxRetention()).isEqualTo(java.time.Duration.ofDays(90));
        assertThat(properties.getRuntimeEventRetention()).isEqualTo(java.time.Duration.ofDays(90));
        assertThat(properties.getTaskSlaRetention()).isEqualTo(java.time.Duration.ofDays(180));
    }

}
