package com.ruoyi.flowable.service.retention;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import com.ruoyi.flowable.config.WorkflowDataRetentionProperties;

/**
 * 工作流固定数据保留域和危险配置边界测试。
 */
class WorkflowDataRetentionContractTest
{
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

}
