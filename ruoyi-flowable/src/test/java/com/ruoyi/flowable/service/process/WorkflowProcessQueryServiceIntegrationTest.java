package com.ruoyi.flowable.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.core.page.PageResult;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessService;
import com.ruoyi.flowable.domain.dto.WorkflowManagedProcessQueryDto;
import com.ruoyi.flowable.domain.dto.WorkflowOwnedProcessQueryDto;
import com.ruoyi.flowable.domain.vo.WorkflowManagedProcessView;
import com.ruoyi.flowable.domain.vo.WorkflowOwnedProcessView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfCopyMapper;
import com.ruoyi.flowable.service.identity.WorkflowParticipantRuleRuntimeService;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifactRepository;
import com.ruoyi.flowable.service.model.WorkflowDeploymentService;
import com.ruoyi.flowable.service.task.WorkflowTaskLifecycleService;
import com.ruoyi.system.service.ISysUserService;

/**
 * 使用真实 Flowable 8 和 H2 验证实例列表的部署分类及当前任务批量查询边界。
 */
class WorkflowProcessQueryServiceIntegrationTest
{
    private static final String CURRENT_USER_ID = "100";

    private ProcessEngine processEngine;
    private RepositoryService repositoryService;
    private RuntimeService runtimeService;
    private TaskService taskService;
    private WorkflowProcessQueryService service;
    private ProcessInstance firstFinanceInstance;
    private ProcessInstance secondFinanceInstance;
    private ProcessInstance hrInstance;

    /**
     * 创建真实内存引擎，部署定义分类与业务分类故意不同的流程并装配查询服务。
     *
     * @return void，无返回值；每个测试使用独立 H2 数据库
     */
    @BeforeEach
    @SuppressWarnings({ "rawtypes", "unchecked" })
    void setUp()
    {
        processEngine = ProcessEngineConfiguration
                .createStandaloneInMemProcessEngineConfiguration()
                .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
                .setHistory("full")
                .buildProcessEngine();
        repositoryService = processEngine.getRepositoryService();
        runtimeService = processEngine.getRuntimeService();
        HistoryService historyService = processEngine.getHistoryService();
        taskService = spy(processEngine.getTaskService());

        Deployment financeDeployment = deploy("finance", "financeProcess",
                "财务审批", "urn:definition:finance");
        Deployment hrDeployment = deploy("hr", "hrProcess",
                "人事审批", "urn:definition:hr");
        assertDefinitionCategoryDiffers(financeDeployment, "finance");
        assertDefinitionCategoryDiffers(hrDeployment, "hr");

        processEngine.getIdentityService().setAuthenticatedUserId(CURRENT_USER_ID);
        try
        {
            firstFinanceInstance = runtimeService.startProcessInstanceByKey(
                    "financeProcess", "FINANCE-1");
            secondFinanceInstance = runtimeService.startProcessInstanceByKey(
                    "financeProcess", "FINANCE-2");
            hrInstance = runtimeService.startProcessInstanceByKey("hrProcess", "HR-1");
        }
        finally
        {
            processEngine.getIdentityService().setAuthenticatedUserId(null);
        }

        WorkflowEngineOperations engineOperations = mock(WorkflowEngineOperations.class);
        when(engineOperations.read(any(Supplier.class))).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        WorkflowIdentityResolver identityResolver = mock(WorkflowIdentityResolver.class);
        when(identityResolver.resolveCurrentIdentity()).thenReturn(
                new WorkflowCurrentIdentity(CURRENT_USER_ID, Set.of()));
        service = new WorkflowProcessQueryService(engineOperations, repositoryService,
                historyService, runtimeService, taskService, identityResolver,
                mock(WorkflowProcessAccessService.class),
                mock(WorkflowDeploymentService.class),
                mock(WorkflowDeploymentArtifactRepository.class), mock(WfCopyMapper.class),
                mock(ISysUserService.class), mock(WorkflowTaskLifecycleService.class),
                mock(WorkflowParticipantRuleRuntimeService.class));
    }

