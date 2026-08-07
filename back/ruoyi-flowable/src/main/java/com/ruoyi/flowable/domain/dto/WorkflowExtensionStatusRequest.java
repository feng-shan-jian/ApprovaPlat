package com.ruoyi.flowable.domain.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 修改扩展目录启停状态请求。
 *
 * @param enabled Boolean，true 启用，false 停用；只影响后续设计和部署
 */
public record WorkflowExtensionStatusRequest(@NotNull(message = "扩展状态不能为空") Boolean enabled)
{
}
