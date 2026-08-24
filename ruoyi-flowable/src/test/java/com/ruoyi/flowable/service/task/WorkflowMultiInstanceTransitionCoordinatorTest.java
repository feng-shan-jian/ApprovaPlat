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
                coordinator.beginReturn(returnExecutionPlan(plan), "201"))
        {
            WorkflowMultiInstanceTransitionMembers members =
                    coordinator.resolveTransitionMembers("pi", "approve",
                            WorkflowMultiInstanceMode.ALL);
            assertThat(members.overrideMembers()).containsExactly("201");
            assertThat(members.refreshAuthoritative()).isFalse();
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
     * 验证重提刷新权威成员，并只接受唯一新根和完整创建数量。
     *
     * @return void，成员、根或创建数量漂移时测试失败
     */
    @Test
    void completesReopenProtocolWithAuthoritativeMembers()
    {
        WorkflowMultiInstanceTransitionCoordinator coordinator =
                new WorkflowMultiInstanceTransitionCoordinator();
        MultiInstanceGroupReopenPlan plan = reopenPlan();

        try (WorkflowMultiInstanceTransitionScope scope =
                coordinator.beginReopen(plan, "100"))
        {
            WorkflowMultiInstanceTransitionMembers members =
                    coordinator.resolveTransitionMembers("pi", "firstApprove",
                            WorkflowMultiInstanceMode.ALL);
            assertThat(members.overrideMembers()).isEmpty();
            assertThat(members.refreshAuthoritative()).isTrue();
            coordinator.requirePersistedSnapshot("pi", "firstApprove",
                    WorkflowMultiInstanceMode.ALL, List.of("201", "202"), 3);
            assertThat(coordinator.resolveTransitionMembers("pi", "firstApprove",
                    WorkflowMultiInstanceMode.ALL)).isNull();
            assertThat(coordinator.observeControlledRootCancellation("pi", "pd",
                    "firstApprove", "first-root", "100")).isNotNull();
            coordinator.observeReopenedTask("pi", "firstApprove", "new-root", "301");
            coordinator.observeReopenedTask("pi", "firstApprove", "new-root", "302");

            coordinator.requireReopenCompleted(scope, "new-root",
                    List.of("301", "302"), true);
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
     * 构造一份 RETURNED 来源轮次和首审批受控临时任务组成的重提计划。
     *
     * @return MultiInstanceGroupReopenPlan，申请人任务与正式轮次关联一致
     */
    private MultiInstanceGroupReopenPlan reopenPlan()
    {
        ReturnedApplicationSnapshot application = new ReturnedApplicationSnapshot(
                "applicant-task", "applicant-exec", "first-root", "pi", "pd",
                "firstApprove", "100",
                ReturnedApplicationSnapshot.SourceKind.TEMPORARY_MULTI_INSTANCE_ROOT);
        MultiInstanceRoundSnapshot source = round(
                WorkflowMultiInstanceRoundStatus.RETURNED, "applicant-task");
        MultiInstanceRoundSnapshot target = round("firstApprove", "first-root",
                WorkflowMultiInstanceRoundStatus.COMPLETED, null);
        return new MultiInstanceGroupReopenPlan(source, application,
                new WorkflowTaskMovementPolicy.ControlledReturnPathPlan(
                        List.of("firstApprove", "approve")),
                List.of(replay("firstApprove", target), replay("approve", source)));
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
