package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.Execution;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.identity.WorkflowUserSelectionValidator;
import com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport;

/**
 * 为两个多实例公共 API 契约类提供真实引擎生命周期、只读查询和完整事务快照。
 */
abstract class WorkflowMultiInstanceContractIntegrationSupport
{
    /** 整组收敛后唯一修改任务的发起人主键。 */
    protected static final String APPLICANT_ID = "100";

    /** 所有受控多实例场景的初始审批成员。 */
    protected static final List<String> ORIGINAL_APPROVER_IDS =
            List.of("201", "202", "203");

    /** Flowable 多实例根维护的本轮总实例数。 */
    private static final String NUMBER_OF_INSTANCES = "nrOfInstances";

    /** Flowable 多实例根维护的本轮活动实例数。 */
    private static final String NUMBER_OF_ACTIVE_INSTANCES =
            "nrOfActiveInstances";

    /** Flowable 多实例根维护的本轮已完成实例数。 */
    private static final String NUMBER_OF_COMPLETED_INSTANCES =
            "nrOfCompletedInstances";

    protected ProcessEngine processEngine;
    protected RuntimeService runtimeService;
    protected TaskService taskService;
    protected TransactionTemplate transactionTemplate;

    /** 当前用例独占且在 teardown 显式关闭的引擎基础设施。 */
    private WorkflowFlowableEngineTestSupport engineInfrastructure;

    /**
     * 为每个用例创建独立的真实 Flowable 8 引擎、H2 数据库和 Spring 事务。
     *
     * @return void，无返回值；身份目录使用显式 mock，仅隔离本阶段不关心的 RBAC 数据源
     */
    @BeforeEach
    protected final void setUpEngine()
    {
        WorkflowIdentityResolver identityResolver = mock(WorkflowIdentityResolver.class);
        when(identityResolver.resolveApprovalEligibleUserIds(anyCollection()))
                .thenAnswer(invocation ->
                {
                    Collection<String> requested = invocation.getArgument(0);
                    return new LinkedHashSet<>(requested);
                });
        WorkflowMultiInstanceHandler multiInstanceHandler =
                new WorkflowMultiInstanceHandler(
                        new WorkflowUserSelectionValidator(identityResolver),
                        new WorkflowMultiInstanceTransitionCoordinator());

        Map<Object, Object> beans = new LinkedHashMap<>();
        beans.put("multiInstanceHandler", multiInstanceHandler);
        beans.putAll(scenarioBeans());

        engineInfrastructure = WorkflowFlowableEngineTestSupport.start(
                "mi-change-state", beans);
        processEngine = engineInfrastructure.processEngine();
        runtimeService = processEngine.getRuntimeService();
        taskService = processEngine.getTaskService();
        transactionTemplate = engineInfrastructure.transactionTemplate();
        processEngine.getRepositoryService().createDeployment()
                .addClasspathResource(bpmnResource())
                .deploy();
    }

    /**
     * 关闭当前用例的真实引擎，隔离任务、execution、变量与历史数据。
     *
     * @return void，无返回值
     */
    @AfterEach
    protected final void tearDownEngine()
    {
        if (engineInfrastructure != null)
        {
            engineInfrastructure.close();
        }
        processEngine = null;
        engineInfrastructure = null;
    }

    /**
     * 返回当前聚焦契约类部署的外部 BPMN 资源。
     *
     * @return String，classpath 下的 BPMN 资源路径
     */
    protected abstract String bpmnResource();

    /**
     * 返回当前契约类 BPMN 所需的场景级监听器 Bean。
     *
     * @return Map&lt;Object,Object&gt;，仅包含本类真实故障路径使用的 Bean
     */
    protected abstract Map<Object, Object> scenarioBeans();

    /**
     * 校验活动成员、正式快照协议和当前多实例根计数。
     *
     * @param processInstanceId String，活动流程实例主键
     * @param activityId String，受控多实例节点 ID
     * @param expectedMembers List&lt;String&gt;，预期成员与活动办理人
     * @param expectedMode WorkflowMultiInstanceMode，预期 ALL 或 ANY
     * @param expectedRevision int，预期 revision
     * @param expectedCounts MultiInstanceCounts，预期本轮引擎计数
     * @return void，任一事实不一致时失败
     */
    protected final void assertControlledGroup(String processInstanceId,
            String activityId, List<String> expectedMembers,
            WorkflowMultiInstanceMode expectedMode, int expectedRevision,
            MultiInstanceCounts expectedCounts)
    {
        assertThat(requireGroupTasks(processInstanceId, activityId))
                .extracting(Task::getAssignee)
                .containsExactlyInAnyOrderElementsOf(expectedMembers);
        assertControlledVariables(processInstanceId, activityId,
                expectedMembers, expectedMode, expectedRevision);
        assertCounts(processInstanceId, activityId, expectedCounts);
    }

