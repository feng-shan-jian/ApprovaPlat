package com.ruoyi.flowable.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * BPMN 错误或升级编码新增与修改请求。
 *
 * @param eventType String，ERROR 或 ESCALATION
 * @param eventCode String，稳定业务编码；修改时仍必须与原编码一致
 * @param eventName String，用户可见名称
 * @param notificationPolicy String，NONE 或 INITIATOR
 * @param description String，可选业务说明
 */
public record WorkflowBpmnEventCodeRequest(
        @NotBlank(message = "事件类型不能为空")
        @Pattern(regexp = "ERROR|ESCALATION", message = "事件类型不受支持") String eventType,
        @NotBlank(message = "事件编码不能为空")
        @Pattern(regexp = "[A-Z][A-Z0-9_.-]{1,63}", message = "事件编码格式不合法") String eventCode,
        @NotBlank(message = "事件名称不能为空")
        @Size(max = 128, message = "事件名称不能超过 128 个字符") String eventName,
        @NotBlank(message = "通知策略不能为空")
        @Pattern(regexp = "NONE|INITIATOR", message = "通知策略不受支持") String notificationPolicy,
        @Size(max = 500, message = "业务说明不能超过 500 个字符") String description)
{
}
