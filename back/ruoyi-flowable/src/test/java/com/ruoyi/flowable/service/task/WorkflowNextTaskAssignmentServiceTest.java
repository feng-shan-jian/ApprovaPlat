package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.MultiInstanceLoopCharacteristics;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ExecutionQuery;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.identitylink.api.IdentityLinkType;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.identity.WorkflowUserSelectionValidator;

class WorkflowNextTaskAssignmentServiceTest
{
    private WorkflowUserSelectionValidator userSelectionValidator;

    private RepositoryService repositoryService;

    private TaskService taskService;

    private RuntimeService runtimeService;

    private WorkflowNextTaskAssignmentService assignmentService;

    /**
     * 为每个测试创建独立的用户校验、BPMN 仓库和任务服务替身。
     *
     * @return 无返回值；初始化失败时测试失败
     */
    @BeforeEach
    void setUp()
    {
        userSelectionValidator = mock(WorkflowUserSelectionValidator.class);
        repositoryService = mock(RepositoryService.class);
        taskService = mock(TaskService.class);
        runtimeService = mock(RuntimeService.class);
        assignmentService = new WorkflowNextTaskAssignmentService(
                userSelectionValidator, repositoryService, taskService, runtimeService);
    }

    /**
     * 验证唯一无条件直接后继用户任务会生成保持用户顺序的不可变分配计划。
     *
     * @return 无返回值；拓扑识别、计划字段或不可变约束不符合时测试失败
     */
    @Test
    void preparesImmutablePlanForUniqueUnconditionalUserTask()
    {
        Task sourceTask = sourceTask();
        when(userSelectionValidator.requireApprovalEligibleUserIds(List.of(8L, 9L)))
                .thenReturn(List.of("8", "9"));
        when(userSelectionValidator.requireClaimEligibleUserIds(List.of(8L, 9L)))
                .thenReturn(List.of("8", "9"));
        stubCurrentTasks(List.of(sourceTask));
        stubDefinition(serialModel(1, false));

        WorkflowNextTaskAssignmentService.AssignmentPlan plan = assignmentService.prepare(
                sourceTask, List.of(8L, 9L));

        assertThat(plan.processInstanceId()).isEqualTo("instance-1");
        assertThat(plan.sourceTaskId()).isEqualTo("source-task");
        assertThat(plan.expectedTaskDefinitionKey()).isEqualTo("review-1");
        assertThat(plan.userIds()).containsExactly("8", "9");
        assertThat(plan.requested()).isTrue();
        assertThatThrownBy(() -> plan.userIds().add("10"))
                .isInstanceOf(UnsupportedOperationException.class);
        verify(userSelectionValidator).requireClaimEligibleUserIds(List.of(8L, 9L));
    }

    /**
     * 验证无流程办理权限的目标用户在任何 BPMN、任务或运行时访问前被整批拒绝。
     *
     * @return 无返回值；权限异常、错误码或零副作用契约漂移时测试失败
     */
    @Test
    void rejectsApprovalIneligibleUsersBeforeAnyEngineAccess()
    {
        Task sourceTask = sourceTask();
        when(userSelectionValidator.requireApprovalEligibleUserIds(List.of(8L)))
                .thenThrow(new ServiceException(
                        "所选用户不存在、已停用或无流程办理权限",
                        HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> assignmentService.prepare(sourceTask, List.of(8L)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getMessage()).isEqualTo(
                            "所选用户不存在、已停用或无流程办理权限");
                });

        verifyNoInteractions(repositoryService, taskService, runtimeService);
    }

    /**
     * 验证实例中存在并行活动任务时拒绝动态指定，避免只改写局部分支办理人。
     *
     * @return 无返回值；并行任务进入 BPMN 分配阶段或未返回 HTTP 409 时测试失败
     */
    @Test
    void rejectsParallelActiveTasksBeforeReadingBpmn()
    {
        Task sourceTask = sourceTask();
        when(userSelectionValidator.requireApprovalEligibleUserIds(List.of(8L)))
                .thenReturn(List.of("8"));
        stubCurrentTasks(List.of(sourceTask, task("parallel-task", "parallel")));

        assertConflict(() -> assignmentService.prepare(sourceTask, List.of(8L)));

        verifyNoInteractions(repositoryService);
    }

