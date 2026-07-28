package com.ruoyi.flowable.runtime;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToDoubleFunction;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import jakarta.annotation.PreDestroy;
import org.flowable.job.service.impl.asyncexecutor.AsyncExecutor;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.ruoyi.flowable.mapper.WorkflowRuntimeMetricsMapper;
import com.ruoyi.flowable.config.WorkflowAttachmentProperties;
import com.ruoyi.flowable.config.WorkflowRuntimeProperties;
import com.ruoyi.flowable.config.WorkflowRuntimeProperties.AttachmentStorageMode;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentCleanupCoordinator;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentStorage;

/**
 * 按固定周期刷新工作流合并指标快照，Prometheus 抓取只读取内存且不会直接查询大表。
 */
@Component
public class WorkflowRuntimeMetrics implements MeterBinder
{
    private static final Logger log = LoggerFactory.getLogger(WorkflowRuntimeMetrics.class);

    /** 首次数据库刷新前发布零值并显式标记 unavailable，不能伪装成真实空库。 */
    private static final WorkflowRuntimeMetricValues EMPTY_VALUES =
            new WorkflowRuntimeMetricValues(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                    0L, 0L, 0L, 0L, 0L, 0L, 0L);

    private final WorkflowRuntimeMetricsMapper metricsMapper;
    private final SpringProcessEngineConfiguration engineConfiguration;
    private final WorkflowAttachmentStorage attachmentStorage;
    private final WorkflowAttachmentCleanupCoordinator cleanupCoordinator;
    private final WorkflowRuntimeProperties runtimeProperties;
    private final WorkflowAttachmentProperties attachmentProperties;
    private final Clock clock;

    /** 文件系统或驱动阻塞只占用此单一 daemon worker，不能占住 Spring 调度线程。 */
    private final ExecutorService refreshExecutor = Executors.newSingleThreadExecutor(task ->
    {
        Thread worker = new Thread(task, "workflow-runtime-metrics-refresh");
        worker.setDaemon(true);
        return worker;
    });

    /** 防止上一轮阻塞时重复提交采集任务并形成无界队列或线程增长。 */
    private final AtomicBoolean refreshInFlight = new AtomicBoolean(false);

    /** 最近一次成功快照采用原子整体替换，避免一次抓取读到跨批次混合字段。 */
    private final AtomicReference<CachedSnapshot> snapshot = new AtomicReference<>(
            new CachedSnapshot(EMPTY_VALUES, 0L, 0.0D, 0.0D, 0L, false));

    /** 刷新失败累计值单调递增，保留上一次成功快照供告警平台判断陈旧时间。 */
    private final AtomicLong refreshFailures = new AtomicLong();

    /** 连续刷新失败只记录一次异常类型，恢复后清空以控制调度日志量。 */
    private final AtomicReference<String> lastRefreshFailureType = new AtomicReference<>();

    /**
     * 创建生产工作流指标组件并使用 UTC 系统时钟计算快照年龄。
     *
     * @param metricsMapper WorkflowRuntimeMetricsMapper，单次数据库往返的合并只读查询
     * @param engineConfiguration SpringProcessEngineConfiguration，两个 executor 实际状态
     * @param attachmentStorage WorkflowAttachmentStorage，当前挂载点可用空间
     * @param cleanupCoordinator WorkflowAttachmentCleanupCoordinator，清理锁实时和降级状态
     * @param runtimeProperties WorkflowRuntimeProperties，生产门禁、存储模式和共享卷标识
     * @param attachmentProperties WorkflowAttachmentProperties，生产磁盘低水位
     * @return 无返回值，构造后由 Spring 调度并绑定到 Micrometer
     */
    @Autowired
    public WorkflowRuntimeMetrics(WorkflowRuntimeMetricsMapper metricsMapper,
            SpringProcessEngineConfiguration engineConfiguration,
            WorkflowAttachmentStorage attachmentStorage,
            WorkflowAttachmentCleanupCoordinator cleanupCoordinator,
            WorkflowRuntimeProperties runtimeProperties,
            WorkflowAttachmentProperties attachmentProperties)
    {
        this(metricsMapper, engineConfiguration, attachmentStorage, cleanupCoordinator,
                runtimeProperties, attachmentProperties, Clock.systemUTC());
    }

