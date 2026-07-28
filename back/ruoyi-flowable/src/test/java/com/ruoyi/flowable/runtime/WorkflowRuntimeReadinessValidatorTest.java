package com.ruoyi.flowable.runtime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import com.ruoyi.flowable.config.WorkflowAttachmentProperties;
import com.ruoyi.flowable.config.WorkflowRuntimeProperties;
import com.ruoyi.flowable.config.WorkflowRuntimeProperties.AttachmentStorageMode;
import com.ruoyi.flowable.config.WorkflowRuntimeProperties.DeploymentTopology;
import com.ruoyi.flowable.config.WorkflowRuntimeProperties.ExecutorTopology;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentStorage;

class WorkflowRuntimeReadinessValidatorTest
{
    /**
     * 验证开发和普通单测默认不要求生产审批字段，也不会执行生产文件系统探针。
     *
     * @return void，未开启生产门禁仍修改或探测运行环境时测试失败
     */
    @Test
    void leavesNonProductionContextsUntouched()
    {
        WorkflowAttachmentStorage storage = mock(WorkflowAttachmentStorage.class);
        WorkflowRuntimeReadinessValidator validator = validator(
                new MockEnvironment(), new WorkflowRuntimeProperties(), storage);

        assertThatCode(validator::afterPropertiesSet).doesNotThrowAnyException();
        verifyNoInteractions(storage);
    }

