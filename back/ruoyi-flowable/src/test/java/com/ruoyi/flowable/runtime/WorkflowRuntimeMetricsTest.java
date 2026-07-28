package com.ruoyi.flowable.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flowable.job.service.impl.asyncexecutor.AsyncExecutor;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.junit.jupiter.api.Test;
import com.ruoyi.flowable.mapper.WorkflowRuntimeMetricsMapper;
import com.ruoyi.flowable.config.WorkflowAttachmentProperties;
import com.ruoyi.flowable.config.WorkflowRuntimeProperties;
import com.ruoyi.flowable.config.WorkflowRuntimeProperties.AttachmentStorageMode;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentCleanupCoordinator;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentStorage;

class WorkflowRuntimeMetricsTest
{
    /**
     * 验证抓取只读原子快照，显式刷新才执行一次合并查询并发布完整固定指标集合。
     *
     * @return void，抓取触发数据库查询、标签漂移或字段映射错误时测试失败
     */
    @Test
    void publishesCachedMergedRuntimeSnapshotWithoutQueryingOnScrape()
    {
        WorkflowRuntimeMetricsMapper metricsMapper =
                mock(WorkflowRuntimeMetricsMapper.class);
        SpringProcessEngineConfiguration engineConfiguration = mock(
                SpringProcessEngineConfiguration.class, RETURNS_DEEP_STUBS);
        WorkflowAttachmentStorage storage = mock(WorkflowAttachmentStorage.class);
        WorkflowAttachmentCleanupCoordinator coordinator =
                mock(WorkflowAttachmentCleanupCoordinator.class);
        WorkflowRuntimeProperties runtimeProperties = runtimeProperties();
        runtimeProperties.setProductionGateEnabled(true);
        runtimeProperties.setAttachmentStorageMode(
                AttachmentStorageMode.SHARED_FILESYSTEM);
        runtimeProperties.setAttachmentStorageId("shared-storage-a");
        WorkflowAttachmentProperties attachmentProperties = attachmentProperties(100L);
        AsyncExecutor asyncExecutor = mock(AsyncExecutor.class);
        AsyncExecutor historyExecutor = mock(AsyncExecutor.class);
        WorkflowRuntimeMetricValues values = new WorkflowRuntimeMetricValues(
                3L, 5L, 1L, 2L, 3L, 4L, 5L, 6L,
                7L, 8L, 9L, 10L, 1024L, 11L, 12L);

        when(metricsMapper.selectRuntimeMetricValues()).thenReturn(values);
        when(storage.probeRuntimeReadiness("shared-storage-a", 100L))
                .thenReturn(2048L);
        when(engineConfiguration.getJobServiceConfiguration().getAsyncExecutor())
                .thenReturn(asyncExecutor);
        when(engineConfiguration.getAsyncHistoryExecutor()).thenReturn(historyExecutor);
        when(asyncExecutor.isActive()).thenReturn(true);
        when(historyExecutor.isActive()).thenReturn(false);
        when(coordinator.isLockActive()).thenReturn(true);
        when(coordinator.isLockDegraded()).thenReturn(true);
        when(coordinator.getLockAcquisitionFailures()).thenReturn(3L);
        when(coordinator.getLockReleaseFailures()).thenReturn(2L);

        WorkflowRuntimeMetrics metrics = new WorkflowRuntimeMetrics(metricsMapper,
                engineConfiguration, storage, coordinator, runtimeProperties,
                attachmentProperties);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        metrics.bindTo(registry);

        assertGauge(registry, "workflow.runtime.metrics.snapshot.available", 0.0D);
        assertGauge(registry, "workflow.process.instances.active", 0.0D);
        verifyNoInteractions(metricsMapper);

        metrics.refreshSnapshot();

        assertGauge(registry, "workflow.runtime.metrics.snapshot.available", 1.0D);
        assertGauge(registry, "workflow.process.instances.active", 3.0D);
        assertGauge(registry, "workflow.tasks.active", 5.0D);
        assertTaggedGauge(registry, "workflow.jobs", "type", "executable", 1.0D);
        assertTaggedGauge(registry, "workflow.jobs", "type", "timer", 2.0D);
        assertTaggedGauge(registry, "workflow.jobs", "type", "suspended", 3.0D);
        assertTaggedGauge(registry, "workflow.jobs", "type", "deadletter", 4.0D);
        assertTaggedGauge(registry, "workflow.jobs", "type", "external_worker", 5.0D);
        assertTaggedGauge(registry, "workflow.jobs", "type", "history", 6.0D);
        assertTaggedGauge(registry, "workflow.executor.active", "type", "async", 1.0D);
        assertTaggedGauge(registry, "workflow.executor.active", "type", "async_history", 0.0D);
        assertTaggedGauge(registry, "workflow.attachments", "status", "temp", 7.0D);
        assertTaggedGauge(registry, "workflow.attachments", "status", "bound", 8.0D);
        assertTaggedGauge(registry, "workflow.attachments", "status", "expired", 9.0D);
        assertTaggedGauge(registry, "workflow.attachments", "status", "deleted", 10.0D);
        assertTaggedGauge(registry, "workflow.attachment.storage.bytes",
                "state", "undeleted_metadata", 1024.0D);
        assertTaggedGauge(registry, "workflow.attachment.storage.bytes",
                "state", "usable", 2048.0D);
        assertGauge(registry, "workflow.attachment.cleanup.pending", 11.0D);
        assertGauge(registry, "workflow.attachment.cleanup.deferred", 12.0D);
        assertGauge(registry, "workflow.attachment.cleanup.lock.active", 1.0D);
        assertGauge(registry, "workflow.attachment.cleanup.lock.degraded", 1.0D);
        assertGauge(registry, "workflow.attachment.cleanup.lock.acquisition.failures", 3.0D);
        assertGauge(registry, "workflow.attachment.cleanup.lock.release.failures", 2.0D);
        assertGauge(registry, "workflow.runtime.metrics.refresh.failures", 0.0D);
        assertGauge(registry, "workflow.runtime.metrics.refresh.inflight", 0.0D);

        WorkflowRuntimeHealthSnapshot healthSnapshot = metrics.readHealthSnapshot();
        assertThat(healthSnapshot.available()).isTrue();
        assertThat(healthSnapshot.ageMillis()).isGreaterThanOrEqualTo(0L);
        assertThat(healthSnapshot.attachmentUsableBytes()).isEqualTo(2048L);
        assertThat(healthSnapshot.asyncExecutorActive()).isTrue();
        assertThat(healthSnapshot.asyncHistoryActive()).isFalse();

        // 重复抓取多个 Gauge 不能重新访问 Mapper、Flowable 查询 API 或文件系统。
        assertGauge(registry, "workflow.process.instances.active", 3.0D);
        assertGauge(registry, "workflow.attachment.cleanup.pending", 11.0D);
        verify(metricsMapper, times(1)).selectRuntimeMetricValues();
        verify(storage, times(1)).probeRuntimeReadiness("shared-storage-a", 100L);
        verify(storage, never()).usableSpace();
    }

