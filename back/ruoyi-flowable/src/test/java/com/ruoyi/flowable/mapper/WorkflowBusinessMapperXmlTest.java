package com.ruoyi.flowable.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import com.ruoyi.flowable.domain.WfCopy;

class WorkflowBusinessMapperXmlTest
{
    private static final Map<String, List<String>> EXPECTED_STATEMENTS = Map.of(
            "WfCategoryMapper", List.of("selectById", "selectList", "selectExportList",
                    "selectByCode", "insert", "update", "logicalDelete", "countActiveByIds"),
            "WfFormMapper", List.of("selectById", "selectList", "selectSummaryList", "insert",
                    "update", "logicalDelete", "countActiveByIds"),
            "WfDeployFormMapper", List.of("insertBatch", "selectByDeploymentId",
                    "countByFormIds", "deleteByDeploymentId"),
            "WfCopyMapper", List.of("selectById", "selectByIdAndUserId", "insertBatch",
                    "insertBatchIdempotent", "markRead", "selectListByUserId",
                    "countListByUserId", "selectPageByUserId", "logicalDelete",
                    "countActiveByInstanceAndUser", "countActiveByInstanceIds",
                    "logicalDeleteByInstanceIds"));

    /**
     * 验证四组 Mapper XML 可由 MyBatis 完整解析并注册全部冻结 statement。
     * @return void，XML 或 statement 契约错误时测试失败
     * @throws Exception 读取或解析 classpath XML 失败
     */
    @Test
    void parsesAllFrozenMapperStatements() throws Exception
    {
        for (Map.Entry<String, List<String>> mapper : EXPECTED_STATEMENTS.entrySet())
        {
            Configuration configuration = parseMapper(mapper.getKey());
            String namespace = "com.ruoyi.flowable.mapper." + mapper.getKey() + ".";
            for (String statementId : mapper.getValue())
            {
                assertThat(configuration.hasStatement(namespace + statementId))
                        .as("%s 必须注册 statement %s", mapper.getKey(), statementId)
                        .isTrue();
            }
        }
    }

