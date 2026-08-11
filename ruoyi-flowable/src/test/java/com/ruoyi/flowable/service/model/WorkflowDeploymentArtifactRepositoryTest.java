package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.DeploymentBuilder;
import org.flowable.engine.repository.DeploymentQuery;
import org.flowable.engine.repository.ProcessDefinitionQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfDeployCallActivitySnapshot;
import com.ruoyi.flowable.domain.WfDeployConditionRule;
import com.ruoyi.flowable.domain.WfDeployControlledLoop;
import com.ruoyi.flowable.domain.WfDeployDmnSnapshot;
import com.ruoyi.flowable.domain.WfDeployExtensionSnapshot;
import com.ruoyi.flowable.domain.WfDeployForm;
import com.ruoyi.flowable.domain.WfDeployParticipantRule;
import com.ruoyi.flowable.domain.WfDeployTaskSla;

/**
 * Flowable 子部署业务资源仓库的序列化、查询和生命周期契约测试。
 */
class WorkflowDeploymentArtifactRepositoryTest
{
    private RepositoryService repositoryService;
    private DeploymentQuery deploymentQuery;
    private DeploymentBuilder deploymentBuilder;
    private ProcessDefinitionQuery processDefinitionQuery;
    private WorkflowDeploymentArtifactRepository repository;

    /**
     * 创建支持父子部署查询、资源部署和流程定义计数的 Flowable 公共 API 替身。
     *
     * @return void，初始化完成后每个测试拥有独立仓库和依赖
     */
    @BeforeEach
    void setUp()
    {
        repositoryService = mock(RepositoryService.class);
        deploymentQuery = mock(DeploymentQuery.class);
        deploymentBuilder = mock(DeploymentBuilder.class);
        processDefinitionQuery = mock(ProcessDefinitionQuery.class);

        when(repositoryService.createDeploymentQuery()).thenReturn(deploymentQuery);
        when(deploymentQuery.parentDeploymentId(anyString())).thenReturn(deploymentQuery);
        when(deploymentQuery.deploymentCategory(anyString())).thenReturn(deploymentQuery);
        when(deploymentQuery.orderByDeploymentTime()).thenReturn(deploymentQuery);
        when(deploymentQuery.orderByDeploymentId()).thenReturn(deploymentQuery);
        when(deploymentQuery.asc()).thenReturn(deploymentQuery);
        when(deploymentQuery.list()).thenReturn(List.of());

        when(repositoryService.createDeployment()).thenReturn(deploymentBuilder);
        when(deploymentBuilder.name(anyString())).thenReturn(deploymentBuilder);
        when(deploymentBuilder.key(anyString())).thenReturn(deploymentBuilder);
        when(deploymentBuilder.category(anyString())).thenReturn(deploymentBuilder);
        when(deploymentBuilder.parentDeploymentId(anyString())).thenReturn(deploymentBuilder);
        when(deploymentBuilder.addBytes(anyString(), any(byte[].class)))
                .thenReturn(deploymentBuilder);

        when(repositoryService.createProcessDefinitionQuery()).thenReturn(processDefinitionQuery);
        when(processDefinitionQuery.deploymentId(anyString())).thenReturn(processDefinitionQuery);
        when(processDefinitionQuery.count()).thenReturn(0L);
        repository = new WorkflowDeploymentArtifactRepository(repositoryService);
    }

