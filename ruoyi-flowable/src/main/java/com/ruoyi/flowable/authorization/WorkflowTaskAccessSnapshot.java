package com.ruoyi.flowable.authorization;

import java.time.Instant;
import org.flowable.task.api.Task;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 任务对象授权结果；稳定输出字段与仅供本次读取使用的运行对象。
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
 * @param unclaimAllowed boolean，当前服务端身份能否取消该任务的真实认领
 * @param runtimeTask Task，同次读取的运行对象；仅内部投影使用，历史对象为空且不进入 JSON
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
        Instant claimTime,
        boolean unclaimAllowed,
        @JsonIgnore Task runtimeTask)
{
}