    /**
     * 使用可控时钟创建指标组件，供快照年龄和失败保留测试复用。
     *
     * @param metricsMapper WorkflowRuntimeMetricsMapper，合并指标查询
     * @param engineConfiguration SpringProcessEngineConfiguration，executor 实际状态
     * @param attachmentStorage WorkflowAttachmentStorage，附件挂载点边界
     * @param cleanupCoordinator WorkflowAttachmentCleanupCoordinator，清理锁状态
     * @param runtimeProperties WorkflowRuntimeProperties，生产门禁与存储模式
     * @param attachmentProperties WorkflowAttachmentProperties，附件磁盘低水位
     * @param clock Clock，快照完成时间来源
     * @return 无返回值，依赖会固定到当前组件
     */
    WorkflowRuntimeMetrics(WorkflowRuntimeMetricsMapper metricsMapper,
            SpringProcessEngineConfiguration engineConfiguration,
            WorkflowAttachmentStorage attachmentStorage,
            WorkflowAttachmentCleanupCoordinator cleanupCoordinator,
            WorkflowRuntimeProperties runtimeProperties,
            WorkflowAttachmentProperties attachmentProperties, Clock clock)
    {
        this.metricsMapper = metricsMapper;
        this.engineConfiguration = engineConfiguration;
        this.attachmentStorage = attachmentStorage;
        this.cleanupCoordinator = cleanupCoordinator;
        this.runtimeProperties = runtimeProperties;
        this.attachmentProperties = attachmentProperties;
        this.clock = clock;
    }

    /**
     * 从 Spring 调度线程向唯一采集 worker 提交刷新；上一轮未结束时跳过本轮，避免共享
     * 文件系统硬阻塞拖死全局 scheduler 或形成无界任务积压。
     *
     * @return void，无返回值，提交后立即释放 Spring 调度线程
     */
    @Scheduled(
            initialDelayString = "${flowable.runtime.metrics-refresh-initial-delay:PT10S}",
            fixedDelayString = "${flowable.runtime.metrics-refresh-interval:PT1M}")
    public void scheduleSnapshotRefresh()
    {
        if (!refreshInFlight.compareAndSet(false, true))
        {
            return;
        }
        try
        {
            refreshExecutor.execute(() ->
            {
                try
                {
                    refreshSnapshot();
                }
                finally
                {
                    refreshInFlight.set(false);
                }
            });
        }
        catch (RejectedExecutionException failure)
        {
            refreshInFlight.set(false);
            if (!refreshExecutor.isShutdown())
            {
                recordRefreshFailure(failure);
            }
        }
    }

    /**
     * 原子刷新数据库、存储和 executor 快照；失败时保留上次成功值并累计失败次数。
     * 本函数由专用 worker 调用，包内测试可同步执行以验证完整采集语义。
     *
     * @return void，无返回值，下一次固定延迟调度会继续重试
     */
    void refreshSnapshot()
    {
        try
        {
            WorkflowRuntimeMetricValues values = metricsMapper.selectRuntimeMetricValues();
            if (values == null)
            {
                throw new IllegalStateException("工作流运行指标合并查询未返回结果");
            }
            // 生产周期采集必须覆盖真实写入、跨目录移动、回读、清理和低水位检查；
            // readiness 请求线程只会读取这里完成后原子发布的结果。
            long usableBytes = collectAttachmentUsableBytes();
            if (usableBytes < 0L)
            {
                throw new IllegalStateException("工作流附件可用空间不能为负数");
            }
            double asyncExecutorActive = isActive(engineConfiguration
                    .getJobServiceConfiguration().getAsyncExecutor());
            double asyncHistoryActive = isActive(
                    engineConfiguration.getAsyncHistoryExecutor());

            snapshot.set(new CachedSnapshot(values, usableBytes,
                    asyncExecutorActive, asyncHistoryActive, clock.millis(), true));
            if (lastRefreshFailureType.getAndSet(null) != null)
            {
                log.info("工作流运行指标快照刷新恢复");
            }
        }
        catch (RuntimeException failure)
        {
            recordRefreshFailure(failure);
        }
    }

