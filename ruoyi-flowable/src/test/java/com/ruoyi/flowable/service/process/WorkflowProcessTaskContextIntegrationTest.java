package com.ruoyi.flowable.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
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
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.page.PageResult;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessService;
import com.ruoyi.flowable.domain.vo.WorkflowAssignedTaskView;
import com.ruoyi.flowable.domain.vo.WorkflowClaimableTaskView;
import com.ruoyi.flowable.domain.vo.WorkflowCompletedTaskView;
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
 * 使用真实 Flowable 8 和 H2 验证任务列表当前页关联上下文的批量查询边界。
 */
class WorkflowProcessTaskContextIntegrationTest
{
    /** 当前测试身份，也是流程历史发起人和任务办理人。 */
    private static final String CURRENT_USER_ID = "100";

    /** 当前测试身份具备的真实 Flowable 候选组。 */
    private static final String CURRENT_GROUP_ID = "ROLE9";

    private ProcessEngine processEngine;
    private RepositoryService repositoryService;
    private HistoryService historyService;
    private RuntimeService runtimeService;
    private TaskService taskService;
    private WorkflowTaskLifecycleService taskLifecycleService;
    private WorkflowProcessQueryService service;

    /**
     * 创建启用完整历史的真实内存引擎，并装配只对外部依赖使用 mock 的查询服务。
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
        // repositoryService 和 historyService 使用真实服务 spy，仅统计生产服务发起的粗粒度查询次数。
        repositoryService = spy(processEngine.getRepositoryService());
        historyService = spy(processEngine.getHistoryService());
        runtimeService = processEngine.getRuntimeService();
        taskService = processEngine.getTaskService();

        WorkflowEngineOperations engineOperations = mock(WorkflowEngineOperations.class);
        when(engineOperations.read(any(Supplier.class))).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        WorkflowIdentityResolver identityResolver = mock(WorkflowIdentityResolver.class);
        when(identityResolver.resolveCurrentIdentity()).thenReturn(
                new WorkflowCurrentIdentity(CURRENT_USER_ID, Set.of(CURRENT_GROUP_ID)));
        when(identityResolver.resolveClaimEligibleUserIds(any()))
                .thenReturn(Set.of(CURRENT_USER_ID));

        ISysUserService userService = mock(ISysUserService.class);
        SysUser currentUser = new SysUser();
        currentUser.setUserId(Long.valueOf(CURRENT_USER_ID));
        currentUser.setNickName("当前用户");
        when(userService.selectUserById(Long.valueOf(CURRENT_USER_ID))).thenReturn(currentUser);
        taskLifecycleService = mock(WorkflowTaskLifecycleService.class);

        service = new WorkflowProcessQueryService(engineOperations, repositoryService,
                historyService, runtimeService, taskService, identityResolver,
                mock(WorkflowProcessAccessService.class),
                mock(WorkflowDeploymentService.class),
                mock(WorkflowDeploymentArtifactRepository.class), mock(WfCopyMapper.class),
                userService, taskLifecycleService,
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
     * 验证不同定义、部署和实例的多个待办使用一组批量关联查询并返回完整业务上下文。
     *
     * @return void，关联字段错误或发生逐任务定义、实例、部署查询时测试失败
     */
    @Test
    void loadsAssignedTaskContextsInThreePageLevelQueries()
    {
        Deployment financeDeployment = deploy("finance", "assignedFinance",
                "财务待办", "flowable:assignee=\"100\"");
        Deployment hrDeployment = deploy("hr", "assignedHr",
                "人事待办", "flowable:assignee=\"100\"");
        ProcessDefinition financeDefinition = definitionOf(financeDeployment);
        ProcessDefinition hrDefinition = definitionOf(hrDeployment);
        ProcessInstance financeInstance = start("assignedFinance", "FINANCE-1001");
        ProcessInstance hrInstance = start("assignedHr", "HR-1001");

        clearAssociationQueryInvocations();
        PageResult<WorkflowAssignedTaskView> page = service.listAssigned(null, 1, 20);

        assertThat(page.total()).isEqualTo(2);
        assertAssignedRow(page, financeInstance, financeDefinition,
                financeDeployment, "finance", "FINANCE-1001");
        assertAssignedRow(page, hrInstance, hrDefinition,
                hrDeployment, "hr", "HR-1001");
        verifyOneAssociationQueryPerType();
    }

