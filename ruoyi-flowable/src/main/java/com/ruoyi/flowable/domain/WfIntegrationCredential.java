package com.ruoyi.flowable.domain;

import java.util.Date;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 工作流集成账号实体。Token 正文永不进入该对象，只保存可识别前缀和 SHA-256。
 */
public class WfIntegrationCredential extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 集成凭据主键。 */
    private Long credentialId;
    /** 集成账号显示名称。 */
    private String credentialName;
    /** Token 前 12 个 URL-safe 字符。 */
    private String tokenPrefix;
    /** 完整 Token 的小写 SHA-256。 */
    private String tokenHash;
    /** 排序后的事件范围。 */
    private String scopes;
    /** 排序后的变量白名单。 */
    private String allowedVariables;
    /** 每分钟最大请求数。 */
    private Integer rateLimitPerMinute;
    /** 到期时间，空表示长期有效。 */
    private Date expiresAt;
    /** 吊销时间，空表示未吊销。 */
    private Date revokedAt;
    /** Token 轮换修订号。 */
    private Integer revisionNo;
    /** 最近一次认证成功时间。 */
    private Date lastUsedAt;

    /** @return Long，集成凭据主键。 */
    public Long getCredentialId() { return credentialId; }
    /** @param credentialId Long，集成凭据主键；@return void，无返回值。 */
    public void setCredentialId(Long credentialId) { this.credentialId = credentialId; }
    /** @return String，集成账号显示名称。 */
    public String getCredentialName() { return credentialName; }
    /** @param credentialName String，集成账号显示名称；@return void，无返回值。 */
    public void setCredentialName(String credentialName) { this.credentialName = credentialName; }
    /** @return String，Token 可识别前缀。 */
    public String getTokenPrefix() { return tokenPrefix; }
    /** @param tokenPrefix String，Token 可识别前缀；@return void，无返回值。 */
    public void setTokenPrefix(String tokenPrefix) { this.tokenPrefix = tokenPrefix; }
    /** @return String，完整 Token 的 SHA-256。 */
    public String getTokenHash() { return tokenHash; }
    /** @param tokenHash String，完整 Token 的 SHA-256；@return void，无返回值。 */
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    /** @return String，排序后的事件范围。 */
    public String getScopes() { return scopes; }
    /** @param scopes String，排序后的事件范围；@return void，无返回值。 */
    public void setScopes(String scopes) { this.scopes = scopes; }
    /** @return String，排序后的变量白名单。 */
    public String getAllowedVariables() { return allowedVariables; }
    /** @param allowedVariables String，排序后的变量白名单；@return void，无返回值。 */
    public void setAllowedVariables(String allowedVariables) { this.allowedVariables = allowedVariables; }
    /** @return Integer，每分钟最大请求数。 */
    public Integer getRateLimitPerMinute() { return rateLimitPerMinute; }
    /** @param rateLimitPerMinute Integer，每分钟最大请求数；@return void，无返回值。 */
    public void setRateLimitPerMinute(Integer rateLimitPerMinute) { this.rateLimitPerMinute = rateLimitPerMinute; }
    /** @return Date，到期时间或 null。 */
    public Date getExpiresAt() { return copy(expiresAt); }
    /** @param expiresAt Date，到期时间；@return void，无返回值。 */
    public void setExpiresAt(Date expiresAt) { this.expiresAt = copy(expiresAt); }
    /** @return Date，吊销时间或 null。 */
    public Date getRevokedAt() { return copy(revokedAt); }
    /** @param revokedAt Date，吊销时间；@return void，无返回值。 */
    public void setRevokedAt(Date revokedAt) { this.revokedAt = copy(revokedAt); }
    /** @return Integer，Token 修订号。 */
    public Integer getRevisionNo() { return revisionNo; }
    /** @param revisionNo Integer，Token 修订号；@return void，无返回值。 */
    public void setRevisionNo(Integer revisionNo) { this.revisionNo = revisionNo; }
    /** @return Date，最近使用时间或 null。 */
    public Date getLastUsedAt() { return copy(lastUsedAt); }
    /** @param lastUsedAt Date，最近使用时间；@return void，无返回值。 */
    public void setLastUsedAt(Date lastUsedAt) { this.lastUsedAt = copy(lastUsedAt); }

    /**
     * 复制可变时间值，避免认证状态被调用方篡改。
     * @param value Date，允许为空的时间
     * @return Date，时间副本或 null
     */
    private Date copy(Date value)
    {
        return value == null ? null : new Date(value.getTime());
    }
}
