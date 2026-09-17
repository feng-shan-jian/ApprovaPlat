package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WorkflowMultiInstanceRoundStatus;

/**
 * 聚焦验证命令内迁移协调器的状态机、完成命令和 ThreadLocal 清理边界。
 */
class WorkflowMultiInstanceTransitionCoordinatorTest
{
    /** 外来回调的定义、实例、节点、execution、操作者和重复取消仍是实际边界。 */
    @Test
    void returnRejectsMismatchedCallbacksAndObservesTheRealTarget()
    {
        var coordinator = new WorkflowMultiInstanceTransitionCoordinator();
        var plan = returnExecutionPlan(returnPlan());
        assertThatThrownBy(() -> coordinator.beginReturn(plan, "100")).isInstanceOf(ServiceException.class);
        try (var scope = coordinator.beginReturn(plan, "201"))
        {
            String[] expected = { "pi", "pd", "approve", "root", "201" };
            for (int field = 0; field < expected.length; field++)
            {
                String[] wrong = expected.clone(); wrong[field] = "other";
                assertThatThrownBy(() -> coordinator.observeControlledRootCancellation(
                        wrong[0], wrong[1], wrong[2], wrong[3], wrong[4])).isInstanceOf(ServiceException.class);
            }
            var cancellation = coordinator.observeControlledRootCancellation("pi", "pd", "approve", "root", "201");
            assertThat(cancellation).isSameAs(plan);
            assertThatThrownBy(() -> coordinator.observeControlledRootCancellation("pi", "pd", "approve", "root", "201"))
                    .isInstanceOf(ServiceException.class);
            assertThatThrownBy(() -> coordinator.resolveTransitionMembers("pi", "other", WorkflowMultiInstanceMode.ALL))
                    .isInstanceOf(ServiceException.class);
            coordinator.resolveTransitionMembers("pi", "approve", WorkflowMultiInstanceMode.ALL);
            assertThatThrownBy(() -> coordinator.requirePersistedSnapshot("pi", "approve", WorkflowMultiInstanceMode.ALL,
                    List.of("201", "202"), 4)).isInstanceOf(ServiceException.class);
            coordinator.requirePersistedSnapshot("pi", "approve", WorkflowMultiInstanceMode.ALL, List.of("201", "202"), 3);
            assertThatThrownBy(() -> coordinator.observeTemporaryTask("pi", "approve", "new-root", "new-task", "100"))
                    .isInstanceOf(ServiceException.class);
            assertThat(coordinator.observeTemporaryTask("pi", "approve", "new-root", "new-task", "201")).isTrue();
            coordinator.requireReturnCompleted(scope, "new-task", true);
        }
        assertThat(coordinator.resolveTransitionMembers("pi", "approve", WorkflowMultiInstanceMode.ALL)).isNull();
    }

    @Test
    void reopenUsesReturnedPlanAndRequiresOneCompleteNewRoot()
    {
        var coordinator = new WorkflowMultiInstanceTransitionCoordinator();
        var returnedRound = round(WorkflowMultiInstanceRoundStatus.RETURNED, "applicant-task");
        var plan = new MultiInstanceGroupReopenPlan(returnedRound,
                new ReturnedApplicationSnapshot("applicant-task", "applicant-exec", "temporary-root", "pi", "pd", "approve", "100",
                        ReturnedApplicationSnapshot.SourceKind.TEMPORARY_MULTI_INSTANCE_ROOT),
                new WorkflowTaskMovementPolicy.ControlledReturnPathPlan(List.of("approve")),
                List.of(replay("approve", returnedRound)));
        assertThatThrownBy(() -> coordinator.beginReopen(plan, "201")).isInstanceOf(ServiceException.class);
        try (var scope = coordinator.beginReopen(plan, "100"))
        {
            assertThatThrownBy(() -> coordinator.observeControlledRootCancellation("pi", "pd", "approve", "root", "100"))
                    .isInstanceOf(ServiceException.class);
            coordinator.observeControlledRootCancellation("pi", "pd", "approve", "temporary-root", "100");
            assertThat(coordinator.resolveTransitionMembers("pi", "approve", WorkflowMultiInstanceMode.ALL)).isNotNull();
            assertThat(coordinator.resolveTransitionMembers("pi", "approve", WorkflowMultiInstanceMode.ALL)).isNull();
            coordinator.observeReopenedTask("pi", "approve", "reopened-root", "201");
            assertThatThrownBy(() -> coordinator.observeReopenedTask("pi", "approve", "other-root", "202"))
                    .isInstanceOf(ServiceException.class);
            assertThatThrownBy(() -> coordinator.observeReopenedTask("pi", "approve", "reopened-root", "201"))
                    .isInstanceOf(ServiceException.class);
            assertThatThrownBy(() -> coordinator.requireReopenCompleted(scope, "reopened-root", List.of("201", "202"), true))
                    .isInstanceOf(ServiceException.class);
            coordinator.observeReopenedTask("pi", "approve", "reopened-root", "202");
            coordinator.requireReopenCompleted(scope, "reopened-root", List.of("201", "202"), true);
        }
    }
    /**
     * 验证异常离开作用域仍清除线程状态，下一命令可以在同一线程重新开启。
     *
     * @return void，异常路径残留或错误嵌套时测试失败
     */
    @Test
    void clearsThreadStateAfterExceptionalScopeExit()
    {
        WorkflowMultiInstanceTransitionCoordinator coordinator =
                new WorkflowMultiInstanceTransitionCoordinator();
        MultiInstanceGroupReturnPlan plan = returnPlan();

        assertThatThrownBy(() ->
        {
            try (WorkflowMultiInstanceTransitionScope ignored =
                    coordinator.beginReturn(returnExecutionPlan(plan), "201"))
            {
                throw new IllegalStateException("injected command failure");
            }
        }).isInstanceOf(IllegalStateException.class);

        try (WorkflowMultiInstanceTransitionScope ignored =
                coordinator.beginReturn(returnExecutionPlan(plan), "201"))
        {
            assertThatThrownBy(() -> coordinator.beginReturn(
                    returnExecutionPlan(plan), "201"))
                    .isInstanceOf(ServiceException.class)
                    .hasMessage("工作流多实例受控迁移上下文异常");
        }
    }

