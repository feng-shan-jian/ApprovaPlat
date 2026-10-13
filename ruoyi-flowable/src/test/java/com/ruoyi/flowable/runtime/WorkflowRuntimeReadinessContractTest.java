package com.ruoyi.flowable.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import com.ruoyi.flowable.config.WorkflowAttachmentProperties;
import com.ruoyi.flowable.config.WorkflowRuntimeProperties;
import com.ruoyi.flowable.config.WorkflowRuntimeProperties.AttachmentStorageMode;
import com.ruoyi.flowable.config.WorkflowRuntimeProperties.DeploymentTopology;
import com.ruoyi.flowable.config.WorkflowRuntimeProperties.ExecutorTopology;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentStorage;

/** Configuration gates and real local storage probes; no Spring context or Flowable engine. */
class WorkflowRuntimeReadinessContractTest
{
    @TempDir
    Path profile;

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidProductionConfigurations")
    void productionOnlyChecksHaveAnExplicitGateOffCounterpart(String name,
            Consumer<Fixture> configure, String message)
    {
        Fixture fixture = new Fixture(profile);
        configure.accept(fixture);
        fixture.runtime.setProductionGateEnabled(false);
        assertThatCode(fixture.validator::afterPropertiesSet).doesNotThrowAnyException();
        fixture.runtime.setProductionGateEnabled(true);
        assertThatThrownBy(fixture.validator::afterPropertiesSet)
                .isInstanceOfAny(IllegalStateException.class, IllegalArgumentException.class)
                .hasMessageContaining(message);
    }

