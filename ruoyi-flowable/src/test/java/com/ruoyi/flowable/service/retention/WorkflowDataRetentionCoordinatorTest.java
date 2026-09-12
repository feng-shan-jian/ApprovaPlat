package com.ruoyi.flowable.service.retention;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.ruoyi.flowable.runtime.WorkflowDataRetentionMetrics;

/**
 * 十二个固定数据域的统一协调与失败隔离测试。
 */
class WorkflowDataRetentionCoordinatorTest
{
    /**
     * 验证轮次域缺少消费者时协调器拒绝启动，防止新增表形成无人清理的数据孤岛。
     * @return void，缺失 MULTI_INSTANCE_ROUND 清理器未被识别时测试失败
     */
    @Test
    void rejectsMissingMultiInstanceRoundCleaner()
    {
        List<WorkflowDataRetentionCleaner> cleaners = successfulCleaners();
        cleaners.removeIf(cleaner -> cleaner.domain()
                == WorkflowDataRetentionDomain.MULTI_INSTANCE_ROUND);

        assertThatThrownBy(() -> new WorkflowDataRetentionCoordinator(cleaners,
                mock(WorkflowDataRetentionMetrics.class), fixedClock()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MULTI_INSTANCE_ROUND");
    }

    /**
     * 创建十二个返回空成功结果的领域清理器。
     * @return List&lt;WorkflowDataRetentionCleaner&gt;，按固定枚举顺序排列的清理器
     */
    private List<WorkflowDataRetentionCleaner> successfulCleaners()
    {
        List<WorkflowDataRetentionCleaner> cleaners = new ArrayList<>();
        for (WorkflowDataRetentionDomain domain : WorkflowDataRetentionDomain.values())
        {
            WorkflowDataRetentionCleaner cleaner = mock(WorkflowDataRetentionCleaner.class);
            when(cleaner.domain()).thenReturn(domain);
            cleaners.add(cleaner);
        }
        return cleaners;
    }

    /**
     * 创建本轮测试共用的固定 UTC 时钟。
     * @return Clock，固定在 2026-08-23T00:00:00Z
     */
    private Clock fixedClock()
    {
        return Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC);
    }
}
