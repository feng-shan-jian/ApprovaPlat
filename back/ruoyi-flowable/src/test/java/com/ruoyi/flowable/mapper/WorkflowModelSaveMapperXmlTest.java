package com.ruoyi.flowable.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class WorkflowModelSaveMapperXmlTest
{
    /** 模型保存 Mapper 的正式 classpath 资源路径。 */
    private static final String RESOURCE = "mapper/flowable/WorkflowModelSaveMapper.xml";

    /**
     * 验证模型保存 Mapper XML 可解析，且全部锁定和完成语句均注册到正式 namespace。
     *
     * @return void，XML、resultMap 或 statement 契约错误时测试失败
     * @throws Exception Mapper XML 无法读取或解析时测试失败
     */
    @Test
    void parsesAllModelSaveStatements() throws Exception
    {
        Configuration configuration = parseMapper();
        String namespace = WorkflowModelSaveMapper.class.getName() + ".";

        assertThat(configuration.hasStatement(namespace + "ensureSaveRequest")).isTrue();
        assertThat(configuration.hasStatement(namespace + "selectSaveRequestForUpdate")).isTrue();
        assertThat(configuration.hasStatement(
                namespace + "selectOldestDefaultTenantModelForUpdate")).isTrue();
        assertThat(configuration.hasStatement(
                namespace + "selectLatestDefaultTenantModelForUpdate")).isTrue();
        assertThat(configuration.hasStatement(
                namespace + "selectDefaultTenantModelForUpdate")).isTrue();
        assertThat(configuration.hasStatement(namespace + "completeSaveRequest")).isTrue();
    }

    /**
     * 验证同 key 稳定锚点锁使用 ACT_UNIQ_MODEL、默认租户、升序版本和十秒当前读超时。
     *
     * @return void，最早版本不能先串行化同 key 写入时测试失败
     * @throws Exception Mapper XML 无法读取或解析时测试失败
     */
    @Test
    void locksOldestDefaultTenantVersionAsStableGroupAnchor() throws Exception
    {
        Configuration configuration = parseMapper();
        MappedStatement statement = statement(configuration,
                "selectOldestDefaultTenantModelForUpdate");
        String sql = render(statement, Map.of("modelKey", "expense"));

        assertThat(statement.getTimeout()).isEqualTo(10);
        assertThat(sql).contains(
                "from act_re_model force index (act_uniq_model)",
                "where key_ = ? and tenant_id_ = ''",
                "order by version_ asc",
                "limit 1 for update");
        assertThat(sql).doesNotContain("${");
    }

    /**
     * 验证锚点后的最高版本当前读使用 ACT_UNIQ_MODEL、默认租户、降序版本和十秒超时。
     *
     * @return void，最高版本当前读无法取得锁等待期间的新版本时测试失败
     * @throws Exception Mapper XML 无法读取或解析时测试失败
     */
    @Test
    void locksLatestDefaultTenantVersionThroughUniqueIndex() throws Exception
    {
        Configuration configuration = parseMapper();
        MappedStatement statement = statement(configuration,
                "selectLatestDefaultTenantModelForUpdate");
        String sql = render(statement, Map.of("modelKey", "expense"));

        assertThat(statement.getTimeout()).isEqualTo(10);
        assertThat(sql).contains(
                "from act_re_model force index (act_uniq_model)",
                "where key_ = ? and tenant_id_ = ''",
                "order by version_ desc",
                "limit 1 for update");
        assertThat(sql).doesNotContain("${");
    }

    /**
     * 验证来源模型锁使用主键、默认租户和十秒当前读超时，并保持在最高版本锁之后调用。
     *
     * @return void，来源锁可能跨租户或退化为快照读时测试失败
     * @throws Exception Mapper XML 无法读取或解析时测试失败
     */
    @Test
    void locksSourceModelByPrimaryKeyAndDefaultTenant() throws Exception
    {
        Configuration configuration = parseMapper();
        MappedStatement statement = statement(configuration,
                "selectDefaultTenantModelForUpdate");
        String sql = render(statement, Map.of("modelId", "model-1"));

        assertThat(statement.getTimeout()).isEqualTo(10);
        assertThat(sql).contains(
                "from act_re_model force index (primary)",
                "where id_ = ? and tenant_id_ = ''",
                "limit 1 for update");
        assertThat(sql).doesNotContain("${");
    }

    /**
     * 验证 requestId 重放只保留首次请求字段，并通过锁定读返回持久化结果。
     *
     * @return void，幂等登记覆盖首次载荷或读取缺少 FOR UPDATE 时测试失败
     * @throws Exception Mapper XML 无法读取或解析时测试失败
     */
    @Test
    void preservesFirstRequestAndLocksReplayRecord() throws Exception
    {
        Configuration configuration = parseMapper();
        Map<String, Object> parameters = representativeParameters();
        MappedStatement ensure = statement(configuration, "ensureSaveRequest");
        MappedStatement select = statement(configuration, "selectSaveRequestForUpdate");
        String ensureSql = render(ensure, parameters);
        String selectSql = render(select, parameters);

        assertThat(ensure.getTimeout()).isEqualTo(10);
        assertThat(ensureSql).contains(
                "insert into wf_model_save_idempotency",
                "(request_id, user_id, source_model_id, payload_sha256)",
                "on duplicate key update request_id = request_id");
        assertThat(ensureSql).doesNotContain("saved_model_id");
        assertThat(select.getTimeout()).isEqualTo(10);
        assertThat(selectSql).contains(
                "from wf_model_save_idempotency force index (primary)",
                "where request_id = ?",
                "limit 1 for update");
    }

    /**
     * 验证完成写入仅能从未完成状态原子设置真实结果主键和毫秒完成时间。
     *
     * @return void，完成语句可能覆盖既有重放结果时测试失败
     * @throws Exception Mapper XML 无法读取或解析时测试失败
     */
    @Test
    void completesOnlyPendingSaveRequest() throws Exception
    {
        Configuration configuration = parseMapper();
        MappedStatement statement = statement(configuration, "completeSaveRequest");
        String sql = render(statement, representativeParameters());

        assertThat(statement.getTimeout()).isEqualTo(10);
        assertThat(sql).contains(
                "update wf_model_save_idempotency",
                "set saved_model_id = ?, complete_time = current_timestamp(3)",
                "where request_id = ?",
                "saved_model_id is null",
                "complete_time is null");
        assertThat(sql).doesNotContain("${");
    }

    /**
     * 解析模型保存 Mapper XML 到独立 MyBatis Configuration。
     *
     * @return Configuration，已注册正式模型保存 statements 的配置
     * @throws Exception Mapper XML 无法读取或解析时测试失败
     */
    private Configuration parseMapper() throws Exception
    {
        Configuration configuration = new Configuration();
        try (InputStream input = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(RESOURCE))
        {
            assertThat(input).as("模型保存 Mapper XML 必须进入模块资源").isNotNull();
            new XMLMapperBuilder(input, configuration, RESOURCE,
                    configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    /**
     * 读取指定模型保存 statement。
     *
     * @param configuration Configuration，已解析 Mapper 的 MyBatis 配置
     * @param statementId String，不含 namespace 的 statement 主键
     * @return MappedStatement，完整 namespace 下的已注册语句
     */
    private MappedStatement statement(Configuration configuration, String statementId)
    {
        return configuration.getMappedStatement(
                WorkflowModelSaveMapper.class.getName() + "." + statementId);
    }

    /**
     * 使用代表性安全参数渲染并规范 SQL 空白及大小写。
     *
     * @param statement MappedStatement，待渲染语句
     * @param parameters Map&lt;String,Object&gt;，语句所需的已校验参数
     * @return String，单空格小写 SQL
     */
    private String render(MappedStatement statement, Map<String, Object> parameters)
    {
        BoundSql boundSql = statement.getBoundSql(new HashMap<>(parameters));
        return boundSql.getSql().replaceAll("\\s+", " ").trim().toLowerCase();
    }

    /**
     * 构造覆盖全部模型保存语句占位符的代表性参数。
     *
     * @return Map&lt;String,Object&gt;，不会触发空参数分支的安全值
     */
    private Map<String, Object> representativeParameters()
    {
        return Map.of(
                "requestId", "019fae0e-b76c-75a3-af26-691b9202f65f",
                "userId", "1",
                "sourceModelId", "model-1",
                "payloadSha256", "0".repeat(64),
                "savedModelId", "model-2");
    }
}