    static Stream<Arguments> invalidProductionConfigurations()
    {
        return Stream.of(
                invalid("schema update", f -> f.env.setProperty("flowable.database-schema-update", "true"), "database-schema-update=false"),
                invalid("async without topology", f -> f.async(), "仍为 DISABLED"),
                invalid("history without topology", f -> f.env.setProperty("flowable.async-history-executor-activate", "true"), "仍为 DISABLED"),
                invalid("topology without either executor", f -> f.runtime.setExecutorTopology(ExecutorTopology.SINGLE_NODE), "均未开启"),
                invalid("single deployment with cluster executor", f -> { f.async(); f.runtime.setExecutorTopology(ExecutorTopology.DATABASE_LOCKED_MULTI_NODE); }, "单节点部署不能"),
                invalid("multi deployment with single executor", f -> { f.async(); f.multi(); f.runtime.setExecutorTopology(ExecutorTopology.SINGLE_NODE); }, "多节点部署不能"),
                invalid("missing deployment", f -> f.runtime.setDeploymentTopology(null), "应用部署拓扑不能为空"),
                invalid("missing executor topology", f -> f.runtime.setExecutorTopology(null), "executor 拓扑不能为空"),
                invalid("missing node", f -> { f.enabledSingle(); f.runtime.setNodeId(""); }, "工作流节点标识"),
                invalid("unstable node", f -> { f.enabledSingle(); f.runtime.setNodeId("node\nsecret"); }, "工作流节点标识"),
                invalid("missing topology approval", f -> { f.enabledSingle(); f.runtime.setApprovalReference(""); }, "拓扑必须提供真实审批引用"),
                invalid("placeholder topology approval", f -> { f.enabledSingle(); f.runtime.setApprovalReference("APPROVED-123"); }, "拓扑必须提供真实审批引用"),
                invalid("missing attachment mode", f -> f.runtime.setAttachmentStorageMode(null), "附件存储模式不能为空"),
                invalid("shared storage without identity", f -> f.runtime.setAttachmentStorageMode(AttachmentStorageMode.SHARED_FILESYSTEM), "共享附件存储标识"),
                invalid("multi deployment with local storage", Fixture::multi, "多节点部署必须使用"),
                invalid("missing capacity approval", f -> f.runtime.setCapacityApprovalReference(""), "生产附件容量必须提供"),
                invalid("placeholder capacity approval", f -> f.runtime.setCapacityApprovalReference("<capacity>"), "生产附件容量必须提供"),
                invalid("temporary capacity inversion", f -> f.attachments.setMaxTemporaryBytes(1), "临时附件容量不能小于"),
                invalid("snapshot equals refresh", f -> f.runtime.setMetricsSnapshotMaxAge(Duration.ofMinutes(1)), "最大年龄必须大于"),
                invalid("snapshot less than refresh", f -> f.runtime.setMetricsSnapshotMaxAge(Duration.ofSeconds(30)), "最大年龄必须大于"),
                invalid("cleanup retry inversion", f -> { f.attachments.setCleanupRetryInitialDelay(Duration.ofHours(2)); f.attachments.setCleanupRetryMaxDelay(Duration.ofHours(1)); }, "最大"));
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void requiringOrdinaryAsyncIsIndependentOfProductionGateAndHistoryExecutor(boolean gate)
    {
        Fixture fixture = new Fixture(profile);
        fixture.runtime.setProductionGateEnabled(gate);
        fixture.env.setProperty("flowable.runtime.require-async-executor", "true");
        fixture.env.setProperty("flowable.async-history-executor-activate", "true");
        assertThatThrownBy(fixture.validator::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("必须配置普通 async executor");
        fixture.enabledSingle();
        assertThatCode(fixture.validator::afterPropertiesSet).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = { "single-disabled", "single-async", "single-history", "multi-disabled", "multi-async" })
    void validTopologyUsesTheActualStorageProbeAndLeavesNoProbeFiles(String topology) throws Exception
    {
        Fixture fixture = new Fixture(profile);
        fixture.runtime.setProductionGateEnabled(true);
        if (topology.equals("single-async")) fixture.enabledSingle();
        if (topology.equals("single-history"))
        {
            fixture.env.setProperty("flowable.async-history-executor-activate", "true");
            fixture.runtime.setExecutorTopology(ExecutorTopology.SINGLE_NODE);
        }
        if (topology.startsWith("multi"))
        {
            fixture.multi();
            fixture.shared();
            if (topology.equals("multi-async"))
            {
                fixture.async();
                fixture.runtime.setExecutorTopology(ExecutorTopology.DATABASE_LOCKED_MULTI_NODE);
            }
            Files.writeString(fixture.marker(), "shared-contract");
        }
        fixture.validator.afterPropertiesSet();
        try (Stream<Path> files = Files.walk(profile))
        {
            assertThat(files.filter(Files::isRegularFile).toList())
                    .allMatch(path -> path.equals(fixture.marker()));
        }
    }

    @Test
    void sharedStorageMustAlreadyCarryTheMatchingMarkerAndCanRecover() throws Exception
    {
        Fixture fixture = new Fixture(profile);
        fixture.runtime.setProductionGateEnabled(true);
        fixture.multi();
        fixture.shared();
        assertThatThrownBy(fixture.validator::afterPropertiesSet).hasMessageContaining("存储就绪校验失败");
        assertThat(fixture.marker()).doesNotExist();
        Files.writeString(fixture.marker(), "another-volume");
        assertThatThrownBy(fixture.validator::afterPropertiesSet).hasMessageContaining("存储就绪校验失败");
        Files.writeString(fixture.marker(), "shared-contract");
        assertThatCode(fixture.validator::afterPropertiesSet).doesNotThrowAnyException();
    }

    private static Arguments invalid(String name, Consumer<Fixture> configure, String message)
    {
        return Arguments.of(name, configure, message);
    }

    private static class Fixture
    {
        final MockEnvironment env = new MockEnvironment();
        final WorkflowRuntimeProperties runtime = new WorkflowRuntimeProperties();
        final WorkflowAttachmentProperties attachments = new WorkflowAttachmentProperties();
        final Path profile;
        final WorkflowRuntimeReadinessValidator validator;

        Fixture(Path profile)
        {
            this.profile = profile;
            runtime.setCapacityApprovalReference("capacity/contract-123");
            runtime.setApprovalReference("change/contract-123");
            runtime.setNodeId("node-contract");
            attachments.setMaxSize(128);
            attachments.setMaxTemporaryBytes(1024);
            attachments.setMinFreeBytes(1);
            validator = new WorkflowRuntimeReadinessValidator(env, runtime, attachments,
                    new WorkflowAttachmentStorage(profile, attachments.getMaxSize()));
        }

        void async() { env.setProperty("flowable.async-executor-activate", "true"); }
        void enabledSingle() { async(); runtime.setExecutorTopology(ExecutorTopology.SINGLE_NODE); }
        void multi() { runtime.setDeploymentTopology(DeploymentTopology.MULTI_NODE); }
        void shared()
        {
            runtime.setAttachmentStorageMode(AttachmentStorageMode.SHARED_FILESYSTEM);
            runtime.setAttachmentStorageId("shared-contract");
        }
        Path marker()
        {
            return profile.resolve(WorkflowAttachmentStorage.PRIVATE_DIRECTORY_NAME)
                    .resolve(WorkflowAttachmentStorage.STORAGE_ID_MARKER_NAME);
        }
    }
}
