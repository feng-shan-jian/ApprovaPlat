package com.ruoyi.flowable.domain.vo;

/**
 * 创建或轮换后仅返回一次的集成 Token 视图。
 */
public record WorkflowIntegrationCredentialSecretView(Long credentialId, Integer revisionNo,
        String token, WorkflowIntegrationCredentialView credential)
{
}
