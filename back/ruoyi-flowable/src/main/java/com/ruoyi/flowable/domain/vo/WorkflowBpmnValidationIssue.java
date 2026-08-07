package com.ruoyi.flowable.domain.vo;

/**
 * 单条 BPMN 编译诊断。
 *
 * @param code String，供客户端稳定分流的诊断编码
 * @param severity String，ERROR 或 WARNING
 * @param elementId String，能够定位时返回 BPMN 元素标识，否则为空
 * @param message String，不包含 XML 正文和内部异常的用户可读提示
 */
public record WorkflowBpmnValidationIssue(String code, String severity,
        String elementId, String message)
{
}
