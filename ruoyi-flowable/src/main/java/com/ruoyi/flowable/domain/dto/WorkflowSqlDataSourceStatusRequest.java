package com.ruoyi.flowable.domain.dto;

import jakarta.validation.constraints.NotNull;

/**
 * SQL 数据源目录启停请求。
 *
 * @param enabled Boolean，是否允许后续设计和部署选择
 */
public record WorkflowSqlDataSourceStatusRequest(@NotNull Boolean enabled)
{
}