    /**
     * 按最终运行配置采集附件存储状态；生产门禁开启时执行完整可写探针，开发和测试环境
     * 只读取可用空间，避免非生产实例周期性创建运维探针文件。
     *
     * @return long，本轮完整探针清理后附件文件系统的非负可用字节数
     */
    private long collectAttachmentUsableBytes()
    {
        if (!runtimeProperties.isProductionGateEnabled())
        {
            return attachmentStorage.usableSpace();
        }
        String expectedStorageId = runtimeProperties.getAttachmentStorageMode()
                == AttachmentStorageMode.SHARED_FILESYSTEM
                        ? runtimeProperties.getAttachmentStorageId() : null;
        return attachmentStorage.probeRuntimeReadiness(expectedStorageId,
                attachmentProperties.getMinFreeBytes());
    }

    /**
     * 累计一次采集失败并按连续异常类型抑制重复日志，不记录 SQL、路径或异常正文。
     *
     * @param failure RuntimeException，采集或任务提交阶段异常
     * @return void，无返回值
     */
    private void recordRefreshFailure(RuntimeException failure)
    {
        refreshFailures.incrementAndGet();
        String failureType = failure.getClass().getName();
        if (!failureType.equals(lastRefreshFailureType.getAndSet(failureType)))
        {
            log.error("工作流运行指标快照刷新失败，exceptionType={}", failureType);
        }
    }

    /**
     * 绑定固定名称和低基数标签 Gauge；所有数值函数只读取原子快照或本地原子状态。
     *
     * @param registry MeterRegistry，Actuator 管理的应用指标注册表
     * @return void，无返回值
     */
    @Override
    public void bindTo(MeterRegistry registry)
    {
        registerSnapshotGauge(registry, "workflow.process.instances.active",
                WorkflowRuntimeMetricValues::activeProcessInstances,
                "Flowable 当前运行流程实例数");
        registerSnapshotGauge(registry, "workflow.tasks.active",
                WorkflowRuntimeMetricValues::activeTasks,
                "Flowable 当前运行任务数");

        registerJobGauge(registry, "executable", WorkflowRuntimeMetricValues::executableJobs);
        registerJobGauge(registry, "timer", WorkflowRuntimeMetricValues::timerJobs);
        registerJobGauge(registry, "suspended", WorkflowRuntimeMetricValues::suspendedJobs);
        registerJobGauge(registry, "deadletter", WorkflowRuntimeMetricValues::deadletterJobs);
        registerJobGauge(registry, "external_worker",
                WorkflowRuntimeMetricValues::externalWorkerJobs);
        registerJobGauge(registry, "history", WorkflowRuntimeMetricValues::historyJobs);

        registerAttachmentGauge(registry, "temp",
                WorkflowRuntimeMetricValues::temporaryAttachments);
        registerAttachmentGauge(registry, "bound",
                WorkflowRuntimeMetricValues::boundAttachments);
        registerAttachmentGauge(registry, "expired",
                WorkflowRuntimeMetricValues::expiredAttachments);
        registerAttachmentGauge(registry, "deleted",
                WorkflowRuntimeMetricValues::deletedAttachments);
        registerSnapshotGauge(registry, "workflow.attachment.storage.bytes",
                WorkflowRuntimeMetricValues::undeletedAttachmentBytes,
                "工作流附件数据库登记字节数", "state", "undeleted_metadata");
        registerSnapshotGauge(registry, "workflow.attachment.cleanup.pending",
                WorkflowRuntimeMetricValues::pendingAttachmentCleanup,
                "终态但尚未完成物理删除的附件记录数");
        registerSnapshotGauge(registry, "workflow.attachment.cleanup.deferred",
                WorkflowRuntimeMetricValues::deferredAttachmentCleanup,
                "尚未到指数退避时间的附件清理记录数");

        Gauge.builder("workflow.attachment.storage.bytes", this,
                metrics -> metrics.snapshot.get().attachmentUsableBytes())
                .description("工作流附件挂载点字节数")
                .tag("state", "usable")
                .register(registry);
        Gauge.builder("workflow.executor.active", this,
                metrics -> metrics.snapshot.get().asyncExecutorActive())
                .description("当前 JVM Flowable executor 实际激活状态")
                .tag("type", "async")
                .register(registry);
        Gauge.builder("workflow.executor.active", this,
                metrics -> metrics.snapshot.get().asyncHistoryActive())
                .description("当前 JVM Flowable executor 实际激活状态")
                .tag("type", "async_history")
                .register(registry);
        Gauge.builder("workflow.attachment.cleanup.lock.active", cleanupCoordinator,
                coordinator -> coordinator.isLockActive() ? 1.0D : 0.0D)
                .description("当前 JVM 是否持有附件 MySQL 清理锁")
                .register(registry);
        Gauge.builder("workflow.attachment.cleanup.lock.degraded", cleanupCoordinator,
                coordinator -> coordinator.isLockDegraded() ? 1.0D : 0.0D)
                .description("当前 JVM 是否发生过无法确认成功的附件清理锁获取或释放")
                .register(registry);
        Gauge.builder("workflow.attachment.cleanup.lock.acquisition.failures",
                cleanupCoordinator,
                WorkflowAttachmentCleanupCoordinator::getLockAcquisitionFailures)
                .description("附件 MySQL 清理锁获取结果不确定累计次数")
                .register(registry);
        Gauge.builder("workflow.attachment.cleanup.lock.release.failures",
                cleanupCoordinator,
                WorkflowAttachmentCleanupCoordinator::getLockReleaseFailures)
                .description("附件 MySQL 清理锁释放失败累计次数")
                .register(registry);
        Gauge.builder("workflow.runtime.metrics.snapshot.available", this,
                metrics -> metrics.snapshot.get().available() ? 1.0D : 0.0D)
                .description("工作流运行指标是否已有成功快照")
                .register(registry);
        Gauge.builder("workflow.runtime.metrics.snapshot.age.seconds", this,
                WorkflowRuntimeMetrics::snapshotAgeSeconds)
                .description("最近一次成功工作流运行指标快照的年龄")
                .register(registry);
        Gauge.builder("workflow.runtime.metrics.refresh.failures", refreshFailures,
                AtomicLong::doubleValue)
                .description("工作流运行指标快照刷新失败累计次数")
                .register(registry);
        Gauge.builder("workflow.runtime.metrics.refresh.inflight", refreshInFlight,
                inFlight -> inFlight.get() ? 1.0D : 0.0D)
                .description("工作流运行指标采集 worker 是否仍在执行上一轮刷新")
                .register(registry);
    }