    /**
     * 验证所有正式查询、更新和删除显式过滤有效记录且不存在字符串直拼占位符。
     * @return void，逻辑删除或 SQL 注入契约错误时测试失败
     * @throws Exception 读取或解析 XML 失败
     */
    @Test
    void requiresActiveFlagAndSafeParametersForAllBusinessReadsAndMutations() throws Exception
    {
        for (String mapperName : EXPECTED_STATEMENTS.keySet())
        {
            String resource = resource(mapperName);
            String xml;
            try (InputStream input = Resources.getResourceAsStream(resource))
            {
                xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            assertThat(xml).doesNotContain("${");

            Document document = parseDocument(resource);
            for (String tagName : List.of("select", "update", "delete"))
            {
                NodeList statements = document.getElementsByTagName(tagName);
                for (int index = 0; index < statements.getLength(); index++)
                {
                    Element statement = (Element) statements.item(index);
                    String statementId = statement.getAttribute("id");
                    String renderedSql = renderSql(mapperName, statementId,
                            representativeParameters());
                    assertThat(renderedSql)
                            .as("%s.%s 必须显式包含 del_flag='0'", mapperName,
                                    statementId)
                            .contains("del_flag = '0'");
                }
            }
        }
    }

    /**
     * 验证部署表单保存双来源字段且查询严格读取不可变快照，不回连当前 wf_form。
     * @return void，快照映射或查询契约错误时测试失败
     * @throws Exception 解析 XML 失败
     */
    @Test
    void keepsDeploymentFormAsIndependentImmutableSnapshot() throws Exception
    {
        Document document = parseDocument(resource("WfDeployFormMapper"));
        Element resultMap = elementById(document, "resultMap", "WfDeployFormResult");
        Element select = elementById(document, "select", "selectByDeploymentId");
        String selectSql = normalizeSql(select.getTextContent()).toLowerCase();

        assertThat(attributeForProperty(resultMap, "sourceType")).isEqualTo("source_type");
        assertThat(attributeForProperty(resultMap, "formId")).isEqualTo("form_id");
        assertThat(selectSql).contains("from wf_deploy_form");
        assertThat(selectSql).doesNotContain(" join ").doesNotContain("wf_form ");
    }

    /**
     * 验证表单导出摘要不读取 content 大字段且 LIMIT 使用参数绑定。
     * @return void，摘要查询加载大字段或无界时测试失败
     * @throws Exception 解析 XML 失败
     */
    @Test
    void keepsFormSummaryExportBoundedAndContentFree() throws Exception
    {
        Document document = parseDocument(resource("WfFormMapper"));
        Element summary = elementById(document, "select", "selectSummaryList");
        String sql = normalizeSql(summary.getTextContent()).toLowerCase();

        assertThat(sql).doesNotContain(" content").contains("limit #{limit}");
    }

    /**
     * 验证抄送任务列映射、服务端用户过滤和对象授权计数均使用冻结列名。
     * @return void，抄送 Mapper 契约错误时测试失败
     * @throws Exception 解析 XML 失败
     */
    @Test
    void enforcesCopyColumnAndCurrentUserContracts() throws Exception
    {
        Document document = parseDocument(resource("WfCopyMapper"));
        Element resultMap = elementById(document, "resultMap", "WfCopyResult");
        Element count = elementById(document, "select", "countActiveByInstanceAndUser");
        Map<String, Object> parameters = representativeParameters();
        WfCopy filter = new WfCopy();
        filter.setProcessId("definition-1");
        parameters.put("filter", filter);
        String listSql = renderSql("WfCopyMapper", "selectListByUserId", parameters);
        String pageSql = renderSql("WfCopyMapper", "selectPageByUserId", parameters);
        String listCountSql = renderSql("WfCopyMapper", "countListByUserId", parameters);

        assertThat(attributeForProperty(resultMap, "taskId")).isEqualTo("task_id");
        assertThat(attributeForProperty(resultMap, "copyEventId")).isEqualTo("copy_event_id");
        assertThat(listSql)
                .contains("user_id = #{userId}", "process_id = ?")
                .doesNotContain("#{filter.userId}");
        assertThat(pageSql)
                .contains("user_id = #{userId}", "del_flag = '0'", "process_id = ?",
                        "limit #{offset}, #{limit}")
                .doesNotContain("#{filter.userId}");
        assertThat(listCountSql)
                .contains("user_id = #{userId}", "del_flag = '0'", "process_id = ?")
                .doesNotContain("#{filter.userId}");
        assertThat(normalizeSql(count.getTextContent()))
                .contains("instance_id = #{instanceId}", "user_id = #{userId}");
    }

    /**
     * 验证空批量参数会生成 1=0 的合法零影响 SQL，而不是 IN () 或空 VALUES。
     * @return void，空批量 SQL 契约错误时测试失败
     * @throws Exception 解析 Mapper XML 失败
     */
    @Test
    void rendersSafeNoOpSqlForEmptyBatches() throws Exception
    {
        assertEmptyBatchSql("WfCategoryMapper", "logicalDelete",
                Map.of("categoryIds", List.of(), "updateBy", "admin"));
        assertEmptyBatchSql("WfCategoryMapper", "countActiveByIds",
                Map.of("categoryIds", List.of()));
        assertEmptyBatchSql("WfFormMapper", "logicalDelete",
                Map.of("formIds", List.of(), "updateBy", "admin"));
        assertEmptyBatchSql("WfFormMapper", "countActiveByIds", Map.of("formIds", List.of()));
        assertEmptyBatchSql("WfDeployFormMapper", "countByFormIds", Map.of("formIds", List.of()));
        assertEmptyBatchSql("WfDeployFormMapper", "insertBatch", Map.of("forms", List.of()));
        assertEmptyBatchSql("WfCopyMapper", "insertBatch", Map.of("copies", List.of()));
        assertEmptyBatchSql("WfCopyMapper", "logicalDelete",
                Map.of("copyIds", List.of(), "updateBy", "admin"));
        assertEmptyBatchSql("WfCopyMapper", "countActiveByInstanceIds",
                Map.of("instanceIds", List.of()));
        assertEmptyBatchSql("WfCopyMapper", "logicalDeleteByInstanceIds",
                Map.of("instanceIds", List.of(), "updateBy", "admin"));
    }

    /**
     * 解析指定 Mapper XML 到独立 MyBatis Configuration。
     * @param mapperName String，不含包名和后缀的 Mapper 名称
     * @return Configuration，已注册该 Mapper statement 的配置
     * @throws Exception 读取或解析 XML 失败
     */
    private Configuration parseMapper(String mapperName) throws Exception
    {
        String resource = resource(mapperName);
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
     * 使用禁用外部 DTD 加载的 DOM 解析器读取 Mapper XML。
     * @param resource String，classpath XML 资源路径
     * @return Document，解析后的 XML 文档
     * @throws Exception 读取或解析 XML 失败
     */
    private Document parseDocument(String resource) throws Exception
    {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        try (InputStream input = Resources.getResourceAsStream(resource))
        {
            return builder.parse(input);
        }
    }

    /**
     * 查找指定标签和 id 的 XML 元素。
     * @param document Document，Mapper XML 文档
     * @param tagName String，元素标签名
     * @param id String，元素 id 属性
     * @return Element，匹配元素
     */
    private Element elementById(Document document, String tagName, String id)
    {
        NodeList nodes = document.getElementsByTagName(tagName);
        for (int index = 0; index < nodes.getLength(); index++)
        {
            Element element = (Element) nodes.item(index);
            if (id.equals(element.getAttribute("id")))
            {
                return element;
            }
        }
        throw new AssertionError("未找到 XML 元素: " + tagName + "#" + id);
    }

    /**
     * 查询 resultMap 中指定 property 对应的 column。
     * @param resultMap Element，resultMap 元素
     * @param property String，Java 属性名
     * @return String，对应数据库列名
     */
    private String attributeForProperty(Element resultMap, String property)
    {
        NodeList children = resultMap.getChildNodes();
        for (int index = 0; index < children.getLength(); index++)
        {
            Node node = children.item(index);
            if (node instanceof Element element && property.equals(element.getAttribute("property")))
            {
                return element.getAttribute("column");
            }
        }
        throw new AssertionError("resultMap 未映射属性: " + property);
    }

    /**
     * 断言指定 statement 在空批量参数下生成合法零影响 SQL。
     * @param mapperName String，Mapper 名称
     * @param statementId String，statement id
     * @param parameters Map&lt;String,Object&gt;，空集合参数
     * @return void，SQL 不安全时断言失败
     * @throws Exception 解析 Mapper XML 失败
     */
    private void assertEmptyBatchSql(String mapperName, String statementId,
            Map<String, Object> parameters) throws Exception
    {
        Configuration configuration = parseMapper(mapperName);
        String statementName = "com.ruoyi.flowable.mapper." + mapperName + "." + statementId;
        MappedStatement statement = configuration.getMappedStatement(statementName);
        String sql = normalizeSql(statement.getBoundSql(new HashMap<>(parameters)).getSql()).toLowerCase();

        assertThat(sql).contains("1 = 0").doesNotContain("in ()");
    }

    /**
     * 使用 MyBatis 完整展开 sql/include/if 后渲染指定 statement。
     * @param mapperName String，Mapper 名称
     * @param statementId String，statement id
     * @param parameters Map&lt;String,Object&gt;，用于选择动态 SQL 分支的参数
     * @return String，保留参数占位语义并规范空白后的最终 SQL
     * @throws Exception Mapper XML 读取或解析失败
     */
    private String renderSql(String mapperName, String statementId,
            Map<String, Object> parameters) throws Exception
    {
        Configuration configuration = parseMapper(mapperName);
        String statementName = "com.ruoyi.flowable.mapper." + mapperName + "." + statementId;
        MappedStatement statement = configuration.getMappedStatement(statementName);
        String rendered = normalizeSql(statement.getBoundSql(new HashMap<>(parameters)).getSql());

        // BoundSql 已把 #{} 转换为 ?，测试断言恢复字段级占位名只用于验证独立参数契约。
        if ("WfCopyMapper".equals(mapperName))
        {
            rendered = rendered.replaceFirst("user_id = \\?", "user_id = #{userId}")
                    .replaceFirst("instance_id = \\?", "instance_id = #{instanceId}")
                    .replaceFirst("limit \\?, \\?", "limit #{offset}, #{limit}");
        }
        return rendered;
    }

    /**
     * 构造覆盖全部 Mapper 动态分支所需的非空安全参数。
     * @return Map&lt;String,Object&gt;，不会触发空 IN 或危险写入的测试参数
     */
    private Map<String, Object> representativeParameters()
    {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("categoryIds", List.of(1L));
        parameters.put("formIds", List.of(1L));
        parameters.put("copyIds", List.of(1L));
        parameters.put("categoryId", 1L);
        parameters.put("formId", 1L);
        parameters.put("copyId", 1L);
        parameters.put("deploymentId", "deployment-1");
        parameters.put("instanceId", "instance-1");
        parameters.put("instanceIds", List.of("instance-1"));
        parameters.put("userId", 1L);
        parameters.put("updateBy", "admin");
        parameters.put("offset", 0);
        parameters.put("limit", 10);
        parameters.put("filter", null);
        return parameters;
    }

    /**
     * 将 SQL 空白规范化，便于稳定断言结构而非排版。
     * @param sql String，原始 SQL 或 XML 文本
     * @return String，连续空白合并后的 SQL
     */
    private String normalizeSql(String sql)
    {
        return sql.replaceAll("\\s+", " ").trim();
    }

    /**
     * 生成 Mapper XML 的 classpath 路径。
     * @param mapperName String，Mapper 名称
     * @return String，classpath XML 资源路径
     */
    private String resource(String mapperName)
    {
        return "mapper/flowable/" + mapperName + ".xml";
    }
}
