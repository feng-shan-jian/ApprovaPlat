package com.ruoyi.flowable.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.Reader;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

/**
 * 参与者规则保留审计 Mapper XML 契约测试。
 */
class WorkflowParticipantRuleMapperXmlTest
{
    /**
     * 验证保留审计 Mapper 可解析，且不提供随部署删除审计的语句。
     *
     * @return void，statement 缺失或审计生命周期与部署删除错误耦合时测试失败
     * @throws Exception classpath Mapper 资源读取失败
     */
    @Test
    void parsesRetentionAuditStatements() throws Exception
    {
        Configuration configuration = new Configuration();
        parse(configuration, "mapper/flowable/WfParticipantResolutionAuditMapper.xml");

        assertThat(configuration.hasStatement(
                WfParticipantResolutionAuditMapper.class.getName() + ".insert")).isTrue();

        String auditXml = read("mapper/flowable/WfParticipantResolutionAuditMapper.xml")
                .toLowerCase();
        assertThat(auditXml).contains("insert into wf_participant_resolution_audit")
                .doesNotContain("delete from wf_participant_resolution_audit");
    }

    /**
     * 解析指定 Mapper 并注册 statement。
     *
     * @param configuration Configuration，共用 MyBatis 测试配置
     * @param resource String，classpath Mapper 路径
     * @return void，解析成功后 statement 写入配置
     * @throws Exception 资源读取或 XML 解析失败
     */
    private void parse(Configuration configuration, String resource) throws Exception
    {
        try (Reader reader = Resources.getResourceAsReader(resource))
        {
            new XMLMapperBuilder(reader, configuration, resource,
                    configuration.getSqlFragments()).parse();
        }
    }

    /**
     * 读取指定 Mapper XML 正文。
     *
     * @param resource String，classpath Mapper 路径
     * @return String，UTF-8 XML 正文
     * @throws Exception 资源读取失败
     */
    private String read(String resource) throws Exception
    {
        try (java.io.InputStream input = Resources.getResourceAsStream(resource))
        {
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
