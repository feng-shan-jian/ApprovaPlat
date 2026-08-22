package com.ruoyi.flowable.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 取消运行中流程实例的专用请求。
 *
 * @param processInstanceId String，待取消的 Flowable 流程实例主键
 * @param comment String，取消原因
 */
public record WorkflowProcessCancelRequest(
        @NotBlank(message = "流程实例主键不能为空")
        @Size(max = 64, message = "流程实例主键长度不能超过64个字符")
        String processInstanceId,
        @NotBlank(message = "取消原因不能为空")
        @Size(max = 500, message = "取消原因长度不能超过500个字符")
        String comment)
{
}