    /**
     * 验证首次成功刷新前的健康快照明确不可用，不能把初始化零值伪装为真实状态。
     *
     * @return void，首次采集前快照年龄或可用性语义漂移时测试失败
     */
    @Test
    void exposesUnavailableHealthSnapshotBeforeFirstRefresh()
    {
        WorkflowRuntimeMetrics metrics = new WorkflowRuntimeMetrics(
                mock(WorkflowRuntimeMetricsMapper.class),
                mock(SpringProcessEngineConfiguration.class, RETURNS_DEEP_STUBS),
                mock(WorkflowAttachmentStorage.class),
                mock(WorkflowAttachmentCleanupCoordinator.class), runtimeProperties(),
                attachmentProperties(0L));

        assertThat(metrics.readHealthSnapshot()).isEqualTo(
                new WorkflowRuntimeHealthSnapshot(false, -1L, 0L, false, false));
    }

    /**
     * 验证文件系统探针阻塞时定时触发立即返回，且后续触发不会重复查询或积压采集任务。
     *
     * @return void，共享挂载阻塞占住 Spring scheduler 或形成重复采集时测试失败
     * @throws Exception 等待专用采集 worker 进入和退出超时
     */
    @Test
    void isolatesBlockingStorageProbeFromSchedulerAndPreventsRefreshBacklog()
            throws Exception
    {
        WorkflowRuntimeMetricsMapper metricsMapper =
                mock(WorkflowRuntimeMetricsMapper.class);
        SpringProcessEngineConfiguration engineConfiguration = mock(
                SpringProcessEngineConfiguration.class, RETURNS_DEEP_STUBS);
        WorkflowAttachmentStorage storage = mock(WorkflowAttachmentStorage.class);
        WorkflowAttachmentCleanupCoordinator coordinator =
                mock(WorkflowAttachmentCleanupCoordinator.class);
        CountDownLatch storageEntered = new CountDownLatch(1);
        CountDownLatch releaseStorage = new CountDownLatch(1);
        when(metricsMapper.selectRuntimeMetricValues()).thenReturn(
                new WorkflowRuntimeMetricValues(0L, 0L, 0L, 0L, 0L, 0L, 0L,
                        0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L));
        when(storage.usableSpace()).thenAnswer(invocation ->
        {
            storageEntered.countDown();
            if (!releaseStorage.await(5, TimeUnit.SECONDS))
            {
                throw new IllegalStateException("storage test timeout");
            }
            return 1024L;
        });
        WorkflowRuntimeMetrics metrics = new WorkflowRuntimeMetrics(metricsMapper,
                engineConfiguration, storage, coordinator, runtimeProperties(),
                attachmentProperties(0L));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        metrics.bindTo(registry);
        try
        {
            metrics.scheduleSnapshotRefresh();
            assertThat(storageEntered.await(2, TimeUnit.SECONDS)).isTrue();

            long triggerStarted = System.nanoTime();
            metrics.scheduleSnapshotRefresh();
            long triggerMillis = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - triggerStarted);

            assertThat(triggerMillis).isLessThan(500L);
            assertGauge(registry, "workflow.runtime.metrics.refresh.inflight", 1.0D);
            verify(metricsMapper, times(1)).selectRuntimeMetricValues();
            verify(storage, times(1)).usableSpace();
        }
        finally
        {
            releaseStorage.countDown();
            metrics.shutdownRefreshExecutor();
        }
    }

    /**
     * 验证刷新异常保留上一轮完整快照并累计失败，不能发布半更新或敏感异常正文。
     *
     * @return void，失败覆盖成功值或未形成可告警失败指标时测试失败
     */
    @Test
    void retainsLastSuccessfulSnapshotWhenRefreshFails()
    {
        WorkflowRuntimeMetricsMapper metricsMapper =
                mock(WorkflowRuntimeMetricsMapper.class);
        SpringProcessEngineConfiguration engineConfiguration = mock(
                SpringProcessEngineConfiguration.class, RETURNS_DEEP_STUBS);
        WorkflowAttachmentStorage storage = mock(WorkflowAttachmentStorage.class);
        WorkflowAttachmentCleanupCoordinator coordinator =
                mock(WorkflowAttachmentCleanupCoordinator.class);
        WorkflowRuntimeMetricValues values = new WorkflowRuntimeMetricValues(
                9L, 8L, 7L, 6L, 5L, 4L, 3L, 2L,
                1L, 0L, 0L, 0L, 512L, 1L, 1L);
        when(metricsMapper.selectRuntimeMetricValues())
                .thenReturn(values)
                .thenThrow(new IllegalStateException("sensitive database detail"));
        when(storage.usableSpace()).thenReturn(4096L);

        WorkflowRuntimeMetrics metrics = new WorkflowRuntimeMetrics(metricsMapper,
                engineConfiguration, storage, coordinator, runtimeProperties(),
                attachmentProperties(0L));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        metrics.bindTo(registry);
        metrics.refreshSnapshot();
        metrics.refreshSnapshot();

        assertGauge(registry, "workflow.process.instances.active", 9.0D);
        assertGauge(registry, "workflow.attachment.storage.bytes",
                "state", "usable", 4096.0D);
        assertGauge(registry, "workflow.runtime.metrics.snapshot.available", 1.0D);
        assertGauge(registry, "workflow.runtime.metrics.refresh.failures", 1.0D);
        verify(metricsMapper, times(2)).selectRuntimeMetricValues();
        verify(storage, times(1)).usableSpace();
    }

    /**
     * 创建默认关闭生产门禁的运行配置，供非生产轻量空间读取分支复用。
     *
     * @return WorkflowRuntimeProperties，默认本地持久卷且生产门禁关闭
     */
    private WorkflowRuntimeProperties runtimeProperties()
    {
        return new WorkflowRuntimeProperties();
    }

    /**
     * 创建带指定磁盘低水位的附件配置。
     *
     * @param minFreeBytes long，生产完整探针必须保留的最小可用字节数
     * @return WorkflowAttachmentProperties，已写入低水位的测试配置
     */
    private WorkflowAttachmentProperties attachmentProperties(long minFreeBytes)
    {
        WorkflowAttachmentProperties properties = new WorkflowAttachmentProperties();
        properties.setMinFreeBytes(minFreeBytes);
        return properties;
    }

    /**
     * 断言无标签 Gauge 当前值。
     *
     * @param registry SimpleMeterRegistry，测试注册表
     * @param name String，指标名
     * @param expected double，预期值
     * @return void，指标缺失或值不一致时测试失败
     */
    private void assertGauge(SimpleMeterRegistry registry, String name, double expected)
    {
        assertThat(registry.get(name).gauge().value()).isEqualTo(expected);
    }

    /**
     * 断言固定标签 Gauge 当前值。
     *
     * @param registry SimpleMeterRegistry，测试注册表
     * @param name String，指标名
     * @param tagKey String，标签名
     * @param tagValue String，标签值
     * @param expected double，预期值
     * @return void，指标或固定标签漂移时测试失败
     */
    private void assertTaggedGauge(SimpleMeterRegistry registry, String name,
            String tagKey, String tagValue, double expected)
    {
        assertThat(registry.get(name).tag(tagKey, tagValue).gauge().value())
                .isEqualTo(expected);
    }

    /**
     * 断言带固定 state 标签的存储 Gauge，避免测试调用处重复标签参数。
     *
     * @param registry SimpleMeterRegistry，测试注册表
     * @param name String，指标名
     * @param tagKey String，固定为 state
     * @param tagValue String，存储状态标签
     * @param expected double，预期值
     * @return void，指标或标签不一致时测试失败
     */
    private void assertGauge(SimpleMeterRegistry registry, String name,
            String tagKey, String tagValue, double expected)
    {
        assertTaggedGauge(registry, name, tagKey, tagValue, expected);
    }
}
