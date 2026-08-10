package com.ruoyi.flowable.domain.dto;

import jakarta.validation.constraints.NotNull;

/**
 * BPMN 事件编码启停请求。
 *
 * @param enabled Boolean，true 启用，false 停用
 */
public record WorkflowBpmnEventCodeStatusRequest(
        @NotNull(message = "启停状态不能为空") Boolean enabled)
{
}
