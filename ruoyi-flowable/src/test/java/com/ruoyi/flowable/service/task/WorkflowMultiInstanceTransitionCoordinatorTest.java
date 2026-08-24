package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    /**
     * 验证整组退回必须完成集合解析、根取消和临时任务观察，并在关闭后清除状态。
     *
     * @return void，任一观察缺失或状态泄漏时测试失败
     */
    @Test
    void completesReturnProtocolAndClearsThreadState()
    {
        WorkflowMultiInstanceTransitionCoordinator coordinator =
                new WorkflowMultiInstanceTransitionCoordinator();
        MultiInstanceGroupReturnPlan plan = returnPlan();

        try (WorkflowMultiInstanceTransitionScope scope =
                coordinator.beginReturn(plan, "201", "approve"))
        {
            assertThat(coordinator.resolveTransitionMembers("pi", "approve",
                    WorkflowMultiInstanceMode.ALL)).containsExactly("201");
            assertThat(coordinator.observeControlledRootCancellation("pi", "pd",
                    "approve", "root", "201")).isNotNull();
            assertThat(coordinator.observeTemporaryTask("pi", "approve",
                    "temporary-root", "applicant-task", "201")).isTrue();

            coordinator.requireReturnCompleted(scope, "applicant-task", true);
        }

        assertThat(coordinator.resolveTransitionMembers("pi", "approve",
                WorkflowMultiInstanceMode.ALL)).isNull();
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
                    coordinator.beginReturn(plan, "201", "approve"))
            {
                throw new IllegalStateException("injected command failure");
            }
        }).isInstanceOf(IllegalStateException.class);

        try (WorkflowMultiInstanceTransitionScope ignored =
                coordinator.beginReturn(plan, "201", "approve"))
        {
            assertThatThrownBy(() -> coordinator.beginReturn(plan, "201", "approve"))
                    .isInstanceOf(ServiceException.class)
                    .hasMessage("工作流多实例受控迁移上下文异常");
        }
    }

    /**
     * 验证重提只接受冻结成员、唯一新根和完整创建数量。
     *
     * @return void，成员、根或创建数量漂移时测试失败
     */
    @Test
    void completesReopenProtocolWithFrozenMembers()
    {
        WorkflowMultiInstanceTransitionCoordinator coordinator =
                new WorkflowMultiInstanceTransitionCoordinator();
        MultiInstanceGroupReopenPlan plan = reopenPlan();

        try (WorkflowMultiInstanceTransitionScope scope =
                coordinator.beginReopen(plan, "100"))
        {
            assertThat(coordinator.resolveTransitionMembers("pi", "approve",
                    WorkflowMultiInstanceMode.ALL)).containsExactly("201", "202");
            coordinator.requirePersistedSnapshot("pi", "approve",
                    WorkflowMultiInstanceMode.ALL, List.of("201", "202"), 3);
            coordinator.observeReopenedTask("pi", "approve", "new-root", "201");
            coordinator.observeReopenedTask("pi", "approve", "new-root", "202");

            coordinator.requireReopenCompleted(scope, "new-root", false);
        }
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
     * 构造一份 RETURNED 轮次和普通申请人任务组成的重提计划。
     *
     * @return MultiInstanceGroupReopenPlan，申请人任务与正式轮次关联一致
     */
    private MultiInstanceGroupReopenPlan reopenPlan()
    {
        ReturnedApplicationSnapshot application = new ReturnedApplicationSnapshot(
                "applicant-task", "applicant-exec", "applicant-exec", "pi", "pd",
                "startApprove", "100",
                ReturnedApplicationSnapshot.SourceKind.ORDINARY_EXECUTION);
        return new MultiInstanceGroupReopenPlan(round(
                WorkflowMultiInstanceRoundStatus.RETURNED, "applicant-task"),
                application);
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
        LocalDateTime now = LocalDateTime.of(2026, 8, 24, 9, 0);
        return new MultiInstanceRoundSnapshot(1L, "dep", "pd", "pi", "approve",
                "root", 1, WorkflowMultiInstanceMode.ALL, List.of("201", "202"),
                3, status, "task-201", "201", applicantTaskId, now,
                status == WorkflowMultiInstanceRoundStatus.RETURNED ? now : null,
                null, null, null);
    }
}