    /**
     * 验证 8 类快照作为一个父子关联的 Flowable 部署原子写入，并绑定父部署主键。
     *
     * @return void，资源数、资源名、父子关系或快照绑定不完整时测试失败
     */
    @Test
    void persistsEightArtifactFamiliesAsSingleChildDeployment()
    {
        Deployment deployed = mock(Deployment.class);
        when(deployed.getId()).thenReturn("artifact-1");
        when(deploymentBuilder.deploy()).thenReturn(deployed);
        WorkflowDeploymentArtifacts artifacts = artifacts();

        assertThat(repository.persist("deployment-1", artifacts)).isEqualTo("artifact-1");

        verify(deploymentBuilder).key("approvaplat-artifacts:deployment-1");
        verify(deploymentBuilder).category("APPROVAPLAT_WORKFLOW_ARTIFACTS");
        verify(deploymentBuilder).parentDeploymentId("deployment-1");
        ArgumentCaptor<String> resourceNames = ArgumentCaptor.forClass(String.class);
        verify(deploymentBuilder, org.mockito.Mockito.times(9))
                .addBytes(resourceNames.capture(), any(byte[].class));
        assertThat(resourceNames.getAllValues()).containsExactly(
                "approvaplat/manifest-v1.json",
                "approvaplat/forms-v1.json",
                "approvaplat/conditions-v1.json",
                "approvaplat/controlled-loops-v1.json",
                "approvaplat/participants-v1.json",
                "approvaplat/extensions-v1.json",
                "approvaplat/dmn-v1.json",
                "approvaplat/call-activities-v1.json",
                "approvaplat/task-sla-v1.json");
        assertThat(artifacts.forms().get(0).getDeployId()).isEqualTo("deployment-1");
        assertThat(artifacts.conditionRules().get(0).getDeployId()).isEqualTo("deployment-1");
        assertThat(artifacts.controlledLoops().get(0).getDeployId()).isEqualTo("deployment-1");
        assertThat(artifacts.participantRules().get(0).getRuleId()).isPositive();
        assertThat(artifacts.extensionSnapshots().get(0).getDeployId()).isEqualTo("deployment-1");
        assertThat(artifacts.dmnSnapshots().get(0).getDeployId()).isEqualTo("deployment-1");
        assertThat(artifacts.callActivitySnapshots().get(0).getDeployId())
                .isEqualTo("deployment-1");
        assertThat(artifacts.taskSlaSnapshots().get(0).getDeploymentId())
                .isEqualTo("deployment-1");
    }

    /**
     * 验证正式资源可按原业务查询语义读取，并支持表单、扩展和 DMN 删除保护统计。
     *
     * @return void，任一资源无法解析、过滤或参与引用保护时测试失败
     * @throws Exception 构造 JSON 资源失败
     */
    @Test
    void readsAllArtifactFamiliesAndProtectsReferencedCatalogData() throws Exception
    {
        Deployment artifactDeployment = mock(Deployment.class);
        when(artifactDeployment.getId()).thenReturn("artifact-1");
        when(deploymentQuery.list()).thenReturn(List.of(artifactDeployment));
        Map<String, byte[]> resources = serializedResources(artifacts());
        when(repositoryService.getResourceAsStream(eq("artifact-1"), anyString()))
                .thenAnswer(invocation ->
                {
                    byte[] bytes = resources.get(invocation.getArgument(1, String.class));
                    return bytes == null ? null : new ByteArrayInputStream(bytes);
                });

        assertThat(repository.selectForms("deployment-1"))
                .singleElement().extracting(WfDeployForm::getFormId).isEqualTo(11L);
        assertThat(repository.selectRuntimeConditionRules(
                "deployment-1", "expense", "gateway-token"))
                .singleElement().extracting(WfDeployConditionRule::getFlowId)
                .isEqualTo("approved");
        assertThat(repository.selectControlledLoop(
                "deployment-1", "expense", "review")).isNotNull();
        assertThat(repository.selectTaskParticipantRule(
                "deployment-1", "expense", "review")).isNotNull();
        assertThat(repository.selectExtensionSnapshot(
                "deployment-1", "expense", "service-task")).isNotNull();
        assertThat(repository.selectDmnSnapshots("deployment-1"))
                .singleElement().extracting(WfDeployDmnSnapshot::getDecisionKey)
                .isEqualTo("expenseDecision");
        assertThat(repository.selectCallActivitySnapshots("deployment-1"))
                .singleElement().extracting(WfDeployCallActivitySnapshot::getTargetProcessKey)
                .isEqualTo("childProcess");
        assertThat(repository.selectTaskSlaSnapshot(
                "deployment-1", "expense", "review")).isNotNull();
        assertThat(repository.hasFormReference(List.of(11L))).isTrue();
        assertThat(repository.countExtensionVersionReferences(List.of(22L))).isEqualTo(1);
        assertThat(repository.countDmnSourceReferences("dmn-source-1")).isEqualTo(1L);

        assertThat(repository.delete("deployment-1")).isEqualTo(1);
        verify(repositoryService).deleteDeployment("artifact-1");
    }