    /**
     * 验证真实候选用户和候选组任务保持未分配语义，并共享当前页批量关联结果。
     *
     * @return void，候选身份、未分配状态或批量查询边界被破坏时测试失败
     */
    @Test
    void loadsClaimableUserAndGroupTaskContextsInThreePageLevelQueries()
    {
        Deployment userDeployment = deploy("purchase", "candidateUserProcess",
                "采购待签", "flowable:candidateUsers=\"100\"");
        Deployment groupDeployment = deploy("contract", "candidateGroupProcess",
                "合同待签", "flowable:candidateGroups=\"ROLE9\"");
        ProcessDefinition userDefinition = definitionOf(userDeployment);
        ProcessDefinition groupDefinition = definitionOf(groupDeployment);
        ProcessInstance userInstance = start("candidateUserProcess", "PURCHASE-1001");
        ProcessInstance groupInstance = start("candidateGroupProcess", "CONTRACT-1001");
        assertThat(taskService.createTaskQuery().processInstanceId(userInstance.getId())
                .singleResult().getAssignee()).isNull();
        assertThat(taskService.createTaskQuery().processInstanceId(groupInstance.getId())
                .singleResult().getAssignee()).isNull();

        clearAssociationQueryInvocations();
        PageResult<WorkflowClaimableTaskView> page = service.listClaimable(null, 1, 20);

        assertThat(page.total()).isEqualTo(2);
        assertClaimableRow(page, userInstance, userDefinition,
                userDeployment, "purchase", "PURCHASE-1001");
        assertClaimableRow(page, groupInstance, groupDefinition,
                groupDeployment, "contract", "CONTRACT-1001");
        verifyOneAssociationQueryPerType();
    }

    /**
     * 验证真实已完成历史任务批量装载上下文，并原样投影正式撤回服务的计算结果。
     *
     * @return void，历史上下文查询退化或 revocable 被列表自行推导时测试失败
     */
    @Test
    void loadsCompletedTaskContextsAndKeepsLifecycleRevocableResults()
    {
        Deployment travelDeployment = deploy("travel", "completedTravel",
                "差旅已办", "flowable:assignee=\"100\"");
        Deployment expenseDeployment = deploy("expense", "completedExpense",
                "报销已办", "flowable:assignee=\"100\"");
        ProcessDefinition travelDefinition = definitionOf(travelDeployment);
        ProcessDefinition expenseDefinition = definitionOf(expenseDeployment);
        ProcessInstance travelInstance = start("completedTravel", "TRAVEL-1001");
        ProcessInstance expenseInstance = start("completedExpense", "EXPENSE-1001");
        Task travelTask = taskService.createTaskQuery()
                .processInstanceId(travelInstance.getId()).singleResult();
        Task expenseTask = taskService.createTaskQuery()
                .processInstanceId(expenseInstance.getId()).singleResult();
        taskService.complete(travelTask.getId(), CURRENT_USER_ID);
        taskService.complete(expenseTask.getId(), CURRENT_USER_ID);
        when(taskLifecycleService.isProcessRevocable(
                travelInstance.getId(), travelTask.getId())).thenReturn(true);
        when(taskLifecycleService.isProcessRevocable(
                expenseInstance.getId(), expenseTask.getId())).thenReturn(false);

        clearAssociationQueryInvocations();
        PageResult<WorkflowCompletedTaskView> page = service.listCompleted(null, 1, 20);

        assertThat(page.total()).isEqualTo(2);
        assertCompletedRow(page, travelTask, travelInstance, travelDefinition,
                travelDeployment, "travel", "TRAVEL-1001", true);
        assertCompletedRow(page, expenseTask, expenseInstance, expenseDefinition,
                expenseDeployment, "expense", "EXPENSE-1001", false);
        verify(taskLifecycleService, times(1)).isProcessRevocable(
                travelInstance.getId(), travelTask.getId());
        verify(taskLifecycleService, times(1)).isProcessRevocable(
                expenseInstance.getId(), expenseTask.getId());
        verifyOneAssociationQueryPerType();
    }

