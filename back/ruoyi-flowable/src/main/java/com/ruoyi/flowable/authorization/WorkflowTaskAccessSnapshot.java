package com.ruoyi.flowable.authorization;

import java.time.Instant;

/**
 * 任务对象授权通过后的不可变快照。
 *
 * @param taskId String，Flowable 任务 ID
 * @param processInstanceId String，所属流程实例 ID
 * @param processDefinitionId String，所属流程定义 ID
 * @param taskDefinitionKey String，BPMN 用户任务节点 key
 * @param taskName String，可为空的任务名称
 * @param assignee String，可为空的当前办理人 ID
 * @param owner String，可为空的任务 owner ID
 * @param delegationState String，可为空的委派状态
 * @param active boolean，任务是否仍为运行时活动任务
 * @param createTime Instant，可为空的任务创建时间
 * @param endTime Instant，可为空的任务结束时间
 * @param claimedBy String，可为空的真实认领用户 ID
 * @param claimTime Instant，可为空的真实认领时间
 */
public record WorkflowTaskAccessSnapshot(
        String taskId,
        String processInstanceId,
        String processDefinitionId,
        String taskDefinitionKey,
        String taskName,
        String assignee,
        String owner,
        String delegationState,
        boolean active,
        Instant createTime,
        Instant endTime,
        String claimedBy,
        Instant claimTime)
{
    /**
     * 保留不需要认领元数据的内部调用兼容构造器。
     *
     * @param taskId String，Flowable 任务 ID
     * @param processInstanceId String，所属流程实例 ID
     * @param processDefinitionId String，所属流程定义 ID
     * @param taskDefinitionKey String，BPMN 用户任务节点 key
     * @param taskName String，可为空的任务名称
     * @param assignee String，可为空的当前办理人 ID
     * @param owner String，可为空的任务 owner ID
     * @param delegationState String，可为空的委派状态
     * @param active boolean，任务是否仍为运行时活动任务
     * @param createTime Instant，可为空的任务创建时间
     * @param endTime Instant，可为空的任务结束时间
     * @return 无返回值，认领字段按未知处理
     */
    public WorkflowTaskAccessSnapshot(String taskId, String processInstanceId,
            String processDefinitionId, String taskDefinitionKey, String taskName,
            String assignee, String owner, String delegationState, boolean active,
            Instant createTime, Instant endTime)
    {
        this(taskId, processInstanceId, processDefinitionId, taskDefinitionKey,
                taskName, assignee, owner, delegationState, active, createTime,
                endTime, null, null);
    }
}
