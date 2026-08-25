package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import com.ruoyi.flowable.domain.WfMultiInstanceRound;
import com.ruoyi.flowable.domain.WorkflowMultiInstanceRoundStatus;
import com.ruoyi.flowable.domain.vo.WorkflowInstanceTerminateView;

/**
 * 通过生产管理员入口验证 RETURNED 多实例轮次的真实 Flowable 终止闭环。
 */
@SpringJUnitConfig(WorkflowMultiInstanceEngineHarness.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class WorkflowMultiInstanceReturnedTerminationIntegrationTest
{
    /** 只调用生产公开入口的业务驱动。 */
    @Autowired
    private WorkflowMultiInstanceBusinessDriver driver;

    /** 只读取终止后的引擎、历史和轮次事实。 */
    @Autowired
    private WorkflowMultiInstanceStateProbe probe;

    /**
     * 验证 ACTIVE 多实例轮次经正式管理员入口终止后与 Flowable 实例同时关闭。
     *
     * @return void，活动任务、execution 或 OPEN 轮次残留时失败
     */
    @Test
    void terminatesActiveMultiInstanceRound()
    {
        ProcessInstance instance = driver.startLifecycle("roundGroupFirstAll",
                "firstAllReturnStart", "firstAllReview",
                List.of("201", "202", "203"));

        WorkflowInstanceTerminateView result = driver.terminate(instance.getId());

        assertThat(result.processStatus()).isEqualTo("terminated");
        assertThat(probe.processInstance(instance.getId())).isNull();
        assertThat(probe.executions(instance.getId())).isEmpty();
        assertThat(probe.openRounds(Set.of(instance.getId()))).isEmpty();
        assertThat(probe.rounds(instance.getId())).singleElement()
                .extracting(WfMultiInstanceRound::getRoundStatus)
                .isEqualTo(WorkflowMultiInstanceRoundStatus.TERMINATED);
    }

    /**
     * 验证首审批受控组退回后，管理员终止会删除临时申请人根并关闭 RETURNED 轮次。
     *
     * @return void，权限、引擎删除、历史状态或轮次终态漂移时失败
     */
    @Test
    void terminatesReturnedFirstMultiInstanceApplicantRoot()
    {
        ProcessInstance instance = driver.startLifecycle("roundGroupFirstAll",
                "firstAllReturnStart", "firstAllReview",
                List.of("201", "202", "203"));
        WfMultiInstanceRound active = probe.activeRound(
                instance.getId(), "firstAllReview");
        Task source = probe.task(instance.getId(), "firstAllReview", "202");
        driver.returnGroup(source, "202");

        Task applicantTask = probe.returnedTask(instance.getId());
        WfMultiInstanceRound returned = probe.rounds(instance.getId()).get(0);
        Execution applicantExecution = probe.execution(applicantTask.getExecutionId());
        assertThat(applicantExecution).isNotNull();
        assertThat(applicantExecution.getParentId())
                .isNotBlank().isNotEqualTo(active.getRootExecutionId());
        assertThat(returned.getRoundStatus())
                .isEqualTo(WorkflowMultiInstanceRoundStatus.RETURNED);

        WorkflowInstanceTerminateView result = driver.terminate(instance.getId());

        assertThat(result.processStatus()).isEqualTo("terminated");
        assertThat(probe.processInstance(instance.getId())).isNull();
        assertThat(probe.tasks(instance.getId(), "firstAllReview")).isEmpty();
        assertThat(probe.historicBusinessStatus(instance.getId()))
                .isEqualTo("terminated");
        assertThat(probe.rounds(instance.getId())).singleElement().satisfies(terminated ->
        {
            assertThat(terminated.getRoundStatus())
                    .isEqualTo(WorkflowMultiInstanceRoundStatus.TERMINATED);
            assertThat(terminated.getTerminateTime()).isNotNull();
            assertThat(terminated.getReturnSourceTaskId())
                    .isEqualTo(returned.getReturnSourceTaskId());
            assertThat(terminated.getApplicantTaskId())
                    .isEqualTo(returned.getApplicantTaskId());
        });
        assertThat(probe.openRounds(Set.of(instance.getId()))).isEmpty();
    }
}
