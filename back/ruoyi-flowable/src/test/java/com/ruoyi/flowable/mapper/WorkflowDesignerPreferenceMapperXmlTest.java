package com.ruoyi.flowable.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import com.ruoyi.flowable.domain.WfDesignerPreference;

class WorkflowDesignerPreferenceMapperXmlTest
{
    private static final String RESOURCE =
            "mapper/flowable/WfDesignerPreferenceMapper.xml";

    /**
     * 验证偏好 Mapper 可解析且查询严格按当前用户主键隔离。
     * @return void，Mapper 缺失或查询可能跨用户时测试失败
     * @throws Exception Mapper XML 无法读取时测试失败
     */
    @Test
    void selectsPreferenceByExactUserId() throws Exception
    {
        Configuration configuration = parseMapper();
        String sql = render(configuration.getMappedStatement(
                WfDesignerPreferenceMapper.class.getName() + ".selectByUserId"),
                Map.of("userId", 7L));

        assertThat(sql).contains("from wf_designer_preference", "where user_id = ?")
                .doesNotContain("${");
    }

    /**
     * 验证偏好写入使用单语句完整 upsert，不存在先查后写竞态或局部字段漂移。
     * @return void，upsert 未覆盖全部持久化字段时测试失败
     * @throws Exception Mapper XML 无法读取时测试失败
     */
    @Test
    void upsertsCompletePreferenceAtomically() throws Exception
    {
        Configuration configuration = parseMapper();
        WfDesignerPreference preference = new WfDesignerPreference();
        preference.setUserId(7L);
        preference.setTheme("DARK");
        preference.setGridEnabled(true);
        preference.setMinimapEnabled(true);
        preference.setLintEnabled(true);
        preference.setTokenSimulationEnabled(false);
        preference.setPropertiesCollapsed(false);
        String sql = render(configuration.getMappedStatement(
                WfDesignerPreferenceMapper.class.getName() + ".upsert"),
                Map.of("preference", preference));

        assertThat(sql).contains(
                "insert into wf_designer_preference",
                "on duplicate key update",
                "theme = values(theme)",
                "grid_enabled = values(grid_enabled)",
                "minimap_enabled = values(minimap_enabled)",
                "lint_enabled = values(lint_enabled)",
                "token_simulation_enabled = values(token_simulation_enabled)",
                "properties_collapsed = values(properties_collapsed)")
                .doesNotContain("${");
    }

    /**
     * 解析正式偏好 Mapper XML。
     * @return Configuration，包含偏好 statements 的 MyBatis 配置
     * @throws Exception XML 读取或解析失败
     */
    private Configuration parseMapper() throws Exception
    {
        Configuration configuration = new Configuration();
        try (InputStream input = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(RESOURCE))
        {
            assertThat(input).isNotNull();
            new XMLMapperBuilder(input, configuration, RESOURCE,
                    configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    /**
     * 渲染并规范指定 Mapper statement 的 SQL。
     * @param statement MappedStatement，待渲染语句
     * @param parameters Map&lt;String,Object&gt;，安全代表参数
     * @return String，单空格小写 SQL
     */
    private String render(MappedStatement statement, Map<String, Object> parameters)
    {
        return statement.getBoundSql(new HashMap<>(parameters)).getSql()
                .replaceAll("\\s+", " ").trim().toLowerCase();
    }
}
