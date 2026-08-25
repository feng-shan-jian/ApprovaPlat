package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.delegate.ExecutionListener;
import org.flowable.engine.delegate.TaskListener;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.identity.WorkflowUserSelectionValidator;
import com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport;

/**
 * 只验证正式业务链无法替代的两个 Flowable changeState 多实例引擎原语。
 */
class WorkflowMultiInstanceEngineContractIntegrationTest
{
    /** 整组收敛后的申请人主键。 */
    private static final String APPLICANT_ID = "100";

    /** 契约场景的初始审批成员。 */
    private static final List<String> MEMBERS = List.of("201", "202", "203");

    /** 同活动重建前通过公共 API 增加的成员。 */
    private static final String ADDED_MEMBER = "204";

    /** Flowable 多实例根维护的总实例数。 */
    private static final String NUMBER_OF_INSTANCES = "nrOfInstances";

    /** Flowable 多实例根维护的活动实例数。 */
    private static final String NUMBER_OF_ACTIVE_INSTANCES =
            "nrOfActiveInstances";

    /** Flowable 多实例根维护的已完成实例数。 */
    private static final String NUMBER_OF_COMPLETED_INSTANCES =
            "nrOfCompletedInstances";

    /** 当前用例独占的真实引擎生命周期。 */
    private WorkflowFlowableEngineTestSupport engineHarness;

    /** 当前用例真实 Flowable 引擎。 */
    private ProcessEngine processEngine;

    /** changeState、execution 和受控变量入口。 */
    private RuntimeService runtimeService;

    /** 活动任务查询、改派和完成入口。 */
    private TaskService taskService;

    /** 保证公共 Flowable 操作在同一事务提交或回滚。 */
    private TransactionTemplate transactionTemplate;

    /**
     * 启动独立 Flowable/H2 并部署两个最小契约流程。
     *
     * @return void，无返回值；每个用例使用独立数据库
     */
    @BeforeEach
    void setUpEngine()
    {
        WorkflowIdentityResolver identityResolver = mock(WorkflowIdentityResolver.class);
        when(identityResolver.resolveApprovalEligibleUserIds(anyCollection()))
                .thenAnswer(invocation ->
                {
                    Collection<String> requested = invocation.getArgument(0);
                    return new LinkedHashSet<>(requested);
                });
        WorkflowMultiInstanceHandler handler = new WorkflowMultiInstanceHandler(
                new WorkflowUserSelectionValidator(identityResolver),
                new WorkflowMultiInstanceTransitionCoordinator());
        ExecutionListener noOpExecutionListener = execution -> { };
        TaskListener noOpTaskListener = task -> { };
        engineHarness = WorkflowFlowableEngineTestSupport.start("mi-contract",
                Map.of("multiInstanceHandler", handler,
                        "firstNodeMigrationFailureGuard", noOpExecutionListener,
                        "lateAssignmentFailureGuard", noOpTaskListener,
                        "crossActivityMigrationFailureGuard", noOpExecutionListener));
        processEngine = engineHarness.processEngine();
        runtimeService = processEngine.getRuntimeService();
        taskService = processEngine.getTaskService();
        transactionTemplate = engineHarness.transactionTemplate();
        processEngine.getRepositoryService().createDeployment()
                .addClasspathResource(
                        "bpmn/workflow-multi-instance-first-node-contract.bpmn20.xml")
                .addClasspathResource(
                        "bpmn/workflow-multi-instance-cross-activity-contract.bpmn20.xml")
                .deploy();
    }

    /**
     * 关闭真实引擎和 H2，禁止任务、execution 或变量跨用例泄漏。
     *
     * @return void，无返回值
     */
    @AfterEach
    void tearDownEngine()
    {
        if (engineHarness != null)
        {
            engineHarness.close();
        }
    }

