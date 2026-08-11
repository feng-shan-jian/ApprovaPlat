package com.ruoyi.flowable.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

/**
 * 扩展目录 Mapper 的安全参数、锁定和精确身份契约测试。
 */
class WorkflowExtensionMapperXmlTest
{
    /**
     * 验证正式扩展目录 Mapper XML 可解析且不存在 MyBatis 字符串替换占位符。
     * @return 无返回值；Mapper XML 或参数绑定不安全时测试失败
     * @throws Exception Mapper 资源读取或解析失败
     */
    @Test
    void parsesAllStatementsWithoutStringSubstitution() throws Exception
    {
        assertStatements("WfBpmnExtensionMapper", List.of(
                "selectManagementList", "selectLatestEnabledOptions", "selectByKey", "selectByKeyForUpdate",
                "selectByIdForUpdate", "selectLatestEnabledByKey", "selectMaxVersionNo",
                "insertExtension", "insertVersion", "updateStatus", "selectVersionIds",
                "deleteVersions", "deleteExtension"));
    }

    /**
     * 验证目录锁查询使用 FOR UPDATE，最新版查询固定来自版本最大值且没有客户端排序输入。
     * @return 无返回值；部署版本冻结可能发生竞态时测试失败
     * @throws Exception Mapper 资源读取或解析失败
     */
    @Test
    void locksDirectoryBeforeSelectingLatestVersion() throws Exception
    {
        Configuration configuration = parse("WfBpmnExtensionMapper");
        String byKeyLock = render(configuration, "WfBpmnExtensionMapper",
                "selectByKeyForUpdate", Map.of("extensionKey", "approva.set-variable"));
        String byIdLock = render(configuration, "WfBpmnExtensionMapper",
                "selectByIdForUpdate", Map.of("extensionId", 1L));
        String latest = render(configuration, "WfBpmnExtensionMapper",
                "selectLatestEnabledByKey", Map.of("extensionKey", "approva.set-variable"));

        assertThat(byKeyLock).contains("where extension_key = ? for update");
        assertThat(byIdLock).contains("where extension_id = ? for update");
        assertThat(latest).contains(
                "max(version_no) as version_no",
                "latest.extension_id = v.extension_id",
                "latest.version_no = v.version_no",
                "e.status = 'enabled'",
                "e.extension_key = ?");
    }

    /**
     * 验证目录删除会先读取目标版本主键，并且只删除目标主键下的版本与停用目录。
     * @return 无返回值；删除边界扩大或版本引用检查缺少稳定主键时测试失败
     * @throws Exception Mapper 资源读取或解析失败
     */
    @Test
    void scopesExtensionDeletionToUnlockedDeploymentFreeDirectory() throws Exception
    {
        Configuration configuration = parse("WfBpmnExtensionMapper");
        Map<String, Object> parameters = Map.of("extensionId", 11L);
        String versionIds = render(configuration, "WfBpmnExtensionMapper",
                "selectVersionIds", parameters);
        String deleteVersions = render(configuration, "WfBpmnExtensionMapper",
                "deleteVersions", parameters);
        String deleteExtension = render(configuration, "WfBpmnExtensionMapper",
                "deleteExtension", parameters);

        assertThat(versionIds).contains(
                "select version_id", "from wf_bpmn_extension_version",
                "where extension_id = ?", "order by version_no");
        assertThat(deleteVersions).contains("where extension_id = ?");
        assertThat(deleteExtension).contains(
                "where extension_id = ?", "and status = 'disabled'");
    }

    /**
     * 解析指定正式 Mapper XML。
     * @param mapperName String，不含包名和后缀的 Mapper 名称
     * @return Configuration，已注册 statement 的 MyBatis 配置
     * @throws Exception 资源读取或 XML 解析失败
     */
    private Configuration parse(String mapperName) throws Exception
    {
        String resource = "mapper/flowable/" + mapperName + ".xml";
        Configuration configuration = new Configuration();
        try (InputStream input = Resources.getResourceAsStream(resource))
        {
            new XMLMapperBuilder(input, configuration, resource,
                    configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    /**
     * 验证 Mapper 注册预期 statement 且原始 XML 不含字符串替换占位符。
     * @param mapperName String，Mapper 名称
     * @param statementIds List&lt;String&gt;，必须存在的 statement id
     * @return 无返回值；任一契约缺失时测试失败
     * @throws Exception 资源读取或 XML 解析失败
     */
    private void assertStatements(String mapperName, List<String> statementIds) throws Exception
    {
        Configuration configuration = parse(mapperName);
        String resource = "mapper/flowable/" + mapperName + ".xml";
        try (InputStream input = Resources.getResourceAsStream(resource))
        {
            assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                    .doesNotContain("${");
        }
        for (String statementId : statementIds)
        {
            assertThat(configuration.hasStatement(
                    "com.ruoyi.flowable.mapper." + mapperName + "." + statementId)).isTrue();
        }
    }

    /**
     * 渲染并规范一个 Mapper statement 的最终 SQL。
     * @param configuration Configuration，已解析 Mapper 配置
     * @param mapperName String，Mapper 名称
     * @param statementId String，statement id
     * @param parameters Map&lt;String,Object&gt;，代表性安全参数
     * @return String，小写且空白规范化的 SQL
     */
    private String render(Configuration configuration, String mapperName,
            String statementId, Map<String, Object> parameters)
    {
        MappedStatement statement = configuration.getMappedStatement(
                "com.ruoyi.flowable.mapper." + mapperName + "." + statementId);
        return statement.getBoundSql(new HashMap<>(parameters)).getSql()
                .replaceAll("\\s+", " ").trim().toLowerCase();
    }
}
