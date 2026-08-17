package com.ruoyi.flowable.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 工作流生产运行拓扑、共享存储和指标刷新配置。
 */
@Component
@ConfigurationProperties(prefix = "flowable.runtime")
public class WorkflowRuntimeProperties
{
    /** 应用部署拓扑，用于约束 executor 与附件存储组合。 */
    public enum DeploymentTopology
    {
        SINGLE_NODE,
        MULTI_NODE
    }

    /** Flowable executor 运行拓扑；数据库锁拓扑不等同于已完成多节点验收。 */
    public enum ExecutorTopology
    {
        DISABLED,
        SINGLE_NODE,
        DATABASE_LOCKED_MULTI_NODE
    }

    /** 附件根目录的持久化语义。 */
    public enum AttachmentStorageMode
    {
        LOCAL_PERSISTENT,
        SHARED_FILESYSTEM
    }

    /** 仅部署覆盖配置开启；开发和测试环境不因生产审批字段缺失而启动失败。 */
    private boolean productionGateEnabled;

    /** 默认以单节点部署启动，不能据此推断生产已完成单节点验收。 */
    private DeploymentTopology deploymentTopology = DeploymentTopology.SINGLE_NODE;

    /** 默认禁用 executor，与生产首次启动红线一致。 */
    private ExecutorTopology executorTopology = ExecutorTopology.DISABLED;

    /** 当前应用节点的稳定运维标识，不是用户凭据。 */
    private String nodeId = "";

    /** executor 或多节点配置对应的审批单、变更单或发布单引用。 */
    private String approvalReference = "";

    /** 附件容量、并发与磁盘低水位对应的独立审批或容量评审引用。 */
    private String capacityApprovalReference = "";

    /** 默认使用单节点持久卷；多节点必须显式声明共享文件系统。 */
    private AttachmentStorageMode attachmentStorageMode =
            AttachmentStorageMode.LOCAL_PERSISTENT;

    /** 共享卷预置 .storage-id 的期望值，不由应用自动创建。 */
    private String attachmentStorageId = "";

    /** 启动后首次采集工作流合并指标前的默认等待时间。 */
    private Duration metricsRefreshInitialDelay = Duration.ofSeconds(10);

    /** 工作流数据库合并指标默认每分钟刷新一次，Prometheus 抓取不直接查询数据库。 */
    private Duration metricsRefreshInterval = Duration.ofMinutes(1);

    /** readiness 允许最近成功运行快照保持有效的最长时间。 */
    private Duration metricsSnapshotMaxAge = Duration.ofMinutes(3);

    /**
     * 获取生产启动门禁开关。
     *
     * @return boolean，部署覆盖明确开启时为 true
     */
    public boolean isProductionGateEnabled()
    {
        return productionGateEnabled;
    }

    /**
     * 设置生产启动门禁开关。
     *
     * @param productionGateEnabled boolean，生产部署必须设置为 true
     * @return void，无返回值
     */
    public void setProductionGateEnabled(boolean productionGateEnabled)
    {
        this.productionGateEnabled = productionGateEnabled;
    }

    /**
     * 获取应用部署拓扑。
     *
     * @return DeploymentTopology，单节点或多节点
     */
    public DeploymentTopology getDeploymentTopology()
    {
        return deploymentTopology;
    }

    /**
     * 设置应用部署拓扑。
     *
     * @param deploymentTopology DeploymentTopology，不能为空
     * @return void，无返回值
     */
    public void setDeploymentTopology(DeploymentTopology deploymentTopology)
    {
        this.deploymentTopology = deploymentTopology;
    }

    /**
     * 获取 executor 拓扑声明。
     *
     * @return ExecutorTopology，禁用、单节点或数据库锁协调多节点
     */
    public ExecutorTopology getExecutorTopology()
    {
        return executorTopology;
    }

    /**
     * 设置 executor 拓扑声明。
     *
     * @param executorTopology ExecutorTopology，不能为空
     * @return void，无返回值
     */
    public void setExecutorTopology(ExecutorTopology executorTopology)
    {
        this.executorTopology = executorTopology;
    }

    /**
     * 获取当前运维节点标识。
     *
     * @return String，启用 executor 或多节点部署时使用的稳定标识
     */
    public String getNodeId()
    {
        return nodeId;
    }

    /**
     * 设置当前运维节点标识。
     *
     * @param nodeId String，非敏感稳定节点标识
     * @return void，无返回值
     */
    public void setNodeId(String nodeId)
    {
        this.nodeId = normalize(nodeId);
    }

    /**
     * 获取拓扑审批引用。
     *
     * @return String，审批单、变更单或发布单的非敏感编号
     */
    public String getApprovalReference()
    {
        return approvalReference;
    }

    /**
     * 设置拓扑审批引用。
     *
     * @param approvalReference String，非敏感审批引用
     * @return void，无返回值
     */
    public void setApprovalReference(String approvalReference)
    {
        this.approvalReference = normalize(approvalReference);
    }