    /**
     * 关闭真实 Flowable 引擎，避免测试之间共享运行时与历史表。
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
     * 验证我发起的和管理员列表都只按 Deployment.category 筛选并返回同一分类。
     *
     * @return void，任一列表使用定义分类或返回错误分类时测试失败
     */
    @Test
    void filtersOwnedAndManagedInstancesByDeploymentCategory()
    {
        clearInvocations(taskService);
        WorkflowOwnedProcessQueryDto ownedFilter = new WorkflowOwnedProcessQueryDto(
                null, null, "finance", null, null, null);
        PageResult<WorkflowOwnedProcessView> owned = service.listOwned(ownedFilter, 1, 20);

        assertThat(owned.total()).isEqualTo(2);
        assertThat(owned.rows()).extracting(WorkflowOwnedProcessView::processInstanceId)
                .containsExactlyInAnyOrder(firstFinanceInstance.getId(),
                        secondFinanceInstance.getId());
        assertThat(owned.rows()).extracting(WorkflowOwnedProcessView::category)
                .containsOnly("finance");
        verify(taskService, times(1)).createTaskQuery();

        WorkflowManagedProcessQueryDto managedFilter = new WorkflowManagedProcessQueryDto(
                null, null, null, "hr", null, null, null, null);
        PageResult<WorkflowManagedProcessView> managed = service.listManaged(
                managedFilter, 1, 20);

        assertThat(managed.total()).isEqualTo(1);
        assertThat(managed.rows()).singleElement().satisfies(row ->
        {
            assertThat(row.processInstanceId()).isEqualTo(hrInstance.getId());
            assertThat(row.category()).isEqualTo("hr");
        });
        verify(taskService, times(2)).createTaskQuery();
    }

    /**
     * 验证一页多个实例只创建一个有界任务查询，保持任务顺序，并在挂起后继续返回当前环节。
     *
     * @return void，任务发生 N+1、顺序变化或挂起任务丢失时测试失败
     */
    @Test
    void loadsCurrentTasksOnceAndKeepsSuspendedTasksVisible()
    {
        List<String> expectedFirstTaskNames = currentTaskNames(firstFinanceInstance.getId());
        List<String> expectedSecondTaskNames = currentTaskNames(secondFinanceInstance.getId());
        List<String> expectedHrTaskNames = currentTaskNames(hrInstance.getId());
        assertThat(expectedFirstTaskNames).hasSize(2);
        assertThat(expectedSecondTaskNames).hasSize(2);
        assertThat(expectedHrTaskNames).hasSize(2);
        runtimeService.suspendProcessInstanceById(firstFinanceInstance.getId());

        clearInvocations(taskService);
        PageResult<WorkflowOwnedProcessView> page = service.listOwned(null, 1, 20);

        verify(taskService, times(1)).createTaskQuery();
        assertThat(page.total()).isEqualTo(3);
        assertOwnedRow(page, firstFinanceInstance.getId(), expectedFirstTaskNames, "suspended");
        assertOwnedRow(page, secondFinanceInstance.getId(), expectedSecondTaskNames, "running");
        assertOwnedRow(page, hrInstance.getId(), expectedHrTaskNames, "running");
    }

    /**
     * 验证不存在的正式部署分类返回真实空页，且不会退化成全量实例或触发任务查询。
     *
     * @return void，空部署集合未短路时测试失败
     */
    @Test
    void returnsEmptyPagesForUnknownDeploymentCategory()
    {
        clearInvocations(taskService);
        PageResult<WorkflowOwnedProcessView> owned = service.listOwned(
                new WorkflowOwnedProcessQueryDto(null, null, "missing", null, null, null),
                1, 20);
        PageResult<WorkflowManagedProcessView> managed = service.listManaged(
                new WorkflowManagedProcessQueryDto(null, null, null, "missing",
                        null, null, null, null),
                1, 20);

        assertThat(owned.total()).isZero();
        assertThat(owned.rows()).isEmpty();
        assertThat(managed.total()).isZero();
        assertThat(managed.rows()).isEmpty();
        verify(taskService, never()).createTaskQuery();
    }

