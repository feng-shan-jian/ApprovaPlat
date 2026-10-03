package com.ruoyi.system.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.system.mapper.SysMenuMapper;
import com.ruoyi.system.service.impl.SysMenuServiceImpl;

/** 实际主数据 Mapper 与目录服务验证有效用户、角色和菜单关系，无工作流/Web 装配。 */
class SysPermissionEvaluatorTest
{
    private static final String PERMISSION = "workflow:process:state";
    private JdbcTemplate jdbc;
    private SysPermissionEvaluator evaluator;
    private LoginUser login;

    @BeforeEach
    void setUp() throws Exception
    {
        var source = new DriverManagerDataSource("jdbc:h2:mem:permission-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("create table sys_user(user_id bigint primary key, status char(1), del_flag char(1))");
        jdbc.execute("create table sys_role(role_id bigint primary key, status char(1), del_flag char(1))");
        jdbc.execute("create table sys_menu(menu_id bigint primary key, status char(1), perms varchar(200))");
        jdbc.execute("create table sys_role_menu(role_id bigint, menu_id bigint)");
        jdbc.execute("create table sys_user_role(user_id bigint, role_id bigint)");
        jdbc.update("insert into sys_user values(7, '0', '0')");
        jdbc.update("insert into sys_role values(2, '0', '0')");
        jdbc.update("insert into sys_menu values(3, '0', ?)", PERMISSION);
        jdbc.update("insert into sys_role_menu values(2, 3)");
        jdbc.update("insert into sys_user_role values(7, 2)");
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.ruoyi.common.core.domain.entity");
        factory.setMapperLocations(new ClassPathResource("mapper/system/SysMenuMapper.xml"));
        var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(SysMenuMapper.class);
        var menus = new SysMenuServiceImpl();
        ReflectionTestUtils.setField(menus, "menuMapper", mapper);
        evaluator = new SysPermissionEvaluator(menus);
        var user = new SysUser();
        user.setUserId(7L);
        login = new LoginUser(7L, 10L, user, Set.of(PERMISSION));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(login, null, List.of()));
    }

    @AfterEach
    void tearDown()
    {
        SecurityContextHolder.clearContext();
        if (jdbc != null) jdbc.execute("shutdown");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "update sys_user set status = '1' where user_id = 7",
            "update sys_user set del_flag = '2' where user_id = 7",
            "update sys_role set status = '1' where role_id = 2",
            "update sys_role set del_flag = '2' where role_id = 2",
            "update sys_menu set status = '1' where menu_id = 3",
            "delete from sys_role_menu where role_id = 2",
            "delete from sys_user_role where user_id = 7" })
    void revocationOfAnyRequiredMasterDataRelationRejectsExistingToken(String revoke)
    {
        assertThat(evaluator.hasPermi(PERMISSION)).isTrue();
        jdbc.update(revoke);
        assertThat(evaluator.hasPermi(PERMISSION)).isFalse();
    }

    @Test
    void newGrantRequiresTokenAndDoesNotReplaceCurrentLoginWithAnotherIdentity()
    {
        login.setPermissions(Set.of("workflow:process:query"));
        assertThat(evaluator.hasPermi(PERMISSION)).isFalse();
        login.setPermissions(Set.of(PERMISSION));
        assertThat(evaluator.hasPermi(PERMISSION)).isTrue();
        login.setUserId(8L);
        assertThat(evaluator.hasPermi(PERMISSION)).isFalse();
    }
}
