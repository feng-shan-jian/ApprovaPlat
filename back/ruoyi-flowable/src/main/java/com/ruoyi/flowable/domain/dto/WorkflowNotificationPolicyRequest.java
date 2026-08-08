package com.ruoyi.flowable.domain.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 通知策略新增或乐观锁更新请求。
 * @param policyId 策略主键，新增时为空
 * @param scopeType DEFAULT、PROCESS 或 NODE
 * @param processDefinitionKey 流程定义 key
 * @param taskDefinitionKey 节点 key
 * @param eventType 生命周期事件
 * @param recipientRules 固定接收人规则 CSV
 * @param channels INBOX、EMAIL 或 INBOX,EMAIL
 * @param titleTemplate 标题模板
 * @param contentTemplate 正文模板
 * @param maxAttempts 最大投递次数
 * @param status ENABLED 或 DISABLED
 * @param expectedRevision 更新时客户端读取的版本
 */
public record WorkflowNotificationPolicyRequest(
        Long policyId,
        @NotBlank @Size(max = 16) String scopeType,
        @Size(max = 255) String processDefinitionKey,
        @Size(max = 255) String taskDefinitionKey,
        @NotBlank @Size(max = 40) String eventType,
        @NotBlank @Size(max = 128) String recipientRules,
        @NotBlank @Size(max = 32) String channels,
        @NotBlank @Size(max = 160) String titleTemplate,
        @NotBlank @Size(max = 700) String contentTemplate,
        @NotNull @Min(1) @Max(20) Integer maxAttempts,
        @NotBlank @Size(max = 16) String status,
        Integer expectedRevision)
{
}
