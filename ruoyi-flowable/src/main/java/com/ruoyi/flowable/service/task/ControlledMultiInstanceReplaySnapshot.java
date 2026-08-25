package com.ruoyi.flowable.service.task;

/**
 * 重新流转路径中单个受控多实例节点的部署事实、最近正式轮次和流程变量对账快照。
 *
 * @param definition ControlledMultiInstanceDefinitionSnapshot，部署固定节点和 ALL/ANY 模式
 * @param round MultiInstanceRoundSnapshot，该节点最近一次正式轮次审计事实
 */
public record ControlledMultiInstanceReplaySnapshot(
        ControlledMultiInstanceDefinitionSnapshot definition,
        MultiInstanceRoundSnapshot round)
{
    /**
     * 校验部署、定义、实例节点和模式在两个正式来源中完全一致。
     *
     * @return 无返回值，构造时拒绝损坏的重放快照
     */
    public ControlledMultiInstanceReplaySnapshot
    {
        if (definition == null || round == null
                || !definition.deployId().equals(round.deployId())
                || !definition.processDefinitionId().equals(
                        round.processDefinitionId())
                || !definition.activityId().equals(round.activityId())
                || definition.mode() != round.mode())
        {
            throw new IllegalArgumentException("受控多实例重放快照不一致");
        }
    }
}