    /**
     * 获取生产容量评审引用。
     *
     * @return String，附件容量、并发和低水位对应的非敏感审批编号
     */
    public String getCapacityApprovalReference()
    {
        return capacityApprovalReference;
    }

    /**
     * 设置生产容量评审引用。
     *
     * @param capacityApprovalReference String，非敏感容量审批或评审编号
     * @return void，无返回值
     */
    public void setCapacityApprovalReference(String capacityApprovalReference)
    {
        this.capacityApprovalReference = normalize(capacityApprovalReference);
    }

    /**
     * 获取附件存储模式。
     *
     * @return AttachmentStorageMode，本地持久卷或共享文件系统
     */
    public AttachmentStorageMode getAttachmentStorageMode()
    {
        return attachmentStorageMode;
    }

    /**
     * 设置附件存储模式。
     *
     * @param attachmentStorageMode AttachmentStorageMode，不能为空
     * @return void，无返回值
     */
    public void setAttachmentStorageMode(AttachmentStorageMode attachmentStorageMode)
    {
        this.attachmentStorageMode = attachmentStorageMode;
    }

    /**
     * 获取共享附件卷标识。
     *
     * @return String，共享卷预置 .storage-id 的期望值
     */
    public String getAttachmentStorageId()
    {
        return attachmentStorageId;
    }

    /**
     * 设置共享附件卷标识。
     *
     * @param attachmentStorageId String，非敏感稳定存储标识
     * @return void，无返回值
     */
    public void setAttachmentStorageId(String attachmentStorageId)
    {
        this.attachmentStorageId = normalize(attachmentStorageId);
    }

    /**
     * 获取启动后首次刷新工作流运行指标的等待时间。
     *
     * @return Duration，允许为零且不超过十分钟的初始等待
     */
    public Duration getMetricsRefreshInitialDelay()
    {
        return metricsRefreshInitialDelay;
    }

    /**
     * 设置启动后首次刷新工作流运行指标的等待时间。
     *
     * @param metricsRefreshInitialDelay Duration，必须非负且不超过十分钟
     * @return void，无返回值
     */
    public void setMetricsRefreshInitialDelay(Duration metricsRefreshInitialDelay)
    {
        if (metricsRefreshInitialDelay == null || metricsRefreshInitialDelay.isNegative()
                || metricsRefreshInitialDelay.compareTo(Duration.ofMinutes(10)) > 0)
        {
            throw new IllegalArgumentException("工作流指标首次刷新等待必须处于0至10分钟范围");
        }
        this.metricsRefreshInitialDelay = metricsRefreshInitialDelay;
    }

    /**
     * 获取工作流数据库合并指标的固定刷新间隔。
     *
     * @return Duration，30 秒至 10 分钟的刷新间隔
     */
    public Duration getMetricsRefreshInterval()
    {
        return metricsRefreshInterval;
    }

    /**
     * 设置工作流数据库合并指标的固定刷新间隔。
     *
     * @param metricsRefreshInterval Duration，必须处于30秒至10分钟范围
     * @return void，无返回值
     */
    public void setMetricsRefreshInterval(Duration metricsRefreshInterval)
    {
        if (metricsRefreshInterval == null
                || metricsRefreshInterval.compareTo(Duration.ofSeconds(30)) < 0
                || metricsRefreshInterval.compareTo(Duration.ofMinutes(10)) > 0)
        {
            throw new IllegalArgumentException("工作流指标刷新间隔必须处于30秒至10分钟范围");
        }
        this.metricsRefreshInterval = metricsRefreshInterval;
    }

    /**
     * 获取 readiness 接受的工作流运行快照最大年龄。
     *
     * @return Duration，30 秒至 1 小时的最大年龄
     */
    public Duration getMetricsSnapshotMaxAge()
    {
        return metricsSnapshotMaxAge;
    }

    /**
     * 设置 readiness 接受的工作流运行快照最大年龄。
     *
     * @param metricsSnapshotMaxAge Duration，必须处于30秒至1小时范围
     * @return void，无返回值
     */
    public void setMetricsSnapshotMaxAge(Duration metricsSnapshotMaxAge)
    {
        if (metricsSnapshotMaxAge == null
                || metricsSnapshotMaxAge.compareTo(Duration.ofSeconds(30)) < 0
                || metricsSnapshotMaxAge.compareTo(Duration.ofHours(1)) > 0)
        {
            throw new IllegalArgumentException("工作流指标快照最大年龄必须处于30秒至1小时范围");
        }
        this.metricsSnapshotMaxAge = metricsSnapshotMaxAge;
    }

    /**
     * 去除外部配置首尾空白并把 null 归一为空字符串，便于跨字段启动校验。
     *
     * @param value String，可能为空的外部配置文本
     * @return String，非 null 的规范文本
     */
    private String normalize(String value)
    {
        return value == null ? "" : value.trim();
    }
}
