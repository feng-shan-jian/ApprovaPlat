package com.ruoyi.flowable.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class WorkflowProcessDefinitionLockMapperXmlTest
{
    /**
     * 验证 key 发起的最终定义复核是有界唯一索引锁定读，而不是旧快照普通查询。
     *
     * @return void，SQL 缺少默认租户、版本顺序、唯一索引或 FOR UPDATE 时测试失败
     * @throws Exception Mapper XML 无法读取或解析时测试失败
     */
    @Test
    void parsesBoundedLatestDefaultTenantLockQuery() throws Exception
    {
        Configuration configuration = new Configuration();
        String resource = "mapper/flowable/WorkflowProcessDefinitionLockMapper.xml";
        try (InputStream input = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resource))
        {
            assertThat(input).as("流程定义锁 Mapper XML 必须进入模块资源").isNotNull();
            new XMLMapperBuilder(input, configuration, resource,
                    configuration.getSqlFragments()).parse();
        }

        String statementId = WorkflowProcessDefinitionLockMapper.class.getName()
                + ".selectLatestDefaultTenantDefinitionForUpdate";
        var statement = configuration.getMappedStatement(statementId);
        BoundSql boundSql = statement.getBoundSql(Map.of("processKey", "expense"));
        String sql = normalizeSql(boundSql.getSql());

        assertThat(statement.getTimeout()).isEqualTo(10);
        assertThat(sql).contains(
                "from act_re_procdef force index (act_uniq_procdef)",
                "where key_ = ? and tenant_id_ = ''",
                "order by version_ desc, derived_version_ desc",
                "limit 1 for update");
        assertThat(sql).doesNotContain("${");
    }

    /**
     * 规范 SQL 空白和大小写，避免格式差异影响锁契约断言。
     *
     * @param sql String，MyBatis 生成的 SQL
     * @return String，单空格小写 SQL
     */
    private String normalizeSql(String sql)
    {
        return sql.replaceAll("\\s+", " ").trim().toLowerCase();
    }
}
