package com.ruoyi.flowable.domain.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 不包含任何连接凭据正文的 SQL 数据源管理视图。
 *
 * @param dataSourceId Long，目录主键
 * @param dataSourceKey String，稳定逻辑键
 * @param dataSourceName String，显示名称
 * @param connectionType String，PRIMARY 或 EXTERNAL
 * @param jdbcUrlRef String，可空 JDBC URL 环境引用
 * @param usernameRef String，可空用户名环境引用
 * @param passwordRef String，可空密码环境引用
 * @param allowedTables List&lt;String&gt;，授权表清单
 * @param connectTimeoutMs Integer，建连超时毫秒
 * @param queryTimeoutSeconds Integer，执行超时秒
 * @param revisionNo Integer，不可回退修订号
 * @param status String，ENABLED 或 DISABLED
 * @param checksum String，当前修订摘要
 * @param createTime LocalDateTime，创建时间
 * @param updateTime LocalDateTime，最后修改时间
 */
public record WorkflowSqlDataSourceView(Long dataSourceId, String dataSourceKey,
        String dataSourceName, String connectionType, String jdbcUrlRef,
        String usernameRef, String passwordRef, List<String> allowedTables,
        Integer connectTimeoutMs, Integer queryTimeoutSeconds, Integer revisionNo,
        String status, String checksum, LocalDateTime createTime, LocalDateTime updateTime)
{
}
