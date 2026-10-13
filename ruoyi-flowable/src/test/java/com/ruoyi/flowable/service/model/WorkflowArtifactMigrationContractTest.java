package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.extension.WorkflowConditionRouter;
import com.ruoyi.flowable.extension.WorkflowExtensionChecksum;
import com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport;

/** 与 PowerShell 迁移测试共享输入/输出制品，确认输出进入真实资源仓库及当前路由器。 */
class WorkflowArtifactMigrationContractTest
{
    @Test
    void migratedConditionsKeepPreciseRoutingAndCurrentManifestGate() throws Exception
    {
        try (var fixture = WorkflowFlowableEngineTestSupport.start("artifact-migration", Map.of()))
        {
            var repository = fixture.processEngine().getRepositoryService();
            var artifacts = new WorkflowDeploymentArtifactRepository(repository);
            String deploymentId = repository.createDeployment().addString("migration.bpmn20.xml", """
                    <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="migration">
                      <process id="migration" isExecutable="true"><startEvent id="start"/></process>
                    </definitions>
                    """).deploy().getId();
            String definitionId = repository.createProcessDefinitionQuery().deploymentId(deploymentId).singleResult().getId();
            String legacyId = repository.createDeployment().parentDeploymentId(deploymentId)
                    .category("APPROVAPLAT_WORKFLOW_ARTIFACTS")
                    .addString("approvaplat/manifest-v1.json", "{\"schemaVersion\":1}")
                    .addString("approvaplat/conditions-v1.json", resource("conditions-v1.json")).deploy().getId();
            assertThatThrownBy(() -> artifacts.selectRuntimeConditionRules(deploymentId, "migration", "gateway-token"))
                    .isInstanceOf(ServiceException.class).hasMessage("流程部署业务资源协议版本不受支持");
            repository.deleteDeployment(legacyId);
            String converted = resource("conditions-v2.json").replace("legacy-parent", deploymentId);
            repository.createDeployment().parentDeploymentId(deploymentId).category("APPROVAPLAT_WORKFLOW_ARTIFACTS")
                    .addString("approvaplat/manifest-v1.json", "{\"schemaVersion\":2}")
                    .addString("approvaplat/conditions-v2.json", converted).deploy();
            var rules = artifacts.selectRuntimeConditionRules(deploymentId, "migration", "gateway-token");
            assertThat(rules).hasSize(2).allSatisfy(rule -> {
                assertThat(rule.getRuleId()).isEqualTo(9007199254740993L);
                assertThat(rule.getDeployId()).isEqualTo(deploymentId);
                assertThat(rule.getSnapshotChecksum()).isEqualTo(WorkflowExtensionChecksum.sha256(
                        rule.getProcessKey(), rule.getGatewayId(), rule.getGatewayType(), rule.getGatewayToken(),
                        rule.getFlowId(), rule.getFlowName(), rule.getFlowToken(),
                        Boolean.TRUE.equals(rule.getDefaultFlow()) ? "1" : "0", rule.getRuleJson()));
            });
            var router = new WorkflowConditionRouter(repository, artifacts);
            var execution = mock(DelegateExecution.class);
            when(execution.getProcessDefinitionId()).thenReturn(definitionId);
            when(execution.hasVariable("amount")).thenReturn(true);
            when(execution.getVariable("amount")).thenReturn(new BigDecimal("9007199254740993.00000002"));
            assertThat(router.matches(execution, "gateway-token", "large-token")).isTrue();
            when(execution.getVariable("amount")).thenReturn(new BigDecimal("9007199254740993.00000001"));
            assertThat(router.matches(execution, "gateway-token", "large-token")).isFalse();
            assertThat(router.matches(execution, "gateway-token", "default-token")).isFalse();
        }
    }

    private static String resource(String name) throws Exception
    {
        try (var input = WorkflowArtifactMigrationContractTest.class.getResourceAsStream("/migration/" + name))
        {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
