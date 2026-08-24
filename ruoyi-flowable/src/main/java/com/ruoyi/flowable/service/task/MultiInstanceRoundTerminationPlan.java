package com.ruoyi.flowable.service.task;

import java.util.Map;
import java.util.Set;

/**
 * Flowable 根删除前冻结的完整开放轮次终止计划。
 *
 * @param processInstanceIds Set&lt;String&gt;，根及活动 CallActivity 子实例主键
 * @param roundsById Map&lt;Long,MultiInstanceRoundSnapshot&gt;，按主键冻结的 ACTIVE/RETURNED 事实
 */
public record MultiInstanceRoundTerminationPlan(Set<String> processInstanceIds,
        Map<Long, MultiInstanceRoundSnapshot> roundsById)
{
    /**
     * 复制终止集合并校验每条轮次都属于本次流程树且主键索引一致。
     *
     * @return 无返回值，非法计划拒绝构造
     */
    public MultiInstanceRoundTerminationPlan
    {
        if (processInstanceIds == null || processInstanceIds.isEmpty()
                || roundsById == null)
        {
            throw new IllegalArgumentException("多实例轮次终止计划不完整");
        }
        Set<String> immutableProcessInstanceIds = Set.copyOf(processInstanceIds);
        Map<Long, MultiInstanceRoundSnapshot> immutableRoundsById = Map.copyOf(
                roundsById);
        if (immutableRoundsById.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getValue() == null
                || entry.getKey().longValue() != entry.getValue().roundId()
                || !immutableProcessInstanceIds.contains(
                        entry.getValue().processInstanceId())
                || !entry.getValue().status().isOpen()))
        {
            throw new IllegalArgumentException("多实例轮次终止计划不一致");
        }
        processInstanceIds = immutableProcessInstanceIds;
        roundsById = immutableRoundsById;
    }
}