    /**
     * 部署一个带真实办理人或候选身份配置的单任务流程。
     *
     * @param category String，发布时冻结的 Deployment.category
     * @param processKey String，流程定义标识
     * @param processName String，流程定义名称
     * @param taskAssignment String，受测试控制的 Flowable 办理人或候选人属性
     * @return Deployment，真实 Flowable 部署记录
     */
    private Deployment deploy(String category, String processKey, String processName,
            String taskAssignment)
    {
        return repositoryService.createDeployment()
                .category(category)
                .addString(processKey + ".bpmn20.xml",
                        bpmn(processKey, processName, taskAssignment))
                .deploy();
    }

    /**
     * 查询夹具部署中的唯一流程定义，作为列表关联断言的真实基准。
     *
     * @param deployment Deployment，刚完成的真实流程部署
     * @return ProcessDefinition，该部署中的唯一流程定义
     */
    private ProcessDefinition definitionOf(Deployment deployment)
    {
        return repositoryService.createProcessDefinitionQuery()
                .deploymentId(deployment.getId()).singleResult();
    }

    /**
     * 以当前测试用户作为历史发起人启动真实流程实例。
     *
     * @param processKey String，待启动流程定义标识
     * @param businessKey String，实例业务主键
     * @return ProcessInstance，真实运行时流程实例
     */
    private ProcessInstance start(String processKey, String businessKey)
    {
        processEngine.getIdentityService().setAuthenticatedUserId(CURRENT_USER_ID);
        try
        {
            return runtimeService.startProcessInstanceByKey(processKey, businessKey);
        }
        finally
        {
            processEngine.getIdentityService().setAuthenticatedUserId(null);
        }
    }

    /**
     * 清除夹具构造阶段的服务调用记录，使查询次数只覆盖待测列表的一次请求。
     *
     * @return void，无返回值
     */
    private void clearAssociationQueryInvocations()
    {
        clearInvocations(repositoryService, historyService);
    }

    /**
     * 断言当前页任务上下文只创建一次定义、历史实例和部署批量查询。
     *
     * @return void，任一关联对象发生零次或多次查询时测试失败
     */
    private void verifyOneAssociationQueryPerType()
    {
        verify(repositoryService, times(1)).createProcessDefinitionQuery();
        verify(historyService, times(1)).createHistoricProcessInstanceQuery();
        verify(repositoryService, times(1)).createDeploymentQuery();
    }

    /**
     * 断言指定待办行完整投影真实定义、实例、部署和发起人信息。
     *
     * @param page PageResult&lt;WorkflowAssignedTaskView&gt;，待办查询结果
     * @param instance ProcessInstance，期望实例
     * @param definition ProcessDefinition，期望定义
     * @param deployment Deployment，期望部署
     * @param category String，期望业务分类
     * @param businessKey String，期望业务主键
     * @return void，任一关联字段不一致时测试失败
     */
    private void assertAssignedRow(PageResult<WorkflowAssignedTaskView> page,
            ProcessInstance instance, ProcessDefinition definition, Deployment deployment,
            String category, String businessKey)
    {
        assertThat(page.rows()).filteredOn(row ->
                instance.getId().equals(row.processInstanceId()))
                .singleElement().satisfies(row ->
                {
                    assertThat(row.definitionId()).isEqualTo(definition.getId());
                    assertThat(row.processKey()).isEqualTo(definition.getKey());
                    assertThat(row.processName()).isEqualTo(definition.getName());
                    assertThat(row.deploymentId()).isEqualTo(deployment.getId());
                    assertThat(row.businessKey()).isEqualTo(businessKey);
                    assertThat(row.category()).isEqualTo(category);
                    assertThat(row.startUserId()).isEqualTo(CURRENT_USER_ID);
                    assertThat(row.startUserName()).isEqualTo("当前用户");
                    assertThat(row.assigneeId()).isEqualTo(CURRENT_USER_ID);
                });
    }

