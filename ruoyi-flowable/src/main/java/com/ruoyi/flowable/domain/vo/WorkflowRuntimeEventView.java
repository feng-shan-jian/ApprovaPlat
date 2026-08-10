package com.ruoyi.flowable.domain.vo;

import java.util.Date;

/**
 * 运行事件处理结果和审计视图，不包含变量正文或 Token。
 */
public record WorkflowRuntimeEventView(String requestId, Long credentialId,
        String eventType, String eventName, String correlationType,
        String correlationValue, String matchedProcessInstanceId,
        String matchedExecutionId, String status, String resultCode,
        String resultSummary, Date createTime, Date completeTime)
{
}
