package com.ruoyi.flowable.domain.vo;

import java.util.Date;

/** 不含变量、端点密钥引用和响应正文的协作 outbox 管理视图。 */
public record WorkflowCollaborationOutboxView(String messageId, String messageName,
        String sourceProcessDefinitionKey, String sourceProcessInstanceId,
        String targetProcessDefinitionKey, String correlationKey, Long sequenceNo,
        String status, Integer attemptCount, Integer maxAttempts, Integer compensationCount,
        Integer lastHttpStatus, String lastErrorCode, String lastErrorSummary,
        Date createTime, Date nextAttemptTime, Date completeTime)
{
}
