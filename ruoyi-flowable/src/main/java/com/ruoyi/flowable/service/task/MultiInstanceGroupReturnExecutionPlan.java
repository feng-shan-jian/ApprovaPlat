package com.ruoyi.flowable.service.task;

import java.util.HashSet;
import java.util.List;
import org.springframework.util.StringUtils;

/**
 * 整组退回执行前冻结的来源轮次、首审批目标和完整受控重放路径。
 *
 * @param source MultiInstanceGroupReturnPlan，当前活动组和 ACTIVE 来源轮次
 * @param targetActivityId String，服务端历史确定的首审批节点 key
 * @param controlledPath WorkflowTaskMovementPolicy.ControlledReturnPathPlan，安全路径计划
 * @param replaySnapshots List&lt;ControlledMultiInstanceReplaySnapshot&gt;，路径节点正式快照
 */
public record MultiInstanceGroupReturnExecutionPlan(
        MultiInstanceGroupReturnPlan source, String targetActivityId,
        WorkflowTaskMovementPolicy.ControlledReturnPathPlan controlledPath,
        List<ControlledMultiInstanceReplaySnapshot> replaySnapshots)
{
    /**
     * 冻结执行计划并确保路径节点与正式轮次快照一一对应。
     *
     * @return 无返回值，构造时拒绝目标、路径或来源轮次缺失
     */
    public MultiInstanceGroupReturnExecutionPlan
    {
        replaySnapshots = replaySnapshots == null
                ? List.of() : List.copyOf(replaySnapshots);
        if (source == null || !StringUtils.hasText(targetActivityId)
                || controlledPath == null
                || new HashSet<>(controlledPath.controlledActivityIds()).size()
                        != replaySnapshots.size()
                || !new HashSet<>(controlledPath.controlledActivityIds()).equals(
                        replaySnapshots.stream().map(snapshot ->
                                snapshot.definition().activityId())
                                .collect(java.util.stream.Collectors.toSet()))
                || replaySnapshots.stream().noneMatch(snapshot ->
                        source.round().activityId().equals(
                                snapshot.definition().activityId())))
        {
            throw new IllegalArgumentException("多实例整组退回执行计划不一致");
        }
    }

    /**
     * 读取首审批节点的受控快照；普通首审批节点返回 null。
     *
     * @return ControlledMultiInstanceReplaySnapshot，首审批受控快照或 null
     */
    public ControlledMultiInstanceReplaySnapshot targetReplay()
    {
        return replaySnapshots.stream().filter(snapshot -> targetActivityId.equals(
                snapshot.definition().activityId())).findFirst().orElse(null);
    }
}
