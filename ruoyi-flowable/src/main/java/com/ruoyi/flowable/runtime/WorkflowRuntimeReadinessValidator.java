package com.ruoyi.flowable.runtime;

import java.util.regex.Pattern;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import com.ruoyi.flowable.config.WorkflowAttachmentProperties;
import com.ruoyi.flowable.config.WorkflowRuntimeProperties;
import com.ruoyi.flowable.config.WorkflowRuntimeProperties.AttachmentStorageMode;
import com.ruoyi.flowable.config.WorkflowRuntimeProperties.DeploymentTopology;
import com.ruoyi.flowable.config.WorkflowRuntimeProperties.ExecutorTopology;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentStorage;

/**
 * 在生产 ApplicationContext 完成创建前校验 executor、附件存储和容量组合。
 */
@Component
public class WorkflowRuntimeReadinessValidator implements InitializingBean
{
    /** 节点和共享卷标识只接受稳定 ASCII，避免控制字符进入日志、指标和标记文件。 */
    private static final Pattern STABLE_ID_PATTERN = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._-]{2,63}");

    /** 审批引用允许常见工单路径分隔符，但拒绝空白、占位符和控制字符。 */
    private static final Pattern APPROVAL_REFERENCE_PATTERN = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._:/-]{2,127}");

    private final Environment environment;
    private final WorkflowRuntimeProperties runtimeProperties;
    private final WorkflowAttachmentProperties attachmentProperties;
    private final WorkflowAttachmentStorage attachmentStorage;

    /**
     * 创建工作流生产启动门禁。
     *
     * @param environment Environment，读取 Flowable 引擎最终生效开关
     * @param runtimeProperties WorkflowRuntimeProperties，部署拓扑与运维批准配置
     * @param attachmentProperties WorkflowAttachmentProperties，附件磁盘低水位配置
     * @param attachmentStorage WorkflowAttachmentStorage，私有附件文件系统边界
     * @return 无返回值，构造后由 Spring 在单例初始化阶段执行
     */
    public WorkflowRuntimeReadinessValidator(Environment environment,
            WorkflowRuntimeProperties runtimeProperties,
            WorkflowAttachmentProperties attachmentProperties,
            WorkflowAttachmentStorage attachmentStorage)
    {
        this.environment = environment;
        this.runtimeProperties = runtimeProperties;
        this.attachmentProperties = attachmentProperties;
        this.attachmentStorage = attachmentStorage;
    }

    /**
     * 仅在生产门禁开启时执行 fail-closed 校验，拒绝 executor 误开和伪共享存储声明。
     *
     * @return void，任一配置组合或真实存储探针失败时阻止应用启动
     */
    @Override
    public void afterPropertiesSet()
    {
        if (!runtimeProperties.isProductionGateEnabled())
        {
            return;
        }

        requireProductionSchemaPolicy();
        requireExecutorTopology();
        requireAttachmentTopology();
        requireApprovedCapacity();

        String expectedStorageId = runtimeProperties.getAttachmentStorageMode()
                == AttachmentStorageMode.SHARED_FILESYSTEM
                ? runtimeProperties.getAttachmentStorageId() : null;
        attachmentStorage.verifyRuntimeReadiness(expectedStorageId,
                attachmentProperties.getMinFreeBytes());
    }

    /**
     * 强制生产 schema 只能由审计 SQL 管理，禁止应用启动时自动建表或升级。
     *
     * @return void，配置不是字符串 false 时抛出启动异常
     */
    private void requireProductionSchemaPolicy()
    {
        String schemaUpdate = environment.getProperty(
                "flowable.database-schema-update", "false");
        if (!"false".equalsIgnoreCase(schemaUpdate.trim()))
        {
            throw invalid("生产门禁要求 flowable.database-schema-update=false");
        }
    }

    /**
     * 核对 Flowable 实际开关与部署拓扑声明；这里只防误开，不证明集群唯一执行。
     *
     * @return void，executor 开关、拓扑或审批元数据不一致时抛出启动异常
     */
    private void requireExecutorTopology()
    {
        DeploymentTopology deploymentTopology = requireNonNull(
                runtimeProperties.getDeploymentTopology(), "应用部署拓扑不能为空");
        ExecutorTopology executorTopology = requireNonNull(
                runtimeProperties.getExecutorTopology(), "executor 拓扑不能为空");
        boolean asyncExecutorActive = environment.getProperty(
                "flowable.async-executor-activate", Boolean.class, false);
        boolean asyncHistoryExecutorActive = environment.getProperty(
                "flowable.async-history-executor-activate", Boolean.class, false);
        boolean anyExecutorActive = asyncExecutorActive || asyncHistoryExecutorActive;

        if (anyExecutorActive && executorTopology == ExecutorTopology.DISABLED)
        {
            throw invalid("Flowable executor 已启用但运行拓扑仍为 DISABLED");
        }
        if (!anyExecutorActive && executorTopology != ExecutorTopology.DISABLED)
        {
            throw invalid("executor 拓扑已启用但 async 与 async-history 执行器均未开启");
        }
        if (deploymentTopology == DeploymentTopology.SINGLE_NODE
                && executorTopology == ExecutorTopology.DATABASE_LOCKED_MULTI_NODE)
        {
            throw invalid("单节点部署不能声明数据库锁协调多节点 executor");
        }
        if (deploymentTopology == DeploymentTopology.MULTI_NODE
                && executorTopology == ExecutorTopology.SINGLE_NODE)
        {
            throw invalid("多节点部署不能启用 SINGLE_NODE executor");
        }
        if (executorTopology != ExecutorTopology.DISABLED
                || deploymentTopology == DeploymentTopology.MULTI_NODE)
        {
            requireStableId(runtimeProperties.getNodeId(), "工作流节点标识");
            requireApprovalReference(runtimeProperties.getApprovalReference());
        }
    }

    /**
     * 校验共享存储声明；本校验不会把单机探针当作多节点证据。
     *
     * @return void，共享卷标识或多节点存储不符合生产约束时抛出启动异常
     */
    private void requireAttachmentTopology()
    {
        AttachmentStorageMode storageMode = requireNonNull(
                runtimeProperties.getAttachmentStorageMode(), "附件存储模式不能为空");
        if (storageMode == AttachmentStorageMode.SHARED_FILESYSTEM)
        {
            requireStableId(runtimeProperties.getAttachmentStorageId(), "共享附件存储标识");
        }
        if (runtimeProperties.getDeploymentTopology() == DeploymentTopology.MULTI_NODE)
        {
            if (storageMode != AttachmentStorageMode.SHARED_FILESYSTEM)
            {
                throw invalid("多节点部署必须使用经批准的共享附件文件系统");
            }
        }
    }

    /**
     * 校验生产附件容量和指标刷新频率已经过独立评审，且各级容量不会自相矛盾。
     *
     * @return void，审批引用缺失、容量倒置或抓取频率过高时抛出启动异常
     */
    private void requireApprovedCapacity()
    {
        requireApprovalReference(runtimeProperties.getCapacityApprovalReference(),
                "生产附件容量必须提供真实审批引用");
        if (attachmentProperties.getMaxTemporaryBytes()
                < attachmentProperties.getMaxSize())
        {
            throw invalid("单用户临时附件容量不能小于单文件上限");
        }
        if (runtimeProperties.getMetricsSnapshotMaxAge().compareTo(
                runtimeProperties.getMetricsRefreshInterval()) <= 0)
        {
            throw invalid("工作流指标快照最大年龄必须大于刷新间隔");
        }
        attachmentProperties.validateCleanupRetryBackoff();
    }

    /**
     * 校验节点或共享卷标识为稳定非占位 ASCII。
     *
     * @param value String，待校验标识
     * @param label String，异常消息中的业务字段名
     * @return void，格式不合法时抛出启动异常
     */
    private void requireStableId(String value, String label)
    {
        if (!StringUtils.hasText(value) || !STABLE_ID_PATTERN.matcher(value).matches())
        {
            throw invalid(label + "必须为3至64位稳定 ASCII");
        }
    }

    /**
     * 校验已批准拓扑的非敏感工单引用，拒绝模板占位文本进入生产。
     *
     * @param value String，审批单、变更单或发布单引用
     * @return void，格式不合法时抛出启动异常
     */
    private void requireApprovalReference(String value)
    {
        requireApprovalReference(value, "executor 或多节点拓扑必须提供真实审批引用");
    }

    /**
     * 校验非敏感审批引用并使用调用方指定的稳定错误消息。
     *
     * @param value String，审批单、变更单或容量评审引用
     * @param message String，校验失败时允许写入启动日志的稳定消息
     * @return void，格式不合法时抛出启动异常
     */
    private void requireApprovalReference(String value, String message)
    {
        if (!StringUtils.hasText(value)
                || !APPROVAL_REFERENCE_PATTERN.matcher(value).matches()
                || value.contains("APPROVED") || value.contains("<") || value.contains(">"))
        {
            throw invalid(message);
        }
    }

    /**
     * 返回非空配置枚举，避免绑定错误在后续分支产生模糊空指针异常。
     *
     * @param value T，待校验配置值
     * @param message String，缺失时的稳定错误消息
     * @param <T> 配置枚举类型
     * @return T，原始非空配置值
     */
    private <T> T requireNonNull(T value, String message)
    {
        if (value == null)
        {
            throw invalid(message);
        }
        return value;
    }

    /**
     * 创建统一生产启动失败异常，不输出路径、凭据或外部配置正文。
     *
     * @param message String，可安全记录的配置错误说明
     * @return IllegalStateException，阻止 ApplicationContext 完成启动
     */
    private IllegalStateException invalid(String message)
    {
        return new IllegalStateException(message);
    }
}