    /**
     * 验证同活动首节点临时根可收敛并按更新后的成员快照重建全新根。
     *
     * @return void，新根、成员、模式、revision 或计数漂移时失败
     */
    @Test
    void recreatesTemporaryMultiInstanceRootAtSameActivity()
    {
        ProcessInstance instance = startControlledProcess(
                "firstAllReturn", "firstAllGroup", MEMBERS);
        List<String> adjustedMembers = addMember(
                instance.getId(), "firstAllGroup", ADDED_MEMBER);
        assertControlledGroup(instance.getId(), "firstAllGroup", adjustedMembers,
                WorkflowMultiInstanceMode.ALL, 1, new Counts(4, 4, 0));
        String oldRootId = requireRoot(instance.getId(), "firstAllGroup").getId();

        collapseGroup(instance.getId(), "firstAllGroup");

        assertThat(requireSingleTask(instance.getId(), "firstAllGroup").getAssignee())
                .isEqualTo(APPLICANT_ID);
        assertThat(requireRoot(instance.getId(), "firstAllGroup").getId())
                .isEqualTo(oldRootId);
        assertCounts(instance.getId(), "firstAllGroup", new Counts(1, 1, 0));
        assertControlledVariables(instance.getId(), "firstAllGroup",
                adjustedMembers, WorkflowMultiInstanceMode.ALL, 1);

        recreateAtSameActivity(instance.getId(), "firstAllGroup");

        assertThat(requireRoot(instance.getId(), "firstAllGroup").getId())
                .isNotEqualTo(oldRootId);
        assertControlledGroup(instance.getId(), "firstAllGroup", adjustedMembers,
                WorkflowMultiInstanceMode.ALL, 1, new Counts(4, 4, 0));
    }

    /**
     * 验证部分完成的中间 ALL 根可跨活动迁回首审批并重新进入完整新根。
     *
     * @return void，迁移目标、成员快照或新根计数漂移时失败
     */
    @Test
    void migratesMultiInstanceRootAcrossActivities()
    {
        ProcessInstance instance = startControlledProcess(
                "middleAllReturn", "middleAllGroup", MEMBERS);
        taskService.complete(requireSingleTask(instance.getId(), "middlePre").getId());
        Task completedMember = requireGroupTasks(
                instance.getId(), "middleAllGroup").get(0);
        completeMember(completedMember);
        assertPartiallyCompleted(instance.getId(), "middleAllGroup",
                completedMember.getAssignee());
        String oldRootId = requireRoot(instance.getId(), "middleAllGroup").getId();

        ReturnedTask returned = moveToFirstApproval(
                instance.getId(), "middleAllGroup", "middlePre");

        Task applicantTask = requireSingleTask(instance.getId(), "middlePre");
        assertThat(applicantTask.getId()).isEqualTo(returned.taskId());
        assertThat(applicantTask.getAssignee()).isEqualTo(APPLICANT_ID);
        assertNoActivityExecution(instance.getId(), "middleAllGroup");
        assertControlledVariables(instance.getId(), "middleAllGroup", MEMBERS,
                WorkflowMultiInstanceMode.ALL, 1);

        restoreAndComplete(returned);

        assertThat(requireRoot(instance.getId(), "middleAllGroup").getId())
                .isNotEqualTo(oldRootId);
        assertControlledGroup(instance.getId(), "middleAllGroup", MEMBERS,
                WorkflowMultiInstanceMode.ALL, 1, new Counts(3, 3, 0));
    }

    /**
     * 使用受控集合变量启动真实流程。
     *
     * @param processDefinitionKey String，流程定义 key
     * @param activityId String，受控多实例节点 ID
     * @param members List&lt;String&gt;，初始成员
     * @return ProcessInstance，已经进入业务节点的真实实例
     */
    private ProcessInstance startControlledProcess(String processDefinitionKey,
            String activityId, List<String> members)
    {
        return runtimeService.startProcessInstanceByKey(processDefinitionKey,
                Map.of(WorkflowMultiInstanceVariables.userCollectionName(activityId),
                        members.stream().map(Long::valueOf).toList()));
    }

