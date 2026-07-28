package com.ruoyi.flowable.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

/**
 * WorkflowHistoricVariableMapper 的 Flowable 8 原生表两阶段读取安全契约测试。
 */
class WorkflowHistoricVariableMapperXmlTest
{
    /** Mapper XML classpath 固定位置。 */
    private static final String RESOURCE =
            "mapper/flowable/WorkflowHistoricVariableMapper.xml";

    /**
     * 验证快照第一阶段看见固定变量名的全部行，且不会提前返回或过滤正文。
     *
     * @return 无返回值，SQL 隐藏损坏行、读取正文或出现非绑定参数时测试失败
     * @throws Exception 读取或解析 Mapper XML 失败
     */
    @Test
    void parsesCompleteSubmissionMetadataQuery() throws Exception
    {
        Configuration configuration = parseMapper();
        MappedStatement statement = configuration.getMappedStatement(
                "com.ruoyi.flowable.mapper.WorkflowHistoricVariableMapper.selectSubmissionMetadata");
        BoundSql boundSql = statement.getBoundSql(Map.of(
                "processInstanceId", "instance-1",
                "variableName", "__ruoyi_workflow_form_submission_v1",
                "rowLimit", 10_001));
        String sql = normalizeSql(boundSql.getSql()).toLowerCase();

        assertThat(boundSql.getParameterMappings()).hasSize(3);
        assertThat(sql).contains(
                "from act_hi_detail d",
                "left join act_ge_bytearray b on b.id_ = d.bytearray_id_",
                "d.proc_inst_id_ = ?",
                "d.name_ = ?",
                "d.type_ as detail_type",
                "d.bytearray_id_ as byte_array_id",
                "octet_length(d.text_) as text_bytes",
                "octet_length(b.bytes_) as stored_bytes",
                "order by d.time_, d.rev_, d.id_",
                "limit ?");
        assertThat(sql).doesNotContain(
                "d.type_ = 'variableupdate'",
                "lower(d.var_type_)",
                "between 1 and",
                "d.text_ as stored_text",
                "b.bytes_ as stored_bytes");
    }

    /**
     * 验证快照第二阶段同时绑定实例、固定变量名和第一阶段已验证主键。
     *
     * @return 无返回值，正文查询扩大到其他实例、变量名或主键时测试失败
     * @throws Exception 读取或解析 Mapper XML 失败
     */
    @Test
    void bindsSubmissionBodyQueryToValidatedRows() throws Exception
    {
        Configuration configuration = parseMapper();
        MappedStatement statement = configuration.getMappedStatement(
                "com.ruoyi.flowable.mapper.WorkflowHistoricVariableMapper.selectSubmissionBodies");
        BoundSql boundSql = statement.getBoundSql(Map.of(
                "processInstanceId", "instance-1",
                "variableName", "__ruoyi_workflow_form_submission_v1",
                "rowIds", List.of("detail-1", "detail-2")));
        String sql = normalizeSql(boundSql.getSql()).toLowerCase();

        assertThat(boundSql.getParameterMappings()).hasSize(4);
        assertThat(sql).contains(
                "d.proc_inst_id_ = ?",
                "d.name_ = ?",
                "d.id_ in ( ? , ? )",
                "d.text_ as stored_text",
                "b.bytes_ as stored_bytes");
    }

