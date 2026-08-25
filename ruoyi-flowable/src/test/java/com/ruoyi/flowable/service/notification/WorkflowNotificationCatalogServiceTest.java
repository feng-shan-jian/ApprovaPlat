package com.ruoyi.flowable.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.Supplier;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.DeploymentBuilder;
import org.flowable.engine.repository.ProcessDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.framework.web.service.PermissionService;

/**
 * 使用真实 Flowable 8 内存引擎验证审批通知流程与递归 UserTask 目录。
 */
class WorkflowNotificationCatalogServiceTest
{
    private ProcessEngine processEngine;
    private RepositoryService repositoryService;
    private PermissionService permissionService;
    private WorkflowNotificationCatalogService catalogService;

    /**
     * 创建独立真实 Flowable 引擎，仅模拟统一执行边界和实时权限结果。
     *
     * @return void，无返回值
     */
    @BeforeEach
    @SuppressWarnings({ "rawtypes", "unchecked" })
    void setUp()
    {
        processEngine = ProcessEngineConfiguration
                .createStandaloneInMemProcessEngineConfiguration()
                .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
                .buildProcessEngine();
        repositoryService = processEngine.getRepositoryService();
        permissionService = mock(PermissionService.class);
        when(permissionService.hasPermi("workflow:notification:manage")).thenReturn(true);
        WorkflowEngineOperations engineOperations = mock(WorkflowEngineOperations.class);
        when(engineOperations.read(any(Supplier.class))).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        catalogService = new WorkflowNotificationCatalogService(repositoryService,
                engineOperations, permissionService);
    }

    /**
     * 关闭真实引擎并销毁当前测试 H2 schema。
     *
     * @return void，无返回值
     */
    @AfterEach
    void tearDown()
    {
        if (processEngine != null)
        {
            processEngine.close();
        }
    }

    /**
     * 验证目录只返回最新激活定义，并递归提取嵌套 SubProcess 中的 UserTask。
     *
     * @return void，流程版本、节点类型或递归范围漂移时测试失败
     */
    @Test
    void listsLatestActiveDefinitionsAndRecursiveUserTasks()
    {
        deploy(null, "approval.bpmn20.xml", approvalV1());
        deploy(null, "approval.bpmn20.xml", approvalV2());
        ProcessDefinition disabled = deploy(null, "disabled.bpmn20.xml", disabledProcess());
        repositoryService.suspendProcessDefinitionById(disabled.getId());

        assertThat(catalogService.processes()).containsExactly(
                new WorkflowNotificationCatalogService.ProcessOption(
                        "approval", "审批流程 V2", 2));
        assertThat(catalogService.nodes("approval")).containsExactly(
                new WorkflowNotificationCatalogService.NodeOption(
                        "nestedApprove", "嵌套审批"),
                new WorkflowNotificationCatalogService.NodeOption(
                        "reviewTask", "部门复核"));

        assertThatCode(() -> catalogService.validateScope("DEFAULT", null, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> catalogService.validateScope("PROCESS", "approval", null))
                .doesNotThrowAnyException();
        assertThatCode(() -> catalogService.validateScope(
                "NODE", "approval", "nestedApprove"))
                .doesNotThrowAnyException();
    }

    /**
     * 验证服务层实时权限复核先于流程目录读取和作用域校验。
     *
     * @return void，缺少 manage 权限未返回 403 时测试失败
     */
    @Test
    void rejectsEveryCatalogOperationWithoutCurrentManagePermission()
    {
        when(permissionService.hasPermi("workflow:notification:manage")).thenReturn(false);

        assertServiceError(HttpStatus.FORBIDDEN, () -> catalogService.processes());
        assertServiceError(HttpStatus.FORBIDDEN, () -> catalogService.nodes("approval"));
        assertServiceError(HttpStatus.FORBIDDEN,
                () -> catalogService.validateScope("DEFAULT", null, null));
    }

    /**
     * 验证非法 key、不存在流程、作用域字段漂移和非 UserTask 节点均被拒绝。
     *
     * @return void，客户端可绕过目录提交伪造作用域时测试失败
     */
    @Test
    void rejectsInvalidKeysMissingDefinitionsAndNonUserTaskNodes()
    {
        deploy(null, "approval.bpmn20.xml", approvalV2());

        assertServiceError(HttpStatus.BAD_REQUEST, () -> catalogService.nodes("bad key"));
        assertServiceError(HttpStatus.NOT_FOUND, () -> catalogService.nodes("missing"));
        assertServiceError(HttpStatus.BAD_REQUEST,
                () -> catalogService.validateScope("PROCESS", "approval", "reviewTask"));
        assertServiceError(HttpStatus.BAD_REQUEST,
                () -> catalogService.validateScope("NODE", "approval", "nestedPhase"));
        assertServiceError(HttpStatus.BAD_REQUEST,
                () -> catalogService.validateScope("UNKNOWN", null, null));
    }

    /**
     * 验证同一流程 key 的多租户最新激活定义不会被静默合并或任意选取。
     *
     * @return void，租户歧义未按 409 失败关闭时测试失败
     */
    @Test
    void rejectsDuplicateProcessKeysAcrossTenants()
    {
        deploy("tenant-a", "approval-a.bpmn20.xml", approvalV2());
        deploy("tenant-b", "approval-b.bpmn20.xml", approvalV2());

        assertServiceError(HttpStatus.CONFLICT, () -> catalogService.processes());
        assertServiceError(HttpStatus.CONFLICT, () -> catalogService.nodes("approval"));
    }

    /**
     * 将真实 BPMN XML 部署到当前内存引擎并返回持久化定义。
     *
     * @param tenantId String，可空 Flowable tenant ID
     * @param resourceName String，当前部署资源名
     * @param bpmnXml String，待由 Flowable 解析和持久化的 BPMN XML
     * @return ProcessDefinition，部署产生的真实流程定义
     */
    private ProcessDefinition deploy(String tenantId, String resourceName, String bpmnXml)
    {
        DeploymentBuilder builder = repositoryService.createDeployment()
                .name(resourceName)
                .addString(resourceName, bpmnXml);
        if (tenantId != null)
        {
            builder.tenantId(tenantId);
        }
        String deploymentId = builder.deploy().getId();
        List<ProcessDefinition> definitions = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deploymentId)
                .list();
        assertThat(definitions).singleElement();
        return definitions.get(0);
    }