    /**
     * 通过 Flowable 公共加签 API 增加成员并同步正式受控变量。
     *
     * @param processInstanceId String，流程实例主键
     * @param activityId String，受控节点 ID
     * @param userId String，新增成员主键
     * @return List&lt;String&gt;，调整后的有序成员
     */
    private List<String> addMember(String processInstanceId, String activityId,
            String userId)
    {
        return transactionTemplate.execute(status ->
        {
            List<String> members = new ArrayList<>(controlledMembers(
                    processInstanceId, activityId));
            runtimeService.setVariable(processInstanceId,
                    WorkflowMultiInstanceVariables.revisionName(activityId), 1);
            runtimeService.addMultiInstanceExecution(activityId, processInstanceId,
                    Map.of(WorkflowMultiInstanceModelContract.ELEMENT_VARIABLE, userId));
            members.add(userId);
            persistMembers(processInstanceId, activityId, members);
            return List.copyOf(members);
        });
    }

    /**
     * 删除 sibling 并把唯一保留任务改派给申请人。
     *
     * @param processInstanceId String，流程实例主键
     * @param activityId String，当前多实例节点 ID
     * @return void，无返回值
     */
    private void collapseGroup(String processInstanceId, String activityId)
    {
        transactionTemplate.executeWithoutResult(status ->
        {
            List<Task> tasks = requireGroupTasks(processInstanceId, activityId);
            Task survivor = tasks.get(0);
            tasks.stream().filter(task -> !task.getId().equals(survivor.getId()))
                    .forEach(task -> runtimeService.deleteMultiInstanceExecution(
                            task.getExecutionId(), false));
            taskService.setOwner(survivor.getId(), null);
            taskService.setAssignee(survivor.getId(), APPLICANT_ID);
        });
    }

    /**
     * 将临时根迁到相同活动，让 Flowable 创建全新根和任务。
     *
     * @param processInstanceId String，流程实例主键
     * @param activityId String，受控节点 ID
     * @return void，无返回值
     */
    private void recreateAtSameActivity(String processInstanceId, String activityId)
    {
        transactionTemplate.executeWithoutResult(status -> runtimeService
                .createChangeActivityStateBuilder()
                .processInstanceId(processInstanceId)
                .moveExecutionToActivityId(
                        requireRoot(processInstanceId, activityId).getId(), activityId)
                .changeState());
    }

    /**
     * 将中间多实例根迁回首审批，并把新任务改派给申请人。
     *
     * @param processInstanceId String，流程实例主键
     * @param groupActivityId String，中间多实例节点 ID
     * @param targetActivityId String，首审批节点 ID
     * @return ReturnedTask，迁移创建任务及其原办理人
     */
    private ReturnedTask moveToFirstApproval(String processInstanceId,
            String groupActivityId, String targetActivityId)
    {
        return transactionTemplate.execute(status ->
        {
            runtimeService.createChangeActivityStateBuilder()
                    .processInstanceId(processInstanceId)
                    .moveExecutionToActivityId(
                            requireRoot(processInstanceId, groupActivityId).getId(),
                            targetActivityId)
                    .changeState();
            Task task = requireSingleTask(processInstanceId, targetActivityId);
            String originalAssignee = task.getAssignee();
            assertThat(originalAssignee).isEqualTo("301");
            taskService.setOwner(task.getId(), null);
            taskService.setAssignee(task.getId(), APPLICANT_ID);
            return new ReturnedTask(task.getId(), processInstanceId, originalAssignee);
        });
    }

    /**
     * 恢复首审批原办理人并完成任务，使流程重新进入中间多实例节点。
     *
     * @param returned ReturnedTask，迁回任务的冻结事实
     * @return void，无返回值
     */
    private void restoreAndComplete(ReturnedTask returned)
    {
        transactionTemplate.executeWithoutResult(status ->
        {
            Task task = taskService.createTaskQuery().taskId(returned.taskId())
                    .active().singleResult();
            assertThat(task).isNotNull();
            taskService.setAssignee(task.getId(), returned.originalAssignee());
            taskService.complete(task.getId());
        });
    }

