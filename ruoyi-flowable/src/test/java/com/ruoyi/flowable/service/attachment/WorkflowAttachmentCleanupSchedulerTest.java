package com.ruoyi.flowable.service.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import com.ruoyi.flowable.runtime.WorkflowAttachmentCleanupMetrics;

class WorkflowAttachmentCleanupSchedulerTest
{
    /**
     * 验证完成、重试和租约丢失数分别进入固定低基数 Counter。
     *
     * @return void，调度结果未进入正式指标时测试失败
     */
    @Test
    void recordsCommittedCleanupResultMetrics()
    {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WorkflowAttachmentCleanupCoordinator coordinator =
                mock(WorkflowAttachmentCleanupCoordinator.class);
        when(coordinator.cleanupBatch()).thenReturn(
                new WorkflowAttachmentCleanupResult(2, 1, 3));
        WorkflowAttachmentCleanupScheduler scheduler = new WorkflowAttachmentCleanupScheduler(
                coordinator, new WorkflowAttachmentCleanupMetrics(registry));

        scheduler.cleanupExpiredAttachments();

        assertCounter(registry, "workflow.attachment.cleanup.executions",
                "result", "completed", 1.0D);
        assertCounter(registry, "workflow.attachment.cleanup.items",
                "result", "cleaned", 2.0D);
        assertCounter(registry, "workflow.attachment.cleanup.items",
                "result", "failed", 1.0D);
        assertCounter(registry, "workflow.attachment.cleanup.items",
                "result", "lease_lost", 3.0D);
    }

    /**
     * 验证事务级异常会累计失败次数并继续抛出，交由 Spring 调度器和日志处理。
     *
     * @return void，异常被吞掉或未计入监控时测试失败
     */
    @Test
    void recordsAndRethrowsSchedulerFailure()
    {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WorkflowAttachmentCleanupCoordinator coordinator =
                mock(WorkflowAttachmentCleanupCoordinator.class);
        IllegalStateException failure = new IllegalStateException("forced scheduler failure");
        when(coordinator.cleanupBatch()).thenThrow(failure);
        WorkflowAttachmentCleanupScheduler scheduler = new WorkflowAttachmentCleanupScheduler(
                coordinator, new WorkflowAttachmentCleanupMetrics(registry));

        assertThatThrownBy(scheduler::cleanupExpiredAttachments).isSameAs(failure);

        assertCounter(registry, "workflow.attachment.cleanup.executions",
                "result", "failed", 1.0D);
    }

    /**
     * 断言指定名称和标签的 Counter 当前值。
     *
     * @param registry SimpleMeterRegistry，测试指标注册表
     * @param name String，Micrometer 指标名
     * @param tagKey String，固定标签名
     * @param tagValue String，固定标签值
     * @param expected double，预期累计值
     * @return void，指标缺失或数值不一致时测试失败
     */
    private void assertCounter(SimpleMeterRegistry registry, String name,
            String tagKey, String tagValue, double expected)
    {
        assertThat(registry.get(name).tag(tagKey, tagValue).counter().count())
                .isEqualTo(expected);
    }
}