    /**
     * 验证带条件表达式的直接分支不能由客户端动态覆盖下一办理人。
     *
     * @return 无返回值；条件分支被接受或错误契约变化时测试失败
     */
    @Test
    void rejectsConditionalOutgoingBranch()
    {
        Task sourceTask = sourceTask();
        when(userSelectionValidator.requireApprovalEligibleUserIds(List.of(8L)))
                .thenReturn(List.of("8"));
        stubCurrentTasks(List.of(sourceTask));
        stubDefinition(serialModel(1, true));

        assertConflict(() -> assignmentService.prepare(sourceTask, List.of(8L)));
    }

    /**
     * 验证来源节点存在多个直接后继时拒绝动态指定，禁止猜测实际条件执行结果。
     *
     * @return 无返回值；非唯一后继生成分配计划或未返回 HTTP 409 时测试失败
     */
    @Test
    void rejectsMultipleOutgoingSuccessors()
    {
        Task sourceTask = sourceTask();
        when(userSelectionValidator.requireApprovalEligibleUserIds(List.of(8L)))
                .thenReturn(List.of("8"));
        stubCurrentTasks(List.of(sourceTask));
        stubDefinition(serialModel(2, false));

        assertConflict(() -> assignmentService.prepare(sourceTask, List.of(8L)));
    }

    /**
     * 验证受控并行多实例后继在完成前写入节点专属集合变量并冻结 ALL 模式计划。
     *
     * @return 无返回值；集合变量、模式或计划字段不符合契约时测试失败
     */
    @Test
    void preparesControlledParallelMultiInstanceCollectionBeforeCompletion()
    {
        Task sourceTask = sourceTask();
        when(userSelectionValidator.requireApprovalEligibleUserIds(List.of(8L, 9L)))
                .thenReturn(List.of("8", "9"));
        stubCurrentTasks(List.of(sourceTask));
        BpmnModel model = serialModel(1, false);
        configureControlledMultiInstance(model, WorkflowMultiInstanceMode.ALL);
        stubDefinition(model);

        WorkflowNextTaskAssignmentService.AssignmentPlan plan = assignmentService.prepare(
                sourceTask, List.of(8L, 9L));

        assertThat(plan.multiInstance()).isTrue();
        assertThat(plan.multiInstanceMode()).isEqualTo(WorkflowMultiInstanceMode.ALL);
        assertThat(plan.userIds()).containsExactly("8", "9");
        verify(runtimeService).setVariable("instance-1", "wfMiUsers_review-1",
                List.of(8L, 9L));
    }

    /**
     * 验证固定成员多实例不接受当前办理人通过 nextUserIds 覆盖，空选择可直接进入 BPMN 固定集合。
     *
     * @return 无返回值；固定成员被动态覆盖或空选择被错误拦截时测试失败。
     */
    @Test
    void isolatesFixedMultiInstanceFromDynamicNextUserSelection()
    {
        Task sourceTask = sourceTask();
        BpmnModel model = serialModel(1, false);
        configureFixedMultiInstance(model, WorkflowMultiInstanceMode.ALL, "8,9");
        stubDefinition(model);
        when(userSelectionValidator.requireApprovalEligibleUserIds(List.of(10L)))
                .thenReturn(List.of("10"));
        stubCurrentTasks(List.of(sourceTask));

        assertConflict(() -> assignmentService.prepare(sourceTask, List.of(10L)));
        assertThat(assignmentService.prepare(sourceTask, List.of()).requested()).isFalse();
        verify(runtimeService, never()).setVariable(anyString(), anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    /**
     * 验证受控动态多实例后继在成员为空时于完成命令前返回 400，且不写集合变量。
     *
     * @return 无返回值；空成员被放行、错误码漂移或发生变量写入时测试失败
     */
    @Test
    void rejectsMissingUsersBeforeEnteringControlledMultiInstance()
    {
        Task sourceTask = sourceTask();
        when(userSelectionValidator.requireApprovalEligibleUserIds(List.of()))
                .thenReturn(List.of());
        BpmnModel model = serialModel(1, false);
        configureControlledMultiInstance(model, WorkflowMultiInstanceMode.ANY);
        stubDefinition(model);

        assertThatThrownBy(() -> assignmentService.prepare(sourceTask, List.of()))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getMessage())
                            .isEqualTo("动态多实例下一办理人不能为空");
                });

