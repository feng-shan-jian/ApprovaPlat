package com.ruoyi.framework.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.core.domain.entity.SysDept;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.system.mapper.SysUserMapper;

/**
 * 预置管理员原子初始化 Mapper SQL 契约测试。
 */
class SysUserMapperBootstrapContractTest
{
    /**
     * 使用真实 MyBatis 解析器验证初始化 SQL 同时约束固定身份、密码标记、停用状态和逻辑删除标志。
     * @return void，Mapper XML 无法解析或原子更新门禁发生漂移时测试失败
     * @throws Exception Mapper 资源读取失败
     */
    @Test
    void keepsBootstrapMarkerAndStatusTransitionAtomic() throws Exception
    {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("SysUser", SysUser.class);
        configuration.getTypeAliasRegistry().registerAlias("SysDept", SysDept.class);
        configuration.getTypeAliasRegistry().registerAlias("SysRole", SysRole.class);
        String resource = "mapper/system/SysUserMapper.xml";
        try (InputStream input = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resource))
        {
            assertThat(input).as("系统用户 Mapper XML 必须进入应用资源").isNotNull();
            new XMLMapperBuilder(input, configuration, resource,
                    configuration.getSqlFragments()).parse();
        }

        String statementId = SysUserMapper.class.getName()
                + ".initializeBootstrapAdminCredential";
        BoundSql boundSql = configuration.getMappedStatement(statementId).getBoundSql(Map.of(
                "userId", 1L,
                "userName", "admin",
                "expectedPassword", AdminBootstrapInitializer.BOOTSTRAP_PASSWORD_MARKER,
                "newPassword", "$2a$10$test-only-hash"));
        String normalizedSql = normalizeSql(boundSql.getSql());

        assertThat(normalizedSql).contains(
                "update sys_user",
                "set password = ?",
                "status = '0'",
                "pwd_update_date = sysdate()",
                "update_by = 'bootstrap'",
                "where user_id = ?",
                "and user_name = ?",
                "and password = ?",
                "and status = '1'",
                "and del_flag = '0'");
        assertThat(boundSql.getParameterMappings())
                .extracting(mapping -> mapping.getProperty())
                .containsExactly("newPassword", "userId", "userName", "expectedPassword");
    }

    /**
     * 将 MyBatis 生成 SQL 的空白折叠为便于断言的单行小写文本。
     * @param sql String，MyBatis 生成的参数化 SQL
     * @return String，空白折叠后的小写 SQL
     */
    private String normalizeSql(String sql)
    {
        return sql.replaceAll("\\s+", " ").trim().toLowerCase();
    }
}
