package com.ruoyi.flowable.service.task;

import org.springframework.util.StringUtils;

/**
 * 受控多实例活动成员任务的不可变最小事实。
 *
 * @param taskId String，Flowable 任务主键
 * @param executionId String，任务 child execution 主键
 * @param assignee String，真实办理人主键
 * @param owner String，可为空的委派 owner
 * @param delegated boolean，任务是否存在委派状态
 */
public record MultiInstanceActiveTaskSnapshot(String taskId, String executionId,
        String assignee, String owner, boolean delegated)
{
    /**
     * 校验跨服务传递的任务事实包含稳定主键和办理人。
     *
     * @param taskId String，任务主键
     * @param executionId String，child execution 主键
     * @param assignee String，办理人主键
     * @param owner String，可为空的委派 owner
     * @param delegated boolean，是否处于委派状态
     * @return 无返回值，非法事实拒绝构造
     */
    public MultiInstanceActiveTaskSnapshot
    {
        if (!StringUtils.hasText(taskId) || !StringUtils.hasText(executionId)
                || !StringUtils.hasText(assignee))
        {
            throw new IllegalArgumentException("多实例活动任务快照不合法");
        }
    }
}