    /**
     * 校验成员快照、受控集合、mode 和 revision 四项流程变量。
     *
     * @param processInstanceId String，活动流程实例主键
     * @param activityId String，受控多实例节点 ID
     * @param expectedMembers List&lt;String&gt;，预期有序成员
     * @param expectedMode WorkflowMultiInstanceMode，预期完成模式
     * @param expectedRevision int，预期 revision
     * @return void，任一受控变量不一致时失败
     */
    protected final void assertControlledVariables(String processInstanceId,
            String activityId, List<String> expectedMembers,
            WorkflowMultiInstanceMode expectedMode, int expectedRevision)
    {
        assertThat(controlledMembers(processInstanceId, activityId))
                .containsExactlyElementsOf(expectedMembers);
        assertThat(runtimeService.getVariable(processInstanceId,
                WorkflowMultiInstanceVariables.userCollectionName(activityId)))
                .isEqualTo(expectedMembers.stream().map(Long::valueOf).toList());
        assertThat(runtimeService.getVariable(processInstanceId,
                WorkflowMultiInstanceVariables.modeName(activityId)))
                .isEqualTo(expectedMode.name());
        assertThat(controlledRevision(processInstanceId, activityId))
                .isEqualTo(expectedRevision);
    }

    /**
     * 读取正式多实例成员快照并转换为字符串列表。
     *
     * @param processInstanceId String，活动流程实例主键
     * @param activityId String，受控多实例节点 ID
     * @return List&lt;String&gt;，handler 初始化或调整后的有序成员
     */
    protected final List<String> controlledMembers(String processInstanceId,
            String activityId)
    {
        Object raw = runtimeService.getVariable(processInstanceId,
                WorkflowMultiInstanceVariables.memberSnapshotName(activityId));
        assertThat(raw).isInstanceOf(List.class);
        List<?> members = (List<?>) raw;
        assertThat(members).allMatch(String.class::isInstance);
        return members.stream().map(String.class::cast).toList();
    }

    /**
     * 读取正式多实例 revision。
     *
     * @param processInstanceId String，活动流程实例主键
     * @param activityId String，受控多实例节点 ID
     * @return int，当前非负 revision
     */
    protected final int controlledRevision(String processInstanceId,
            String activityId)
    {
        Object raw = runtimeService.getVariable(processInstanceId,
                WorkflowMultiInstanceVariables.revisionName(activityId));
        assertThat(raw).isInstanceOf(Integer.class);
        return (Integer) raw;
    }

    /**
     * 从真实多实例根读取三个局部计数并与预期值比较。
     *
     * @param processInstanceId String，活动流程实例主键
     * @param activityId String，多实例活动 ID
     * @param expected MultiInstanceCounts，预期本轮计数
     * @return void，任一计数不存在或不一致时失败
     */
    protected final void assertCounts(String processInstanceId, String activityId,
            MultiInstanceCounts expected)
    {
        Execution root = requireMultiInstanceRoot(processInstanceId, activityId);
        MultiInstanceCounts actual = new MultiInstanceCounts(
                (Integer) runtimeService.getVariableLocal(
                        root.getId(), NUMBER_OF_INSTANCES),
                (Integer) runtimeService.getVariableLocal(
                        root.getId(), NUMBER_OF_ACTIVE_INSTANCES),
                (Integer) runtimeService.getVariableLocal(
                        root.getId(), NUMBER_OF_COMPLETED_INSTANCES));
        assertThat(actual).isEqualTo(expected);
    }

    /**
     * 从活动成员任务的共同父 execution 定位唯一多实例根。
     *
     * @param processInstanceId String，活动流程实例主键
     * @param activityId String，多实例活动 ID
     * @return Execution，唯一多实例根 execution
     */
    protected final Execution requireMultiInstanceRoot(String processInstanceId,
            String activityId)
    {
        List<String> parentIds = requireGroupTasks(processInstanceId, activityId).stream()
                .map(Task::getExecutionId)
                .map(executionId -> runtimeService.createExecutionQuery()
                        .executionId(executionId).singleResult())
                .map(Execution::getParentId).distinct().toList();
        assertThat(parentIds).singleElement();
        Execution root = runtimeService.createExecutionQuery()
                .executionId(parentIds.get(0)).singleResult();
        assertThat(root).isNotNull();
        assertThat(root.getProcessInstanceId()).isEqualTo(processInstanceId);
        assertThat(root.getActivityId()).isEqualTo(activityId);
        return root;
    }

