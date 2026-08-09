package com.ruoyi.flowable.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 人工催办请求。
 * @param processInstanceId 运行中根流程实例主键
 * @param reason 发起人或管理员填写的催办原因
 */
public record WorkflowManualUrgeRequest(
        @NotBlank @Size(max = 64) String processInstanceId,
        @NotBlank @Size(max = 500) String reason)
{
}
