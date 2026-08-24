package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import com.ruoyi.flowable.domain.WfMultiInstanceRound;
import com.ruoyi.flowable.domain.WorkflowMultiInstanceRoundStatus;
import com.ruoyi.flowable.service.process.WorkflowFormSubmissionSnapshotCodec;
import com.ruoyi.flowable.service.process.WorkflowProcessStartService;

/**
 * 通过生产重提入口验证来源轮次只关闭审计，并从可信首审批节点重新自然流转。
 */
@SpringJUnitConfig(WorkflowMultiInstanceEngineHarness.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class WorkflowMultiInstanceGroupResubmitIntegrationTest
{
    /** 只调用生产公开入口的业务驱动。 */
    @Autowired
    private WorkflowMultiInstanceBusinessDriver driver;

    /** 只读取引擎和业务表事实的状态探针。 */
    @Autowired
    private WorkflowMultiInstanceStateProbe probe;

    /**
     * 验证后续 ALL 轮次已有成员完成后，重提停留在普通首审批而不是直接重开来源组。
     *
     * @return void，来源轮次、新首审批任务、表单或运行状态漂移时失败
     */
    @Test
    void resumesAtOrdinaryFirstApprovalBeforeReenteringLaterAll()
    {
        List<String> members = List.of("203", "201", "202");
        ProcessInstance instance = driver.startLifecycle("roundGroupLaterAll",
                "laterAllReturnStart", "laterAllReview", members);
        driver.completeOrdinary(probe.task(instance.getId(),
                "laterInitialApproval", "150"));
        WfMultiInstanceRound original = probe.activeRound(
                instance.getId(), "laterAllReview");
        driver.complete(probe.task(instance.getId(), "laterAllReview", "201"), 0);
        Task source = probe.task(instance.getId(), "laterAllReview", "202");
        driver.returnGroup(source, "202");
        Task applicantTask = probe.returnedTask(instance.getId());
        WfMultiInstanceRound returned = probe.rounds(instance.getId()).get(0);

        driver.resubmit(applicantTask, Map.of("requestTitle", "修改后申请"));

        List<WfMultiInstanceRound> roundsAfterResubmit = probe.rounds(instance.getId());
        assertThat(roundsAfterResubmit).singleElement().satisfies(reopened ->
        {
            assertThat(reopened.getRoundId()).isEqualTo(original.getRoundId());
            assertThat(reopened.getRoundStatus())
                    .isEqualTo(WorkflowMultiInstanceRoundStatus.REOPENED);
            assertThat(reopened.getReturnTime()).isEqualTo(returned.getReturnTime());
            assertThat(reopened.getReopenTime()).isNotNull();
        });
        assertThat(probe.tasks(instance.getId(), "laterInitialApproval"))
                .extracting(Task::getId, Task::getAssignee)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        applicantTask.getId(), "150"));
        assertThat(probe.tasks(instance.getId(), "laterAllReview")).isEmpty();
        assertThat(probe.variable(instance.getId(),
                WorkflowMultiInstanceVariables.memberSnapshotName("laterAllReview")))
                .isNull();
        assertThat(probe.variable(instance.getId(),
                WorkflowMultiInstanceVariables.modeName("laterAllReview")))
                .isNull();
        assertThat(probe.variable(instance.getId(),
                WorkflowMultiInstanceVariables.revisionName("laterAllReview")))
                .isNull();
        probe.assertDoubleStatus(instance.getId(), WorkflowProcessStartService.RUNNING_STATUS);
        WorkflowFormSubmissionSnapshotCodec.SubmissionSnapshot snapshot =
                WorkflowFormSubmissionSnapshotCodec.decode((String) probe.variable(
                        instance.getId(), WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME));
        assertThat(snapshot.values().get("requestTitle").stringValue())
                .isEqualTo("修改后申请");

        driver.completeOrdinary(probe.task(
                instance.getId(), "laterInitialApproval", "150"));
        assertThat(probe.tasks(instance.getId(), "laterAllReview"))
                .extracting(Task::getAssignee)
                .containsExactlyElementsOf(members.stream().sorted().toList());
        assertThat(probe.rounds(instance.getId())).hasSize(2);
        assertThat(probe.activeRound(instance.getId(), "laterAllReview"))
                .satisfies(active ->
                {
                    assertThat(active.getRoundNo())
                            .isEqualTo(original.getRoundNo() + 1);
                    assertThat(active.getMembers()).containsExactlyElementsOf(members);
                    assertThat(active.getRevisionNo()).isZero();
                });
    }

    /**
     * 验证首节点 ANY 轮次重提生成新根，并由任一成员完成真实结束流程。
     *
     * @return void，新根未继承 ANY 快照或单成员完成未关闭新轮时失败
     */
    @Test
    void rebuildsFirstAnyGroupAndKeepsAnyCompletionBehavior()
    {
        List<String> members = List.of("203", "201", "202");
        ProcessInstance instance = driver.startLifecycle("roundGroupFirstAny",
                "firstAnyReturnStart", "firstAnyReview", members);
        WfMultiInstanceRound original = probe.activeRound(
                instance.getId(), "firstAnyReview");
        driver.returnGroup(probe.task(instance.getId(), "firstAnyReview", "201"), "201");
        Task applicantTask = probe.returnedTask(instance.getId());

        driver.resubmit(applicantTask, Map.of("requestTitle", "或签重提"));

        List<WfMultiInstanceRound> rounds = probe.rounds(instance.getId());
        assertThat(rounds).hasSize(2);
        assertThat(rounds.get(0).getRoundStatus())
                .isEqualTo(WorkflowMultiInstanceRoundStatus.REOPENED);
        assertThat(rounds.get(1)).satisfies(active ->
        {
            assertThat(active.getRootExecutionId())
                    .isNotEqualTo(original.getRootExecutionId());
            assertThat(active.getMembers()).containsExactlyElementsOf(members);
            assertThat(active.getMode()).isEqualTo(WorkflowMultiInstanceMode.ANY.name());
            assertThat(active.getRevisionNo()).isZero();
        });
        driver.complete(probe.task(instance.getId(), "firstAnyReview", "203"), 0);

        assertThat(probe.processInstance(instance.getId())).isNull();
        assertThat(probe.tasks(instance.getId(), "firstAnyReview")).isEmpty();
        assertThat(probe.rounds(instance.getId()))
                .extracting(WfMultiInstanceRound::getRoundStatus)
                .containsExactly(WorkflowMultiInstanceRoundStatus.REOPENED,
                        WorkflowMultiInstanceRoundStatus.COMPLETED);
    }
}
