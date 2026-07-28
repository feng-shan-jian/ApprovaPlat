package com.ruoyi.flowable.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.ruoyi.flowable.domain.WorkflowInstanceState;

/**
 * 流程实例激活或挂起请求。
 *
 * @param instanceId String，待切换状态的 Flowable 运行实例主键
 * @param state WorkflowInstanceState，目标状态，仅允许 active 或 suspended
 */
public record WorkflowInstanceStateRequest(
        @NotBlank(message = "流程实例主键不能为空")
        @Size(max = 64, message = "流程实例主键长度不能超过64个字符")
        String instanceId,
        @NotNull(message = "流程实例目标状态不能为空")
        WorkflowInstanceState state)
{
}
