package com.ruoyi.flowable.domain.dto;

import jakarta.validation.constraints.NotNull;

/**
 * HTTP 连接器端点启停请求。
 *
 * @param enabled Boolean，true 启用，false 停用
 */
public record WorkflowConnectorEndpointStatusRequest(@NotNull Boolean enabled)
{
}