    /**
     * 推进 revision 后完成真实成员任务。
     *
     * @param task Task，待完成成员任务
     * @return void，无返回值
     */
    private void completeMember(Task task)
    {
        transactionTemplate.executeWithoutResult(status ->
        {
            runtimeService.setVariable(task.getProcessInstanceId(),
                    WorkflowMultiInstanceVariables.revisionName(
                            task.getTaskDefinitionKey()), 1);
            taskService.complete(task.getId());
        });
    }

    /**
     * 同步 handler 使用的成员快照与受控集合。
     *
     * @param processInstanceId String，流程实例主键
     * @param activityId String，受控节点 ID
     * @param members List&lt;String&gt;，完整有序成员
     * @return void，无返回值
     */
    private void persistMembers(String processInstanceId, String activityId,
            List<String> members)
    {
        runtimeService.setVariable(processInstanceId,
                WorkflowMultiInstanceVariables.memberSnapshotName(activityId),
                new ArrayList<>(members));
        runtimeService.setVariable(processInstanceId,
                WorkflowMultiInstanceVariables.userCollectionName(activityId),
                members.stream().map(Long::valueOf).toList());
    }

    /**
     * 校验完整活动组的任务、受控变量和根计数。
     *
     * @param processInstanceId String，流程实例主键
     * @param activityId String，受控节点 ID
     * @param expectedMembers List&lt;String&gt;，预期成员
     * @param expectedMode WorkflowMultiInstanceMode，预期模式
     * @param expectedRevision int，预期 revision
     * @param expectedCounts Counts，预期根计数
     * @return void，无返回值
     */
    private void assertControlledGroup(String processInstanceId, String activityId,
            List<String> expectedMembers, WorkflowMultiInstanceMode expectedMode,
            int expectedRevision, Counts expectedCounts)
    {
        assertThat(requireGroupTasks(processInstanceId, activityId))
                .extracting(Task::getAssignee)
                .containsExactlyInAnyOrderElementsOf(expectedMembers);
        assertControlledVariables(processInstanceId, activityId, expectedMembers,
                expectedMode, expectedRevision);
        assertCounts(processInstanceId, activityId, expectedCounts);
    }

    /**
     * 校验中间 ALL 已完成一人且其余成员仍活动。
     *
     * @param processInstanceId String，流程实例主键
     * @param activityId String，中间多实例节点 ID
     * @param completedMember String，已完成成员主键
     * @return void，无返回值
     */
    private void assertPartiallyCompleted(String processInstanceId,
            String activityId, String completedMember)
    {
        assertThat(requireGroupTasks(processInstanceId, activityId))
                .extracting(Task::getAssignee)
                .containsExactlyInAnyOrderElementsOf(MEMBERS.stream()
                        .filter(member -> !member.equals(completedMember)).toList());
        assertControlledVariables(processInstanceId, activityId, MEMBERS,
                WorkflowMultiInstanceMode.ALL, 1);
        assertCounts(processInstanceId, activityId, new Counts(3, 2, 1));
    }

    /**
     * 校验成员快照、集合、模式和 revision。
     *
     * @param processInstanceId String，流程实例主键
     * @param activityId String，受控节点 ID
     * @param expectedMembers List&lt;String&gt;，预期有序成员
     * @param expectedMode WorkflowMultiInstanceMode，预期模式
     * @param expectedRevision int，预期 revision
     * @return void，无返回值
     */
    private void assertControlledVariables(String processInstanceId,
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
        assertThat(runtimeService.getVariable(processInstanceId,
                WorkflowMultiInstanceVariables.revisionName(activityId)))
                .isEqualTo(expectedRevision);
    }

