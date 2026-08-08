package com.ruoyi.flowable.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.Reader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class WorkflowIdentityMapperXmlTest
{
    /**
     * 验证已选身份批量回显按三类正式表精确查询并保留基础启用状态。
     *
     * @return void，远程分页外对象无法正式回显时测试失败
     * @throws Exception 读取 Mapper XML 失败
     */
    @Test
    void definesSavedIdentitySelectionResolutionContract() throws Exception
    {
        String xml = readMapper().toLowerCase();

        assertThat(xml).contains(
                "<select id=\"selectidentityselectionsbyids\"",
                "from sys_user u",
                "from sys_role r",
                "from sys_dept d",
                "as available",
                "<foreach collection=\"ids\" item=\"id\"");
    }

    /**
     * 验证模块自有 Mapper XML 可被 MyBatis 完整解析且所有正式查询均已注册。
     *
     * @return 无返回值；XML 语法或 statement 映射错误时测试失败
     * @throws Exception 读取测试 classpath 资源失败
     */
    @Test
    void parsesAllIdentityStatements() throws Exception
    {
        String resource = "mapper/flowable/WorkflowIdentityMapper.xml";
        Configuration configuration = new Configuration();
        try (Reader reader = Resources.getResourceAsReader(resource))
        {
            XMLMapperBuilder mapperBuilder = new XMLMapperBuilder(
                    reader, configuration, resource, configuration.getSqlFragments());
            mapperBuilder.parse();
        }

        String namespace = WorkflowIdentityMapper.class.getName() + ".";
        assertThat(configuration.hasStatement(namespace + "countActiveIdentityOptions")).isTrue();
        assertThat(configuration.hasStatement(namespace + "selectActiveIdentityOptions")).isTrue();
        assertThat(configuration.hasStatement(namespace + "countApprovalEligibleUserOptions"))
                .isTrue();
        assertThat(configuration.hasStatement(namespace + "selectApprovalEligibleUserOptions"))
                .isTrue();
        assertThat(configuration.hasStatement(namespace + "selectActiveUserIdsByUserIds")).isTrue();
        assertThat(configuration.hasStatement(
                namespace + "selectApprovalEligibleUserIdsByUserIds")).isTrue();
        assertThat(configuration.hasStatement(namespace + "selectActiveUserIdsByRoleIds")).isTrue();
        assertThat(configuration.hasStatement(namespace + "selectActiveUserIdsByDeptIds")).isTrue();
        assertThat(configuration.hasStatement(namespace + "selectActiveRoleIdsByUserId")).isTrue();
        assertThat(configuration.hasStatement(namespace + "selectActiveDeptIdsByUserId")).isTrue();
        assertThat(configuration.hasStatement(namespace + "selectIdentitySelectionsByIds")).isTrue();
    }

    /**
     * 从测试 classpath 读取正式身份 Mapper XML 正文。
     *
     * @return String，UTF-8 Mapper XML
     * @throws Exception 资源不存在或读取失败
     */
    private String readMapper() throws Exception
    {
        try (InputStream input = Resources.getResourceAsStream(
                "mapper/flowable/WorkflowIdentityMapper.xml"))
        {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