    /**
     * 将来源轮次计划包装为包含唯一受控节点的退回路径执行计划。
     *
     * @param source MultiInstanceGroupReturnPlan，已经对账的来源轮次和运行树
     * @return MultiInstanceGroupReturnExecutionPlan，目标和来源均为 approve 的冻结计划
     */
    private MultiInstanceGroupReturnExecutionPlan returnExecutionPlan(
            MultiInstanceGroupReturnPlan source)
    {
        ControlledMultiInstanceReplaySnapshot replay = replay(
                source.round().activityId(), source.round());
        return new MultiInstanceGroupReturnExecutionPlan(source, "approve",
                new WorkflowTaskMovementPolicy.ControlledReturnPathPlan(
                        List.of("approve")),
                List.of(replay));
    }

    /**
     * 构造一份通过轮次和实时执行树严格对账的整组退回计划。
     *
     * @return MultiInstanceGroupReturnPlan，包含两名成员和 revision 3 的冻结计划
     */
    private MultiInstanceGroupReturnPlan returnPlan()
    {
        MultiInstanceRoundSnapshot round = round(
                WorkflowMultiInstanceRoundStatus.ACTIVE, null);
        ControlledMultiInstanceSnapshot runtime = new ControlledMultiInstanceSnapshot(
                "dep", "pd", "pi", "approve", "root", "task-201", "exec-201",
                WorkflowMultiInstanceMode.ALL, List.of("201", "202"), 3,
                new MultiInstanceEngineCounts(2, 2, 0),
                List.of(new MultiInstanceActiveTaskSnapshot("task-201", "exec-201",
                                "201", null, false),
                        new MultiInstanceActiveTaskSnapshot("task-202", "exec-202",
                                "202", null, false)),
                List.of("exec-201", "exec-202"));
        return new MultiInstanceGroupReturnPlan(round, runtime, "100");
    }

    /**
     * 构造与正式轮次身份、节点和模式一致的受控重放快照。
     *
     * @param activityId String，受控多实例节点 key
     * @param round MultiInstanceRoundSnapshot，该节点最近正式轮次
     * @return ControlledMultiInstanceReplaySnapshot，可供协调器逐项核验的部署和轮次事实
     */
    private ControlledMultiInstanceReplaySnapshot replay(String activityId,
            MultiInstanceRoundSnapshot round)
    {
        return new ControlledMultiInstanceReplaySnapshot(
                new ControlledMultiInstanceDefinitionSnapshot(
                        "dep", "pd", activityId, WorkflowMultiInstanceMode.ALL),
                round);
    }

    /**
     * 构造测试使用的完整正式轮次快照。
     *
     * @param status WorkflowMultiInstanceRoundStatus，待构造轮次状态
     * @param applicantTaskId String，RETURNED 状态关联的申请人任务主键
     * @return MultiInstanceRoundSnapshot，稳定身份、成员和 revision 均完整
     */
    private MultiInstanceRoundSnapshot round(WorkflowMultiInstanceRoundStatus status,
            String applicantTaskId)
    {
        return round("approve", "root", status, applicantTaskId);
    }

    /**
     * 按指定节点和根 execution 构造测试使用的完整正式轮次快照。
     *
     * @param activityId String，受控多实例节点 key
     * @param rootExecutionId String，正式轮次根 execution 主键
     * @param status WorkflowMultiInstanceRoundStatus，待构造轮次状态
     * @param applicantTaskId String，RETURNED 状态关联的申请人任务主键
     * @return MultiInstanceRoundSnapshot，稳定身份、成员和 revision 均完整
     */
    private MultiInstanceRoundSnapshot round(String activityId,
            String rootExecutionId, WorkflowMultiInstanceRoundStatus status,
            String applicantTaskId)
    {
        LocalDateTime now = LocalDateTime.of(2026, 8, 24, 9, 0);
        return new MultiInstanceRoundSnapshot(1L, "dep", "pd", "pi", activityId,
                rootExecutionId, 1, WorkflowMultiInstanceMode.ALL,
                List.of("201", "202"),
                3, status, "task-201", "201", applicantTaskId, now,
                status == WorkflowMultiInstanceRoundStatus.RETURNED ? now : null,
                null, null, null);
    }
}
