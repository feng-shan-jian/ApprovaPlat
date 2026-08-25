package com.ruoyi.flowable.service.retention;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import com.ruoyi.flowable.runtime.WorkflowDataRetentionMetrics;

/**
 * 十二个固定数据域的统一协调与失败隔离测试。
 */
class WorkflowDataRetentionCoordinatorTest
{
    /**
     * 验证协调器无论注入顺序如何，都按十二个固定域顺序执行并记录整轮成功。
     * @return void，任一固定域漏执行、重复执行或顺序漂移时测试失败
     */
    @Test
    void runsAllTwelveDomainsInStableOrder()
    {
        WorkflowDataRetentionMetrics metrics = mock(WorkflowDataRetentionMetrics.class);
        List<WorkflowDataRetentionCleaner> cleaners = successfulCleaners();
        List<WorkflowDataRetentionCleaner> reversed = new ArrayList<>(cleaners);
        Collections.reverse(reversed);
        WorkflowDataRetentionCoordinator coordinator = new WorkflowDataRetentionCoordinator(
                reversed, metrics, fixedClock());

        coordinator.runScheduledBatch();

        InOrder order = inOrder(cleaners.toArray());
        for (WorkflowDataRetentionCleaner cleaner : cleaners)
        {
            order.verify(cleaner).cleanBatch(LocalDateTime.of(2026, 8, 23, 0, 0));
        }
        verify(metrics).recordExecutionCompleted();
    }

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
        LocalDateTime executionTime = LocalDateTime.of(2026, 8, 23, 0, 0);
        for (WorkflowDataRetentionDomain domain : WorkflowDataRetentionDomain.values())
        {
            WorkflowDataRetentionCleaner cleaner = mock(WorkflowDataRetentionCleaner.class);
            when(cleaner.domain()).thenReturn(domain);
            when(cleaner.cleanBatch(executionTime)).thenReturn(
                    new WorkflowDataRetentionBatchResult(domain, 0, 0, 0, 0, null));
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
