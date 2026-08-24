package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.flowable.domain.WfMultiInstanceRound;
import com.ruoyi.flowable.domain.WorkflowMultiInstanceRoundStatus;
import com.ruoyi.flowable.service.process.WorkflowFormSubmissionSnapshotCodec;
import com.ruoyi.flowable.service.process.WorkflowProcessStartService;

/**
 * 通过生产重提链验证旧轮 REOPENED 与冻结快照驱动的新 ACTIVE 完整审批组。
 */
class WorkflowMultiInstanceGroupResubmitIntegrationTest
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
     * 验证后续 ALL 三人组在一人已完成后退回，重提仍按旧轮完整快照重建三名成员。
     *
     * @return void，旧轮状态、新根、成员顺序、模式、revision 或表单状态任一漂移时失败
     */
    @Test
    void rebuildsCompleteLaterAllGroupFromReturnedRoundSnapshot()
    {
        List<String> members = List.of("203", "201", "202");
        ProcessInstance instance = fixture.startLifecycle("roundGroupLaterAll",
                "laterAllReturnStart", "laterAllReview", members);
        fixture.completeOrdinary(instance.getId(), "laterInitialApproval", "150");
        WfMultiInstanceRound original = fixture.activeRound(instance.getId(), "laterAllReview");
        fixture.setCurrentUser("201");
        fixture.complete(fixture.task(instance.getId(), "laterAllReview", "201"), 0);
        Task source = fixture.task(instance.getId(), "laterAllReview", "202");
        fixture.returnGroup(source, "202");
        Task applicantTask = fixture.returnedTask(instance.getId());
        WfMultiInstanceRound returned = fixture.rounds(instance.getId()).get(0);
        fixture.insertTaskSla(applicantTask, "ACTIVE");

        fixture.resubmitGroup(applicantTask, "修改后申请");

        List<WfMultiInstanceRound> allRounds = fixture.rounds(instance.getId());
        assertThat(allRounds).hasSize(2);
        WfMultiInstanceRound reopened = allRounds.get(0);
        WfMultiInstanceRound active = allRounds.get(1);
        assertThat(reopened.getRoundId()).isEqualTo(original.getRoundId());
        assertThat(reopened.getRoundStatus())
                .isEqualTo(WorkflowMultiInstanceRoundStatus.REOPENED);
        assertThat(reopened.getReturnSourceTaskId()).isEqualTo(source.getId());
        assertThat(reopened.getApplicantTaskId()).isEqualTo(applicantTask.getId());
        assertThat(reopened.getReturnTime()).isEqualTo(returned.getReturnTime());
        assertThat(reopened.getReopenTime()).isNotNull();

        assertThat(active.getRoundNo()).isEqualTo(original.getRoundNo() + 1);
        assertThat(active.getRoundStatus())
                .isEqualTo(WorkflowMultiInstanceRoundStatus.ACTIVE);
        assertThat(active.getRootExecutionId())
                .isNotEqualTo(original.getRootExecutionId());
        assertThat(active.getMembers()).containsExactlyElementsOf(members);
        assertThat(active.getMode()).isEqualTo(WorkflowMultiInstanceMode.ALL.name());
        assertThat(active.getRevisionNo()).isEqualTo(1);
        assertThat(active.getReturnSourceTaskId()).isNull();
        assertThat(active.getApplicantTaskId()).isNull();
        assertThat(fixture.tasks(instance.getId(), "laterAllReview"))
                .extracting(Task::getAssignee).containsExactlyElementsOf(
                        members.stream().sorted().toList());
        assertThat(fixture.taskService.createTaskQuery().taskId(applicantTask.getId())
                .active().singleResult()).isNull();
        assertThat(fixture.runtimeService.getVariable(instance.getId(),
                WorkflowMultiInstanceVariables.memberSnapshotName("laterAllReview")))
                .isEqualTo(members);
        assertThat(fixture.runtimeService.getVariable(instance.getId(),
                WorkflowMultiInstanceVariables.modeName("laterAllReview")))
                .isEqualTo(WorkflowMultiInstanceMode.ALL.name());
        assertThat(fixture.runtimeService.getVariable(instance.getId(),
                WorkflowMultiInstanceVariables.revisionName("laterAllReview")))
                .isEqualTo(1);
        fixture.assertDoubleStatus(instance.getId(), WorkflowProcessStartService.RUNNING_STATUS);
        assertThat(fixture.runtimeService.getVariable(instance.getId(), "requestTitle"))
                .isEqualTo("修改后申请");
        WorkflowFormSubmissionSnapshotCodec.SubmissionSnapshot snapshot =
                WorkflowFormSubmissionSnapshotCodec.decode((String) fixture.runtimeService.getVariable(
                        instance.getId(),
                        WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME));
        assertThat(snapshot.values().get("requestTitle").stringValue())
                .isEqualTo("修改后申请");
        fixture.assertTaskSlasWithdrawn(instance.getId(), List.of(applicantTask.getId()),
                fixture.APPLICANT_ID, "受控重提撤销申请人待修改任务");
    }

    /**
     * 验证首节点 ANY 三人组重提生成新根，并在任一成员完成后按 ANY 条件真实结束。
     *
     * @return void，首节点迁移、ANY 模式或完成条件未使用冻结快照时失败
     */
    @Test
    void rebuildsFirstAnyGroupAndKeepsAnyCompletionBehavior()
    {
        List<String> members = List.of("203", "201", "202");
        ProcessInstance instance = fixture.startLifecycle("roundGroupFirstAny",
                "firstAnyReturnStart", "firstAnyReview", members);
        WfMultiInstanceRound original = fixture.activeRound(instance.getId(), "firstAnyReview");
        Task source = fixture.task(instance.getId(), "firstAnyReview", "201");
        fixture.returnGroup(source, "201");
        Task applicantTask = fixture.returnedTask(instance.getId());

        fixture.resubmitGroup(applicantTask, "或签重提");

        List<WfMultiInstanceRound> reopenedRows = fixture.rounds(instance.getId());
        assertThat(reopenedRows).hasSize(2);
        assertThat(reopenedRows.get(0).getRoundStatus())
                .isEqualTo(WorkflowMultiInstanceRoundStatus.REOPENED);
        WfMultiInstanceRound newRound = reopenedRows.get(1);
        assertThat(newRound.getRoundStatus())
                .isEqualTo(WorkflowMultiInstanceRoundStatus.ACTIVE);
        assertThat(newRound.getRootExecutionId())
                .isNotEqualTo(original.getRootExecutionId());
        assertThat(newRound.getMembers()).containsExactlyElementsOf(members);
        assertThat(newRound.getMode()).isEqualTo(WorkflowMultiInstanceMode.ANY.name());
        assertThat(newRound.getRevisionNo()).isZero();
        assertThat(fixture.tasks(instance.getId(), "firstAnyReview"))
                .extracting(Task::getAssignee)
                .containsExactlyElementsOf(members.stream().sorted().toList());

        fixture.setCurrentUser("203");
        fixture.complete(fixture.task(instance.getId(), "firstAnyReview", "203"), 0);

        assertThat(fixture.runtimeService.createProcessInstanceQuery()
                .processInstanceId(instance.getId()).singleResult()).isNull();
        assertThat(fixture.taskService.createTaskQuery().processInstanceId(instance.getId())
                .active().count()).isZero();
        assertThat(fixture.rounds(instance.getId())).hasSize(2);
        assertThat(fixture.rounds(instance.getId()).get(0).getRoundStatus())
                .isEqualTo(WorkflowMultiInstanceRoundStatus.REOPENED);
        assertThat(fixture.rounds(instance.getId()).get(1).getRoundStatus())
                .isEqualTo(WorkflowMultiInstanceRoundStatus.COMPLETED);
    }
}
