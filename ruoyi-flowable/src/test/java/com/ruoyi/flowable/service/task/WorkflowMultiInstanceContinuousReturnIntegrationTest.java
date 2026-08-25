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

/**
 * 使用生产入口和真实 Flowable 验证普通首审批、连续 ALL 与 ANY 的退回重提自然重放。
 */
@SpringJUnitConfig(WorkflowMultiInstanceEngineHarness.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class WorkflowMultiInstanceContinuousReturnIntegrationTest
{
    /** 只调用正式生命周期入口的业务驱动。 */
    @Autowired
    private WorkflowMultiInstanceBusinessDriver driver;

    /** 只读取引擎和轮次表真实事实的状态探针。 */
    @Autowired
    private WorkflowMultiInstanceStateProbe probe;

    /**
     * 验证从第二个 ALL 部分完成组退回后，重提只恢复普通首审批并再次依次进入全部节点。
     *
     * @return 无返回值，直接重开来源组、轮次遗漏或节点顺序漂移时失败
     */
    @Test
    void returnsFromSecondAllAndReplaysFromOrdinaryFirstApproval()
    {
        ProcessInstance instance = startContinuousProcess();
        driver.completeOrdinary(probe.task(instance.getId(),
                "allAllAnyInitialApproval", "150"));
        completeAll(instance.getId(), "firstSerialAll");
        driver.complete(probe.task(instance.getId(), "secondSerialAll", "301"), 0);
        Task source = probe.task(instance.getId(), "secondSerialAll", "302");

        assertThat(driver.returnAllowed(source, "302")).isTrue();
        driver.returnGroup(source, "302");
        Task applicantTask = probe.returnedTask(instance.getId());
        driver.resubmit(applicantTask, Map.of("requestTitle", "第二会签退回后修改"));

        assertThat(probe.tasks(instance.getId(), "allAllAnyInitialApproval"))
                .extracting(Task::getId, Task::getAssignee)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        applicantTask.getId(), "150"));
        assertThat(probe.tasks(instance.getId(), "secondSerialAll")).isEmpty();
        assertThat(probe.rounds(instance.getId()).stream()
                .filter(round -> "secondSerialAll".equals(round.getActivityId())))
                .extracting(WfMultiInstanceRound::getRoundStatus)
                .containsExactly(WorkflowMultiInstanceRoundStatus.REOPENED);

        driver.completeOrdinary(probe.task(instance.getId(),
                "allAllAnyInitialApproval", "150"));
        completeAll(instance.getId(), "firstSerialAll");
        completeAll(instance.getId(), "secondSerialAll");
        assertThat(probe.tasks(instance.getId(), "thirdSerialAny"))
                .extracting(Task::getAssignee)
                .containsExactly("501", "502", "503");
    }

    /**
     * 验证两个 ALL 完成后第三个 ANY 仍可整组退回，且重提不会直接产生来源 ANY。
     *
     * @return 无返回值，按钮能力、兄弟撤销或自然重放任一不一致时失败
     */
    @Test
    void returnsThirdAnyWithSiblingsAndDoesNotReopenItDirectly()
    {
        ProcessInstance instance = startContinuousProcess();
        driver.completeOrdinary(probe.task(instance.getId(),
                "allAllAnyInitialApproval", "150"));
        completeAll(instance.getId(), "firstSerialAll");
        completeAll(instance.getId(), "secondSerialAll");
        Task source = probe.task(instance.getId(), "thirdSerialAny", "502");
        assertThat(probe.tasks(instance.getId(), "thirdSerialAny")).hasSize(3);

        assertThat(driver.returnAllowed(source, "502")).isTrue();
        driver.returnGroup(source, "502");
        assertThat(probe.tasks(instance.getId(), "thirdSerialAny")).isEmpty();
        Task applicantTask = probe.returnedTask(instance.getId());
        driver.resubmit(applicantTask, Map.of("requestTitle", "或签退回后修改"));

        assertThat(probe.tasks(instance.getId(), "allAllAnyInitialApproval"))
                .extracting(Task::getAssignee).containsExactly("150");
        assertThat(probe.tasks(instance.getId(), "thirdSerialAny")).isEmpty();

        driver.completeOrdinary(probe.task(instance.getId(),
                "allAllAnyInitialApproval", "150"));
        completeAll(instance.getId(), "firstSerialAll");
        completeAll(instance.getId(), "secondSerialAll");
        assertThat(probe.tasks(instance.getId(), "thirdSerialAny"))
                .extracting(Task::getAssignee)
                .containsExactly("501", "502", "503");
        assertThat(probe.activeRound(instance.getId(), "thirdSerialAny")
                .getRoundNo()).isEqualTo(2);
    }

    /**
     * 验证首审批本身为 ALL 时，从后续 ANY 退回和重提仍重建首节点而不是来源节点。
     *
     * @return 无返回值，临时根取消、首节点成员来源或来源轮次关闭漂移时失败
     */
    @Test
    void rebuildsControlledFirstApprovalWhenReturnSourceIsLaterAny()
    {
        ProcessInstance instance = driver.startLifecycle(
                "roundGroupFirstAllLaterAny", "firstAllLaterAnyReturnStart",
                Map.of("firstReplayAll", List.of("201", "202"),
                        "laterReplayAny", List.of("301", "302", "303")));
        completeAll(instance.getId(), "firstReplayAll");
        Task source = probe.task(instance.getId(), "laterReplayAny", "302");

        driver.returnGroup(source, "302");
        Task applicantTask = probe.returnedTask(instance.getId());
        assertThat(applicantTask.getTaskDefinitionKey()).isEqualTo("firstReplayAll");
        assertThat(probe.tasks(instance.getId(), "firstReplayAll"))
                .extracting(Task::getAssignee)
                .containsExactly(WorkflowMultiInstanceBusinessDriver.APPLICANT_ID);

        driver.resubmit(applicantTask, Map.of("requestTitle", "首会签重新开始"));

        assertThat(probe.tasks(instance.getId(), "firstReplayAll"))
                .extracting(Task::getAssignee)
                .containsExactly("201", "202");
        assertThat(probe.tasks(instance.getId(), "laterReplayAny")).isEmpty();
        assertThat(probe.activeRound(instance.getId(), "firstReplayAll")
                .getRoundNo()).isEqualTo(2);
        assertThat(probe.rounds(instance.getId()).stream()
                .filter(round -> "laterReplayAny".equals(round.getActivityId()))
                .map(WfMultiInstanceRound::getRoundStatus).toList())
                .containsExactly(WorkflowMultiInstanceRoundStatus.REOPENED);
    }

    /**
     * 启动包含普通首审批、两个 ALL 和一个 ANY 的真实测试实例。
     *
     * @return ProcessInstance，所有节点成员来源均由服务端流程变量预置的活动实例
     */
    private ProcessInstance startContinuousProcess()
    {
        return driver.startLifecycle("roundGroupAllAllAny",
                "allAllAnyReturnStart", Map.of(
                        "firstSerialAll", List.of("201", "202"),
                        "secondSerialAll", List.of("301", "302", "303"),
                        "thirdSerialAny", List.of("501", "502", "503")));
    }

    /**
     * 按真实活动任务逐一完成指定 ALL 节点，不直接改写任何 Flowable 计数变量。
     *
     * @param processInstanceId String，流程实例主键
     * @param activityId String，当前 ALL 节点 key
     * @return 无返回值，全部成员通过正式 completeTask 入口办理
     */
    private void completeAll(String processInstanceId, String activityId)
    {
        List<Task> tasks = probe.tasks(processInstanceId, activityId);
        assertThat(tasks).isNotEmpty();
        // 每次完成都会 CAS 递增正式 revision，后续成员必须携带前一次提交后的新版本。
        for (int revision = 0; revision < tasks.size(); revision++)
        {
            driver.complete(tasks.get(revision), revision);
        }
    }
}