    /**
     * 查询指定多实例活动的全部活动任务并按主键稳定排序。
     *
     * @param processInstanceId String，活动流程实例主键
     * @param activityId String，多实例活动 ID
     * @return List&lt;Task&gt;，至少一个真实活动任务
     */
    protected final List<Task> requireGroupTasks(String processInstanceId,
            String activityId)
    {
        List<Task> tasks = taskService.createTaskQuery()
                .processInstanceId(processInstanceId).taskDefinitionKey(activityId)
                .active().list().stream().sorted(Comparator.comparing(Task::getId)).toList();
        assertThat(tasks).isNotEmpty();
        return tasks;
    }

    /**
     * 查询实例指定节点的唯一活动任务。
     *
     * @param processInstanceId String，活动流程实例主键
     * @param activityId String，预期唯一活动节点 ID
     * @return Task，唯一真实活动任务
     */
    protected final Task requireSingleTask(String processInstanceId,
            String activityId)
    {
        List<Task> tasks = taskService.createTaskQuery()
                .processInstanceId(processInstanceId).taskDefinitionKey(activityId)
                .active().list();
        assertThat(tasks).singleElement();
        return tasks.get(0);
    }

    /**
     * 冻结任务、execution、流程变量及各局部变量，用于严格对账事务回滚。
     *
     * @param processInstanceId String，活动流程实例主键
     * @return EngineSnapshot，可直接值比较的完整运行时快照
     */
    protected final EngineSnapshot captureSnapshot(String processInstanceId)
    {
        List<TaskState> tasks = taskService.createTaskQuery()
                .processInstanceId(processInstanceId).active().list().stream()
                .map(task -> new TaskState(task.getId(), task.getExecutionId(),
                        task.getTaskDefinitionKey(), task.getAssignee(), task.getOwner()))
                .sorted(Comparator.comparing(TaskState::id)).toList();
        List<Execution> executions = runtimeService.createExecutionQuery()
                .processInstanceId(processInstanceId).list().stream()
                .sorted(Comparator.comparing(Execution::getId)).toList();
        List<ExecutionState> executionStates = executions.stream()
                .map(execution -> new ExecutionState(execution.getId(),
                        execution.getParentId(), execution.getActivityId(),
                        execution.isEnded(), execution.isSuspended()))
                .toList();

        Map<String, Map<String, Object>> executionVariables = new TreeMap<>();
        for (Execution execution : executions)
        {
            executionVariables.put(execution.getId(), new TreeMap<>(
                    runtimeService.getVariablesLocal(execution.getId())));
        }
        Map<String, Map<String, Object>> taskVariables = new TreeMap<>();
        for (TaskState task : tasks)
        {
            taskVariables.put(task.id(), new TreeMap<>(
                    taskService.getVariablesLocal(task.id())));
        }
        return new EngineSnapshot(tasks, executionStates,
                new TreeMap<>(runtimeService.getVariables(processInstanceId)),
                executionVariables, taskVariables);
    }

    /**
     * 多实例根三个正式计数的不可变值对象。
     *
     * @param instances int，本轮总实例数
     * @param active int，本轮活动实例数
     * @param completed int，本轮已完成实例数
     */
    protected record MultiInstanceCounts(int instances, int active, int completed)
    {
    }

    /**
     * 活动任务回滚对账所需字段。
     *
     * @param id String，任务主键
     * @param executionId String，承载任务的 execution 主键
     * @param activityId String，任务定义节点 ID
     * @param assignee String，办理人主键
     * @param owner String，所有人主键
     */
    private record TaskState(String id, String executionId, String activityId,
            String assignee, String owner)
    {
    }

    /**
     * execution 回滚对账所需结构字段。
     *
     * @param id String，execution 主键
     * @param parentId String，父 execution 主键
     * @param activityId String，当前活动 ID
     * @param ended boolean，是否结束
     * @param suspended boolean，是否挂起
     */
    private record ExecutionState(String id, String parentId, String activityId,
            boolean ended, boolean suspended)
    {
    }

    /**
     * 故障前后任务、execution 与变量的完整快照。
     *
     * @param tasks List&lt;TaskState&gt;，活动任务结构
     * @param executions List&lt;ExecutionState&gt;，运行时 execution 树
     * @param processVariables Map&lt;String,Object&gt;，流程作用域变量
     * @param executionVariables Map&lt;String,Map&lt;String,Object&gt;&gt;，execution 局部变量
     * @param taskVariables Map&lt;String,Map&lt;String,Object&gt;&gt;，任务局部变量
     */
    protected record EngineSnapshot(List<TaskState> tasks,
            List<ExecutionState> executions, Map<String, Object> processVariables,
            Map<String, Map<String, Object>> executionVariables,
            Map<String, Map<String, Object>> taskVariables)
    {
    }
}
