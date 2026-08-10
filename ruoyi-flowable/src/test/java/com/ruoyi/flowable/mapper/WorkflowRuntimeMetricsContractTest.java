package com.ruoyi.flowable.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class WorkflowRuntimeMetricsContractTest
{
    /**
     * 验证生产指标使用一条有超时的只读合并 SQL，并一次扫描附件表生成全部附件聚合值。
     *
     * @return void，指标退化为逐 Gauge 查询、缺少队列类型或 SQL 无超时时测试失败
     * @throws Exception 读取或解析 Mapper XML 失败
     */
    @Test
    void parsesSingleBoundedRuntimeMetricsQuery() throws Exception
    {
        Configuration configuration = new Configuration();
        String resource = "mapper/flowable/WorkflowRuntimeMetricsMapper.xml";
        try (InputStream input = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resource))
        {
            assertThat(input).as("运行指标 Mapper XML 必须进入模块资源").isNotNull();
            new XMLMapperBuilder(input, configuration, resource,
                    configuration.getSqlFragments()).parse();
        }

        String statementId = WorkflowRuntimeMetricsMapper.class.getName()
                + ".selectRuntimeMetricValues";
        var statement = configuration.getMappedStatement(statementId);
        BoundSql boundSql = statement.getBoundSql(java.util.Map.of());
        String sql = normalizeSql(boundSql.getSql());

        assertThat(statement.getTimeout()).isEqualTo(10);
        assertThat(sql).contains(
                "from act_ru_execution where id_ = proc_inst_id_"
                        + " and suspension_state_ = 1",
                "from act_ru_task where suspension_state_ = 1",
                "from act_ru_job",
                "from act_ru_timer_job",
                "from act_ru_suspended_job",
                "from act_ru_deadletter_job",
                "from act_ru_external_job",
                "from act_ru_history_job",
                "from wf_attachment",
                "cleanup_next_retry_time > current_timestamp(3)");
        assertThat(countOccurrences(sql, "from wf_attachment")).isEqualTo(1);
        assertThat(sql).doesNotContain("${");
        assertThat(configuration.getResultMap(
                WorkflowRuntimeMetricsMapper.class.getName()
                        + ".WorkflowRuntimeMetricValuesResult")
                .getConstructorResultMappings()).hasSize(15);
    }

    /**
     * 规范 SQL 空白和大小写，避免格式差异影响业务契约断言。
     *
     * @param sql String，MyBatis 生成 SQL
     * @return String，单空格小写 SQL
     */
    private String normalizeSql(String sql)
    {
        return sql.replaceAll("\\s+", " ").trim().toLowerCase();
    }

    /**
     * 统计固定片段在规范 SQL 中出现的次数。
     *
     * @param value String，待检查完整文本
     * @param needle String，非空固定片段
     * @return int，非重叠出现次数
     */
    private int countOccurrences(String value, String needle)
    {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(needle, index)) >= 0)
        {
            count++;
            index += needle.length();
        }
        return count;
    }
}
