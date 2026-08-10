package com.ruoyi.flowable.domain.vo;

import java.util.Date;

/** 协作消息脱敏管理视图，不返回变量正文。 */
public record WorkflowCollaborationMessageView(String messageId, String messageName,
        String sourceProcessDefinitionKey, String targetProcessDefinitionKey,
        String correlationKey, String targetProcessInstanceId, String matchedProcessInstanceId,
        String targetExecutionId,
        Long sequenceNo, String status, Integer attemptCount, Integer maxAttempts,
        Integer compensationCount, String lastErrorCode,
        String lastErrorSummary, Date createTime, Date nextAttemptTime, Date completeTime)
{
}
