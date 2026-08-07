package com.ruoyi.flowable.domain.vo;

import java.util.Date;
import java.util.List;

/**
 * 不包含 Token 哈希或正文的集成账号管理视图。
 */
public record WorkflowIntegrationCredentialView(Long credentialId, String credentialName,
        String tokenPrefix, List<String> scopes, List<String> allowedVariables,
        Integer rateLimitPerMinute, Date expiresAt, Date revokedAt, Integer revisionNo,
        Date lastUsedAt, Date createTime, Date updateTime)
{
}