    /**
     * 验证活动变量元数据查询同时绑定实例、任务作用域和部署 schema 字段白名单。
     *
     * @return 无返回值，局部或流程根作用域条件缺失时测试失败
     * @throws Exception 读取或解析 Mapper XML 失败
     */
    @Test
    void bindsCurrentMetadataToAuthorizedScopeAndSchemaWhitelist() throws Exception
    {
        Configuration configuration = parseMapper();
        MappedStatement statement = configuration.getMappedStatement(
                "com.ruoyi.flowable.mapper.WorkflowHistoricVariableMapper.selectCurrentVariableMetadata");
        BoundSql local = statement.getBoundSql(Map.of(
                "processInstanceId", "instance-1",
                "taskId", "task-1",
                "taskLocal", true,
                "variableNames", List.of("decision", "comment"),
                "rowLimit", 3));
        BoundSql process = statement.getBoundSql(Map.of(
                "processInstanceId", "instance-1",
                "taskId", "task-1",
                "taskLocal", false,
                "variableNames", List.of("decision", "comment"),
                "rowLimit", 3));
        String localSql = normalizeSql(local.getSql()).toLowerCase();
        String processSql = normalizeSql(process.getSql()).toLowerCase();

        assertThat(local.getParameterMappings()).hasSize(5);
        assertThat(localSql).contains(
                "from act_hi_varinst v",
                "left join act_ge_bytearray b on b.id_ = v.bytearray_id_",
                "v.bytearray_id_ as byte_array_id",
                "octet_length(b.bytes_) as stored_bytes",
                "v.proc_inst_id_ = ?",
                "v.name_ in ( ? , ? )",
                "v.task_id_ = ?",
                "limit ?");
        assertThat(process.getParameterMappings()).hasSize(4);
        assertThat(processSql).contains(
                "v.task_id_ is null",
                "v.execution_id_ = v.proc_inst_id_",
                "v.sub_scope_id_ is null");
        assertThat(processSql).doesNotContain("v.task_id_ = ?");
    }

    /**
     * 验证活动变量正文查询重复绑定授权作用域、字段白名单和已验证变量主键。
     *
     * @return 无返回值，第二阶段任一授权条件丢失时测试失败
     * @throws Exception 读取或解析 Mapper XML 失败
     */
    @Test
    void bindsCurrentBodiesToScopeWhitelistAndValidatedIds() throws Exception
    {
        Configuration configuration = parseMapper();
        MappedStatement statement = configuration.getMappedStatement(
                "com.ruoyi.flowable.mapper.WorkflowHistoricVariableMapper.selectCurrentVariableBodies");
        BoundSql boundSql = statement.getBoundSql(Map.of(
                "processInstanceId", "instance-1",
                "taskId", "task-1",
                "taskLocal", true,
                "variableNames", List.of("decision", "comment"),
                "rowIds", List.of("var-1", "var-2")));
        String sql = normalizeSql(boundSql.getSql()).toLowerCase();

        assertThat(boundSql.getParameterMappings()).hasSize(6);
        assertThat(sql).contains(
                "v.proc_inst_id_ = ?",
                "v.name_ in ( ? , ? )",
                "v.id_ in ( ? , ? )",
                "v.task_id_ = ?",
                "v.text_ as stored_text",
                "b.bytes_ as stored_bytes");
    }

    /**
     * 验证 XML 正文没有字符串直拼和宽泛列选择。
     *
     * @return 无返回值，出现动态字符串插值或 select * 时测试失败
     * @throws Exception 读取 Mapper XML 失败
     */
    @Test
    void keepsNativeHistoryQueriesExplicitAndParameterized() throws Exception
    {
        String xml;
        try (InputStream input = Resources.getResourceAsStream(RESOURCE))
        {
            xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String normalized = normalizeSql(xml).toLowerCase();

        assertThat(xml).doesNotContain("${");
        assertThat(normalized).doesNotContain("select *");
        assertThat(normalized).contains(
                "selectsubmissionmetadata",
                "selectsubmissionbodies",
                "selectcurrentvariablemetadata",
                "selectcurrentvariablebodies");
    }

    /**
     * 把历史 Mapper XML 解析到独立 MyBatis Configuration。
     *
     * @return Configuration，已注册四个两阶段读取 statement 的独立配置
     * @throws Exception 读取或解析 Mapper XML 失败
     */
    private Configuration parseMapper() throws Exception
    {
        Configuration configuration = new Configuration();
        try (Reader reader = Resources.getResourceAsReader(RESOURCE))
        {
            XMLMapperBuilder mapperBuilder = new XMLMapperBuilder(
                    reader, configuration, RESOURCE, configuration.getSqlFragments());
            mapperBuilder.parse();
        }
        return configuration;
    }

    /**
     * 合并 SQL 连续空白，令测试只关注查询结构。
     *
     * @param sql String，原始 SQL 或 Mapper XML
     * @return String，连续空白合并后的稳定文本
     */
    private String normalizeSql(String sql)
    {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
