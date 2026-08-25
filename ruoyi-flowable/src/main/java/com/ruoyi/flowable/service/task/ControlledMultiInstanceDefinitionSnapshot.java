package com.ruoyi.flowable.service.task;

import org.springframework.util.StringUtils;

/**
 * 部署 BPMN 中受控多实例节点的不可变固定事实。
 *
 * @param deployId String，部署主键
 * @param processDefinitionId String，流程定义主键
 * @param activityId String，节点主键
 * @param mode WorkflowMultiInstanceMode，部署固定 ALL/ANY 模式
 */
public record ControlledMultiInstanceDefinitionSnapshot(String deployId,
        String processDefinitionId, String activityId, WorkflowMultiInstanceMode mode)
{
    /**
     * 校验定义身份和固定模式。
     *
     * @return 无返回值，非法定义事实拒绝构造
     */
    public ControlledMultiInstanceDefinitionSnapshot
    {
        if (!StringUtils.hasText(deployId)
                || !StringUtils.hasText(processDefinitionId)
                || !StringUtils.hasText(activityId) || mode == null)
        {
            throw new IllegalArgumentException("受控多实例定义快照不完整");
        }
    }
}
