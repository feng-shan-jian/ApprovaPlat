package com.ruoyi.flowable.runtime;

import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import com.ruoyi.flowable.config.WorkflowAttachmentProperties;
import com.ruoyi.flowable.config.WorkflowRuntimeProperties;

/**
 * 工作流无阻塞就绪检查，只读取定时采集的原子快照；数据库、共享文件系统
 * 与 executor 的实时读取均不会发生在 Actuator 请求线程。
 */
@Component
public class WorkflowRuntimeHealthIndicator implements HealthIndicator
{
    private static final Logger log = LoggerFactory.getLogger(
            WorkflowRuntimeHealthIndicator.class);

    private final Environment environment;
    private final WorkflowRuntimeMetrics runtimeMetrics;
    private final WorkflowRuntimeProperties runtimeProperties;
    private final WorkflowAttachmentProperties attachmentProperties;

    /** 最近一次已记录的健康失败类型，用于抑制每次抓取重复刷屏。 */
    private final AtomicReference<String> lastLoggedProblem = new AtomicReference<>();

    /**
     * 创建工作流运行健康检查。
     *
     * @param environment Environment，读取两个 executor 最终配置开关
     * @param runtimeMetrics WorkflowRuntimeMetrics，读取一次性原子运行快照
     * @param runtimeProperties WorkflowRuntimeProperties，读取快照最大允许年龄
     * @param attachmentProperties WorkflowAttachmentProperties，附件磁盘低水位
     * @return 无返回值，构造后作为 workflowRuntime 健康贡献者
     */
    public WorkflowRuntimeHealthIndicator(Environment environment,
            WorkflowRuntimeMetrics runtimeMetrics,
            WorkflowRuntimeProperties runtimeProperties,
            WorkflowAttachmentProperties attachmentProperties)
    {
        this.environment = environment;
        this.runtimeMetrics = runtimeMetrics;
        this.runtimeProperties = runtimeProperties;
        this.attachmentProperties = attachmentProperties;
    }

    /**
     * 读取一份原子快照完成轻量检查；快照缺失、陈旧、executor 漂移或磁盘低水位
     * 均返回 DOWN，且本函数不直接访问数据库或文件系统。
     *
     * @return Health，当前节点满足接流条件时为 UP
     */
    @Override
    public Health health()
    {
        try
        {
            WorkflowRuntimeHealthSnapshot snapshot = runtimeMetrics.readHealthSnapshot();
            boolean asyncExecutorExpected = environment.getProperty(
                    "flowable.async-executor-activate", Boolean.class, false);
            boolean asyncHistoryExpected = environment.getProperty(
                    "flowable.async-history-executor-activate", Boolean.class, false);
            boolean snapshotFresh = snapshot.available()
                    && snapshot.ageMillis()
                            <= runtimeProperties.getMetricsSnapshotMaxAge().toMillis();
            boolean asyncExecutorMatches = snapshot.available()
                    && asyncExecutorExpected == snapshot.asyncExecutorActive();
            boolean asyncHistoryMatches = snapshot.available()
                    && asyncHistoryExpected == snapshot.asyncHistoryActive();
            boolean storageAboveLowWatermark = snapshot.available()
                    && snapshot.attachmentUsableBytes()
                            >= attachmentProperties.getMinFreeBytes();
            String problem = healthProblem(snapshot.available(),
                    snapshotFresh, asyncExecutorMatches, asyncHistoryMatches,
                    storageAboveLowWatermark);
            Health.Builder builder;
            if (problem == null)
            {
                logRecoveryIfNeeded();
                builder = Health.up();
            }
            else
            {
                logProblemOnce(problem, null);
                builder = Health.down();
            }
            builder.withDetail("metricsSnapshotAvailable", snapshot.available())
                    .withDetail("metricsSnapshotFresh", snapshotFresh)
                    .withDetail("metricsSnapshotAgeMillis", snapshot.ageMillis())
                    .withDetail("asyncExecutorExpected", asyncExecutorExpected)
                    .withDetail("asyncHistoryExpected", asyncHistoryExpected);
            if (snapshot.available())
            {
                // 只有真实成功快照才回显采集值，避免首次启动零值被误认为真实运行状态。
                builder.withDetail("asyncExecutorActive", snapshot.asyncExecutorActive())
                        .withDetail("asyncHistoryActive", snapshot.asyncHistoryActive())
                        .withDetail("attachmentUsableBytes",
                                snapshot.attachmentUsableBytes())
                        .withDetail("storageAboveLowWatermark",
                                storageAboveLowWatermark);
            }
            return builder.build();
        }
        catch (RuntimeException failure)
        {
            // 不把配置、物理路径或驱动正文放入健康响应；日志只记录异常类型并抑制重复。
            logProblemOnce("workflow_runtime_snapshot_unavailable", failure);
            return Health.down()
                    .withDetail("reason", "workflow_runtime_snapshot_unavailable")
                    .build();
        }
    }

    /**
     * 以稳定优先级返回首个就绪失败类型，避免把配置值或物理资源正文写入日志。
     *
     * @param snapshotAvailable boolean，是否存在完整成功快照
     * @param snapshotFresh boolean，成功快照是否未超过最大允许年龄
     * @param asyncExecutorMatches boolean，普通 executor 配置与快照状态是否一致
     * @param asyncHistoryMatches boolean，历史 executor 配置与快照状态是否一致
     * @param storageAboveLowWatermark boolean，附件存储快照是否高于低水位
     * @return String，健康时为 null，否则为稳定失败类型
     */
    private String healthProblem(boolean snapshotAvailable, boolean snapshotFresh,
            boolean asyncExecutorMatches, boolean asyncHistoryMatches,
            boolean storageAboveLowWatermark)
    {
        if (!snapshotAvailable)
        {
            return "workflow_runtime_snapshot_unavailable";
        }
        if (!snapshotFresh)
        {
            return "workflow_runtime_snapshot_stale";
        }
        if (!asyncExecutorMatches)
        {
            return "async_executor_state_mismatch";
        }
        if (!asyncHistoryMatches)
        {
            return "async_history_executor_state_mismatch";
        }
        if (!storageAboveLowWatermark)
        {
            return "attachment_storage_low_watermark";
        }
        return null;
    }

    /**
     * 每种连续失败只记录一次稳定错误类型，异常正文和物理资源信息不进入日志。
     *
     * @param problem String，固定低基数失败类型
     * @param failure RuntimeException，可选依赖异常，仅记录 Java 类型
     * @return void，无返回值
     */
    private void logProblemOnce(String problem, RuntimeException failure)
    {
        String fingerprint = failure == null ? problem
                : problem + ":" + failure.getClass().getName();
        if (!fingerprint.equals(lastLoggedProblem.getAndSet(fingerprint)))
        {
            if (failure == null)
            {
                log.warn("工作流运行健康检查进入 DOWN，reason={}", problem);
            }
            else
            {
                log.error("工作流运行健康检查进入 DOWN，reason={}，exceptionType={}",
                        problem, failure.getClass().getName());
            }
        }
    }

    /**
     * 健康恢复时只记录一次恢复事件，并清除上一失败指纹。
     *
     * @return void，无返回值
     */
    private void logRecoveryIfNeeded()
    {
        if (lastLoggedProblem.getAndSet(null) != null)
        {
            log.info("工作流运行健康检查恢复为 UP");
        }
    }
}
