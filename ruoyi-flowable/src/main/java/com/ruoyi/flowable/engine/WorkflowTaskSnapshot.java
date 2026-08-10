package com.ruoyi.flowable.engine;

import java.time.Instant;

/**
 * 用户任务的只读业务快照，避免业务层持有或修改 Flowable 运行时任务对象。
 *
 * @param id String，任务 ID
 * @param name String，可为空的任务名称
 * @param processInstanceId String，可为空的流程实例 ID
 * @param taskDefinitionKey String，可为空的 BPMN 任务定义键
 * @param assignee String，可为空的当前办理人 ID
 * @param claimedBy String，可为空的实际认领人 ID
 * @param claimTime Instant，可为空的认领时间
 * @param owner String，可为空的任务所有者 ID
 * @param delegationState String，可为空的委派状态名称
 * @param suspended boolean，任务是否已挂起
 */
public record WorkflowTaskSnapshot(String id, String name, String processInstanceId,
        String taskDefinitionKey, String assignee, String claimedBy, Instant claimTime,
        String owner, String delegationState, boolean suspended)
{
}
