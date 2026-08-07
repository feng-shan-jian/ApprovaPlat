package com.ruoyi.flowable.extension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.exception.ServiceException;

/**
 * SQL 模板 AST 安全边界测试。
 */
class WorkflowSqlTemplateValidatorTest
{
    private final WorkflowSqlTemplateValidator validator = new WorkflowSqlTemplateValidator();

    /**
     * 验证单条命名参数更新会冻结操作、参数和访问表。
     * @return 无返回值；规范结果不一致时测试失败
     */
    @Test
    void normalizesSingleNamedParameterStatement()
    {
        WorkflowSqlTemplate template = validator.validate(
                "update wf_sql_it_target set result_value = :resultValue "
                        + "where request_id = :requestId",
                Set.of("wf_sql_it_target"));

        assertThat(template.operation()).isEqualTo("UPDATE");
        assertThat(template.parameterNames()).containsExactly("requestId", "resultValue");
        assertThat(template.tables()).containsExactly("wf_sql_it_target");
        assertThat(template.sql()).contains(":requestId", ":resultValue");
    }

    /**
     * 验证多语句、DDL、位置参数、无条件写入和越权表全部零执行拒绝。
     * @return 无返回值；任一非法模板通过时测试失败
     */
    @Test
    void rejectsUnsafeStatementShapes()
    {
        Set<String> allowed = Set.of("wf_sql_it_target");
        assertRejected("update wf_sql_it_target set result_value = :value; "
                + "delete from wf_sql_it_target where request_id = :id", allowed,
                "只允许单条语句");
        assertRejected("drop table wf_sql_it_target", allowed, "语句类型未列入白名单");
        assertRejected("update wf_sql_it_target set result_value = ? where request_id = :id",
                allowed, "只允许命名参数");
        assertRejected("delete from wf_sql_it_target", allowed, "必须包含 WHERE");
        assertRejected("select * from sys_user where user_id = :id", allowed, "未授权表");
        assertRejected("select * from wf_sql_it_target -- hidden", allowed, "不允许注释");
    }

    /**
     * 执行一次非法 SQL 断言并核对稳定错误消息。
     * @param sql String，待拒绝 SQL
     * @param allowedTables Set&lt;String&gt;，当前表白名单
     * @param message String，期望错误消息片段
     * @return 无返回值；未拒绝或消息不一致时测试失败
     */
    private void assertRejected(String sql, Set<String> allowedTables, String message)
    {
        assertThatThrownBy(() -> validator.validate(sql, allowedTables))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining(message);
    }
}
