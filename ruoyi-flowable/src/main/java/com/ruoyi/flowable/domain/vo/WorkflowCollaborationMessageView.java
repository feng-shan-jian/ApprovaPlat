package com.ruoyi.flowable.domain.vo;

import java.util.Date;

/**
 * 协作消息脱敏管理视图，不返回变量正文。
 * @param retryAllowed boolean，当前入站状态是否允许管理员补偿
 */
public record WorkflowCollaborationMessageView(String messageId, String messageName,
        String sourceProcessDefinitionKey, String targetProcessDefinitionKey,
        String correlationKey, String targetProcessInstanceId, String matchedProcessInstanceId,
        String targetExecutionId,
        Long sequenceNo, String status, boolean retryAllowed,
        Integer attemptCount, Integer maxAttempts,
        Integer compensationCount, String lastErrorCode,
        String lastErrorSummary, Date createTime, Date nextAttemptTime, Date completeTime)
{
}
