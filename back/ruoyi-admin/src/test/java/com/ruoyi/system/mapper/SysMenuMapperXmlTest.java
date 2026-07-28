package com.ruoyi.system.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.Reader;
import java.util.Locale;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.core.domain.entity.SysMenu;

/**
 * 系统菜单权限 Mapper 的有效主数据门禁契约测试。
 */
class SysMenuMapperXmlTest
{
    /**
     * 验证按用户查询权限时同时过滤无效菜单、角色和用户，防止旧 Token 绕过停用或删除状态。
     *
     * @return void，无返回值；XML 无法解析或缺少任一有效状态条件时测试失败
     * @throws Exception 读取 Mapper XML 资源失败
     */
    @Test
    void filtersInactiveOrDeletedUsersFromCurrentPermissions() throws Exception
    {
        String resource = "mapper/system/SysMenuMapper.xml";
        Configuration configuration = new Configuration();
        // 生产启动由 MyBatis 扫描实体别名；独立 XML 契约测试需注册同一正式实体映射。
        configuration.getTypeAliasRegistry().registerAlias("SysMenu", SysMenu.class);
        try (Reader reader = Resources.getResourceAsReader(resource))
        {
            XMLMapperBuilder mapperBuilder = new XMLMapperBuilder(
                    reader, configuration, resource, configuration.getSqlFragments());
            mapperBuilder.parse();
        }

        // 从 MyBatis 最终注册的 SQL 校验门禁，避免仅对 XML 文本做脆弱的格式匹配。
        MappedStatement statement = configuration.getMappedStatement(
                SysMenuMapper.class.getName() + ".selectMenuPermsByUserId");
        String normalizedSql = statement.getBoundSql(7L).getSql()
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);

        assertThat(normalizedSql)
                .contains("left join sys_user u on u.user_id = ur.user_id")
                .contains("m.status = '0'")
                .contains("r.status = '0'")
                .contains("r.del_flag = '0'")
                .contains("u.status = '0'")
                .contains("u.del_flag = '0'")
                .contains("ur.user_id = ?");
    }
}