    /**
     * 读取正式有序成员快照。
     *
     * @param processInstanceId String，流程实例主键
     * @param activityId String，受控节点 ID
     * @return List&lt;String&gt;，不可变成员列表
     */
    private List<String> controlledMembers(String processInstanceId,
            String activityId)
    {
        Object raw = runtimeService.getVariable(processInstanceId,
                WorkflowMultiInstanceVariables.memberSnapshotName(activityId));
        assertThat(raw).isInstanceOf(List.class);
        return ((List<?>) raw).stream().map(String.class::cast).toList();
    }

    /**
     * 校验多实例根的三个引擎计数。
     *
     * @param processInstanceId String，流程实例主键
     * @param activityId String，受控节点 ID
     * @param expected Counts，预期计数
     * @return void，无返回值
     */
    private void assertCounts(String processInstanceId, String activityId,
            Counts expected)
    {
        Execution root = requireRoot(processInstanceId, activityId);
        assertThat(new Counts(
                (Integer) runtimeService.getVariableLocal(
                        root.getId(), NUMBER_OF_INSTANCES),
                (Integer) runtimeService.getVariableLocal(
                        root.getId(), NUMBER_OF_ACTIVE_INSTANCES),
                (Integer) runtimeService.getVariableLocal(
                        root.getId(), NUMBER_OF_COMPLETED_INSTANCES)))
                .isEqualTo(expected);
    }

    /**
     * 通过成员任务共同父节点定位唯一多实例根。
     *
     * @param processInstanceId String，流程实例主键
     * @param activityId String，受控节点 ID
     * @return Execution，唯一根 execution
     */
    private Execution requireRoot(String processInstanceId, String activityId)
    {
        List<String> parentIds = requireGroupTasks(processInstanceId, activityId)
                .stream().map(Task::getExecutionId)
                .map(id -> runtimeService.createExecutionQuery()
                        .executionId(id).singleResult())
                .map(Execution::getParentId).distinct().toList();
        assertThat(parentIds).singleElement();
        Execution root = runtimeService.createExecutionQuery()
                .executionId(parentIds.get(0)).singleResult();
        assertThat(root).isNotNull();
        return root;
    }

    /**
     * 查询指定多实例节点的全部活动任务。
     *
     * @param processInstanceId String，流程实例主键
     * @param activityId String，节点 ID
     * @return List&lt;Task&gt;，按任务主键稳定排序的非空列表
     */
    private List<Task> requireGroupTasks(String processInstanceId, String activityId)
    {
        List<Task> tasks = taskService.createTaskQuery()
                .processInstanceId(processInstanceId).taskDefinitionKey(activityId)
                .active().list().stream().sorted(Comparator.comparing(Task::getId))
                .toList();
        assertThat(tasks).isNotEmpty();
        return tasks;
    }

    /**
     * 查询指定节点的唯一活动任务。
     *
     * @param processInstanceId String，流程实例主键
     * @param activityId String，节点 ID
     * @return Task，唯一活动任务
     */
    private Task requireSingleTask(String processInstanceId, String activityId)
    {
        List<Task> tasks = taskService.createTaskQuery()
                .processInstanceId(processInstanceId).taskDefinitionKey(activityId)
                .active().list();
        assertThat(tasks).singleElement();
        return tasks.get(0);
    }

    /**
     * 校验迁移后旧活动不存在 execution。
     *
     * @param processInstanceId String，流程实例主键
     * @param activityId String，已经离开的节点 ID
     * @return void，无返回值
     */
    private void assertNoActivityExecution(String processInstanceId,
            String activityId)
    {
        assertThat(runtimeService.createExecutionQuery()
                .processInstanceId(processInstanceId).list())
                .noneMatch(execution -> activityId.equals(execution.getActivityId()));
    }

    /** 多实例根三个引擎计数。 */
    private record Counts(int instances, int active, int completed)
    {
    }

    /** 跨活动迁移创建任务及其原办理人。 */
    private record ReturnedTask(String taskId, String processInstanceId,
            String originalAssignee)
    {
    }
}
