package com.ruoyi.flowable.domain.dto;

import java.time.OffsetDateTime;

/**
 * 集成 Token 轮换请求。
 *
 * @param expiresAt OffsetDateTime，新 Token 到期时间，空表示保持原到期时间
 */
public record WorkflowIntegrationCredentialRotateRequest(OffsetDateTime expiresAt)
{
}
