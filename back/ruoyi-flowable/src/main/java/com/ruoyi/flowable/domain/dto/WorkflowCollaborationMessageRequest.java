package com.ruoyi.flowable.domain.dto;

import java.util.Map;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;

/**
 * 跨 Participant 的协作消息请求。
 * @param messageId String，调用方生成的小写 UUID 幂等键
 * @param messageName String，BPMN MessageFlow 消息名
 * @param sourceProcessDefinitionKey String，发送方流程定义 key，可为空
 * @param targetProcessDefinitionKey String，接收方流程定义 key
 * @param correlationKey String，接收方业务关联键；与 targetProcessInstanceId 二选一
 * @param targetProcessInstanceId String，精确接收实例主键；与 correlationKey 二选一
 * @param sequenceNo Long，同一关联键内从 1 开始且严格连续的消息序号
 * @param variables Map<String,Object>，凭据白名单内的标量变量
 */
public record WorkflowCollaborationMessageRequest(
        @NotBlank @Pattern(regexp = "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$") String messageId,
        @NotBlank @Size(max = 255) String messageName,
        @NotBlank @Size(max = 255) String sourceProcessDefinitionKey,
        @NotBlank @Size(max = 255) String targetProcessDefinitionKey,
        @Size(max = 255) String correlationKey,
        @Size(max = 255) String targetProcessInstanceId,
        @NotNull @Min(1) Long sequenceNo,
        @NotNull @Size(max = 128) Map<@Pattern(regexp = "^[A-Za-z_][A-Za-z0-9_]{0,127}$") String, Object> variables,
        @Min(1) @Max(20) Integer maxAttempts)
{
}
