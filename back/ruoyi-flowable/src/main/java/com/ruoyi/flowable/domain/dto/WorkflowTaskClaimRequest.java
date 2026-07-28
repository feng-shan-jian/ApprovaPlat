package com.ruoyi.flowable.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 认领任务请求，仅允许客户端提交任务主键。
 *
 * @param taskId String，待认领的 Flowable 活动任务主键
 */
public record WorkflowTaskClaimRequest(
        @NotBlank(message = "任务主键不能为空")
        @Size(max = 64, message = "任务主键长度不能超过64个字符")
        String taskId)
{
}
