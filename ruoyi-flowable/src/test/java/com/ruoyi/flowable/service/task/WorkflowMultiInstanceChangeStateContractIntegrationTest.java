package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.ExecutionListener;
import org.flowable.engine.delegate.TaskListener;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.task.service.delegate.DelegateTask;
import org.junit.jupiter.api.Test;

/**
 * 使用真实 Flowable 8 公共 API 验证首节点多实例组的收敛、调整和新轮次重建。
 */
class WorkflowMultiInstanceChangeStateContractIntegrationTest
        extends WorkflowMultiInstanceContractIntegrationSupport
{
    /** ALL 场景退回后仍须保留的加签成员。 */
    private static final String ADDED_APPROVER_ID = "204";

    /** 记录晚期监听故障前已经成功返回的 sibling 删除命令数。 */
    private final AtomicInteger successfulSiblingDeletes = new AtomicInteger();

    /** 同活动重建路径的 execution start 故障注入器。 */
    private final FirstNodeMigrationFailureGuard migrationFailureGuard =
            new FirstNodeMigrationFailureGuard();

    /** sibling 删除后的 assignment 晚期故障注入器。 */
    private final LateAssignmentFailureGuard lateAssignmentFailureGuard =
            new LateAssignmentFailureGuard(successfulSiblingDeletes);

    /**
     * 返回首节点生命周期契约使用的外部 BPMN。
     *
     * @return String，classpath 下的首节点契约 BPMN 路径
     */
    @Override
    protected String bpmnResource()
    {
        return "bpmn/workflow-multi-instance-first-node-contract.bpmn20.xml";
    }

    /**
     * 注册本类两条真实失败路径使用的测试监听器。
     *
     * @return Map&lt;Object,Object&gt;，BPMN delegateExpression 对应的 Bean
     */
    @Override
    protected Map<Object, Object> scenarioBeans()
    {
        Map<Object, Object> beans = new LinkedHashMap<>();
        beans.put("firstNodeMigrationFailureGuard", migrationFailureGuard);
        beans.put("lateAssignmentFailureGuard", lateAssignmentFailureGuard);
        return beans;
    }

    /**
     * 验证首节点 ALL 通过公共加签 API 扩组后，可收敛为唯一修改任务并按正式快照重建。
     *
     * @return void，加签快照、根收敛或新轮计数不符合契约时失败
     */
    @Test
    void collapsesAndRecreatesFirstAllGroupFromAddedMemberSnapshot()
    {
        ProcessInstance instance = startControlledProcess(
                "firstAllReturn", "firstAllGroup", ORIGINAL_APPROVER_IDS);
        List<String> adjustedMembers = addControlledMember(
                instance.getId(), "firstAllGroup", ADDED_APPROVER_ID);
        assertThat(adjustedMembers)
                .containsExactly("201", "202", "203", ADDED_APPROVER_ID);
        assertControlledGroup(instance.getId(), "firstAllGroup",
                adjustedMembers, WorkflowMultiInstanceMode.ALL, 1,
                new MultiInstanceCounts(4, 4, 0));
        String oldRootId = requireMultiInstanceRoot(
                instance.getId(), "firstAllGroup").getId();

        Task applicantTask = collapseFirstGroup(
                instance.getId(), "firstAllGroup");

        assertThat(applicantTask.getAssignee()).isEqualTo(APPLICANT_ID);
        assertThat(requireMultiInstanceRoot(instance.getId(), "firstAllGroup").getId())
                .isEqualTo(oldRootId);
        assertCounts(instance.getId(), "firstAllGroup",
                new MultiInstanceCounts(1, 1, 0));
        assertControlledVariables(instance.getId(), "firstAllGroup",
                adjustedMembers, WorkflowMultiInstanceMode.ALL, 1);

        recreateFirstGroup(instance.getId(), "firstAllGroup");

        assertThat(requireMultiInstanceRoot(instance.getId(), "firstAllGroup").getId())
                .isNotEqualTo(oldRootId);
        assertControlledGroup(instance.getId(), "firstAllGroup",
                adjustedMembers, WorkflowMultiInstanceMode.ALL, 1,
                new MultiInstanceCounts(4, 4, 0));
    }

    /**
     * 验证首节点 ANY 通过公共减签 API 移除原成员后，重建只使用保留成员且仍保持或签语义。
     *
     * @return void，减签快照、ANY 模式或完成条件漂移时失败
     */
    @Test
    void recreatesFirstAnyGroupFromRemovedMemberSnapshot()
    {
        ProcessInstance instance = startControlledProcess(
                "firstAnyReturn", "firstAnyGroup", ORIGINAL_APPROVER_IDS);
        List<String> adjustedMembers = removeControlledMember(
                instance.getId(), "firstAnyGroup", "202");
        assertThat(adjustedMembers).containsExactly("201", "203");
        assertControlledGroup(instance.getId(), "firstAnyGroup",
                adjustedMembers, WorkflowMultiInstanceMode.ANY, 1,
                new MultiInstanceCounts(2, 2, 0));

        collapseFirstGroup(instance.getId(), "firstAnyGroup");
        recreateFirstGroup(instance.getId(), "firstAnyGroup");

        assertControlledGroup(instance.getId(), "firstAnyGroup",
                adjustedMembers, WorkflowMultiInstanceMode.ANY, 1,
                new MultiInstanceCounts(2, 2, 0));
        completeControlledMember(
                requireGroupTasks(instance.getId(), "firstAnyGroup").get(0));
        assertThat(taskService.createTaskQuery().processInstanceId(instance.getId())
                .taskDefinitionKey("firstAnyGroup").active().count()).isZero();
        assertThat(requireSingleTask(instance.getId(), "firstAnyAfter").getAssignee())
                .isEqualTo("301");
        assertControlledVariables(instance.getId(), "firstAnyGroup",
                adjustedMembers, WorkflowMultiInstanceMode.ANY, 2);
    }

    /**
     * 验证连续两轮收敛和重建均创建新的根与任务，nrOf* 只反映当前轮次。
     *
     * @return void，旧 execution、旧任务或旧完成计数泄漏到新轮次时失败
     */
    @Test
    void rebuildsFreshExecutionsTasksAndCountersAcrossTwoReturnRounds()
    {
        ProcessInstance instance = startControlledProcess(
                "firstAllReturn", "firstAllGroup", ORIGINAL_APPROVER_IDS);
        String firstRootId = requireMultiInstanceRoot(
                instance.getId(), "firstAllGroup").getId();
        List<String> firstTaskIds = taskIds(instance.getId(), "firstAllGroup");

        collapseFirstGroup(instance.getId(), "firstAllGroup");
        recreateFirstGroup(instance.getId(), "firstAllGroup");
        String secondRootId = requireMultiInstanceRoot(
                instance.getId(), "firstAllGroup").getId();
        List<String> secondTaskIds = taskIds(instance.getId(), "firstAllGroup");
        assertThat(secondRootId).isNotEqualTo(firstRootId);
        assertThat(secondTaskIds).doesNotContainAnyElementsOf(firstTaskIds);

        Task completedMember = requireGroupTasks(
                instance.getId(), "firstAllGroup").get(0);
        completeControlledMember(completedMember);
        assertCounts(instance.getId(), "firstAllGroup",
                new MultiInstanceCounts(3, 2, 1));
        collapseFirstGroup(instance.getId(), "firstAllGroup");
        assertCounts(instance.getId(), "firstAllGroup",
                new MultiInstanceCounts(2, 1, 1));
        recreateFirstGroup(instance.getId(), "firstAllGroup");

        String thirdRootId = requireMultiInstanceRoot(
                instance.getId(), "firstAllGroup").getId();
        List<String> thirdTaskIds = taskIds(instance.getId(), "firstAllGroup");
        assertThat(thirdRootId).isNotIn(firstRootId, secondRootId);
        assertThat(thirdTaskIds).doesNotContainAnyElementsOf(secondTaskIds);
        assertControlledGroup(instance.getId(), "firstAllGroup",
                ORIGINAL_APPROVER_IDS, WorkflowMultiInstanceMode.ALL, 1,
                new MultiInstanceCounts(3, 3, 0));
    }

    /**
     * 在根到同活动迁移时注入 execution start 异常，验证任务、execution 和全部变量整体回滚。
     *
     * @return void，故障未进入真实迁移或任一运行时事实发生部分提交时失败
     */
    @Test
    void rollsBackTasksExecutionsAndVariablesWhenRecreationMigrationFails()
    {
        ProcessInstance instance = startControlledProcess(
                "firstAllReturn", "firstAllGroup", ORIGINAL_APPROVER_IDS);
        List<String> adjustedMembers = addControlledMember(
                instance.getId(), "firstAllGroup", ADDED_APPROVER_ID);
        collapseFirstGroup(instance.getId(), "firstAllGroup");
        EngineSnapshot before = captureSnapshot(instance.getId());
        migrationFailureGuard.failNextMigration();

        assertThatThrownBy(() -> recreateFirstGroup(
                instance.getId(), "firstAllGroup"))
                .isInstanceOf(FlowableException.class)
                .hasMessageContaining(FirstNodeMigrationFailureGuard.FAILURE_MESSAGE);

        assertThat(migrationFailureGuard.failureObserved()).isTrue();
        assertThat(captureSnapshot(instance.getId())).isEqualTo(before);
        assertThat(requireSingleTask(instance.getId(), "firstAllGroup").getAssignee())
                .isEqualTo(APPLICANT_ID);
        assertCounts(instance.getId(), "firstAllGroup",
                new MultiInstanceCounts(1, 1, 0));
        assertControlledVariables(instance.getId(), "firstAllGroup",
                adjustedMembers, WorkflowMultiInstanceMode.ALL, 1);
    }

    /**
     * 在 sibling 删除成功后注入 assignment 监听异常，验证多条公共命令共享同一事务并整体回滚。
     *
     * @return void，故障发生过早或任务、execution、计数、变量出现部分提交时失败
     */
    @Test
    void rollsBackSuccessfulSiblingDeletesWhenLateAssignmentListenerFails()
    {
        ProcessInstance instance = startControlledProcess(
                "firstAllReturn", "firstAllGroup", ORIGINAL_APPROVER_IDS);
        EngineSnapshot before = captureSnapshot(instance.getId());
        lateAssignmentFailureGuard.failNextApplicantAssignment();

        assertThatThrownBy(() -> collapseFirstGroup(
                instance.getId(), "firstAllGroup"))
                .isInstanceOf(FlowableException.class)
                .hasMessageContaining(
                        LateAssignmentFailureGuard.FAILURE_MESSAGE);

        assertThat(successfulSiblingDeletes.get()).isGreaterThanOrEqualTo(1);
        assertThat(lateAssignmentFailureGuard.failureObserved()).isTrue();
        assertThat(captureSnapshot(instance.getId())).isEqualTo(before);
        assertControlledGroup(instance.getId(), "firstAllGroup",
                ORIGINAL_APPROVER_IDS, WorkflowMultiInstanceMode.ALL, 0,
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
     * 在一个 Spring 事务中调用公共加签 API，并同步 handler 的正式成员快照。
     *
     * @param processInstanceId String，活动流程实例主键
     * @param activityId String，受控多实例节点 ID
     * @param userId String，新增成员主键
     * @return List&lt;String&gt;，加签后的有序成员快照
     */
    private List<String> addControlledMember(String processInstanceId,
            String activityId, String userId)
    {
        return transactionTemplate.execute(status ->
        {
            List<String> currentMembers = controlledMembers(
                    processInstanceId, activityId);
            int nextRevision = controlledRevision(processInstanceId, activityId) + 1;
            runtimeService.setVariable(processInstanceId,
                    WorkflowMultiInstanceVariables.revisionName(activityId),
                    nextRevision);
            // 公共 API 创建真实 execution 和 task，三个 nrOf* 计数完全由 Flowable 维护。
            runtimeService.addMultiInstanceExecution(activityId, processInstanceId,
                    Map.of(WorkflowMultiInstanceModelContract.ELEMENT_VARIABLE, userId));
            List<String> nextMembers = new ArrayList<>(currentMembers);
            nextMembers.add(userId);
            persistControlledMembers(processInstanceId, activityId, nextMembers);
            return List.copyOf(nextMembers);
        });
    }

    /**
     * 在一个 Spring 事务中调用公共减签 API，并同步 handler 的正式成员快照。
     *
     * @param processInstanceId String，活动流程实例主键
     * @param activityId String，受控多实例节点 ID
     * @param userId String，需要从后续轮次排除的成员主键
     * @return List&lt;String&gt;，减签后的有序成员快照
     */
    private List<String> removeControlledMember(String processInstanceId,
            String activityId, String userId)
    {
        return transactionTemplate.execute(status ->
        {
            Task target = requireTaskByAssignee(
                    processInstanceId, activityId, userId);
            List<String> nextMembers = new ArrayList<>(
                    controlledMembers(processInstanceId, activityId));
            assertThat(nextMembers.remove(userId)).isTrue();
            int nextRevision = controlledRevision(processInstanceId, activityId) + 1;
            runtimeService.setVariable(processInstanceId,
                    WorkflowMultiInstanceVariables.revisionName(activityId),
                    nextRevision);
            // false 明确表示减签而非完成，禁止业务代码伪造 nrOfCompletedInstances。
            runtimeService.deleteMultiInstanceExecution(target.getExecutionId(), false);
            persistControlledMembers(processInstanceId, activityId, nextMembers);
            return List.copyOf(nextMembers);
        });
    }

    /**
     * 在单个事务中删除首多实例 sibling，并将唯一保留任务改派给发起人。
     *
     * @param processInstanceId String，活动流程实例主键
     * @param activityId String，首审批多实例节点 ID
     * @return Task，事务提交后的唯一修改任务
     */
    private Task collapseFirstGroup(String processInstanceId, String activityId)
    {
        successfulSiblingDeletes.set(0);
        String survivorId = transactionTemplate.execute(status ->
                collapseCurrentGroup(processInstanceId, activityId,
                        requireGroupTasks(processInstanceId, activityId)));
        assertThat(survivorId).isNotNull();
        return taskService.createTaskQuery().taskId(survivorId).active().singleResult();
    }

    /**
     * 在调用方事务内仅使用公共多实例删除和任务改派 API 收敛当前活动组。
     *
     * @param processInstanceId String，活动流程实例主键
     * @param activityId String，当前多实例节点 ID
     * @param activeMembers List&lt;Task&gt;，当前全部活动成员任务
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
                // 事务外计数仅证明后续故障发生前至少一条公共删除命令已经成功返回。
                successfulSiblingDeletes.incrementAndGet();
            }
        }
        taskService.setOwner(survivor.getId(), null);
        taskService.setAssignee(survivor.getId(), APPLICANT_ID);
        Task applicantTask = requireSingleTask(processInstanceId, activityId);
        assertThat(applicantTask.getId()).isEqualTo(survivor.getId());
        assertThat(applicantTask.getAssignee()).isEqualTo(APPLICANT_ID);
        return survivor.getId();
    }

    /**
     * 把首多实例根迁到同一活动，让 Flowable 按成员快照创建全新的根、任务和 nrOf*。
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
     * 同步正式 handler 使用的成员快照和受控集合变量。
     *
     * @param processInstanceId String，活动流程实例主键
     * @param activityId String，受控多实例节点 ID
     * @param members List&lt;String&gt;，调整后的完整有序成员
     * @return void，无返回值
     */
    private void persistControlledMembers(String processInstanceId,
            String activityId, List<String> members)
    {
        runtimeService.setVariable(processInstanceId,
                WorkflowMultiInstanceVariables.memberSnapshotName(activityId),
                new ArrayList<>(members));
        runtimeService.setVariable(processInstanceId,
                WorkflowMultiInstanceVariables.userCollectionName(activityId),
                members.stream().map(Long::valueOf).toList());
    }

    /**
     * 查询指定活动和办理人的唯一活动任务。
     *
     * @param processInstanceId String，活动流程实例主键
     * @param activityId String，多实例活动 ID
     * @param assignee String，目标成员主键
     * @return Task，唯一匹配的真实活动任务
     */
    private Task requireTaskByAssignee(String processInstanceId, String activityId,
            String assignee)
    {
        Task task = taskService.createTaskQuery().processInstanceId(processInstanceId)
                .taskDefinitionKey(activityId).taskAssignee(assignee)
                .active().singleResult();
        assertThat(task).isNotNull();
        return task;
    }

    /**
     * 返回指定多实例活动的当前任务主键集合。
     *
     * @param processInstanceId String，活动流程实例主键
     * @param activityId String，多实例活动 ID
     * @return List&lt;String&gt;，按主键排序的活动任务主键
     */
    private List<String> taskIds(String processInstanceId, String activityId)
    {
        return requireGroupTasks(processInstanceId, activityId).stream()
                .map(Task::getId).sorted().toList();
    }

    /**
     * 在显式开启时从首多实例 activity 的 execution start 事件注入重建故障。
     */
    private static final class FirstNodeMigrationFailureGuard
            implements ExecutionListener
    {
        /** 测试断言使用的稳定故障消息。 */
        private static final String FAILURE_MESSAGE =
                "injected first-node recreation failure";

        /** 下一次 execution start 是否必须失败。 */
        private final AtomicBoolean failNext = new AtomicBoolean();

        /** 故障是否已经在真实迁移命令中触发。 */
        private final AtomicBoolean observed = new AtomicBoolean();

        /**
         * 开启下一次目标活动启动故障。
         *
         * @return void，无返回值
         */
        private void failNextMigration()
        {
            failNext.set(true);
            observed.set(false);
        }

        /**
         * 在 Flowable 创建目标 execution 时按开关抛出异常。
         *
         * @param execution DelegateExecution，真实迁移正在创建的 execution
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
         * 返回故障是否已在真实迁移路径触发。
         *
         * @return boolean，已触发为 true
         */
        private boolean failureObserved()
        {
            return observed.get();
        }
    }

    /**
     * 仅用于证明 sibling 删除之后的 assignment 监听失败仍由外层 Spring 事务回滚。
     */
    private static final class LateAssignmentFailureGuard implements TaskListener
    {
        /** 测试断言使用的稳定晚期故障消息。 */
        private static final String FAILURE_MESSAGE =
                "injected late assignment listener failure";

        /** 记录当前收敛事务中已经返回的 sibling 删除命令数量。 */
        private final AtomicInteger successfulSiblingDeletes;

        /** 下一次发起人 assignment 是否必须失败。 */
        private final AtomicBoolean failNext = new AtomicBoolean();

        /** 晚期故障是否确实在目标 assignment 触发。 */
        private final AtomicBoolean observed = new AtomicBoolean();

        /**
         * 创建晚期故障监听器。
         *
         * @param successfulSiblingDeletes AtomicInteger，已返回的公共 sibling 删除命令数
         * @return 无返回值，构造后注册到测试 BPMN
         */
        private LateAssignmentFailureGuard(AtomicInteger successfulSiblingDeletes)
        {
            this.successfulSiblingDeletes = successfulSiblingDeletes;
        }

        /**
         * 开启下一次发起人 assignment 的晚期故障。
         *
         * @return void，无返回值
         */
        private void failNextApplicantAssignment()
        {
            failNext.set(true);
            observed.set(false);
        }

        /**
         * 仅在目标发起人 assignment 且 sibling 删除已经返回后抛错。
         *
         * @param delegateTask DelegateTask，真实 Flowable assignment 事件任务
         * @return void，满足故障条件时抛错并中止当前命令
         */
        @Override
        public void notify(DelegateTask delegateTask)
        {
            if (EVENTNAME_ASSIGNMENT.equals(delegateTask.getEventName())
                    && APPLICANT_ID.equals(delegateTask.getAssignee())
                    && failNext.compareAndSet(true, false))
            {
                if (successfulSiblingDeletes.get() < 1)
                {
                    throw new FlowableException(
                            "late failure was armed before sibling deletion");
                }
                observed.set(true);
                throw new FlowableException(FAILURE_MESSAGE);
            }
        }

        /**
         * 返回故障是否已在目标 assignment 触发。
         *
         * @return boolean，已触发为 true
         */
        private boolean failureObserved()
        {
            return observed.get();
        }
    }
}
