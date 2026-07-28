package com.ruoyi.flowable.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 查询当前任务可退节点的专用请求。
 *
 * @param taskId String，当前用户正在办理的活动任务主键
 */
public record WorkflowTaskReturnListRequest(
        @NotBlank(message = "任务主键不能为空")
        @Size(max = 64, message = "任务主键长度不能超过64个字符")
        String taskId)
{
}