    /**
     * 注册一项无标签数据库快照 Gauge。
     *
     * @param registry MeterRegistry，应用统一注册表
     * @param name String，固定指标名
     * @param extractor ToDoubleFunction&lt;WorkflowRuntimeMetricValues&gt;，固定字段读取器
     * @param description String，指标含义
     * @return void，无返回值
     */
    private void registerSnapshotGauge(MeterRegistry registry, String name,
            ToDoubleFunction<WorkflowRuntimeMetricValues> extractor, String description)
    {
        Gauge.builder(name, this,
                metrics -> extractor.applyAsDouble(metrics.snapshot.get().values()))
                .description(description)
                .register(registry);
    }

    /**
     * 注册一项带固定单标签的数据库快照 Gauge。
     *
     * @param registry MeterRegistry，应用统一注册表
     * @param name String，固定指标名
     * @param extractor ToDoubleFunction&lt;WorkflowRuntimeMetricValues&gt;，固定字段读取器
     * @param description String，指标含义
     * @param tagKey String，低基数标签名
     * @param tagValue String，固定标签值
     * @return void，无返回值
     */
    private void registerSnapshotGauge(MeterRegistry registry, String name,
            ToDoubleFunction<WorkflowRuntimeMetricValues> extractor, String description,
            String tagKey, String tagValue)
    {
        Gauge.builder(name, this,
                metrics -> extractor.applyAsDouble(metrics.snapshot.get().values()))
                .description(description)
                .tag(tagKey, tagValue)
                .register(registry);
    }