        verify(runtimeService, never()).setVariable(anyString(), anyString(),
                org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(taskService);
    }

    /**
     * 验证普通后继未选择动态办理人时继续返回空计划，保持部署模型默认分配规则。
     *
     * @return 无返回值；普通 BPMN 被误判为必填或产生运行时写入时测试失败
     */
    @Test
    void preservesDefaultAssignmentWhenOptionalUsersAreMissing()
    {
        Task sourceTask = sourceTask();
        when(userSelectionValidator.requireApprovalEligibleUserIds(List.of()))
                .thenReturn(List.of());
        stubDefinition(serialModel(1, false));

        WorkflowNextTaskAssignmentService.AssignmentPlan plan =
                assignmentService.prepare(sourceTask, List.of());

        assertThat(plan.requested()).isFalse();
        verifyNoInteractions(taskService, runtimeService);
    }

    /**
     * 验证来源任务完成后，受控多实例计划会对账真实任务、父子 execution、服务端变量和根计数。
     *
     * @return 无返回值；任一多实例运行结构未被读取或正常结构被错误拒绝时测试失败
     */
    @Test
    void appliesControlledMultiInstancePlanAfterFullEngineReconciliation()
    {
        WorkflowNextTaskAssignmentService.AssignmentPlan plan =
                new WorkflowNextTaskAssignmentService.AssignmentPlan(
                        "instance-1", "source-task", "review-1",
                        List.of("8", "9"), true, WorkflowMultiInstanceMode.ALL);
        Task taskEight = task("task-8", "review-1");
        Task taskNine = task("task-9", "review-1");
        when(taskEight.getAssignee()).thenReturn("8");
        when(taskEight.getExecutionId()).thenReturn("exec-8");
        when(taskNine.getAssignee()).thenReturn("9");
        when(taskNine.getExecutionId()).thenReturn("exec-9");
        TaskQuery taskQuery = mock(TaskQuery.class, RETURNS_SELF);
        when(taskService.createTaskQuery()).thenReturn(taskQuery);
        when(taskQuery.list()).thenReturn(List.of(taskEight, taskNine));

        Execution executionEight = mock(Execution.class);
        when(executionEight.getId()).thenReturn("exec-8");
        when(executionEight.getParentId()).thenReturn("mi-root");
        when(executionEight.getActivityId()).thenReturn("review-1");
        Execution executionNine = mock(Execution.class);
        when(executionNine.getId()).thenReturn("exec-9");
        when(executionNine.getParentId()).thenReturn("mi-root");
        when(executionNine.getActivityId()).thenReturn("review-1");
        Execution rootExecution = mock(Execution.class);
        when(rootExecution.getId()).thenReturn("mi-root");
        when(rootExecution.getProcessInstanceId()).thenReturn("instance-1");
        when(rootExecution.getActivityId()).thenReturn("review-1");
        ExecutionQuery executionQuery = mock(ExecutionQuery.class, RETURNS_SELF);
        when(runtimeService.createExecutionQuery()).thenReturn(executionQuery);
        when(executionQuery.singleResult()).thenReturn(
                executionEight, executionNine, rootExecution);
        when(executionQuery.list()).thenReturn(List.of(executionEight, executionNine));

        when(runtimeService.getVariable("instance-1", "_wfMiMembers_review-1"))
                .thenReturn(List.of("8", "9"));
        when(runtimeService.getVariable("instance-1", "_wfMiRevision_review-1"))
                .thenReturn(0);
        when(runtimeService.getVariable("instance-1", "_wfMiMode_review-1"))
                .thenReturn("ALL");
        when(runtimeService.getVariableLocal("mi-root", "nrOfInstances"))
                .thenReturn(2);
        when(runtimeService.getVariableLocal("mi-root", "nrOfActiveInstances"))
                .thenReturn(2);
        when(runtimeService.getVariableLocal("mi-root", "nrOfCompletedInstances"))
                .thenReturn(0);

        assignmentService.apply(plan);

        verify(runtimeService).getVariableLocal("mi-root", "nrOfInstances");
        verify(runtimeService).getVariableLocal("mi-root", "nrOfActiveInstances");
        verify(runtimeService).getVariableLocal("mi-root", "nrOfCompletedInstances");
        verify(taskService, never()).setAssignee(anyString(), anyString());
    }

