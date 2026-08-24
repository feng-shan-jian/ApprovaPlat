package com.ruoyi.flowable.service.task;

import org.springframework.util.StringUtils;

/**
 * Flowable 多实例根取消监听器传入终止服务的不可变事件事实。
 *
 * @param processInstanceId String，流程实例主键
 * @param processDefinitionId String，流程定义主键
 * @param activityId String，被取消节点
 * @param activityType String，Flowable 活动类型
 * @param executionId String，事件 execution 主键
 * @param rootExecutionId String，CommandContext 识别的根主键
 * @param rootActivityId String，根实体活动主键
 * @param rootProcessInstanceId String，根实体流程实例主键
 * @param rootProcessDefinitionId String，根实体流程定义主键
 * @param multiInstanceRoot boolean，实体是否为多实例根
 * @param suspended boolean，根是否挂起
 * @param authenticatedUserId String，可为空的 Flowable 当前认证用户
 */
public record MultiInstanceRootCancellationEvent(String processInstanceId,
        String processDefinitionId, String activityId, String activityType,
        String executionId, String rootExecutionId, String rootActivityId,
        String rootProcessInstanceId, String rootProcessDefinitionId,
        boolean multiInstanceRoot, boolean suspended, String authenticatedUserId)
{
    /**
     * 校验根取消事件具备完整引擎身份。
     *
     * @return 无返回值，缺失事实拒绝构造
     */
    public MultiInstanceRootCancellationEvent
    {
        if (!StringUtils.hasText(processInstanceId)
                || !StringUtils.hasText(processDefinitionId)
                || !StringUtils.hasText(activityId)
                || !StringUtils.hasText(activityType)
                || !StringUtils.hasText(executionId)
                || !StringUtils.hasText(rootExecutionId)
                || !StringUtils.hasText(rootActivityId)
                || !StringUtils.hasText(rootProcessInstanceId)
                || !StringUtils.hasText(rootProcessDefinitionId))
        {
            throw new IllegalArgumentException("多实例根取消事件不完整");
        }
    }
}