    /**
     * 断言指定待签行完整投影真实定义、实例、部署和发起人信息。
     *
     * @param page PageResult&lt;WorkflowClaimableTaskView&gt;，待签查询结果
     * @param instance ProcessInstance，期望实例
     * @param definition ProcessDefinition，期望定义
     * @param deployment Deployment，期望部署
     * @param category String，期望业务分类
     * @param businessKey String，期望业务主键
     * @return void，任一关联字段不一致时测试失败
     */
    private void assertClaimableRow(PageResult<WorkflowClaimableTaskView> page,
            ProcessInstance instance, ProcessDefinition definition, Deployment deployment,
            String category, String businessKey)
    {
        assertThat(page.rows()).filteredOn(row ->
                instance.getId().equals(row.processInstanceId()))
                .singleElement().satisfies(row ->
                {
                    assertThat(row.definitionId()).isEqualTo(definition.getId());
                    assertThat(row.processKey()).isEqualTo(definition.getKey());
                    assertThat(row.processName()).isEqualTo(definition.getName());
                    assertThat(row.deploymentId()).isEqualTo(deployment.getId());
                    assertThat(row.businessKey()).isEqualTo(businessKey);
                    assertThat(row.category()).isEqualTo(category);
                    assertThat(row.startUserId()).isEqualTo(CURRENT_USER_ID);
                    assertThat(row.startUserName()).isEqualTo("当前用户");
                });
    }

    /**
     * 断言指定已办行完整投影真实上下文和撤回服务返回值。
     *
     * @param page PageResult&lt;WorkflowCompletedTaskView&gt;，已办查询结果
     * @param task Task，完成前保存的真实任务
     * @param instance ProcessInstance，期望实例
     * @param definition ProcessDefinition，期望定义
     * @param deployment Deployment，期望部署
     * @param category String，期望业务分类
     * @param businessKey String，期望业务主键
     * @param revocable boolean，撤回服务为该任务返回的期望值
     * @return void，任一关联字段或撤回结果不一致时测试失败
     */
    private void assertCompletedRow(PageResult<WorkflowCompletedTaskView> page, Task task,
            ProcessInstance instance, ProcessDefinition definition, Deployment deployment,
            String category, String businessKey, boolean revocable)
    {
        assertThat(page.rows()).filteredOn(row -> task.getId().equals(row.taskId()))
                .singleElement().satisfies(row ->
                {
                    assertThat(row.processInstanceId()).isEqualTo(instance.getId());
                    assertThat(row.definitionId()).isEqualTo(definition.getId());
                    assertThat(row.processKey()).isEqualTo(definition.getKey());
                    assertThat(row.processName()).isEqualTo(definition.getName());
                    assertThat(row.deploymentId()).isEqualTo(deployment.getId());
                    assertThat(row.businessKey()).isEqualTo(businessKey);
                    assertThat(row.category()).isEqualTo(category);
                    assertThat(row.startUserId()).isEqualTo(CURRENT_USER_ID);
                    assertThat(row.startUserName()).isEqualTo("当前用户");
                    assertThat(row.completedBy()).isEqualTo(CURRENT_USER_ID);
                    assertThat(row.revocable()).isEqualTo(revocable);
                });
    }

    /**
     * 生成一个可由真实 Flowable 8 部署和执行的单任务 BPMN。
     *
     * @param processKey String，流程定义标识
     * @param processName String，流程定义名称
     * @param taskAssignment String，用户任务的受控办理人或候选身份属性
     * @return String，UTF-8 BPMN XML 内容
     */
    private String bpmn(String processKey, String processName, String taskAssignment)
    {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:flowable="http://flowable.org/bpmn"
                  targetNamespace="urn:test:task-context">
                  <process id="%s" name="%s" isExecutable="true">
                    <startEvent id="start"/>
                    <userTask id="approval" name="审批" %s/>
                    <endEvent id="end"/>
                    <sequenceFlow id="flow1" sourceRef="start" targetRef="approval"/>
                    <sequenceFlow id="flow2" sourceRef="approval" targetRef="end"/>
                  </process>
                </definitions>
                """.formatted(processKey, processName, taskAssignment);
    }
}