    /**
     * 验证单个动态办理人会清除静态候选关系并持久化为后继任务 assignee。
     *
     * @return 无返回值；候选清理、assignee 写入或写后复核不符合时测试失败
     */
    @Test
    void appliesSingleUserAsExclusiveAssignee()
    {
        WorkflowNextTaskAssignmentService.AssignmentPlan plan = assignmentPlan(List.of("8"));
        Task nextTask = task("next-task", "review-1");
        Task refreshedTask = task("next-task", "review-1");
        when(refreshedTask.getAssignee()).thenReturn("8");
        stubApplyQueries(List.of(nextTask), refreshedTask);
        List<IdentityLink> existingLinks = List.of(
                candidateUser("3"), candidateGroup("ROLE2"));
        when(taskService.getIdentityLinksForTask("next-task")).thenReturn(
                existingLinks, List.of());

        assignmentService.apply(plan);

        verify(taskService).deleteCandidateUser("next-task", "3");
        verify(taskService).deleteCandidateGroup("next-task", "ROLE2");
        verify(taskService).setAssignee("next-task", "8");
        verify(taskService, never()).addCandidateUser(anyString(), anyString());
    }

    /**
     * 验证多个动态办理人会清除静态分配、置空 assignee 并逐个写入候选用户。
     *
     * @return 无返回值；多人候选集合、候选组清理或写后复核不符合时测试失败
     */
    @Test
    void appliesMultipleUsersAsCandidateUsers()
    {
        WorkflowNextTaskAssignmentService.AssignmentPlan plan = assignmentPlan(
                List.of("8", "9"));
        Task nextTask = task("next-task", "review-1");
        Task refreshedTask = task("next-task", "review-1");
        stubApplyQueries(List.of(nextTask), refreshedTask);
        List<IdentityLink> existingLinks = List.of(candidateGroup("ROLE2"));
        List<IdentityLink> appliedLinks = List.of(
                candidateUser("8"), candidateUser("9"));
        when(taskService.getIdentityLinksForTask("next-task")).thenReturn(
                existingLinks, appliedLinks);

        assignmentService.apply(plan);

        verify(taskService).deleteCandidateGroup("next-task", "ROLE2");
        verify(taskService).setAssignee("next-task", null);
        verify(taskService).addCandidateUser("next-task", "8");
        verify(taskService).addCandidateUser("next-task", "9");
    }

    /**
     * 验证来源任务完成后产生多个活动后继任务时，在任何身份写入前拒绝应用计划。
     *
     * @return 无返回值；非唯一真实后继被部分修改或未返回 HTTP 409 时测试失败
     */
    @Test
    void rejectsNonUniqueRuntimeSuccessorsBeforeMutation()
    {
        WorkflowNextTaskAssignmentService.AssignmentPlan plan = assignmentPlan(List.of("8"));
        stubApplyQueries(List.of(
                task("next-task-1", "review-1"),
                task("next-task-2", "review-1")), null);

        assertConflict(() -> assignmentService.apply(plan));

        verify(taskService, never()).setAssignee(anyString(), anyString());
        verify(taskService, never()).addCandidateUser(anyString(), anyString());
    }

    /**
     * 验证身份写入后的真实任务状态与冻结计划不一致时抛出冲突，驱动外层事务回滚。
     *
     * @return 无返回值；写后漂移被静默接受或未发出回滚异常时测试失败
     */
    @Test
    void raisesRollbackSignalWhenPostWriteStateDiverges()
    {
        WorkflowNextTaskAssignmentService.AssignmentPlan plan = assignmentPlan(List.of("8"));
        Task nextTask = task("next-task", "review-1");
        Task refreshedTask = task("next-task", "review-1");
        when(refreshedTask.getAssignee()).thenReturn("99");
        stubApplyQueries(List.of(nextTask), refreshedTask);
        when(taskService.getIdentityLinksForTask("next-task"))
                .thenReturn(List.of(), List.of());

        assertConflict(() -> assignmentService.apply(plan));

        verify(taskService).setAssignee("next-task", "8");
    }

