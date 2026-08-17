package com.ruoyi.flowable.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.mock.env.MockEnvironment;
import com.ruoyi.flowable.config.WorkflowAttachmentProperties;
import com.ruoyi.flowable.config.WorkflowRuntimeProperties;

class WorkflowRuntimeHealthIndicatorTest
{
    private WorkflowRuntimeMetrics runtimeMetrics;
    private WorkflowRuntimeProperties runtimeProperties;
    private WorkflowAttachmentProperties attachmentProperties;

    /**
     * 创建稳定健康快照基线，各测试只覆盖单一失败条件。
     *
     * @return void，无返回值
     */
    @BeforeEach
    void setUp()
    {
        runtimeMetrics = mock(WorkflowRuntimeMetrics.class);
        runtimeProperties = new WorkflowRuntimeProperties();
        runtimeProperties.setMetricsSnapshotMaxAge(Duration.ofMinutes(3));
        attachmentProperties = new WorkflowAttachmentProperties();
        attachmentProperties.setMinFreeBytes(100L);
        when(runtimeMetrics.readHealthSnapshot()).thenReturn(
                snapshot(30_000L, 1000L, false, false));
    }

    /**
     * 验证快照和低水位一致时返回 UP，健康线程只消费原子快照字段。
     *
     * @return void，正常节点被错误摘流或详情缺失关键门禁时测试失败
     */
    @Test
    void reportsUpUsingOnlyAtomicRuntimeSnapshot()
    {
        Health health = indicator(new MockEnvironment()).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("metricsSnapshotAvailable", true)
                .containsEntry("metricsSnapshotFresh", true)
                .containsEntry("metricsSnapshotAgeMillis", 30_000L)
                .containsEntry("asyncExecutorExpected", false)
                .containsEntry("asyncExecutorActive", false)
                .containsEntry("asyncHistoryExpected", false)
                .containsEntry("asyncHistoryActive", false)
                .containsEntry("storageAboveLowWatermark", true)
                .doesNotContainKeys("activeInstances", "activeTasks",
                        "deadletterJobs", "pendingAttachmentCleanup",
                        "cleanupLockDegraded");
    }

    /**
     * 验证 async-history 配置与成功快照状态单独漂移时返回 DOWN。
     *
     * @return void，历史执行器误停仍报告就绪时测试失败
     */
    @Test
    void reportsDownForAsyncHistoryStateMismatch()
    {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("flowable.async-history-executor-activate", "true");

        Health health = indicator(environment).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("asyncHistoryExpected", true)
                .containsEntry("asyncHistoryActive", false);
    }

    /**
     * 验证附件可用空间快照低于正式低水位时返回 DOWN。
     *
     * @return void，存储耗尽风险仍报告就绪时测试失败
     */
    @Test
    void reportsDownBelowAttachmentLowWatermark()
    {
        when(runtimeMetrics.readHealthSnapshot()).thenReturn(
                snapshot(30_000L, 99L, false, false));

        Health health = indicator(new MockEnvironment()).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("storageAboveLowWatermark", false);
    }

    /**
     * 验证首次成功采集前返回 DOWN，且不把初始化零值回显成真实 executor 或容量状态。
     *
     * @return void，不可用快照被误报为正常空库或正常磁盘时测试失败
     */
    @Test
    void reportsDownBeforeFirstSuccessfulSnapshot()
    {
        when(runtimeMetrics.readHealthSnapshot()).thenReturn(
                new WorkflowRuntimeHealthSnapshot(false, -1L, 0L, false, false));

        Health health = indicator(new MockEnvironment()).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("metricsSnapshotAvailable", false)
                .containsEntry("metricsSnapshotFresh", false)
                .containsEntry("metricsSnapshotAgeMillis", -1L)
                .doesNotContainKeys("asyncExecutorActive", "asyncHistoryActive",
                        "attachmentUsableBytes", "storageAboveLowWatermark");
    }

    /**
     * 验证采集线程长期阻塞或失败导致快照超过最大年龄时 readiness 无阻塞转为 DOWN。
     *
     * @return void，陈旧数据库或共享卷状态仍可无限期接流时测试失败
     */
    @Test
    void reportsDownWhenRuntimeSnapshotIsStale()
    {
        when(runtimeMetrics.readHealthSnapshot()).thenReturn(
                snapshot(Duration.ofMinutes(3).toMillis() + 1L,
                        1000L, false, false));

        Health health = indicator(new MockEnvironment()).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("metricsSnapshotAvailable", true)
                .containsEntry("metricsSnapshotFresh", false);
    }

    /**
     * 验证快照依赖异常返回稳定脱敏原因，不把物理路径或异常正文放入健康响应。
     *
     * @return void，异常被误报为 UP 或敏感正文进入详情时测试失败
     */
    @Test
    void reportsSanitizedDownWhenSnapshotReadFails()
    {
        when(runtimeMetrics.readHealthSnapshot())
                .thenThrow(new IllegalStateException("sensitive storage path"));

        Health health = indicator(new MockEnvironment()).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsExactlyEntriesOf(java.util.Map.of(
                        "reason", "workflow_runtime_snapshot_unavailable"));
        assertThat(health.toString()).doesNotContain("sensitive storage path");
    }

    /**
     * 创建一份已有成功采集的健康快照。
     *
     * @param ageMillis long，快照年龄毫秒
     * @param usableBytes long，附件挂载点可用字节数
     * @param asyncActive boolean，普通 executor 实际状态
     * @param historyActive boolean，历史 executor 实际状态
     * @return WorkflowRuntimeHealthSnapshot，字段来自同一采集批次
     */
    private WorkflowRuntimeHealthSnapshot snapshot(long ageMillis, long usableBytes,
            boolean asyncActive, boolean historyActive)
    {
        return new WorkflowRuntimeHealthSnapshot(true, ageMillis, usableBytes,
                asyncActive, historyActive);
    }

    /**
     * 使用当前测试依赖和指定最终环境配置创建健康检查。
     *
     * @param environment MockEnvironment，两个 executor 的期望开关
     * @return WorkflowRuntimeHealthIndicator，被测健康贡献者
     */
    private WorkflowRuntimeHealthIndicator indicator(MockEnvironment environment)
    {
        return new WorkflowRuntimeHealthIndicator(environment, runtimeMetrics,
                runtimeProperties, attachmentProperties);
    }
}
