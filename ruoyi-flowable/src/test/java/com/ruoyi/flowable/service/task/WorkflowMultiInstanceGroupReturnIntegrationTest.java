package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.identitylink.api.IdentityLinkType;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import com.ruoyi.flowable.domain.WfMultiInstanceRound;
import com.ruoyi.flowable.domain.WorkflowMultiInstanceRoundStatus;
import com.ruoyi.flowable.service.process.WorkflowProcessStartService;

/**
 * 通过生产生命周期服务、真实 Flowable 及正式 H2 Mapper 验证阶段三整组退回事务。
 */
@SpringJUnitConfig(WorkflowMultiInstanceEngineHarness.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class WorkflowMultiInstanceGroupReturnIntegrationTest
{
    /** 只调用生产公开入口的业务驱动。 */
    @Autowired
    private WorkflowMultiInstanceBusinessDriver driver;

    /** 只读取引擎和业务表事实的状态探针。 */
    @Autowired
    private WorkflowMultiInstanceStateProbe probe;

    /**
     * 验证后续 ALL 三人组已有一人完成时，另一成员仍能撤销整个活动根并生成唯一申请人任务。
     *
     * @return void，成员快照、计数、退回关联、双状态或任务隔离任一漂移时失败
     */
    @Test
    void returnsWholeLaterAllRoundAfterOneMemberCompleted()
    {
        List<String> members = List.of("201", "202", "203");
        ProcessInstance instance = driver.startLifecycle("roundGroupLaterAll",
                "laterAllReturnStart", "laterAllReview", members);
        driver.completeOrdinary(probe.task(
                instance.getId(), "laterInitialApproval", "150"));
        WfMultiInstanceRound initialRound = probe.activeRound(
                instance.getId(), "laterAllReview");

        driver.complete(probe.task(instance.getId(), "laterAllReview", "201"), 0);
        WfMultiInstanceRound beforeReturn = probe.activeRound(
                instance.getId(), "laterAllReview");
        assertThat(beforeReturn.getRevisionNo()).isEqualTo(1);
        Task source = probe.task(instance.getId(), "laterAllReview", "202");
        List<String> remainingTaskIds = probe.tasks(instance.getId(), "laterAllReview")
                .stream().map(Task::getId).toList();

        assertThat(driver.returnAllowed(source, "202")).isTrue();
        driver.returnGroup(source, "202");

        Task applicantTask = probe.returnedTask(instance.getId());
        assertThat(applicantTask.getTaskDefinitionKey())
                .isEqualTo("laterInitialApproval");
        assertThat(applicantTask.getId()).isNotIn(remainingTaskIds);
        assertThat(applicantTask.getOwner()).isNull();
        assertThat(probe.identityLinks(applicantTask.getId()))
                .noneMatch(link -> IdentityLinkType.CANDIDATE.equals(link.getType()));
        assertThat(probe.taskVariable(applicantTask.getId(),
                WorkflowReturnedApplicationProtocol.RETURN_APPLICANT_VARIABLE))
                .isEqualTo(WorkflowMultiInstanceBusinessDriver.APPLICANT_ID);
        assertThat(probe.taskVariable(applicantTask.getId(),
                WorkflowReturnedApplicationProtocol.RETURN_ASSIGNMENT_VARIABLE)).isNull();
        assertThat(probe.tasks(instance.getId(), "laterAllReview")).isEmpty();
        probe.assertDoubleStatus(instance.getId(),
                WorkflowReturnedApplicationProtocol.RETURNED_STATUS);

        assertThat(probe.rounds(instance.getId())).singleElement().satisfies(round ->
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
        assertThat(probe.variable(instance.getId(),
                WorkflowReturnedApplicationProtocol.CONTROLLED_TRANSITION_VARIABLE)).isNull();
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
        ProcessInstance instance = driver.startLifecycle("roundGroupFirstAny",
                "firstAnyReturnStart", "firstAnyReview", members);
        WfMultiInstanceRound original = probe.activeRound(instance.getId(), "firstAnyReview");
        Task source = probe.task(instance.getId(), "firstAnyReview", "201");

        driver.returnGroup(source, "201");

        Task applicantTask = probe.returnedTask(instance.getId());
        assertThat(applicantTask.getTaskDefinitionKey()).isEqualTo("firstAnyReview");
        assertThat(probe.tasks(instance.getId(), "firstAnyReview"))
                .extracting(Task::getAssignee)
                .containsExactly(WorkflowMultiInstanceBusinessDriver.APPLICANT_ID);
        assertThat(probe.rounds(instance.getId()))
                .noneMatch(round -> WorkflowMultiInstanceRoundStatus.ACTIVE.name()
                        .equals(round.getRoundStatus()));
        assertThat(probe.rounds(instance.getId())).singleElement().satisfies(round ->
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
        probe.assertDoubleStatus(instance.getId(),
                WorkflowReturnedApplicationProtocol.RETURNED_STATUS);
        assertThat(probe.variable(instance.getId(),
                WorkflowProcessStartService.PROCESS_STATUS_VARIABLE))
                .isEqualTo(WorkflowReturnedApplicationProtocol.RETURNED_STATUS);
    }
}