    /**
     * 创建动作前来源任务替身。
     *
     * @return Task，具有实例、定义和 BPMN 节点关系的来源任务
     */
    private Task sourceTask()
    {
        Task sourceTask = task("source-task", "source-node");
        when(sourceTask.getProcessDefinitionId()).thenReturn("definition-1");
        return sourceTask;
    }

    /**
     * 创建指定任务主键和节点 key 的活动任务替身。
     *
     * @param taskId String，Flowable 任务主键
     * @param taskDefinitionKey String，BPMN 用户任务节点 key
     * @return Task，属于测试流程实例且默认未挂起、未委派的任务
     */
    private Task task(String taskId, String taskDefinitionKey)
    {
        Task task = mock(Task.class);
        when(task.getId()).thenReturn(taskId);
        when(task.getProcessInstanceId()).thenReturn("instance-1");
        when(task.getTaskDefinitionKey()).thenReturn(taskDefinitionKey);
        return task;
    }

    /**
     * 配置动作前流程实例的全部活动任务查询结果。
     *
     * @param currentTasks List&lt;Task&gt;，当前流程实例真实活动任务集合
     * @return 无返回值；后续 prepare 调用将读取该集合
     */
    private void stubCurrentTasks(List<Task> currentTasks)
    {
        TaskQuery currentQuery = mock(TaskQuery.class, RETURNS_SELF);
        when(taskService.createTaskQuery()).thenReturn(currentQuery);
        when(currentQuery.list()).thenReturn(currentTasks);
    }

    /**
     * 配置流程定义与已部署 BPMN 模型查询。
     *
     * @param model BpmnModel，包含来源节点及后继拓扑的部署模型
     * @return 无返回值；后续 prepare 调用将读取该定义和模型
     */
    private void stubDefinition(BpmnModel model)
    {
        ProcessDefinition definition = mock(ProcessDefinition.class);
        when(definition.getKey()).thenReturn("process-key");
        when(repositoryService.getProcessDefinition("definition-1")).thenReturn(definition);
        when(repositoryService.getBpmnModel("definition-1")).thenReturn(model);
    }

    /**
     * 构造来源用户任务及指定数量的直接后继用户任务，可选择首条顺序流带条件表达式。
     *
     * @param outgoingCount int，来源节点直接后继数量
     * @param conditional boolean，首条顺序流是否带条件表达式
     * @return BpmnModel，已注册完整双向顺序流引用的 BPMN 模型
     */
    private BpmnModel serialModel(int outgoingCount, boolean conditional)
    {
        BpmnModel model = new BpmnModel();
        org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
        process.setId("process-key");
        process.setExecutable(true);
        model.addProcess(process);

        UserTask source = new UserTask();
        source.setId("source-node");
        process.addFlowElement(source);
        for (int index = 1; index <= outgoingCount; index++)
        {
            UserTask target = new UserTask();
            target.setId("review-" + index);
            process.addFlowElement(target);
            SequenceFlow flow = new SequenceFlow(source.getId(), target.getId());
            flow.setId("flow-" + index);
            flow.setSourceFlowElement(source);
            flow.setTargetFlowElement(target);
            if (conditional && index == 1)
            {
                flow.setConditionExpression("${approved}");
            }
            source.getOutgoingFlows().add(flow);
            target.getIncomingFlows().add(flow);
            process.addFlowElement(flow);
        }
        return model;
    }

    /**
     * 将测试模型的唯一后继配置为正式受控动态并行多实例节点。
     *
     * @param model BpmnModel，包含 review-1 后继用户任务的模型
     * @param mode WorkflowMultiInstanceMode，目标 ALL 会签或 ANY 或签模式
     * @return 无返回值，目标节点被原位更新为固定模型契约
     */
    private void configureControlledMultiInstance(BpmnModel model,
            WorkflowMultiInstanceMode mode)
    {
        UserTask target = (UserTask) model.getMainProcess().getFlowElement("review-1");
        target.setAssignee(WorkflowMultiInstanceModelContract.ASSIGNEE_EXPRESSION);
        MultiInstanceLoopCharacteristics loop = new MultiInstanceLoopCharacteristics();
        loop.setInputDataItem(WorkflowMultiInstanceModelContract.COLLECTION_EXPRESSION);
        loop.setElementVariable(WorkflowMultiInstanceModelContract.ELEMENT_VARIABLE);
        loop.setCompletionCondition(mode == WorkflowMultiInstanceMode.ALL
                ? WorkflowMultiInstanceModelContract.ALL_COMPLETION_CONDITION
                : WorkflowMultiInstanceModelContract.ANY_COMPLETION_CONDITION);
        target.setLoopCharacteristics(loop);
    }

