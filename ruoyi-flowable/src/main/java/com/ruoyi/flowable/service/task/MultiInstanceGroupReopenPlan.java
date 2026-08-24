package com.ruoyi.flowable.service.task;

import java.util.HashSet;
import java.util.List;

import com.ruoyi.flowable.domain.WorkflowMultiInstanceRoundStatus;

/**
 * 发起人重提前由 RETURNED 轮次和唯一申请人任务组成的不可变计划。
 *
 * @param round MultiInstanceRoundSnapshot，唯一 RETURNED 旧轮次
 * @param application ReturnedApplicationSnapshot，唯一申请人任务及迁移来源
 * @param controlledPath WorkflowTaskMovementPolicy.ControlledReturnPathPlan，重新流转的安全受控路径
 * @param replaySnapshots List&lt;ControlledMultiInstanceReplaySnapshot&gt;，路径节点最近正式轮次
 */
public record MultiInstanceGroupReopenPlan(MultiInstanceRoundSnapshot round,
        ReturnedApplicationSnapshot application,
        WorkflowTaskMovementPolicy.ControlledReturnPathPlan controlledPath,
        List<ControlledMultiInstanceReplaySnapshot> replaySnapshots)
{
    /**
     * 校验旧轮和申请人任务严格属于同一流程及正式关联。
     *
     * @return 无返回值，不一致计划拒绝构造
     */
    public MultiInstanceGroupReopenPlan
    {
        replaySnapshots = replaySnapshots == null
                ? List.of() : List.copyOf(replaySnapshots);
        if (round == null || application == null || controlledPath == null
                || round.status() != WorkflowMultiInstanceRoundStatus.RETURNED
                || !round.processInstanceId().equals(application.processInstanceId())
                || !round.processDefinitionId().equals(
                        application.processDefinitionId())
                || round.applicantTaskId() == null
                || !round.applicantTaskId().equals(application.taskId())
                || new HashSet<>(controlledPath.controlledActivityIds()).size()
                        != replaySnapshots.size()
                || !new HashSet<>(controlledPath.controlledActivityIds()).equals(
                        replaySnapshots.stream().map(snapshot ->
                                snapshot.definition().activityId())
                                .collect(java.util.stream.Collectors.toSet()))
                || replaySnapshots.stream().noneMatch(snapshot ->
                        round.activityId().equals(snapshot.definition().activityId())))
        {
            throw new IllegalArgumentException("多实例重提计划不一致");
        }
    }

    /**
     * 读取申请人任务所在首审批节点的受控快照；普通首审批任务返回 null。
     *
     * @return ControlledMultiInstanceReplaySnapshot，首节点受控快照或 null
     */
    public ControlledMultiInstanceReplaySnapshot targetReplay()
    {
        return replaySnapshots.stream().filter(snapshot ->
                application.activityId().equals(snapshot.definition().activityId()))
                .findFirst().orElse(null);
    }
}
