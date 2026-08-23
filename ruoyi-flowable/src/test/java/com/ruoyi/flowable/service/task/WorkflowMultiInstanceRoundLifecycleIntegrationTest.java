package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import com.ruoyi.flowable.domain.WfMultiInstanceRound;
import com.ruoyi.flowable.domain.WorkflowMultiInstanceRoundStatus;

/**
 * 使用真实 Flowable 8 任务监听事务验证四类成员来源、动态调整、完成和同节点新轮次。
 */
class WorkflowMultiInstanceRoundLifecycleIntegrationTest
        extends WorkflowMultiInstanceRoundFlowableSupport
{
    /**
     * 验证办理时、发起时、固定和指定身份四种正式成员来源均创建可对账的 ACTIVE 首轮。
     *
     * @return void，任一来源未持久化部署、根、模式、成员或 revision 时失败
     */
    @Test
    void createsFormalFirstRoundForAllFourMemberSources()
    {
        ProcessInstance dynamic = start("roundDynamicAll", "dynamicReview",
                MEMBERS, Map.of());
        ProcessInstance start = start("roundStartAll", "startReview",
                MEMBERS, Map.of());
        ProcessInstance fixed = start("roundFixedAll");
        ProcessInstance configured = start("roundConfiguredAll");

        assertInitialRound(dynamic, "dynamicReview", MEMBERS,
                WorkflowMultiInstanceMode.ALL);
        assertInitialRound(start, "startReview", MEMBERS,
                WorkflowMultiInstanceMode.ALL);
        assertInitialRound(fixed, "fixedReview", MEMBERS,
                WorkflowMultiInstanceMode.ALL);
        assertInitialRound(configured, "configuredReview", MEMBERS,
                WorkflowMultiInstanceMode.ALL);
    }

    /**
     * 验证加签、减签、ALL 部分完成、整组完成及即时重入均同步成员、revision 和轮次终态。
     *
     * @return void，锁顺序、成员顺序、计数或新根轮次任一漂移时失败
     */
    @Test
    void synchronizesAdjustmentsAllCompletionAndFreshRoundReentry()
    {
        List<String> initialMembers = List.of("201", "202", "203");
        ProcessInstance instance = start("roundLifecycleAll", "lifecycleReview",
                initialMembers, Map.of("repeatRound", false));
        WfMultiInstanceRound firstRound = activeRound(instance.getId(),
                "lifecycleReview");
        String firstRootId = firstRound.getRootExecutionId();

        addMember(task(instance.getId(), "lifecycleReview", "201"), 0, 204L);
        assertRoundAndEngine(instance.getId(), "lifecycleReview",
                List.of("201", "202", "203", "204"), 1, 4);

        removeMember(task(instance.getId(), "lifecycleReview", "201"),
                task(instance.getId(), "lifecycleReview", "202"), 1);
        List<String> adjustedMembers = List.of("201", "203", "204");
        assertRoundAndEngine(instance.getId(), "lifecycleReview",
                adjustedMembers, 2, 3);

        complete(task(instance.getId(), "lifecycleReview", "201"), 2);
        assertRoundAndEngine(instance.getId(), "lifecycleReview",
                adjustedMembers, 3, 2);
        complete(task(instance.getId(), "lifecycleReview", "203"), 3);
        assertRoundAndEngine(instance.getId(), "lifecycleReview",
                adjustedMembers, 4, 1);

        runtimeService.setVariable(instance.getId(), "repeatRound", true);
        complete(task(instance.getId(), "lifecycleReview", "204"), 4);

        List<WfMultiInstanceRound> rounds = rounds(instance.getId());
        assertThat(rounds).hasSize(2);
        WfMultiInstanceRound completed = rounds.get(0);
        WfMultiInstanceRound reopened = rounds.get(1);
        assertThat(completed.getRoundNo()).isEqualTo(1);
        assertThat(completed.getRoundStatus())
                .isEqualTo(WorkflowMultiInstanceRoundStatus.COMPLETED);
        assertThat(completed.getRevisionNo()).isEqualTo(5);
        assertThat(completed.getCompleteTime()).isNotNull();
        assertThat(reopened.getRoundNo()).isEqualTo(2);
        assertThat(reopened.getRoundStatus())
                .isEqualTo(WorkflowMultiInstanceRoundStatus.ACTIVE);
        assertThat(reopened.getRevisionNo()).isEqualTo(5);
        assertThat(reopened.getRootExecutionId()).isNotEqualTo(firstRootId);
        assertThat(reopened.getMembers()).containsExactlyElementsOf(adjustedMembers);
        assertThat(tasks(instance.getId(), "lifecycleReview"))
                .extracting(Task::getAssignee)
                .containsExactlyElementsOf(adjustedMembers);
    }

    /**
     * 验证 assignment 事件只执行原有监听编排，不创建、推进或关闭业务轮次。
     *
     * @return void，轮次主键、成员、revision、状态或生命周期时间发生变化时失败
     */
    @Test
    void leavesRoundUnchangedOnAssignmentEvent()
    {
        ProcessInstance instance = start("roundDynamicAll", "dynamicReview",
                MEMBERS, Map.of());
        Task reassigned = task(instance.getId(), "dynamicReview", "201");
        WfMultiInstanceRound before = activeRound(instance.getId(), "dynamicReview");

        taskService.setAssignee(reassigned.getId(), "202");

        WfMultiInstanceRound after = activeRound(instance.getId(), "dynamicReview");
        assertThat(after.getRoundId()).isEqualTo(before.getRoundId());
        assertThat(after.getMembersJson()).isEqualTo(before.getMembersJson());
        assertThat(after.getRevisionNo()).isEqualTo(before.getRevisionNo());
        assertThat(after.getRoundStatus()).isEqualTo(before.getRoundStatus());
        assertThat(after.getCreateTime()).isEqualTo(before.getCreateTime());
        assertThat(after.getReturnTime()).isEqualTo(before.getReturnTime());
        assertThat(after.getReopenTime()).isEqualTo(before.getReopenTime());
        assertThat(after.getCompleteTime()).isEqualTo(before.getCompleteTime());
    }

    /**
     * 核对初次轮次与任务所属定义、部署、实例、活动、根和变量快照。
     *
     * @param instance ProcessInstance，已经进入受控节点的运行实例
     * @param activityId String，受控多实例活动 ID
     * @param expectedMembers List&lt;String&gt;，预期有序成员
     * @param mode WorkflowMultiInstanceMode，部署固定模式
     * @return void，任一正式事实不一致时失败
     */
    private void assertInitialRound(ProcessInstance instance, String activityId,
            List<String> expectedMembers, WorkflowMultiInstanceMode mode)
    {
        WfMultiInstanceRound round = activeRound(instance.getId(), activityId);
        assertThat(round.getRoundNo()).isEqualTo(1);
        assertThat(round.getRoundStatus())
                .isEqualTo(WorkflowMultiInstanceRoundStatus.ACTIVE);
        assertThat(round.getRevisionNo()).isZero();
        assertThat(round.getMode()).isEqualTo(mode.name());
        assertThat(round.getMembers()).containsExactlyElementsOf(expectedMembers);
        assertThat(round.getProcessInstanceId()).isEqualTo(instance.getId());
        assertThat(round.getProcessDefinitionId())
                .isEqualTo(instance.getProcessDefinitionId());
        assertThat(round.getDeployId()).isNotBlank();
        assertThat(round.getActivityId()).isEqualTo(activityId);
        assertThat(round.getCreateTime()).isNotNull();
        assertThat(round.getCompleteTime()).isNull();

        List<Task> tasks = tasks(instance.getId(), activityId);
        assertThat(tasks).extracting(Task::getAssignee)
                .containsExactlyElementsOf(expectedMembers);
        List<String> parentIds = tasks.stream().map(Task::getExecutionId)
                .map(executionId -> runtimeService.createExecutionQuery()
                        .executionId(executionId).singleResult())
                .map(Execution::getParentId).distinct().toList();
        assertThat(parentIds).containsExactly(round.getRootExecutionId());
    }

    /**
     * 核对调整或部分完成后的 ACTIVE 轮次、变量和活动任务数量。
     *
     * @param processInstanceId String，流程实例主键
     * @param activityId String，受控多实例活动 ID
     * @param members List&lt;String&gt;，完整有序成员快照
     * @param revision int，动作后的 revision
     * @param activeTaskCount int，动作后的活动成员任务数
     * @return void，轮次、变量或任务不一致时失败
     */
    private void assertRoundAndEngine(String processInstanceId, String activityId,
            List<String> members, int revision, int activeTaskCount)
    {
        WfMultiInstanceRound round = activeRound(processInstanceId, activityId);
        assertThat(round.getMembers()).containsExactlyElementsOf(members);
        assertThat(round.getRevisionNo()).isEqualTo(revision);
        assertThat(round.getRoundStatus())
                .isEqualTo(WorkflowMultiInstanceRoundStatus.ACTIVE);
        assertThat(runtimeService.getVariable(processInstanceId,
                WorkflowMultiInstanceVariables.memberSnapshotName(activityId)))
                .isEqualTo(members);
        assertThat(runtimeService.getVariable(processInstanceId,
                WorkflowMultiInstanceVariables.revisionName(activityId)))
                .isEqualTo(revision);
        assertThat(tasks(processInstanceId, activityId)).hasSize(activeTaskCount);
    }
}
