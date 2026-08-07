package com.ruoyi.flowable.domain.dto;

import java.time.OffsetDateTime;
import java.util.List;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 集成账号创建请求。
 *
 * @param credentialName String，用户可见名称
 * @param scopes List&lt;String&gt;，MESSAGE、SIGNAL、RECEIVE 范围
 * @param allowedVariables List&lt;String&gt;，允许写入 Flowable 的变量名
 * @param rateLimitPerMinute Integer，每分钟请求上限
 * @param expiresAt OffsetDateTime，到期时间，空表示长期有效
 */
public record WorkflowIntegrationCredentialCreateRequest(
        @NotBlank @Size(max = 128) String credentialName,
        @NotEmpty @Size(max = 3) List<@Pattern(regexp = "^(MESSAGE|SIGNAL|RECEIVE)$") String> scopes,
        @NotNull @Size(max = 128) List<@Pattern(regexp = "^[A-Za-z_][A-Za-z0-9_]{0,127}$") String> allowedVariables,
        @NotNull @Min(1) @Max(10000) Integer rateLimitPerMinute,
        OffsetDateTime expiresAt)
{
}
