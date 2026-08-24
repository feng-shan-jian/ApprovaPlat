package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.identitylink.api.IdentityLinkType;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.flowable.domain.WfMultiInstanceRound;
import com.ruoyi.flowable.domain.WorkflowMultiInstanceRoundStatus;
import com.ruoyi.flowable.service.process.WorkflowProcessStartService;

/**
 * 通过生产生命周期服务、真实 Flowable 及正式 H2 Mapper 验证阶段三整组退回事务。
 */
class WorkflowMultiInstanceGroupReturnIntegrationTest
{
    private WorkflowGroupReturnScenario fixture;
    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path attachmentRoot;

    /** 创建当前功能所需的整组迁移夹具。 @return void，无返回值 */
    @BeforeEach
    void setUpFixture()
    {
        fixture = new WorkflowGroupReturnScenario(attachmentRoot);
    }

    /** 显式关闭整组迁移夹具。 @return void，无返回值 */
    @AfterEach
    void closeFixture()
    {
        fixture.close();
    }
    /**
     * 验证后续 ALL 三人组已有一人完成时，另一成员仍能撤销整个活动根并生成唯一申请人任务。
     *
     * @return void，成员快照、计数、退回关联、双状态或任务隔离任一漂移时失败
     */
    @Test
    void returnsWholeLaterAllRoundAfterOneMemberCompleted()
    {
        List<String> members = List.of("201", "202", "203");
        ProcessInstance instance = fixture.startLifecycle("roundGroupLaterAll",
                "laterAllReturnStart", "laterAllReview", members);
        fixture.completeOrdinary(instance.getId(), "laterInitialApproval", "150");
        WfMultiInstanceRound initialRound = fixture.activeRound(
                instance.getId(), "laterAllReview");

        fixture.setCurrentUser("201");
        fixture.complete(fixture.task(instance.getId(), "laterAllReview", "201"), 0);
        WfMultiInstanceRound beforeReturn = fixture.activeRound(
                instance.getId(), "laterAllReview");
        assertThat(beforeReturn.getRevisionNo()).isEqualTo(1);
        Task source = fixture.task(instance.getId(), "laterAllReview", "202");
        List<String> remainingTaskIds = fixture.tasks(instance.getId(), "laterAllReview")
                .stream().map(Task::getId).toList();
        fixture.insertTaskSla(source, "ACTIVE");
        fixture.insertTaskSla(fixture.task(instance.getId(), "laterAllReview", "203"),
                "ESCALATED");

        fixture.setCurrentUser("202");
        assertThat(fixture.lifecycleService.isTaskReturnAllowed(source.getId())).isTrue();
        fixture.returnGroup(source, "202");

        Task applicantTask = fixture.returnedTask(instance.getId());
        assertThat(applicantTask.getTaskDefinitionKey())
                .isEqualTo("laterInitialApproval");
        assertThat(applicantTask.getId()).isNotIn(remainingTaskIds);
        assertThat(applicantTask.getOwner()).isNull();
        assertThat(fixture.taskService.getIdentityLinksForTask(applicantTask.getId()))
                .noneMatch(link -> IdentityLinkType.CANDIDATE.equals(link.getType()));
        assertThat(fixture.taskService.getVariableLocal(applicantTask.getId(),
                WorkflowReturnedApplicationProtocol.RETURN_APPLICANT_VARIABLE))
                .isEqualTo(fixture.APPLICANT_ID);
        assertThat(fixture.taskService.getVariableLocal(applicantTask.getId(),
                WorkflowReturnedApplicationProtocol.RETURN_ASSIGNMENT_VARIABLE)).isNull();
        assertThat(fixture.tasks(instance.getId(), "laterAllReview")).isEmpty();
        fixture.assertDoubleStatus(instance.getId(), WorkflowReturnedApplicationProtocol.RETURNED_STATUS);

        assertThat(fixture.rounds(instance.getId())).singleElement().satisfies(round ->
        {
            assertThat(round.getRoundId()).isEqualTo(initialRound.getRoundId());
            assertThat(round.getRoundStatus())
                    .isEqualTo(WorkflowMultiInstanceRoundStatus.RETURNED);
            assertThat(round.getMembers()).containsExactlyElementsOf(members);
            assertThat(round.getMode()).isEqualTo(WorkflowMultiInstanceMode.ALL.name());
            assertThat(round.getRevisionNo()).isEqualTo(1);
            assertThat(round.getReturnSourceTaskId()).isEqualTo(source.getId());
            assertThat(round.getReturnActorUserId()).isEqualTo("202");
            assertThat(round.getApplicantTaskId()).isEqualTo(applicantTask.getId());
            assertThat(round.getReturnTime()).isNotNull();
            assertThat(round.getReopenTime()).isNull();
            assertThat(round.getTerminateTime()).isNull();
        });
        assertThat(fixture.runtimeService.getVariable(instance.getId(),
                WorkflowReturnedApplicationProtocol.CONTROLLED_TRANSITION_VARIABLE)).isNull();
        fixture.assertTaskSlasWithdrawn(instance.getId(), remainingTaskIds, "202",
                "受控整组退回撤销审批任务");
    }

    /**
     * 验证首审批节点本身为 ANY 三人组时，整组退回不会登记临时申请人任务为新 ACTIVE 轮次。
     *
     * @return void，旧轮误终止、临时任务误登记或 ANY 快照丢失时失败
     */
    @Test
    void returnsFirstAnyRoundWithoutRegisteringApplicantAsApprovalRound()
    {
        List<String> members = List.of("203", "201", "202");
        ProcessInstance instance = fixture.startLifecycle("roundGroupFirstAny",
                "firstAnyReturnStart", "firstAnyReview", members);
        WfMultiInstanceRound original = fixture.activeRound(instance.getId(), "firstAnyReview");
        Task source = fixture.task(instance.getId(), "firstAnyReview", "201");

        fixture.returnGroup(source, "201");

        Task applicantTask = fixture.returnedTask(instance.getId());
        assertThat(applicantTask.getTaskDefinitionKey()).isEqualTo("firstAnyReview");
        assertThat(fixture.tasks(instance.getId(), "firstAnyReview"))
                .extracting(Task::getAssignee).containsExactly(fixture.APPLICANT_ID);
        assertThat(fixture.roundMapper.selectActiveByProcessInstanceAndActivity(
                instance.getId(), "firstAnyReview")).isEmpty();
        assertThat(fixture.rounds(instance.getId())).singleElement().satisfies(round ->
        {
            assertThat(round.getRoundId()).isEqualTo(original.getRoundId());
            assertThat(round.getRoundStatus())
                    .isEqualTo(WorkflowMultiInstanceRoundStatus.RETURNED);
            assertThat(round.getMode()).isEqualTo(WorkflowMultiInstanceMode.ANY.name());
            assertThat(round.getMembers()).containsExactlyElementsOf(members);
            assertThat(round.getRevisionNo()).isZero();
            assertThat(round.getApplicantTaskId()).isEqualTo(applicantTask.getId());
            assertThat(round.getReturnTime()).isNotNull();
            assertThat(round.getTerminateTime()).isNull();
        });
        fixture.assertDoubleStatus(instance.getId(), WorkflowReturnedApplicationProtocol.RETURNED_STATUS);
        assertThat(fixture.runtimeService.getVariable(instance.getId(),
                WorkflowProcessStartService.PROCESS_STATUS_VARIABLE))
                .isEqualTo(WorkflowReturnedApplicationProtocol.RETURNED_STATUS);
    }
}
