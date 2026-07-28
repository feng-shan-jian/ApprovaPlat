package com.ruoyi.flowable.domain.vo;

import java.time.Instant;

/**
 * 当前用户真实完成的历史任务视图。
 *
 * @param taskId String，历史任务主键
 * @param taskName String，任务名称
 * @param taskDefinitionKey String，任务节点标识
 * @param assigneeId String，任务结束时的办理人主键
 * @param completedBy String，Flowable 记录的真实完成人主键
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
 * @param finishTime Instant，任务完成时间
 * @param durationMillis Long，任务耗时毫秒
 * @param revocable boolean，当前用户和实时执行树快照是否允许发起撤回
 */
public record WorkflowCompletedTaskView(
        String taskId,
        String taskName,
        String taskDefinitionKey,
        String assigneeId,
        String completedBy,
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
        Instant finishTime,
        Long durationMillis,
        boolean revocable)
{
}
