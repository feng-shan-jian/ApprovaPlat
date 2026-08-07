package com.ruoyi.flowable.domain.dto;

import java.util.Map;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 外部集成账号发布消息、信号或触发 ReceiveTask 的统一请求。
 *
 * @param requestId String，调用方生成的规范小写 UUID 幂等键
 * @param eventName String，消息名、信号名或 ReceiveTask activityId
 * @param processInstanceId String，精确流程实例主键，与 businessKey 二选一
 * @param businessKey String，流程业务键，与 processInstanceId 二选一
 * @param variables Map&lt;String,Object&gt;，凭据白名单内的标量变量
 */
public record WorkflowRuntimeEventRequest(
        @NotBlank @Pattern(regexp = "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$") String requestId,
        @NotBlank @Size(max = 255) String eventName,
        @Size(max = 255) String processInstanceId,
        @Size(max = 255) String businessKey,
        @NotNull @Size(max = 128) Map<@Pattern(regexp = "^[A-Za-z_][A-Za-z0-9_]{0,127}$") String, Object> variables)
{
}
