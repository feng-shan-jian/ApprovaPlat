package com.ruoyi.flowable.domain.vo;

import java.time.Instant;

/**
 * 当前办理人的活动待办任务视图。
 *
 * @param taskId String，任务主键
 * @param taskName String，任务名称
 * @param taskDefinitionKey String，任务节点标识
 * @param assigneeId String，当前办理人主键
 * @param ownerId String，任务所有人主键，允许为空
 * @param definitionId String，流程定义主键
 * @param processKey String，流程定义标识
 * @param processName String，流程定义名称
 * @param version int，流程定义版本
 * @param category String，流程分类编码
 * @param deploymentId String，部署主键
 * @param processInstanceId String，流程实例主键
 * @param businessKey String，业务主键，允许为空
 * @param startUserId String，流程发起人主键
 * @param startUserName String，流程发起人显示名称
 * @param createTime Instant，任务创建时间
 * @param dueTime Instant，任务到期时间，允许为空
 * @param claimedById String，真实认领用户主键，直接指派或转办任务允许为空
 * @param claimTime Instant，真实认领时间，直接指派或转办任务允许为空
 */
public record WorkflowAssignedTaskView(
        String taskId,
        String taskName,
        String taskDefinitionKey,
        String assigneeId,
        String ownerId,
        String definitionId,
        String processKey,
        String processName,
        int version,
        String category,
        String deploymentId,
        String processInstanceId,
        String businessKey,
        String startUserId,
        String startUserName,
        Instant createTime,
        Instant dueTime,
        String claimedById,
        Instant claimTime)
{
}
