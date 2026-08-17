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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import com.ruoyi.flowable.runtime.WorkflowDataRetentionMetrics;

class WorkflowDataRetentionCoordinatorTest
{
    /**
     * 验证协调器按固定数据域顺序执行全部清理器并记录整轮成功。
     * @return void，漏执行或顺序漂移时测试失败
     */
    @Test
    void runsEveryDomainInStableOrder()
    {
        WorkflowDataRetentionMetrics metrics = mock(WorkflowDataRetentionMetrics.class);
        List<WorkflowDataRetentionCleaner> cleaners = cleanersWithSuccessfulResults();
        WorkflowDataRetentionCoordinator coordinator = new WorkflowDataRetentionCoordinator(
                reversed(cleaners), metrics, fixedClock());

        coordinator.runScheduledBatch();

        InOrder order = inOrder(cleaners.toArray());
        for (WorkflowDataRetentionCleaner cleaner : cleaners)
        {
            order.verify(cleaner).cleanBatch(LocalDateTime.of(2026, 8, 16, 0, 0));
        }
        verify(metrics).recordExecutionCompleted();
    }

    /**
     * 验证单域失败后继续后续领域并在整轮结束时抛出聚合异常。
     * @return void，失败被吞掉或后续领域未执行时测试失败
     */
    @Test
    void continuesAfterDomainFailureAndFailsWholeExecution()
    {
        WorkflowDataRetentionMetrics metrics = mock(WorkflowDataRetentionMetrics.class);
        List<WorkflowDataRetentionCleaner> cleaners = cleanersWithSuccessfulResults();
        WorkflowDataRetentionCleaner failed = cleaners.get(2);
        when(failed.cleanBatch(LocalDateTime.of(2026, 8, 16, 0, 0)))
                .thenThrow(new IllegalStateException("draft failure"));
        WorkflowDataRetentionCoordinator coordinator = new WorkflowDataRetentionCoordinator(
                cleaners, metrics, fixedClock());

        assertThatThrownBy(coordinator::runScheduledBatch)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("失败数据域: 1")
                .satisfies(failure -> org.assertj.core.api.Assertions.assertThat(
                        failure.getSuppressed()).hasSize(1));

        verify(cleaners.get(3)).cleanBatch(LocalDateTime.of(2026, 8, 16, 0, 0));
        verify(metrics).recordExecutionFailed();
    }

    /**
     * 验证缺失固定数据域时协调器拒绝启动，避免形成无人消费的数据保留策略。
     * @return void，缺失领域未被识别时测试失败
     */
    @Test
    void rejectsMissingDomainCleaner()
    {
        List<WorkflowDataRetentionCleaner> cleaners = cleanersWithSuccessfulResults();
        cleaners.remove(cleaners.size() - 1);

        assertThatThrownBy(() -> new WorkflowDataRetentionCoordinator(cleaners,
                mock(WorkflowDataRetentionMetrics.class), fixedClock()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少消费者");
    }

    /** 创建全部返回成功结果的领域清理器。 @return List，按固定枚举顺序排列的 mock */
    private List<WorkflowDataRetentionCleaner> cleanersWithSuccessfulResults()
    {
        List<WorkflowDataRetentionCleaner> cleaners = new ArrayList<>();
        LocalDateTime executionTime = LocalDateTime.of(2026, 8, 16, 0, 0);
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

    /** 反转清理器输入顺序以验证协调器内部稳定排序。 @param source List，原始列表 @return List，反序副本 */
    private List<WorkflowDataRetentionCleaner> reversed(List<WorkflowDataRetentionCleaner> source)
    {
        List<WorkflowDataRetentionCleaner> copy = new ArrayList<>(source);
        java.util.Collections.reverse(copy);
        return copy;
    }

    /** 创建固定 UTC 时钟。 @return Clock，固定在 2026-08-16T00:00:00Z */
    private Clock fixedClock()
    {
        return Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC);
    }
}