    /**
     * 部署指定业务分类并让 BPMN targetNamespace 保持不同值，用于识别错误定义分类路径。
     *
     * @param category String，发布时冻结的 Deployment.category
     * @param processKey String，流程定义标识
     * @param processName String，流程显示名称
     * @param targetNamespace String，故意不同于业务分类的 BPMN 命名空间
     * @return Deployment，真实 Flowable 部署记录
     */
    private Deployment deploy(String category, String processKey, String processName,
            String targetNamespace)
    {
        return repositoryService.createDeployment()
                .category(category)
                .addString(processKey + ".bpmn20.xml",
                        bpmn(processKey, processName, targetNamespace))
                .deploy();
    }

    /**
     * 断言真实流程定义分类不是发布业务分类，保证测试能识别旧筛选实现。
     *
     * @param deployment Deployment，真实 Flowable 部署
     * @param businessCategory String，部署业务分类
     * @return void，夹具没有形成分类差异时测试失败
     */
    private void assertDefinitionCategoryDiffers(Deployment deployment, String businessCategory)
    {
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deployment.getId()).singleResult();
        assertThat(definition.getCategory()).isNotEqualTo(businessCategory);
    }

    /**
     * 按生产查询约定读取单实例任务名称，作为批量结果的稳定顺序基准。
     *
     * @param processInstanceId String，真实流程实例主键
     * @return List&lt;String&gt;，按创建时间和任务主键升序排列的任务名称
     */
    private List<String> currentTaskNames(String processInstanceId)
    {
        return taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .orderByTaskCreateTime().asc()
                .orderByTaskId().asc()
                .listPage(0, 10).stream()
                .map(Task::getName)
                .toList();
    }

    /**
     * 断言指定实例行的当前任务名称与实时状态。
     *
     * @param page PageResult&lt;WorkflowOwnedProcessView&gt;，公开查询返回的实例页
     * @param processInstanceId String，待断言实例主键
     * @param taskNames List&lt;String&gt;，期望的稳定任务名称顺序
     * @param processStatus String，期望的实时流程状态
     * @return void，实例缺失或任一事实不一致时测试失败
     */
    private void assertOwnedRow(PageResult<WorkflowOwnedProcessView> page,
            String processInstanceId, List<String> taskNames, String processStatus)
    {
        assertThat(page.rows()).filteredOn(row ->
                processInstanceId.equals(row.processInstanceId()))
                .singleElement().satisfies(row ->
                {
                    assertThat(row.currentTaskNames()).containsExactlyElementsOf(taskNames);
                    assertThat(row.processStatus()).isEqualTo(processStatus);
                });
    }

    /**
     * 生成含两个并行用户任务的真实 BPMN，用于验证同一实例内任务顺序和完整性。
     *
     * @param processKey String，流程定义标识
     * @param processName String，流程显示名称
     * @param targetNamespace String，流程定义分类来源
     * @return String，可由真实 RepositoryService 部署的 BPMN XML
     */
    private String bpmn(String processKey, String processName, String targetNamespace)
    {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:flowable="http://flowable.org/bpmn"
                  targetNamespace="%s">
                  <process id="%s" name="%s" isExecutable="true">
                    <startEvent id="start"/>
                    <parallelGateway id="fork"/>
                    <userTask id="firstTask" name="第一环节" flowable:assignee="approver"/>
                    <userTask id="secondTask" name="第二环节" flowable:assignee="approver"/>
                    <parallelGateway id="join"/>
                    <endEvent id="end"/>
                    <sequenceFlow id="flow1" sourceRef="start" targetRef="fork"/>
                    <sequenceFlow id="flow2" sourceRef="fork" targetRef="firstTask"/>
                    <sequenceFlow id="flow3" sourceRef="fork" targetRef="secondTask"/>
                    <sequenceFlow id="flow4" sourceRef="firstTask" targetRef="join"/>
                    <sequenceFlow id="flow5" sourceRef="secondTask" targetRef="join"/>
                    <sequenceFlow id="flow6" sourceRef="join" targetRef="end"/>
                  </process>
                </definitions>
                """.formatted(targetNamespace, processKey, processName);
    }
}