    /**
     * 验证 executor 拓扑非禁用时至少一个实际 executor 开关必须开启。
     *
     * @return void，只有拓扑声明但两个执行器均关闭仍可启动时测试失败
     */
    @Test
    void rejectsEnabledExecutorTopologyWhenBothExecutorsAreOff()
    {
        WorkflowRuntimeProperties properties = productionProperties();
        properties.setExecutorTopology(ExecutorTopology.SINGLE_NODE);
        properties.setNodeId("node-a");
        properties.setApprovalReference("OPS-123");

        assertThatThrownBy(() -> validator(new MockEnvironment(), properties,
                mock(WorkflowAttachmentStorage.class)).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("executor 拓扑已启用但 async 与 async-history 执行器均未开启");
    }

    /**
     * 验证任一 executor 实际开启但拓扑仍为 DISABLED 时 fail-closed。
     *
     * @return void，生产 executor 可在无批准拓扑下误开时测试失败
     */
    @Test
    void rejectsActiveExecutorWithoutTopologyApproval()
    {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("flowable.async-executor-activate", "true");
        WorkflowRuntimeProperties properties = productionProperties();

        assertThatThrownBy(() -> validator(environment, properties,
                mock(WorkflowAttachmentStorage.class)).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Flowable executor 已启用但运行拓扑仍为 DISABLED");
    }

    /**
     * 验证 async-history 单独开启也必须通过单节点拓扑、节点和审批引用门禁。
     *
     * @return void，合法配置未执行真实存储探针时测试失败
     */
    @Test
    void acceptsApprovedSingleNodeAsyncHistoryTopology()
    {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("flowable.async-history-executor-activate", "true");
        WorkflowRuntimeProperties properties = productionProperties();
        properties.setExecutorTopology(ExecutorTopology.SINGLE_NODE);
        properties.setNodeId("node-a");
        properties.setApprovalReference("OPS-123");
        WorkflowAttachmentStorage storage = mock(WorkflowAttachmentStorage.class);

        assertThatCode(() -> validator(environment, properties, storage)
                .afterPropertiesSet()).doesNotThrowAnyException();
        verify(storage).verifyRuntimeReadiness(null,
                new WorkflowAttachmentProperties().getMinFreeBytes());
    }

    /**
     * 验证多节点部署不能把节点本地目录声明为共享附件闭环。
     *
     * @return void，本地存储在多节点配置下仍可启动时测试失败
     */
    @Test
    void rejectsLocalAttachmentStorageForMultiNodeDeployment()
    {
        WorkflowRuntimeProperties properties = productionProperties();
        properties.setDeploymentTopology(DeploymentTopology.MULTI_NODE);
        properties.setNodeId("node-a");
        properties.setApprovalReference("OPS-123");

        assertThatThrownBy(() -> validator(new MockEnvironment(), properties,
                mock(WorkflowAttachmentStorage.class)).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("多节点部署必须使用经批准的共享附件文件系统");
    }

    /**
     * 验证合法多节点声明会把批准 storage-id 交给只读共享卷探针。
     *
     * @return void，共享标识未参与启动校验或被应用替换时测试失败
     */
    @Test
    void validatesPreprovisionedStorageIdForApprovedMultiNodeDeployment()
    {
        WorkflowRuntimeProperties properties = productionProperties();
        properties.setDeploymentTopology(DeploymentTopology.MULTI_NODE);
        properties.setNodeId("node-a");
        properties.setApprovalReference("OPS-123");
        properties.setAttachmentStorageMode(AttachmentStorageMode.SHARED_FILESYSTEM);
        properties.setAttachmentStorageId("storage-prod-a");
        WorkflowAttachmentStorage storage = mock(WorkflowAttachmentStorage.class);

        assertThatCode(() -> validator(new MockEnvironment(), properties, storage)
                .afterPropertiesSet()).doesNotThrowAnyException();
        verify(storage).verifyRuntimeReadiness("storage-prod-a",
                new WorkflowAttachmentProperties().getMinFreeBytes());
    }

    /**
     * 验证生产门禁同时阻止 Flowable 自动创建或升级 schema。
     *
     * @return void，database-schema-update 漂移仍可启动时测试失败
     */
    @Test
    void rejectsAutomaticSchemaUpdateInProduction()
    {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("flowable.database-schema-update", "true");

        assertThatThrownBy(() -> validator(environment, productionProperties(),
                mock(WorkflowAttachmentStorage.class)).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("生产门禁要求 flowable.database-schema-update=false");
    }

    /**
     * 验证生产门禁即使关闭 executor，也必须提供独立容量评审引用。
     *
     * @return void，未批准的附件容量和低水位可直接进入生产时测试失败
     */
    @Test
    void rejectsProductionCapacityWithoutApprovalReference()
    {
        WorkflowRuntimeProperties properties = productionProperties();
        properties.setCapacityApprovalReference("");

        assertThatThrownBy(() -> validator(new MockEnvironment(), properties,
                mock(WorkflowAttachmentStorage.class)).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("生产附件容量必须提供真实审批引用");
    }

    /**
     * 验证 readiness 最大快照年龄必须严格大于采集间隔，为调度抖动和单次失败留出空间。
     *
     * @return void，快照会在正常刷新周期内提前失效时测试失败
     */
    @Test
    void rejectsSnapshotMaxAgeNotGreaterThanRefreshInterval()
    {
        WorkflowRuntimeProperties properties = productionProperties();
        properties.setMetricsRefreshInterval(Duration.ofMinutes(3));
        properties.setMetricsSnapshotMaxAge(Duration.ofMinutes(3));

        assertThatThrownBy(() -> validator(new MockEnvironment(), properties,
                mock(WorkflowAttachmentStorage.class)).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("工作流指标快照最大年龄必须大于刷新间隔");
    }

    /**
     * 创建已开启生产门禁且保持 executor 和本地存储默认关闭态的配置。
     *
     * @return WorkflowRuntimeProperties，供各非法或合法组合测试继续覆盖
     */
    private WorkflowRuntimeProperties productionProperties()
    {
        WorkflowRuntimeProperties properties = new WorkflowRuntimeProperties();
        properties.setProductionGateEnabled(true);
        properties.setCapacityApprovalReference("CAP-123");
        return properties;
    }

    /**
     * 创建被测启动校验器并使用正式附件低水位默认值。
     *
     * @param environment MockEnvironment，测试最终 Flowable 开关
     * @param properties WorkflowRuntimeProperties，运行拓扑配置
     * @param storage WorkflowAttachmentStorage，存储探针替身
     * @return WorkflowRuntimeReadinessValidator，被测启动门禁
     */
    private WorkflowRuntimeReadinessValidator validator(MockEnvironment environment,
            WorkflowRuntimeProperties properties, WorkflowAttachmentStorage storage)
    {
        return new WorkflowRuntimeReadinessValidator(environment, properties,
                new WorkflowAttachmentProperties(), storage);
    }
}
