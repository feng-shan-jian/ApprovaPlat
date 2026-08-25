package com.ruoyi.flowable.service.task;

import org.springframework.util.StringUtils;

/**
 * 已删除流程实例 PROCESS_CANCELLED 监听事件的不可变事实。
 *
 * @param processInstanceId String，流程实例主键
 * @param processDefinitionId String，流程定义主键
 * @param executionId String，事件 execution 主键
 * @param entityId String，CommandContext 流程实例实体主键
 * @param entityProcessDefinitionId String，实体流程定义主键
 * @param processInstanceType boolean，实体是否为流程实例
 * @param deleted boolean，实体是否已标记删除
 */
public record ProcessInstanceCancellationEventSnapshot(String processInstanceId,
        String processDefinitionId, String executionId, String entityId,
        String entityProcessDefinitionId, boolean processInstanceType,
        boolean deleted)
{
    /**
     * 校验流程取消事件包含完整引擎身份。
     *
     * @return 无返回值，缺失事实拒绝构造
     */
    public ProcessInstanceCancellationEventSnapshot
    {
        if (!StringUtils.hasText(processInstanceId)
                || !StringUtils.hasText(processDefinitionId)
                || !StringUtils.hasText(executionId)
                || !StringUtils.hasText(entityId)
                || !StringUtils.hasText(entityProcessDefinitionId))
        {
            throw new IllegalArgumentException("流程实例取消事件不完整");
        }
    }
}
