package com.ruoyi.flowable.domain;

import com.ruoyi.common.core.domain.BaseEntity;

/**
 * HTTP 连接器端点白名单，对应 {@code wf_connector_endpoint}。
 */
public class WfConnectorEndpoint extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 端点主键。 */
    private Long endpointId;
    /** 设计器和部署快照使用的稳定键。 */
    private String endpointKey;
    /** 用户可见端点名称。 */
    private String endpointName;
    /** 只包含协议、主机和端口的基础 URL。 */
    private String baseUrl;
    /** 逗号分隔且已排序的允许方法。 */
    private String allowedMethods;
    /** 允许请求的路径前缀。 */
    private String pathPrefix;
    /** 认证类型：NONE、BEARER 或 API_KEY。 */
    private String authType;
    /** 外部密钥环境变量引用，不保存密钥正文。 */
    private String secretRef;
    /** API_KEY 模式使用的请求头名称。 */
    private String apiKeyHeader;
    /** 连接超时毫秒数。 */
    private Integer connectTimeoutMs;
    /** 整体请求超时毫秒数。 */
    private Integer requestTimeoutMs;
    /** 网络范围：PUBLIC 或 PRIVATE。 */
    private String networkScope;
    /** 当前不可回退修订号。 */
    private Integer revisionNo;
    /** 端点状态：ENABLED 或 DISABLED。 */
    private String status;
    /** 当前修订配置 SHA-256。 */
    private String checksum;

    /** @return Long，端点主键。 */
    public Long getEndpointId() { return endpointId; }
    /** @param endpointId Long，端点主键；@return void，无返回值。 */
    public void setEndpointId(Long endpointId) { this.endpointId = endpointId; }
    /** @return String，设计器引用的稳定端点键。 */
    public String getEndpointKey() { return endpointKey; }
    /** @param endpointKey String，稳定端点键；@return void，无返回值。 */
    public void setEndpointKey(String endpointKey) { this.endpointKey = endpointKey; }
    /** @return String，端点用户可见名称。 */
    public String getEndpointName() { return endpointName; }
    /** @param endpointName String，端点用户可见名称；@return void，无返回值。 */
    public void setEndpointName(String endpointName) { this.endpointName = endpointName; }
    /** @return String，只包含协议、主机和端口的基础 URL。 */
    public String getBaseUrl() { return baseUrl; }
    /** @param baseUrl String，规范基础 URL；@return void，无返回值。 */
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    /** @return String，排序后的允许 HTTP 方法。 */
    public String getAllowedMethods() { return allowedMethods; }
    /** @param allowedMethods String，逗号分隔方法清单；@return void，无返回值。 */
    public void setAllowedMethods(String allowedMethods) { this.allowedMethods = allowedMethods; }
    /** @return String，允许请求的路径前缀。 */
    public String getPathPrefix() { return pathPrefix; }
    /** @param pathPrefix String，规范路径前缀；@return void，无返回值。 */
    public void setPathPrefix(String pathPrefix) { this.pathPrefix = pathPrefix; }
    /** @return String，NONE、BEARER 或 API_KEY。 */
    public String getAuthType() { return authType; }
    /** @param authType String，认证类型；@return void，无返回值。 */
    public void setAuthType(String authType) { this.authType = authType; }
    /** @return String，外部密钥环境变量引用。 */
    public String getSecretRef() { return secretRef; }
    /** @param secretRef String，外部密钥引用；@return void，无返回值。 */
    public void setSecretRef(String secretRef) { this.secretRef = secretRef; }
    /** @return String，API Key 请求头名称。 */
    public String getApiKeyHeader() { return apiKeyHeader; }
    /** @param apiKeyHeader String，API Key 请求头名称；@return void，无返回值。 */
    public void setApiKeyHeader(String apiKeyHeader) { this.apiKeyHeader = apiKeyHeader; }
    /** @return Integer，连接超时毫秒数。 */
    public Integer getConnectTimeoutMs() { return connectTimeoutMs; }
    /** @param connectTimeoutMs Integer，连接超时毫秒数；@return void，无返回值。 */
    public void setConnectTimeoutMs(Integer connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    /** @return Integer，请求整体超时毫秒数。 */
    public Integer getRequestTimeoutMs() { return requestTimeoutMs; }
    /** @param requestTimeoutMs Integer，请求整体超时毫秒数；@return void，无返回值。 */
    public void setRequestTimeoutMs(Integer requestTimeoutMs) { this.requestTimeoutMs = requestTimeoutMs; }
    /** @return String，PUBLIC 或 PRIVATE 网络范围。 */
    public String getNetworkScope() { return networkScope; }
    /** @param networkScope String，网络范围；@return void，无返回值。 */
    public void setNetworkScope(String networkScope) { this.networkScope = networkScope; }
    /** @return Integer，当前不可回退修订号。 */
    public Integer getRevisionNo() { return revisionNo; }
    /** @param revisionNo Integer，当前修订号；@return void，无返回值。 */
    public void setRevisionNo(Integer revisionNo) { this.revisionNo = revisionNo; }
    /** @return String，ENABLED 或 DISABLED。 */
    public String getStatus() { return status; }
    /** @param status String，端点状态；@return void，无返回值。 */
    public void setStatus(String status) { this.status = status; }
    /** @return String，当前修订 SHA-256 摘要。 */
    public String getChecksum() { return checksum; }
    /** @param checksum String，当前修订摘要；@return void，无返回值。 */
    public void setChecksum(String checksum) { this.checksum = checksum; }
}
