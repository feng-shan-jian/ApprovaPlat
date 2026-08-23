package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.ExecutionListener;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;

/**
 * 使用真实 Flowable 8 公共 API 验证多实例根与普通 execution 的跨活动退回迁移。
 */
class WorkflowMultiInstanceCrossActivityReturnContractIntegrationTest
        extends WorkflowMultiInstanceContractIntegrationSupport
{
    /** 中间会签迁回首审批路径的 execution start 故障注入器。 */
    private final CrossActivityMigrationFailureGuard migrationFailureGuard =
            new CrossActivityMigrationFailureGuard();

    /**
     * 返回跨活动退回契约使用的外部 BPMN。
     *
     * @return String，classpath 下的跨活动契约 BPMN 路径
     */
    @Override
    protected String bpmnResource()
    {
        return "bpmn/workflow-multi-instance-cross-activity-contract.bpmn20.xml";
    }

    /**
     * 注册中间会签跨活动迁移失败使用的 execution start 监听器。
     *
     * @return Map&lt;Object,Object&gt;，BPMN delegateExpression 对应的 Bean
     */
    @Override
    protected Map<Object, Object> scenarioBeans()
    {
        return Map.of("crossActivityMigrationFailureGuard", migrationFailureGuard);
    }

    /**
     * 验证中间 ALL 部分完成后，多实例根迁回真实首审批并重新沿首审批进入完整新组。
     *
     * @return void，迁移目标、成员快照或新轮计数不符合契约时失败
     */
    @Test
    void movesPartiallyCompletedMiddleAllGroupBackThroughFirstApproval()
    {
        ProcessInstance instance = startControlledProcess(
                "middleAllReturn", "middleAllGroup", ORIGINAL_APPROVER_IDS);
        taskService.complete(requireSingleTask(instance.getId(), "middlePre").getId());
        Task completedMember = requireGroupTasks(
                instance.getId(), "middleAllGroup").get(0);
        completeControlledMember(completedMember);
        assertPartiallyCompletedGroup(instance.getId(), "middleAllGroup",
                ORIGINAL_APPROVER_IDS, completedMember.getAssignee(),
                WorkflowMultiInstanceMode.ALL, 1,
                new MultiInstanceCounts(3, 2, 1));
        String oldRootId = requireMultiInstanceRoot(
                instance.getId(), "middleAllGroup").getId();

        ReturnedFirstApproval returned = moveMiddleGroupToFirstApproval(
                instance.getId(), "middleAllGroup", "middlePre");

        Task applicantTask = requireSingleTask(instance.getId(), "middlePre");
        assertThat(applicantTask.getId()).isEqualTo(returned.taskId());
        assertThat(applicantTask.getAssignee()).isEqualTo(APPLICANT_ID);
        assertNoActivityExecutions(instance.getId(), "middleAllGroup");
        assertControlledVariables(instance.getId(), "middleAllGroup",
                ORIGINAL_APPROVER_IDS, WorkflowMultiInstanceMode.ALL, 1);

        restoreAndCompleteFirstApproval(returned);

        assertThat(requireMultiInstanceRoot(instance.getId(), "middleAllGroup").getId())
                .isNotEqualTo(oldRootId);
        assertControlledGroup(instance.getId(), "middleAllGroup",
                ORIGINAL_APPROVER_IDS, WorkflowMultiInstanceMode.ALL, 1,
                new MultiInstanceCounts(3, 3, 0));
    }

    /**
     * 在部分完成的中间 ALL 根迁回首审批时注入 start 异常，验证跨活动迁移完整回滚。
     *
     * @return void，目标监听器未触发或任务、execution、计数、变量任一漂移时失败
     */
    @Test
    void rollsBackPartiallyCompletedMiddleGroupWhenReturnMigrationFails()
    {
        ProcessInstance instance = startControlledProcess(
                "middleAllReturn", "middleAllGroup", ORIGINAL_APPROVER_IDS);
        taskService.complete(requireSingleTask(instance.getId(), "middlePre").getId());
        Task completedMember = requireGroupTasks(
                instance.getId(), "middleAllGroup").get(0);
        completeControlledMember(completedMember);
        assertPartiallyCompletedGroup(instance.getId(), "middleAllGroup",
                ORIGINAL_APPROVER_IDS, completedMember.getAssignee(),
                WorkflowMultiInstanceMode.ALL, 1,
                new MultiInstanceCounts(3, 2, 1));
        String oldRootId = requireMultiInstanceRoot(
                instance.getId(), "middleAllGroup").getId();
        EngineSnapshot before = captureSnapshot(instance.getId());
        // 必须在首次首审批已经完成后再开启，确保异常来自 middleAllGroup -> middlePre 迁移。
        migrationFailureGuard.failNextMigration();

        assertThatThrownBy(() -> moveMiddleGroupToFirstApproval(
                instance.getId(), "middleAllGroup", "middlePre"))
                .isInstanceOf(FlowableException.class)
                .hasMessageContaining(
                        CrossActivityMigrationFailureGuard.FAILURE_MESSAGE);

        assertThat(migrationFailureGuard.failureObserved()).isTrue();
        assertThat(captureSnapshot(instance.getId())).isEqualTo(before);
        assertThat(requireMultiInstanceRoot(instance.getId(), "middleAllGroup").getId())
                .isEqualTo(oldRootId);
        assertPartiallyCompletedGroup(instance.getId(), "middleAllGroup",
                ORIGINAL_APPROVER_IDS, completedMember.getAssignee(),
                WorkflowMultiInstanceMode.ALL, 1,
                new MultiInstanceCounts(3, 2, 1));
        assertThat(taskService.createTaskQuery().processInstanceId(instance.getId())
                .taskDefinitionKey("middlePre").active().count()).isZero();
    }

    /**
     * 验证普通任务迁回已完成的首多实例节点时会先创建完整组，再可原子收敛并重新完整建组。
     *
     * @return void，普通 execution 迁入、组收敛或新轮次计数不符合契约时失败
     */
    @Test
    void movesOrdinaryTaskBackToCompletedFirstMultiInstanceActivity()
    {
        ProcessInstance instance = startControlledProcess(
                "ordinaryToFirstAllReturn", "ordinaryFirstAllGroup",
                ORIGINAL_APPROVER_IDS);
        for (Task member : List.copyOf(
                requireGroupTasks(instance.getId(), "ordinaryFirstAllGroup")))
        {
            completeControlledMember(member);
        }
        Task ordinaryTask = requireSingleTask(
                instance.getId(), "ordinaryFirstAllAfter");
        assertThat(controlledRevision(instance.getId(), "ordinaryFirstAllGroup"))
                .isEqualTo(3);

        Task applicantTask = moveOrdinaryTaskToFirstGroup(
                ordinaryTask, "ordinaryFirstAllGroup");

        assertNoActivityExecutions(instance.getId(), "ordinaryFirstAllAfter");
        assertThat(applicantTask.getAssignee()).isEqualTo(APPLICANT_ID);
        assertCounts(instance.getId(), "ordinaryFirstAllGroup",
                new MultiInstanceCounts(1, 1, 0));
        assertControlledVariables(instance.getId(), "ordinaryFirstAllGroup",
                ORIGINAL_APPROVER_IDS, WorkflowMultiInstanceMode.ALL, 3);

        recreateFirstGroup(instance.getId(), "ordinaryFirstAllGroup");

        assertControlledGroup(instance.getId(), "ordinaryFirstAllGroup",
                ORIGINAL_APPROVER_IDS, WorkflowMultiInstanceMode.ALL, 3,
                new MultiInstanceCounts(3, 3, 0));
    }

    /**
     * 以正式受控集合变量启动真实流程实例。
     *
     * @param processDefinitionKey String，待启动的流程定义 key
     * @param activityId String，流程中的受控多实例节点 ID
     * @param members List&lt;String&gt;，进入节点时的初始成员
     * @return ProcessInstance，已经进入首个业务节点的真实实例
     */
    private ProcessInstance startControlledProcess(String processDefinitionKey,
            String activityId, List<String> members)
    {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put(WorkflowMultiInstanceVariables.userCollectionName(activityId),
                members.stream().map(Long::valueOf).toList());
        return runtimeService.startProcessInstanceByKey(
                processDefinitionKey, variables);
    }

    /**
     * 将中间多实例根迁回真实首审批，并把迁移创建的唯一任务改派给发起人。
     *
     * @param processInstanceId String，活动流程实例主键
     * @param groupActivityId String，当前中间多实例节点 ID
     * @param firstApprovalActivityId String，历史首审批节点 ID
     * @return ReturnedFirstApproval，迁移创建任务及其原办理人
     */
    private ReturnedFirstApproval moveMiddleGroupToFirstApproval(
            String processInstanceId, String groupActivityId,
            String firstApprovalActivityId)
    {
        return transactionTemplate.execute(status ->
        {
            Execution root = requireMultiInstanceRoot(
                    processInstanceId, groupActivityId);
            runtimeService.createChangeActivityStateBuilder()
                    .processInstanceId(processInstanceId)
                    .moveExecutionToActivityId(root.getId(), firstApprovalActivityId)
                    .changeState();
            Task returnedTask = requireSingleTask(
                    processInstanceId, firstApprovalActivityId);
            String originalAssignee = returnedTask.getAssignee();
            assertThat(originalAssignee).isEqualTo("301");
            taskService.setOwner(returnedTask.getId(), null);
            taskService.setAssignee(returnedTask.getId(), APPLICANT_ID);
            return new ReturnedFirstApproval(
                    returnedTask.getId(), processInstanceId, originalAssignee);
        });
    }

    /**
     * 恢复迁回首审批任务的原办理人并完成，使流程沿原连线重新进入中间多实例。
     *
     * @param returned ReturnedFirstApproval，迁移创建任务及原办理配置
     * @return void，任务缺失或重新流转失败时测试失败
     */
    private void restoreAndCompleteFirstApproval(ReturnedFirstApproval returned)
    {
        transactionTemplate.executeWithoutResult(status ->
        {
            Task task = taskService.createTaskQuery()
                    .taskId(returned.taskId()).active().singleResult();
            assertThat(task).isNotNull();
            assertThat(task.getProcessInstanceId())
                    .isEqualTo(returned.processInstanceId());
            taskService.setAssignee(task.getId(), returned.originalAssignee());
            taskService.complete(task.getId());
        });
    }

    /**
     * 将普通 execution 迁入首多实例节点，并在同一事务收敛立即创建的完整组。
     *
     * @param ordinaryTask Task，当前普通审批任务
     * @param firstGroupActivityId String，历史首审批多实例节点 ID
     * @return Task，事务提交后的唯一修改任务
     */
    private Task moveOrdinaryTaskToFirstGroup(Task ordinaryTask,
            String firstGroupActivityId)
    {
        String survivorId = transactionTemplate.execute(status ->
        {
            runtimeService.createChangeActivityStateBuilder()
                    .processInstanceId(ordinaryTask.getProcessInstanceId())
                    .moveExecutionToActivityId(
                            ordinaryTask.getExecutionId(), firstGroupActivityId)
                    .changeState();
            return collapseCurrentGroup(ordinaryTask.getProcessInstanceId(),
                    firstGroupActivityId, requireGroupTasks(
                            ordinaryTask.getProcessInstanceId(), firstGroupActivityId));
        });
        assertThat(survivorId).isNotNull();
        return taskService.createTaskQuery().taskId(survivorId).active().singleResult();
    }

    /**
     * 在调用方事务内仅使用公共多实例删除和任务改派 API 收敛迁入后创建的完整组。
     *
     * @param processInstanceId String，活动流程实例主键
     * @param activityId String，首多实例节点 ID
     * @param activeMembers List&lt;Task&gt;，迁入后由 Flowable 创建的全部成员任务
     * @return String，唯一保留任务主键
     */
    private String collapseCurrentGroup(String processInstanceId, String activityId,
            List<Task> activeMembers)
    {
        Task survivor = activeMembers.get(0);
        for (Task activeMember : activeMembers)
        {
            if (!survivor.getId().equals(activeMember.getId()))
            {
                runtimeService.deleteMultiInstanceExecution(
                        activeMember.getExecutionId(), false);
            }
        }
        taskService.setOwner(survivor.getId(), null);
        taskService.setAssignee(survivor.getId(), APPLICANT_ID);
        Task applicantTask = requireSingleTask(processInstanceId, activityId);
        assertThat(applicantTask.getId()).isEqualTo(survivor.getId());
        return survivor.getId();
    }

    /**
     * 把已收敛的首多实例根迁到同一活动，让 Flowable 创建完整新轮次。
     *
     * @param processInstanceId String，活动流程实例主键
     * @param activityId String，首审批多实例节点 ID
     * @return void，迁移失败时由调用事务完整回滚
     */
    private void recreateFirstGroup(String processInstanceId, String activityId)
    {
        transactionTemplate.executeWithoutResult(status ->
        {
            Task applicantTask = requireSingleTask(processInstanceId, activityId);
            assertThat(applicantTask.getAssignee()).isEqualTo(APPLICANT_ID);
            Execution root = requireMultiInstanceRoot(processInstanceId, activityId);
            runtimeService.createChangeActivityStateBuilder()
                    .processInstanceId(processInstanceId)
                    .moveExecutionToActivityId(root.getId(), activityId)
                    .changeState();
        });
    }

    /**
     * 在一个事务中先推进受控 revision，再完成真实多实例成员任务。
     *
     * @param task Task，当前多实例成员任务
     * @return void，revision 与任务完成同事务提交
     */
    private void completeControlledMember(Task task)
    {
        transactionTemplate.executeWithoutResult(status ->
        {
            int nextRevision = controlledRevision(
                    task.getProcessInstanceId(), task.getTaskDefinitionKey()) + 1;
            runtimeService.setVariable(task.getProcessInstanceId(),
                    WorkflowMultiInstanceVariables.revisionName(
                            task.getTaskDefinitionKey()), nextRevision);
            taskService.complete(task.getId());
        });
    }

    /**
     * 分别校验部分完成组的全量成员快照与剩余活动成员。
     *
     * @param processInstanceId String，活动流程实例主键
     * @param activityId String，受控多实例节点 ID
     * @param expectedMembers List&lt;String&gt;，含已完成人的完整成员快照
     * @param completedMember String，本轮已完成成员
     * @param expectedMode WorkflowMultiInstanceMode，预期 ALL 模式
     * @param expectedRevision int，预期 revision
     * @param expectedCounts MultiInstanceCounts，预期引擎计数
     * @return void，活动任务、快照或计数不一致时失败
     */
    private void assertPartiallyCompletedGroup(String processInstanceId,
            String activityId, List<String> expectedMembers, String completedMember,
            WorkflowMultiInstanceMode expectedMode, int expectedRevision,
            MultiInstanceCounts expectedCounts)
    {
        List<String> expectedActiveMembers = expectedMembers.stream()
                .filter(member -> !member.equals(completedMember)).toList();
        assertThat(requireGroupTasks(processInstanceId, activityId))
                .extracting(Task::getAssignee)
                .containsExactlyInAnyOrderElementsOf(expectedActiveMembers);
        assertControlledVariables(processInstanceId, activityId,
                expectedMembers, expectedMode, expectedRevision);
        assertCounts(processInstanceId, activityId, expectedCounts);
    }

    /**
     * 校验指定活动已经不存在任何运行时 execution。
     *
     * @param processInstanceId String，活动流程实例主键
     * @param activityId String，已经离开的活动 ID
     * @return void，仍有残留 execution 时失败
     */
    private void assertNoActivityExecutions(String processInstanceId,
            String activityId)
    {
        assertThat(runtimeService.createExecutionQuery()
                .processInstanceId(processInstanceId).list())
                .noneMatch(execution -> activityId.equals(execution.getActivityId()));
    }

    /**
     * 中间多实例迁回后创建的首审批任务事实。
     *
     * @param taskId String，迁移创建的任务主键
     * @param processInstanceId String，任务所属流程实例主键
     * @param originalAssignee String，首审批原办理人
     */
    private record ReturnedFirstApproval(String taskId, String processInstanceId,
            String originalAssignee)
    {
    }

    /**
     * 在显式开启时从 middlePre 的 execution start 事件注入跨活动迁移故障。
     */
    private static final class CrossActivityMigrationFailureGuard
            implements ExecutionListener
    {
        /** 测试断言使用的稳定故障消息。 */
        private static final String FAILURE_MESSAGE =
                "injected middle-to-first migration failure";

        /** 下一次 execution start 是否必须失败。 */
        private final AtomicBoolean failNext = new AtomicBoolean();

        /** 故障是否已经在真实跨活动迁移命令中触发。 */
        private final AtomicBoolean observed = new AtomicBoolean();

        /**
         * 开启下一次 middlePre 启动故障。
         *
         * @return void，无返回值
         */
        private void failNextMigration()
        {
            failNext.set(true);
            observed.set(false);
        }

        /**
         * 在 Flowable 创建迁回目标 execution 时按开关抛出异常。
         *
         * @param execution DelegateExecution，真实跨活动迁移创建的 execution
         * @return void，开关开启时抛错并中止当前事务
         */
        @Override
        public void notify(DelegateExecution execution)
        {
            if (failNext.compareAndSet(true, false))
            {
                observed.set(true);
                throw new FlowableException(FAILURE_MESSAGE);
            }
        }

        /**
         * 返回故障是否已在真实跨活动迁移路径触发。
         *
         * @return boolean，已触发为 true
         */
        private boolean failureObserved()
        {
            return observed.get();
        }
    }
}
