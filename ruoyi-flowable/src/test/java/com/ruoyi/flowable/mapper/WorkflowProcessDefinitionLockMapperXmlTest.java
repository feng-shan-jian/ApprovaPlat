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
        Configuration configuration = parseMapper();

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
     * 验证部署生命周期锁按 ACT_RE_DEPLOYMENT 主键执行有界 FOR UPDATE 当前读。
     *
     * @return void，部署删除与草稿创建无法共享同一数据库行锁时测试失败
     * @throws Exception Mapper XML 无法读取或解析时测试失败
     */
    @Test
    void parsesDeploymentLifecycleLockQuery() throws Exception
    {
        Configuration configuration = parseMapper();
        String statementId = WorkflowProcessDefinitionLockMapper.class.getName()
                + ".selectDeploymentIdForUpdate";
        var statement = configuration.getMappedStatement(statementId);
        BoundSql boundSql = statement.getBoundSql(Map.of("deploymentId", "deployment-1"));
        String sql = normalizeSql(boundSql.getSql());

        assertThat(statement.getTimeout()).isEqualTo(10);
        assertThat(sql).isEqualTo(
                "select id_ from act_re_deployment where id_ = ? for update");
        assertThat(sql).doesNotContain("${", "wf_process_draft");
    }

    /**
     * 验证运行与历史实例删除门禁均使用部署条件和 FOR UPDATE 当前读。
     *
     * @return void，旧快照 count 仍可参与删除判断或查询越过部署范围时测试失败
     * @throws Exception Mapper XML 无法读取或解析时测试失败
     */
    @Test
    void parsesCurrentDeploymentInstanceReferenceQueries() throws Exception
    {
        Configuration configuration = parseMapper();
        String namespace = WorkflowProcessDefinitionLockMapper.class.getName() + ".";
        Map<String, Object> parameters = Map.of("deploymentId", "deployment-1");

        BoundSql runtimeSql = configuration.getMappedStatement(namespace
                + "selectRuntimeInstanceReferenceForUpdate").getBoundSql(parameters);
        BoundSql historySql = configuration.getMappedStatement(namespace
                + "selectHistoricInstanceReferenceForUpdate").getBoundSql(parameters);
        String normalizedRuntimeSql = normalizeSql(runtimeSql.getSql());
        String normalizedHistorySql = normalizeSql(historySql.getSql());

        assertThat(normalizedRuntimeSql).contains(
                "from act_ru_execution runtime_instance",
                "inner join act_re_procdef definition",
                "definition.deployment_id_ = ?",
                "runtime_instance.parent_id_ is null",
                "limit 1 for update");
        assertThat(normalizedHistorySql).contains(
                "from act_hi_procinst historic_instance",
                "inner join act_re_procdef definition",
                "definition.deployment_id_ = ?",
                "limit 1 for update");
        assertThat(normalizedRuntimeSql).doesNotContain("${", "count(");
        assertThat(normalizedHistorySql).doesNotContain("${", "count(");
    }

    /**
     * 解析流程定义与部署共享锁 Mapper。
     *
     * @return Configuration，已注册全部锁定读 statement 的 MyBatis 配置
     * @throws Exception Mapper XML 无法读取或解析时测试失败
     */
    private Configuration parseMapper() throws Exception
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
        return configuration;
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
