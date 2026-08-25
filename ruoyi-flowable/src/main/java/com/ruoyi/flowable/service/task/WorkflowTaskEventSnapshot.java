package com.ruoyi.flowable.service.task;

import org.springframework.util.StringUtils;

/**
 * 用户任务监听事件跨服务传递的不可变最小事实。
 *
 * @param taskId String，任务主键
 * @param processInstanceId String，流程实例主键
 * @param processDefinitionId String，流程定义主键
 * @param activityId String，任务节点主键
 * @param executionId String，任务 child execution 主键
 * @param assignee String，事件发生时真实办理人
 * @param completionRevision Integer，complete 事件的任务局部预留 revision；create 时为空
 */
public record WorkflowTaskEventSnapshot(String taskId, String processInstanceId,
        String processDefinitionId, String activityId, String executionId,
        String assignee, Integer completionRevision)
{
    /**
     * 校验监听事件包含定位部署、实例、节点和 execution 所需的稳定字段。
     *
     * @return 无返回值，非法事件拒绝构造
     */
    public WorkflowTaskEventSnapshot
    {
        if (!StringUtils.hasText(taskId) || !StringUtils.hasText(processInstanceId)
                || !StringUtils.hasText(processDefinitionId)
                || !StringUtils.hasText(activityId)
                || !StringUtils.hasText(executionId)
                || !StringUtils.hasText(assignee)
                || (completionRevision != null && completionRevision < 0))
        {
            throw new IllegalArgumentException("用户任务监听事件快照不完整");
        }
    }
}
