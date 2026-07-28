package com.ruoyi.flowable.authorization;

import java.time.Instant;

/**
 * 流程实例对象授权通过后的不可变快照。
 *
 * @param processInstanceId String，Flowable 流程实例 ID
 * @param processDefinitionId String，流程定义 ID
 * @param deploymentId String，部署 ID
 * @param businessKey String，可为空的业务主键
 * @param startUserId String，可为空的流程发起人 ID
 * @param startTime Instant，流程开始时间
 * @param endTime Instant，可为空的流程结束时间
 * @param deleteReason String，可为空的流程删除或终止原因
 * @param businessStatus String，可为空的服务端业务状态
 * @param state String，可为空的 Flowable 实例状态；运行中实例使用实时 running/suspended，已结束实例使用历史状态
 */
public record WorkflowProcessAccessSnapshot(
        String processInstanceId,
        String processDefinitionId,
        String deploymentId,
        String businessKey,
        String startUserId,
        Instant startTime,
        Instant endTime,
        String deleteReason,
        String businessStatus,
        String state)
{
}
