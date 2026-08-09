package com.ruoyi.flowable.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.Reader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.mapping.ResultMap;
import org.junit.jupiter.api.Test;
import com.ruoyi.flowable.domain.vo.WorkflowIdentitySelectionView;

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
        Configuration configuration = parseMapper();

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
     * 使用 MyBatis 实际解析后的构造映射核对 record 原始 boolean 参数，防止包装类型导致运行期 500。
     *
     * @return void，无返回值；MyBatis 类型别名不能解析为真实 record 构造器时测试失败
     * @throws Exception Mapper XML 无法解析或目标构造器不存在时测试失败
     */
    @Test
    void mapsSelectionAvailabilityToTheRealPrimitiveBooleanConstructor() throws Exception
    {
        Configuration configuration = parseMapper();
        String namespace = WorkflowIdentityMapper.class.getName() + ".";
        ResultMap resultMap = configuration.getResultMap(
                namespace + "WorkflowIdentitySelectionViewResult");
        Class<?>[] constructorTypes = new Class<?>[
                resultMap.getConstructorResultMappings().size()];
        for (int index = 0; index < constructorTypes.length; index++)
        {
            constructorTypes[index] = resultMap.getConstructorResultMappings()
                    .get(index).getJavaType();
        }

        assertThat(constructorTypes).containsExactly(
                String.class, String.class, String.class, boolean.class);
        assertThat(WorkflowIdentitySelectionView.class.getDeclaredConstructor(
                constructorTypes)).isNotNull();
        assertThat(configuration.getMappedStatement(
                namespace + "selectIdentitySelectionsByIds").getResultMaps())
                .singleElement()
                .extracting(ResultMap::getId)
                .isEqualTo(resultMap.getId());
    }

    /**
     * 通过 MyBatis 正式 XML 解析器加载身份 Mapper。
     *
     * @return Configuration，包含 SQL 片段、statement 和 resultMap 的真实 MyBatis 配置
     * @throws Exception classpath 资源缺失或 Mapper XML 语法错误时测试失败
     */
    private Configuration parseMapper() throws Exception
    {
        String resource = "mapper/flowable/WorkflowIdentityMapper.xml";
        Configuration configuration = new Configuration();
        try (Reader reader = Resources.getResourceAsReader(resource))
        {
            XMLMapperBuilder mapperBuilder = new XMLMapperBuilder(
                    reader, configuration, resource, configuration.getSqlFragments());
            mapperBuilder.parse();
        }
        return configuration;
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
