package com.ruoyi.flowable.domain.vo;

/**
 * 流程实例取消或管理员终止结果。
 *
 * @param instanceId String，已结束的 Flowable 流程实例主键
 * @param processStatus String，持久化到历史变量的 canceled 或 terminated
 * @param actorUserId String，Flowable 审计记录中的操作人用户主键
 * @param wasSuspended boolean，动作前实例是否处于挂起状态
 */
public record WorkflowInstanceTerminateView(
        String instanceId,
        String processStatus,
        String actorUserId,
        boolean wasSuspended)
{
}
