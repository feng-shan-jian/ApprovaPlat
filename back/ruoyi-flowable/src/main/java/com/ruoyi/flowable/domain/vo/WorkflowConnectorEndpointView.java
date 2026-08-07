package com.ruoyi.flowable.domain.vo;

import java.util.Date;

/**
 * HTTP 连接器端点管理与设计选项视图。
 *
 * @param endpointId Long，端点主键
 * @param endpointKey String，稳定端点键
 * @param endpointName String，用户可见名称
 * @param baseUrl String，基础 URL
 * @param allowedMethods String，规范方法清单
 * @param pathPrefix String，允许路径前缀
 * @param authType String，认证类型
 * @param secretRef String，外部密钥引用
 * @param apiKeyHeader String，API Key 请求头
 * @param connectTimeoutMs Integer，连接超时
 * @param requestTimeoutMs Integer，请求超时
 * @param networkScope String，网络范围
 * @param revisionNo Integer，当前修订号
 * @param status String，启停状态
 * @param checksum String，配置摘要
 * @param updateTime Date，最后修改时间
 */
public record WorkflowConnectorEndpointView(Long endpointId, String endpointKey,
        String endpointName, String baseUrl, String allowedMethods, String pathPrefix,
        String authType, String secretRef, String apiKeyHeader, Integer connectTimeoutMs,
        Integer requestTimeoutMs, String networkScope, Integer revisionNo, String status,
        String checksum, Date updateTime)
{
}
