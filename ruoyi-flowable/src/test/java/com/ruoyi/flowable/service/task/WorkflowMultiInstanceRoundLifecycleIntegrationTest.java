package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import com.ruoyi.flowable.domain.WfMultiInstanceRound;
import com.ruoyi.flowable.domain.WorkflowMultiInstanceRoundStatus;

/**
 * 使用真实 Flowable 8 任务监听事务验证四类成员来源、动态调整、完成和同节点新轮次。
 */
class WorkflowMultiInstanceRoundLifecycleIntegrationTest
{
    private WorkflowMultiInstanceRoundScenario fixture;

    /** 创建当前功能所需的轮次夹具。 @return void，无返回值 */
    @BeforeEach
    void setUpFixture()
    {
        fixture = new WorkflowMultiInstanceRoundScenario();
    }

    /** 显式关闭轮次夹具。 @return void，无返回值 */
    @AfterEach
    void closeFixture()
    {
        fixture.close();
    }
    /**
     * 验证办理时、发起时、固定和指定身份四种正式成员来源均创建可对账的 ACTIVE 首轮。
     *
     * @return void，任一来源未持久化部署、根、模式、成员或 revision 时失败
     */
    @Test
    void createsFormalFirstRoundForAllFourMemberSources()
    {
        ProcessInstance dynamic = fixture.start("roundDynamicAll", "dynamicReview",
                fixture.MEMBERS, Map.of());
        ProcessInstance start = fixture.start("roundStartAll", "startReview",
                fixture.MEMBERS, Map.of());
        ProcessInstance fixed = fixture.start("roundFixedAll");
        ProcessInstance configured = fixture.start("roundConfiguredAll");

        assertInitialRound(dynamic, "dynamicReview", fixture.MEMBERS,
                WorkflowMultiInstanceMode.ALL);
        assertInitialRound(start, "startReview", fixture.MEMBERS,
                WorkflowMultiInstanceMode.ALL);
        assertInitialRound(fixed, "fixedReview", fixture.MEMBERS,
                WorkflowMultiInstanceMode.ALL);
        assertInitialRound(configured, "configuredReview", fixture.MEMBERS,
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
        ProcessInstance instance = fixture.start("roundLifecycleAll", "lifecycleReview",
                initialMembers, Map.of("repeatRound", false));
        WfMultiInstanceRound firstRound = fixture.activeRound(instance.getId(),
                "lifecycleReview");
        String firstRootId = firstRound.getRootExecutionId();

        fixture.addMember(fixture.task(instance.getId(), "lifecycleReview", "201"), 0, 204L);
        assertRoundAndEngine(instance.getId(), "lifecycleReview",
                List.of("201", "202", "203", "204"), 1, 4);

        fixture.removeMember(fixture.task(instance.getId(), "lifecycleReview", "201"),
                fixture.task(instance.getId(), "lifecycleReview", "202"), 1);
        List<String> adjustedMembers = List.of("201", "203", "204");
        assertRoundAndEngine(instance.getId(), "lifecycleReview",
                adjustedMembers, 2, 3);

        fixture.complete(fixture.task(instance.getId(), "lifecycleReview", "201"), 2);
        assertRoundAndEngine(instance.getId(), "lifecycleReview",
                adjustedMembers, 3, 2);
        fixture.complete(fixture.task(instance.getId(), "lifecycleReview", "203"), 3);
        assertRoundAndEngine(instance.getId(), "lifecycleReview",
                adjustedMembers, 4, 1);

        fixture.runtimeService.setVariable(instance.getId(), "repeatRound", true);
        fixture.complete(fixture.task(instance.getId(), "lifecycleReview", "204"), 4);

        List<WfMultiInstanceRound> rounds = fixture.rounds(instance.getId());
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
        assertThat(fixture.tasks(instance.getId(), "lifecycleReview"))
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
        ProcessInstance instance = fixture.start("roundDynamicAll", "dynamicReview",
                fixture.MEMBERS, Map.of());
        Task reassigned = fixture.task(instance.getId(), "dynamicReview", "201");
        WfMultiInstanceRound before = fixture.activeRound(instance.getId(), "dynamicReview");

        fixture.taskService.setAssignee(reassigned.getId(), "202");

        WfMultiInstanceRound after = fixture.activeRound(instance.getId(), "dynamicReview");
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
        WfMultiInstanceRound round = fixture.activeRound(instance.getId(), activityId);
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

        List<Task> tasks = fixture.tasks(instance.getId(), activityId);
        assertThat(tasks).extracting(Task::getAssignee)
                .containsExactlyElementsOf(expectedMembers);
        List<String> parentIds = tasks.stream().map(Task::getExecutionId)
                .map(executionId -> fixture.runtimeService.createExecutionQuery()
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
        WfMultiInstanceRound round = fixture.activeRound(processInstanceId, activityId);
        assertThat(round.getMembers()).containsExactlyElementsOf(members);
        assertThat(round.getRevisionNo()).isEqualTo(revision);
        assertThat(round.getRoundStatus())
                .isEqualTo(WorkflowMultiInstanceRoundStatus.ACTIVE);
        assertThat(fixture.runtimeService.getVariable(processInstanceId,
                WorkflowMultiInstanceVariables.memberSnapshotName(activityId)))
                .isEqualTo(members);
        assertThat(fixture.runtimeService.getVariable(processInstanceId,
                WorkflowMultiInstanceVariables.revisionName(activityId)))
                .isEqualTo(revision);
        assertThat(fixture.tasks(processInstanceId, activityId)).hasSize(activeTaskCount);
    }
}
