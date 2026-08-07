package com.ruoyi.flowable.extension;

import java.util.List;

/**
 * 已通过 AST 安全校验的 SQL 模板。
 *
 * @param sql String，JSqlParser 规范化后的单条命名参数 SQL
 * @param operation String，SELECT、INSERT、UPDATE 或 DELETE
 * @param parameterNames List&lt;String&gt;，去重并按字典序冻结的命名参数
 * @param tables List&lt;String&gt;，去重并按字典序冻结的访问表
 */
public record WorkflowSqlTemplate(String sql, String operation,
        List<String> parameterNames, List<String> tables)
{
}