    /**
     * 注册固定 job 类型的队列深度 Gauge。
     *
     * @param registry MeterRegistry，应用统一注册表
     * @param type String，六类固定 job 标签之一
     * @param extractor ToDoubleFunction&lt;WorkflowRuntimeMetricValues&gt;，对应字段读取器
     * @return void，无返回值
     */
    private void registerJobGauge(MeterRegistry registry, String type,
            ToDoubleFunction<WorkflowRuntimeMetricValues> extractor)
    {
        registerSnapshotGauge(registry, "workflow.jobs", extractor,
                "Flowable job 队列深度", "type", type);
    }

    /**
     * 注册固定附件状态的记录数 Gauge。
     *
     * @param registry MeterRegistry，应用统一注册表
     * @param status String，四类固定附件状态标签之一
     * @param extractor ToDoubleFunction&lt;WorkflowRuntimeMetricValues&gt;，对应字段读取器
     * @return void，无返回值
     */
    private void registerAttachmentGauge(MeterRegistry registry, String status,
            ToDoubleFunction<WorkflowRuntimeMetricValues> extractor)
    {
        registerSnapshotGauge(registry, "workflow.attachments", extractor,
                "工作流附件正式状态记录数", "status", status);
    }

    /**
     * 把可能未创建的 executor 统一映射为 0/1 快照值。
     *
     * @param executor AsyncExecutor，当前进程的实际执行器
     * @return double，已激活为 1，否则为 0
     */
    private double isActive(AsyncExecutor executor)
    {
        return executor != null && executor.isActive() ? 1.0D : 0.0D;
    }

    /**
     * 计算最近成功快照年龄；首次刷新前返回 -1，明确区别于刚采集的零秒。
     *
     * @return double，成功快照年龄秒数，尚无成功快照时为 -1
     */
    private double snapshotAgeSeconds()
    {
        CachedSnapshot current = snapshot.get();
        long ageMillis = snapshotAgeMillis(current);
        return ageMillis < 0L ? -1.0D : ageMillis / 1000.0D;
    }

    /**
     * 一次读取并转换最近成功采集结果，供 readiness 无阻塞地核对陈旧时间、存储和
     * executor 状态。
     *
     * @return WorkflowRuntimeHealthSnapshot，同一原子版本的健康字段；首次刷新前不可用
     */
    public WorkflowRuntimeHealthSnapshot readHealthSnapshot()
    {
        CachedSnapshot current = snapshot.get();
        long ageMillis = snapshotAgeMillis(current);
        return new WorkflowRuntimeHealthSnapshot(current.available(), ageMillis,
                current.attachmentUsableBytes(), current.asyncExecutorActive() == 1.0D,
                current.asyncHistoryActive() == 1.0D);
    }

    /**
     * 根据同一缓存对象计算快照年龄，系统时钟回拨时按零处理。
     *
     * @param current CachedSnapshot，一次原子读取获得的完整缓存对象
     * @return long，成功快照年龄毫秒；尚无成功快照时为 -1
     */
    private long snapshotAgeMillis(CachedSnapshot current)
    {
        if (!current.available())
        {
            return -1L;
        }
        return Math.max(0L, clock.millis() - current.capturedAtMillis());
    }

    /**
     * 应用关闭时中断并停止专用采集 worker；线程为 daemon，底层硬挂载忽略中断时也不会
     * 阻止 JVM 退出。
     *
     * @return void，无返回值
     */
    @PreDestroy
    public void shutdownRefreshExecutor()
    {
        refreshExecutor.shutdownNow();
    }

    /**
     * 原子发布的一轮完整采集结果。
     *
     * @param values WorkflowRuntimeMetricValues，单条合并 SQL 返回的业务计数
     * @param attachmentUsableBytes long，附件挂载点可用字节数
     * @param asyncExecutorActive double，普通 executor 的 0/1 状态
     * @param asyncHistoryActive double，历史 executor 的 0/1 状态
     * @param capturedAtMillis long，本轮全部依赖读取成功后的 UTC 毫秒时间
     * @param available boolean，是否已经存在一轮成功采集
     */
    private record CachedSnapshot(WorkflowRuntimeMetricValues values,
            long attachmentUsableBytes, double asyncExecutorActive,
            double asyncHistoryActive, long capturedAtMillis, boolean available)
    {
    }
}
