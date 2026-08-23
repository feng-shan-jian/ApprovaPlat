package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;

import java.util.List;
import java.util.Map;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfMultiInstanceRound;
import com.ruoyi.flowable.domain.WorkflowMultiInstanceRoundStatus;

/**
 * 使用真实 Flowable signal 边界事件验证引擎原生中断、非中断旁路和同节点新轮次。
 */
class WorkflowMultiInstanceRoundInterruptionIntegrationTest
        extends WorkflowMultiInstanceRoundFlowableSupport
{
    /**
     * 部署包含多实例、CallActivity 和边界事件的正式 BPMN。
     *
     * @return void，无返回值；全局中断监听器已由公共 Flowable 支撑按生产语义注册
     */
    @BeforeEach
    void deployInterruptionProcesses()
    {
        repositoryService.createDeployment()
                .addClasspathResource(
                        "bpmn/workflow-multi-instance-round-interruption.bpmn20.xml")
                .deploy();
    }

    /**
     * 验证中断边界事件删除受控多实例根和成员任务，并在同一命令内异常关闭开放轮次。
     *
     * @return void，旧根、任务、轮次状态或专用关闭时间任一残留时失败
     */
    @Test
    void closesOpenRoundWhenInterruptingBoundaryCancelsMultiInstanceRoot()
    {
        ProcessInstance instance = start("roundInterruptReentry");
        WfMultiInstanceRound before = activeRound(instance.getId(), "reentryReview");
        assertThat(tasks(instance.getId(), "reentryReview")).hasSize(2);

        signal(instance.getId(), "interruptReentrySignal");

        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(instance.getId()).singleResult()).isNotNull();
        assertThat(tasks(instance.getId(), "reentryReview")).isEmpty();
        assertThat(runtimeService.createExecutionQuery()
                .executionId(before.getRootExecutionId()).singleResult()).isNull();
        assertThat(runtimeService.createExecutionQuery()
                .processInstanceId(instance.getId())
                .signalEventSubscriptionName("resumeReentrySignal")
                .singleResult()).isNotNull();
        List<WfMultiInstanceRound> persisted = rounds(instance.getId());
        assertThat(persisted).singleElement();
        assertTerminatedRound(persisted.get(0), before);
    }

    /**
     * 验证父流程 CallActivity 的中断边界会递归取消子流程多实例并关闭子流程轮次。
     *
     * @return void，子流程、成员任务或子流程开放轮次任一遗留时失败
     */
    @Test
    void closesChildRoundWhenInterruptingBoundaryCancelsCallActivity()
    {
        ProcessInstance root = start("roundCallInterruptRoot");
        ProcessInstance child = runtimeService.createProcessInstanceQuery()
                .superProcessInstanceId(root.getId()).singleResult();
        assertThat(child).isNotNull();
        WfMultiInstanceRound before = activeRound(child.getId(), "callChildReview");
        assertThat(tasks(child.getId(), "callChildReview")).hasSize(2);

        signal(root.getId(), "interruptCallSignal");

        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(child.getId()).singleResult()).isNull();
        assertThat(tasks(child.getId(), "callChildReview")).isEmpty();
        assertThat(taskService.createTaskQuery().processInstanceId(root.getId())
                .taskDefinitionKey("callRecoveryTask").active().singleResult())
                .isNotNull();
        assertThat(historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(child.getId()).singleResult().getEndTime()).isNotNull();
        List<WfMultiInstanceRound> persisted = rounds(child.getId());
        assertThat(persisted).singleElement();
        assertTerminatedRound(persisted.get(0), before);
    }

    /**
     * 验证子流程已离开多实例根后，父 CallActivity 中断仍通过流程取消事件关闭 RETURNED 轮次。
     *
     * @return void，PROCESS_CANCELLED 兜底缺失或既有退回审计字段被清空时失败
     */
    @Test
    void closesReturnedChildRoundWhenParentBoundaryCancelsWaitingCallActivity()
    {
        ProcessInstance root = start("roundCallInterruptRoot");
        ProcessInstance child = runtimeService.createProcessInstanceQuery()
                .superProcessInstanceId(root.getId()).singleResult();
        assertThat(child).isNotNull();
        setCurrentUser("201");
        complete(task(child.getId(), "callChildReview", "201"), 0);
        setCurrentUser("202");
        complete(task(child.getId(), "callChildReview", "202"), 1);
        List<WfMultiInstanceRound> completedRounds = rounds(child.getId());
        assertThat(completedRounds).singleElement();
        WfMultiInstanceRound completed = completedRounds.get(0);
        assertThat(completed.getRoundStatus())
                .isEqualTo(WorkflowMultiInstanceRoundStatus.COMPLETED);
        assertThat(runtimeService.createExecutionQuery()
                .processInstanceId(child.getId())
                .signalEventSubscriptionName("childRoundFinishedWaitSignal")
                .singleResult()).isNotNull();

        // 构造领域允许的 RETURNED 审计夹具；成员 JSON 继续使用正式完成轮次原值，不手工拼接。
        assertThat(jdbcTemplate.update("""
                update wf_multi_instance_round
                set round_status='RETURNED', return_source_task_id=?,
                    return_actor_user_id=?, applicant_task_id=?,
                    return_time=current_timestamp(3), complete_time=null
                where round_id=? and round_status='COMPLETED'
                """, "boundary-return-source", "201", "applicant-task",
                completed.getRoundId())).isOne();
        WfMultiInstanceRound returned = roundMapper
                .selectByRootExecutionId(completed.getRootExecutionId());
        returned.requireValidLifecycle();

        signal(root.getId(), "interruptCallSignal");

        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(child.getId()).singleResult()).isNull();
        WfMultiInstanceRound terminated = roundMapper
                .selectByRootExecutionId(completed.getRootExecutionId());
        assertTerminatedRound(terminated, returned);
        assertThat(terminated.getReturnSourceTaskId())
                .isEqualTo(returned.getReturnSourceTaskId());
        assertThat(terminated.getReturnActorUserId())
                .isEqualTo(returned.getReturnActorUserId());
        assertThat(terminated.getApplicantTaskId())
                .isEqualTo(returned.getApplicantTaskId());
        assertThat(terminated.getReturnTime()).isEqualTo(returned.getReturnTime());
    }

    /**
     * 验证非中断边界只创建旁路 token，不得关闭或推进仍在执行的多实例轮次。
     *
     * @return void，原根、成员任务、revision、状态或生命周期时间发生变化时失败
     */
    @Test
    void keepsRoundActiveWhenNonInterruptingBoundaryCreatesSidePath()
    {
        ProcessInstance instance = start("roundNonInterruptBoundary");
        WfMultiInstanceRound before = activeRound(instance.getId(),
                "nonInterruptReview");

        signal(instance.getId(), "nonInterruptSignal");

        WfMultiInstanceRound after = activeRound(instance.getId(),
                "nonInterruptReview");
        assertThat(after.getRoundId()).isEqualTo(before.getRoundId());
        assertThat(after.getRootExecutionId()).isEqualTo(before.getRootExecutionId());
        assertThat(after.getMembersJson()).isEqualTo(before.getMembersJson());
        assertThat(after.getRevisionNo()).isEqualTo(before.getRevisionNo());
        assertThat(after.getRoundStatus())
                .isEqualTo(WorkflowMultiInstanceRoundStatus.ACTIVE);
        assertThat(after.getCreateTime()).isEqualTo(before.getCreateTime());
        assertThat(after.getCompleteTime()).isNull();
        assertThat(after.getTerminateTime()).isNull();
        assertThat(runtimeService.createExecutionQuery()
                .executionId(before.getRootExecutionId()).singleResult()).isNotNull();
        assertThat(tasks(instance.getId(), "nonInterruptReview")).hasSize(2);
        Task sideTask = taskService.createTaskQuery().processInstanceId(instance.getId())
                .taskDefinitionKey("nonInterruptSideTask").active().singleResult();
        assertThat(sideTask).isNotNull();
        assertThat(sideTask.getAssignee()).isEqualTo("999");
    }

    /**
     * 验证全局中断监听器不会把 ANY 正常满足条件时删除的剩余成员误判成根异常退出。
     *
     * @return void，正常或签轮次被改成 TERMINATED 或流程未自然完成时失败
     */
    @Test
    void keepsAnyCompletionOnNormalSiblingCancellation()
    {
        ProcessInstance instance = start("roundDynamicAny", "anyReview",
                List.of("201", "202", "203"), Map.of());

        complete(task(instance.getId(), "anyReview", "201"), 0);

        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(instance.getId()).singleResult()).isNull();
        List<WfMultiInstanceRound> persisted = rounds(instance.getId());
        assertThat(persisted).singleElement().satisfies(round ->
        {
            assertThat(round.getRoundStatus())
                    .isEqualTo(WorkflowMultiInstanceRoundStatus.COMPLETED);
            assertThat(round.getRevisionNo()).isEqualTo(1);
            assertThat(round.getCompleteTime()).isNotNull();
            assertThat(round.getTerminateTime()).isNull();
            round.requireValidLifecycle();
        });
    }

    /**
     * 验证中断后的流程再次进入同一活动时保留已关闭首轮并创建不同根的 ACTIVE 第二轮。
     *
     * @return void，开放唯一约束阻断重入或 round_no、根、成员快照发生漂移时失败
     */
    @Test
    void createsNextRoundWhenProcessReentersSameActivityAfterInterruption()
    {
        ProcessInstance instance = start("roundInterruptReentry");
        WfMultiInstanceRound firstBefore = activeRound(instance.getId(),
                "reentryReview");
        signal(instance.getId(), "interruptReentrySignal");

        signal(instance.getId(), "resumeReentrySignal");

        List<WfMultiInstanceRound> persisted = rounds(instance.getId());
        assertThat(persisted).hasSize(2);
        WfMultiInstanceRound firstAfter = persisted.get(0);
        WfMultiInstanceRound second = persisted.get(1);
        assertTerminatedRound(firstAfter, firstBefore);
        assertThat(second.getRoundNo()).isEqualTo(2);
        assertThat(second.getRoundStatus())
                .isEqualTo(WorkflowMultiInstanceRoundStatus.ACTIVE);
        assertThat(second.getRootExecutionId())
                .isNotEqualTo(firstAfter.getRootExecutionId());
        assertThat(second.getMembers()).containsExactly("201", "202");
        assertThat(second.getRevisionNo()).isZero();
        assertThat(second.getCreateTime()).isNotNull();
        assertThat(second.getTerminateTime()).isNull();
        assertThat(tasks(instance.getId(), "reentryReview"))
                .extracting(Task::getAssignee).containsExactly("201", "202");
    }

    /**
     * 验证轮次中断 CAS 冲突会使 signal、任务删除、execution 删除和变量变化整体回滚。
     *
     * @return void，未返回 409 或任一引擎、变量、业务轮次事实部分提交时失败
     */
    @Test
    void rollsBackBoundaryInterruptionWhenRoundCasConflicts()
    {
        ProcessInstance instance = start("roundInterruptReentry");
        RuntimeSnapshot before = capture(instance.getId());
        doReturn(0).when(roundMapper).compareAndSetTerminatedStatus(
                anyLong(), anyInt(), any(WorkflowMultiInstanceRoundStatus.class));

        assertThatThrownBy(() -> signal(instance.getId(), "interruptReentrySignal"))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(capture(instance.getId())).isEqualTo(before);
        assertThat(runtimeService.createExecutionQuery()
                .processInstanceId(instance.getId())
                .signalEventSubscriptionName("interruptReentrySignal")
                .singleResult()).isNotNull();
    }

    /**
     * 在当前 Spring 事务内定位唯一 signal 订阅并触发真实 Flowable 边界或中间捕获事件。
     *
     * @param processInstanceId String，订阅所属运行流程实例主键
     * @param signalName String，BPMN signal 的稳定名称
     * @return void，无返回值；订阅缺失或重复时在触发前失败
     */
    private void signal(String processInstanceId, String signalName)
    {
        transactionTemplate.executeWithoutResult(status ->
        {
            Execution subscription = runtimeService.createExecutionQuery()
                    .processInstanceId(processInstanceId)
                    .signalEventSubscriptionName(signalName).singleResult();
            assertThat(subscription).isNotNull();
            runtimeService.signalEventReceived(signalName, subscription.getId());
        });
    }

    /**
     * 对账异常关闭轮次只改变状态和 terminate_time，并完整保留原轮次不可变事实。
     *
     * @param actual WfMultiInstanceRound，中断命令提交后重新查询的正式轮次
     * @param before WfMultiInstanceRound，中断前冻结的 ACTIVE 或 RETURNED 开放轮次
     * @return void，关闭状态、时间或审计快照任一不一致时失败
     */
    private void assertTerminatedRound(WfMultiInstanceRound actual,
            WfMultiInstanceRound before)
    {
        assertThat(actual).isNotNull();
        assertThat(actual.getRoundId()).isEqualTo(before.getRoundId());
        assertThat(actual.getRootExecutionId()).isEqualTo(before.getRootExecutionId());
        assertThat(actual.getRoundNo()).isEqualTo(before.getRoundNo());
        assertThat(actual.getMode()).isEqualTo(before.getMode());
        assertThat(actual.getMembersJson()).isEqualTo(before.getMembersJson());
        assertThat(actual.getRevisionNo()).isEqualTo(before.getRevisionNo());
        assertThat(actual.getReturnSourceTaskId())
                .isEqualTo(before.getReturnSourceTaskId());
        assertThat(actual.getReturnActorUserId())
                .isEqualTo(before.getReturnActorUserId());
        assertThat(actual.getApplicantTaskId()).isEqualTo(before.getApplicantTaskId());
        assertThat(actual.getRoundStatus())
                .isEqualTo(WorkflowMultiInstanceRoundStatus.TERMINATED);
        assertThat(actual.getCreateTime()).isEqualTo(before.getCreateTime());
        assertThat(actual.getReturnTime()).isEqualTo(before.getReturnTime());
        assertThat(actual.getReopenTime()).isEqualTo(before.getReopenTime());
        assertThat(actual.getCompleteTime()).isNull();
        assertThat(actual.getTerminateTime()).isNotNull();
        actual.requireValidLifecycle();
    }
}