    /**
     * 将测试模型的唯一后继配置为固定成员受控并行多实例节点。
     *
     * @param model BpmnModel，包含 review-1 后继用户任务的模型。
     * @param mode WorkflowMultiInstanceMode，目标 ALL 会签或 ANY 或签模式。
     * @param userIds String，逗号分隔的固定用户主键。
     * @return 无返回值，目标节点被原位更新为固定成员模型契约。
     */
    private void configureFixedMultiInstance(BpmnModel model,
            WorkflowMultiInstanceMode mode, String userIds)
    {
        configureControlledMultiInstance(model, mode);
        UserTask target = (UserTask) model.getMainProcess().getFlowElement("review-1");
        target.getLoopCharacteristics().setInputDataItem(
                "${multiInstanceHandler.getFixedUserIds(execution, '" + userIds + "')}");
    }

    /**
     * 创建动作完成后使用的动态分配计划。
     *
     * @param userIds List&lt;String&gt;，预期写入的有效用户主键
     * @return AssignmentPlan，指向唯一 review-1 后继节点的冻结计划
     */
    private WorkflowNextTaskAssignmentService.AssignmentPlan assignmentPlan(List<String> userIds)
    {
        return new WorkflowNextTaskAssignmentService.AssignmentPlan(
                "instance-1", "source-task", "review-1", userIds);
    }

    /**
     * 配置动作完成后的后继活动任务查询和写后单任务复核查询。
     *
     * @param nextTasks List&lt;Task&gt;，完成来源任务后真实活动任务集合
     * @param refreshedTask Task，身份写入后按任务主键重新读取的任务；失败分支可为 null
     * @return 无返回值；后续 apply 调用依次使用两个查询替身
     */
    private void stubApplyQueries(List<Task> nextTasks, Task refreshedTask)
    {
        TaskQuery nextQuery = mock(TaskQuery.class, RETURNS_SELF);
        TaskQuery refreshQuery = mock(TaskQuery.class, RETURNS_SELF);
        when(taskService.createTaskQuery()).thenReturn(nextQuery, refreshQuery);
        when(nextQuery.list()).thenReturn(nextTasks);
        when(refreshQuery.singleResult()).thenReturn(refreshedTask);
    }

    /**
     * 创建候选用户身份关系替身。
     *
     * @param userId String，Flowable 候选用户主键
     * @return IdentityLink，类型为 candidate 且仅包含用户主键的身份关系
     */
    private IdentityLink candidateUser(String userId)
    {
        IdentityLink identityLink = mock(IdentityLink.class);
        when(identityLink.getType()).thenReturn(IdentityLinkType.CANDIDATE);
        when(identityLink.getUserId()).thenReturn(userId);
        return identityLink;
    }

    /**
     * 创建候选组身份关系替身。
     *
     * @param groupId String，Flowable 候选组标识
     * @return IdentityLink，类型为 candidate 且仅包含候选组的身份关系
     */
    private IdentityLink candidateGroup(String groupId)
    {
        IdentityLink identityLink = mock(IdentityLink.class);
        when(identityLink.getType()).thenReturn(IdentityLinkType.CANDIDATE);
        when(identityLink.getGroupId()).thenReturn(groupId);
        return identityLink;
    }

    /**
     * 断言动态分配结构或并发状态不受支持时返回稳定 HTTP 409。
     *
     * @param action ThrowingCallable，预期触发结构冲突的调用
     * @return 无返回值；异常类型、状态码或提示不匹配时测试失败
     */
    private void assertConflict(ThrowingCallable action)
    {
        assertThatThrownBy(action).isInstanceOfSatisfying(ServiceException.class, exception ->
        {
            assertThat(exception.getCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(exception.getMessage()).isEqualTo("当前流程结构不支持动态指定下一办理人");
        });
    }
}
