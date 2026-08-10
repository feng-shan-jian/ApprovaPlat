package com.ruoyi.flowable.domain.dto;

import java.util.List;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * SQL 连接器数据源目录创建或发布修订请求。
 *
 * @param dataSourceKey String，设计器引用的稳定逻辑键
 * @param dataSourceName String，管理页显示名称
 * @param connectionType String，PRIMARY 或 EXTERNAL
 * @param jdbcUrlRef String，外库 JDBC URL 环境变量引用
 * @param usernameRef String，外库用户名环境变量引用
 * @param passwordRef String，外库密码环境变量引用
 * @param allowedTables List&lt;String&gt;，允许 SQL AST 访问的表
 * @param connectTimeoutMs Integer，外库建连超时毫秒
 * @param queryTimeoutSeconds Integer，单次 SQL 执行超时秒
 */
public record WorkflowSqlDataSourceRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_.-]{0,127}") String dataSourceKey,
        @NotBlank @Size(max = 128) String dataSourceName,
        @NotBlank @Pattern(regexp = "PRIMARY|EXTERNAL") String connectionType,
        @Size(max = 128) String jdbcUrlRef,
        @Size(max = 128) String usernameRef,
        @Size(max = 128) String passwordRef,
        @NotEmpty @Size(max = 64) List<@Pattern(
                regexp = "[A-Za-z_][A-Za-z0-9_$]{0,127}(\\.[A-Za-z_][A-Za-z0-9_$]{0,127})?") String> allowedTables,
        @NotNull @Min(100) @Max(10000) Integer connectTimeoutMs,
        @NotNull @Min(1) @Max(300) Integer queryTimeoutSeconds)
{
}