    /**
     * 断言目录操作抛出指定稳定业务状态码。
     *
     * @param expectedCode int，期望 ServiceException code
     * @param action Runnable，待执行目录操作
     * @return void，异常类型或状态码不符时测试失败
     */
    private void assertServiceError(int expectedCode, Runnable action)
    {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(expectedCode));
    }

    /** @return String，包含旧节点的 approval 第一版真实 BPMN。 */
    private String approvalV1()
    {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                  targetNamespace="ApprovaPlatNotificationCatalog">
                  <process id="approval" name="审批流程 V1" isExecutable="true">
                    <startEvent id="start" />
                    <userTask id="oldTask" name="旧审批" />
                    <endEvent id="end" />
                    <sequenceFlow id="f1" sourceRef="start" targetRef="oldTask" />
                    <sequenceFlow id="f2" sourceRef="oldTask" targetRef="end" />
                  </process>
                </definitions>
                """;
    }

    /** @return String，包含直接和嵌套 UserTask 的 approval 第二版真实 BPMN。 */
    private String approvalV2()
    {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                  targetNamespace="ApprovaPlatNotificationCatalog">
                  <process id="approval" name="审批流程 V2" isExecutable="true">
                    <startEvent id="start" />
                    <userTask id="reviewTask" name="部门复核" />
                    <subProcess id="nestedPhase" name="嵌套阶段">
                      <startEvent id="nestedStart" />
                      <userTask id="nestedApprove" name="嵌套审批" />
                      <endEvent id="nestedEnd" />
                      <sequenceFlow id="nf1" sourceRef="nestedStart" targetRef="nestedApprove" />
                      <sequenceFlow id="nf2" sourceRef="nestedApprove" targetRef="nestedEnd" />
                    </subProcess>
                    <endEvent id="end" />
                    <sequenceFlow id="f1" sourceRef="start" targetRef="reviewTask" />
                    <sequenceFlow id="f2" sourceRef="reviewTask" targetRef="nestedPhase" />
                    <sequenceFlow id="f3" sourceRef="nestedPhase" targetRef="end" />
                  </process>
                </definitions>
                """;
    }

    /** @return String，供激活状态过滤验证的独立真实 BPMN。 */
    private String disabledProcess()
    {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                  targetNamespace="ApprovaPlatNotificationCatalog">
                  <process id="disabled" name="停用流程" isExecutable="true">
                    <startEvent id="start" />
                    <userTask id="disabledTask" name="停用审批" />
                    <endEvent id="end" />
                    <sequenceFlow id="f1" sourceRef="start" targetRef="disabledTask" />
                    <sequenceFlow id="f2" sourceRef="disabledTask" targetRef="end" />
                  </process>
                </definitions>
                """;
    }
}
