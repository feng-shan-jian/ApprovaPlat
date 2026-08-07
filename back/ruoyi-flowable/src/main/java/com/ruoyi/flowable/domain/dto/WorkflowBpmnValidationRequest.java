package com.ruoyi.flowable.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * BPMN 服务端编译校验请求。
 *
 * @param bpmnXml String，待按保存安全门禁和部署兼容性规则校验的 BPMN 2.0 XML
 */
public record WorkflowBpmnValidationRequest(
        @NotBlank(message = "BPMN XML 不能为空")
        @Size(max = 2097152, message = "BPMN XML 超过大小限制")
        String bpmnXml)
{
}
