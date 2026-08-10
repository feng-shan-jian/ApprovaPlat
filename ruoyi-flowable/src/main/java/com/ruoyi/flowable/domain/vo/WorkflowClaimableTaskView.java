package com.ruoyi.flowable.domain.vo;

import java.time.Instant;

/**
 * 当前用户或其有效角色、部门候选组可认领的未分配任务视图。
 *
 * @param taskId String，任务主键
 * @param taskName String，任务名称
 * @param taskDefinitionKey String，任务节点标识
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
 */
public record WorkflowClaimableTaskView(
        String taskId,
        String taskName,
        String taskDefinitionKey,
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
        Instant dueTime)
{
}
