package com.ruoyi.flowable.domain.vo;

import java.time.Instant;

/**
 * 当前用户正式抄送记录的不可变视图。
 *
 * @param copyId Long，抄送记录主键
 * @param title String，抄送标题
 * @param processId String，流程定义主键
 * @param processName String，流程名称
 * @param categoryId String，流程分类编码
 * @param deploymentId String，部署主键
 * @param instanceId String，流程实例主键
 * @param taskId String，产生抄送的任务主键
 * @param userId Long，当前抄送接收用户主键
 * @param originatorId Long，抄送发起用户主键
 * @param originatorName String，抄送发起用户名称快照
 * @param createTime Instant，抄送时间
 */
public record WorkflowCopyView(
        Long copyId,
        String title,
        String processId,
        String processName,
        String categoryId,
        String deploymentId,
        String instanceId,
        String taskId,
        Long userId,
        Long originatorId,
        String originatorName,
        Instant createTime)
{
}
