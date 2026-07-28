package com.ruoyi.flowable.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 流程实例取消或管理员终止请求。
 *
 * @param instanceId String，待结束的 Flowable 运行实例主键
 * @param reason String，写入历史删除原因和结构化审计意见的业务原因
 */
public record WorkflowInstanceTerminateRequest(
        @NotBlank(message = "流程实例主键不能为空")
        @Size(max = 64, message = "流程实例主键长度不能超过64个字符")
        String instanceId,
        @NotBlank(message = "流程终止原因不能为空")
        @Size(max = 500, message = "流程终止原因长度不能超过500个字符")
        String reason)
{
}