    /**
     * 验证同一资源族的重复自然键会在创建 Flowable 子部署前稳定拒绝。
     *
     * @return void，重复快照进入持久化或错误码不稳定时测试失败
     */
    @Test
    void rejectsDuplicateNaturalKeysBeforeCreatingChildDeployment()
    {
        WfDeployForm first = form();
        WfDeployForm duplicate = form();
        WorkflowDeploymentArtifacts artifacts = new WorkflowDeploymentArtifacts(
                List.of(first, duplicate), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());

        assertThatThrownBy(() -> repository.persist("deployment-1", artifacts))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getMessage()).isEqualTo("部署表单快照自然键重复");
                });
        verify(repositoryService, never()).createDeployment();
    }

    /**
     * 构造包含 8 类资源各一条记录的代表性部署快照。
     *
     * @return WorkflowDeploymentArtifacts，可用于持久化和序列化测试的完整资源集合
     */
    private WorkflowDeploymentArtifacts artifacts()
    {
        WfDeployConditionRule condition = new WfDeployConditionRule();
        condition.setProcessKey("expense");
        condition.setGatewayId("approval-gateway");
        condition.setGatewayToken("gateway-token");
        condition.setFlowId("approved");

        WfDeployControlledLoop loop = new WfDeployControlledLoop();
        loop.setProcessKey("expense");
        loop.setActivityId("review");

        WfDeployParticipantRule participant = new WfDeployParticipantRule();
        participant.setProcessKey("expense");
        participant.setRuleScope("TASK");
        participant.setActivityId("review");
        participant.setChecksum("a".repeat(64));

        WfDeployExtensionSnapshot extension = new WfDeployExtensionSnapshot();
        extension.setProcessKey("expense");
        extension.setElementId("service-task");
        extension.setExtensionVersionId(22L);

        WfDeployDmnSnapshot dmn = new WfDeployDmnSnapshot();
        dmn.setProcessKey("expense");
        dmn.setElementId("decision-task");
        dmn.setDecisionKey("expenseDecision");
        dmn.setSourceDeploymentId("dmn-source-1");

        WfDeployCallActivitySnapshot callActivity = new WfDeployCallActivitySnapshot();
        callActivity.setProcessKey("expense");
        callActivity.setElementId("call-child");
        callActivity.setTargetProcessKey("childProcess");

        WfDeployTaskSla sla = new WfDeployTaskSla();
        sla.setProcessKey("expense");
        sla.setTaskDefinitionKey("review");

        return new WorkflowDeploymentArtifacts(
                List.of(form()), List.of(condition), List.of(loop), List.of(participant),
                List.of(extension), List.of(dmn), List.of(callActivity), List.of(sla));
    }

    /**
     * 构造一条模板表单不可变快照。
     *
     * @return WfDeployForm，具有稳定自然键和正式模板主键的表单快照
     */
    private WfDeployForm form()
    {
        WfDeployForm form = new WfDeployForm();
        form.setSourceType("TEMPLATE");
        form.setFormId(11L);
        form.setFormKey("expense-form");
        form.setNodeKey("start");
        form.setContent("{}");
        return form;
    }

    /**
     * 将完整资源集合编码为仓库正式资源名和 JSON 字节。
     *
     * @param artifacts WorkflowDeploymentArtifacts，待编码的 8 类资源
     * @return Map&lt;String,byte[]&gt;，可由 RepositoryService 流式读取的资源集合
     * @throws Exception Jackson 序列化失败
     */
    private Map<String, byte[]> serializedResources(WorkflowDeploymentArtifacts artifacts)
            throws Exception
    {
        JsonMapper mapper = JsonMapper.shared();
        Map<String, byte[]> resources = new LinkedHashMap<>();
        resources.put("approvaplat/manifest-v1.json",
                mapper.writeValueAsBytes(Map.of("schemaVersion", 1)));
        resources.put("approvaplat/forms-v1.json", mapper.writeValueAsBytes(artifacts.forms()));
        resources.put("approvaplat/conditions-v1.json",
                mapper.writeValueAsBytes(artifacts.conditionRules()));
        resources.put("approvaplat/controlled-loops-v1.json",
                mapper.writeValueAsBytes(artifacts.controlledLoops()));
        resources.put("approvaplat/participants-v1.json",
                mapper.writeValueAsBytes(artifacts.participantRules()));
        resources.put("approvaplat/extensions-v1.json",
                mapper.writeValueAsBytes(artifacts.extensionSnapshots()));
        resources.put("approvaplat/dmn-v1.json",
                mapper.writeValueAsBytes(artifacts.dmnSnapshots()));
        resources.put("approvaplat/call-activities-v1.json",
                mapper.writeValueAsBytes(artifacts.callActivitySnapshots()));
        resources.put("approvaplat/task-sla-v1.json",
                mapper.writeValueAsBytes(artifacts.taskSlaSnapshots()));
        return resources;
    }
}
