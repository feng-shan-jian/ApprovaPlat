package com.ruoyi.flowable.domain.vo;

import java.util.Date;
import java.util.List;

/**
 * 不包含 Token 哈希或正文的集成账号管理视图。
 *
 * @param credentialId Long，集成凭据主键
 * @param credentialName String，集成账号显示名称
 * @param tokenPrefix String，Token 可识别前缀
 * @param scopes List&lt;String&gt;，允许的运行事件范围
 * @param allowedVariables List&lt;String&gt;，允许写入的流程变量
 * @param rateLimitPerMinute Integer，每分钟最大请求数
 * @param expiresAt Date，到期时间，空表示长期有效
 * @param revokedAt Date，吊销时间，空表示未吊销
 * @param revisionNo Integer，Token 修订号
 * @param status String，服务端投影的 ACTIVE、EXPIRED 或 REVOKED
 * @param rotateAllowed boolean，当前业务状态是否允许轮换
 * @param revokeAllowed boolean，当前业务状态是否允许吊销
 * @param lastUsedAt Date，最近一次认证成功时间
 * @param createTime Date，创建时间
 * @param updateTime Date，最近更新时间
 */
public record WorkflowIntegrationCredentialView(Long credentialId, String credentialName,
        String tokenPrefix, List<String> scopes, List<String> allowedVariables,
        Integer rateLimitPerMinute, Date expiresAt, Date revokedAt, Integer revisionNo,
        String status, boolean rotateAllowed, boolean revokeAllowed,
        Date lastUsedAt, Date createTime, Date updateTime)
{
}
