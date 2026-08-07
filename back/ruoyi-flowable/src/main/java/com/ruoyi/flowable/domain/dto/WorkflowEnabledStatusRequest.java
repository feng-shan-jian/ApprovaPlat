package com.ruoyi.flowable.domain.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 通用受控目录启停请求。
 *
 * @param enabled Boolean，true 启用，false 停用
 */
public record WorkflowEnabledStatusRequest(
        @NotNull(message = "启停状态不能为空") Boolean enabled) { }
