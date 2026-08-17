package com.ruoyi.flowable.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

/**
 * HTTP/SQL 连接器端点与数据源目录 Mapper XML 契约测试。
 */
class WorkflowConnectorMapperXmlTest
{
    /**
     * 验证端点 Mapper 注册管理、锁定、修订和状态所需全部 statement。
     * @return void，端点持久化入口缺失或 XML 无法解析时测试失败
     * @throws Exception Mapper XML 读取失败
     */
    @Test
    void parsesEndpointMapperStatements() throws Exception
    {
        Configuration configuration = parse("WfConnectorEndpointMapper");
        String namespace = "com.ruoyi.flowable.mapper.WfConnectorEndpointMapper.";

        for (String statement : List.of("selectList", "selectEnabledOptions",
                "selectEnabledByKeyForUpdate", "selectByIdForUpdate", "insert",
                "updateRevision", "updateStatus"))
        {
            assertThat(configuration.hasStatement(namespace + statement)).isTrue();
        }
        String xml = read("WfConnectorEndpointMapper");
        assertThat(xml).doesNotContain("${").contains(
                "for update",
                "and revision_no = #{expectedRevision}",
                "current_timestamp(3)");
    }

    /**
     * 验证 SQL 数据源目录 Mapper 包含管理、行锁、修订和状态全部正式语句。
     * @return void，SQL 数据源持久化入口缺失或存在字符串直拼时测试失败
     * @throws Exception Mapper XML 读取失败
     */
    @Test
    void parsesSqlDataSourceMapperStatements() throws Exception
    {
        Configuration configuration = parse("WfSqlDataSourceMapper");
        String namespace = "com.ruoyi.flowable.mapper.WfSqlDataSourceMapper.";
        for (String statement : List.of("selectList", "selectEnabledOptions",
                "selectByIdForUpdate", "selectEnabledByKeyForUpdate", "insert",
                "updateRevision", "updateStatus"))
        {
            assertThat(configuration.hasStatement(namespace + statement)).isTrue();
        }
        String normalized = read("WfSqlDataSourceMapper").toLowerCase();
        assertThat(normalized).doesNotContain("${").contains(
                "from wf_sql_datasource", "for update",
                "revision_no = #{expectedrevision}", "current_timestamp(3)");
    }

    /**
     * 解析指定连接器 Mapper XML。
     * @param mapperName String，不含包名和后缀的 Mapper 名称
     * @return Configuration，已注册 statement 的独立配置
     * @throws Exception XML 读取或解析失败
     */
    private Configuration parse(String mapperName) throws Exception
    {
        String resource = resource(mapperName);
        Configuration configuration = new Configuration();
        try (Reader reader = Resources.getResourceAsReader(resource))
        {
            new XMLMapperBuilder(reader, configuration, resource,
                    configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    /**
     * 按 UTF-8 读取指定 Mapper XML 原文。
     * @param mapperName String，不含包名和后缀的 Mapper 名称
     * @return String，完整 XML 文本
     * @throws Exception 资源读取失败
     */
    private String read(String mapperName) throws Exception
    {
        try (InputStream input = Resources.getResourceAsStream(resource(mapperName)))
        {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 生成连接器 Mapper classpath 资源路径。
     * @param mapperName String，不含包名和后缀的 Mapper 名称
     * @return String，classpath 资源路径
     */
    private String resource(String mapperName)
    {
        return "mapper/flowable/" + mapperName + ".xml";
    }
}
