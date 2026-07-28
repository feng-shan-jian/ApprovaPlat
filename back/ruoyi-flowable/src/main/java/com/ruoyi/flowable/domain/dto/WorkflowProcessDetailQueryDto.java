package com.ruoyi.flowable.domain.dto;

/**
 * 流程实例详情的对象关系查询参数。
 *
 * @param processInstanceId String，流程实例主键
 * @param taskId String，可选的活动或历史任务主键
 */
public record WorkflowProcessDetailQueryDto(String processInstanceId, String taskId)
{
}
