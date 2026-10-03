package com.ruoyi.web.controller;

import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.entity.SysMenu;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.framework.config.RedisConfig;
import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.system.mapper.SysMenuMapper;
import com.ruoyi.system.mapper.SysRoleMapper;
import com.ruoyi.system.mapper.SysUserMapper;
import com.ruoyi.system.service.impl.SysMenuServiceImpl;
import com.ruoyi.system.service.impl.SysUserServiceImpl;
import com.ruoyi.web.controller.system.SysMenuController;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

/** Production MyBatis identity reads, real transactions, JWTs and isolated Redis conditional writes. */
class OnlineIdentityIntegrationTest
{
    @Test
    void committedAuthorityChangesReachEverySessionAndRollbackDoesNot() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            String first = f.login();
            String second = f.login();
            LoginUser oldRequest = f.request(first);
            assertEquals(Set.of("b01:before"), oldRequest.getPermissions());
            assertEquals(List.of(f.userId), f.users.selectUserIdsByRoleIds(new Long[] { f.roleId }));
            assertEquals(List.of(f.userId), f.users.selectUserIdsByMenuId(f.menuId));

            f.transaction.executeWithoutResult(status -> {
                f.jdbc.update("update sys_role set data_scope = '3' where role_id = ?", f.roleId);
                f.tokens.refreshUsersAfterCommit(List.of(f.userId));
                assertEquals("1", f.request(first).getUser().getRoles().get(0).getDataScope());
            });
            assertEquals("3", f.request(first).getUser().getRoles().get(0).getDataScope());
            assertEquals("3", f.request(second).getUser().getRoles().get(0).getDataScope());

            f.transaction.executeWithoutResult(status -> {
                f.jdbc.update("update sys_menu set perms = 'b01:rolled-back' where menu_id = ?", f.menuId);
                f.tokens.refreshUsersAfterCommit(List.of(f.userId));
                status.setRollbackOnly();
            });
            assertEquals(Set.of("b01:before"), f.request(first).getPermissions());
            assertEquals("b01:before", f.jdbc.queryForObject("select perms from sys_menu where menu_id = ?", String.class, f.menuId));

            f.transaction.executeWithoutResult(status -> {
                f.jdbc.update("update sys_menu set perms = 'b01:after' where menu_id = ?", f.menuId);
                f.tokens.refreshUsersAfterCommit(f.users.selectUserIdsByMenuId(f.menuId));
            });
            f.tokens.refreshToken(oldRequest);
            f.tokens.setLoginUser(oldRequest);
            assertEquals(Set.of("b01:after"), f.request(first).getPermissions());
            assertEquals(Set.of("b01:after"), f.request(second).getPermissions());

            f.transaction.executeWithoutResult(status -> {
                SysRole role = new SysRole();
                role.setRoleId(f.roleId);
                role.setStatus("1");
                f.roles.updateRole(role);
                f.tokens.refreshUsersAfterCommit(f.users.selectUserIdsByRoleIds(new Long[] { f.roleId }));
            });
            assertTrue(f.request(first).getPermissions().isEmpty());
            assertEquals("1", f.request(first).getUser().getRoles().get(0).getStatus());
            f.jdbc.update("update sys_role set status = '0' where role_id = ?", f.roleId);
            f.jdbc.update("delete from sys_user_role where user_id = ? and role_id = ?", f.userId, f.roleId);
            f.tokens.refreshUsersAfterCommit(List.of(f.userId));
            assertTrue(f.request(first).getUser().getRoles().isEmpty());
            assertTrue(f.request(second).getPermissions().isEmpty());
            f.jdbc.update("insert into sys_user_role(user_id, role_id) values (?, ?)", f.userId, f.roleId);
            f.tokens.refreshUsersAfterCommit(List.of(f.userId));
            assertEquals(Set.of("b01:after"), f.request(first).getPermissions());
            assertEquals(Set.of("b01:after"), f.request(second).getPermissions());

            f.transaction.executeWithoutResult(status -> {
                SysUser user = new SysUser();
                user.setUserId(f.userId);
                user.setStatus("1");
                f.userMapper.updateUser(user);
                f.tokens.refreshUsersAfterCommit(List.of(f.userId));
            });
            assertNull(f.request(first));
            assertNull(f.request(second));
            f.tokens.refreshToken(oldRequest);
            f.tokens.setLoginUser(oldRequest);
            assertNull(f.request(first));
        }
    }

    @Test
    void identityReadUsesOneDatabaseSnapshotAndRedisRejectsStaleOrDeletedValues() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            doAnswer(invocation -> {
                SysUser user = f.userMapper.selectUserById(f.userId);
                CompletableFuture.runAsync(() -> f.jdbc.update(
                        "update sys_menu set perms = 'b01:concurrent' where menu_id = ?", f.menuId)).get(10, TimeUnit.SECONDS);
                return user;
            }).when(f.users).selectUserById(f.userId);
            assertEquals(Set.of("b01:before"), f.permissions.loadIdentity(f.userId).getPermissions());
            doCallRealMethod().when(f.users).selectUserById(f.userId);
            assertEquals(Set.of("b01:concurrent"), f.permissions.loadIdentity(f.userId).getPermissions());

            String jwt = f.login();
            LoginUser session = f.request(jwt);
            String key = CacheConstants.LOGIN_TOKEN_KEY + session.getToken();
            byte[] old = f.cache.getCacheObjectBytes(key);
            f.jdbc.update("update sys_menu set perms = 'b01:latest' where menu_id = ?", f.menuId);
            f.tokens.refreshUsersAfterCommit(List.of(f.userId));
            assertFalse(f.cache.compareAndSetCacheObject(key, old, session, 1800000L));
            assertEquals(Set.of("b01:latest"), f.request(jwt).getPermissions());
            assertTrue(f.cache.getExpire(key) > 0 && f.cache.getExpire(key) <= 1800);
            byte[] latest = f.cache.getCacheObjectBytes(key);
            f.cache.deleteObject(key);
            assertFalse(f.cache.compareAndSetCacheObject(key, latest, session, 1800000L));
            assertNull(f.request(jwt));

            String deletedJwt = f.login();
            f.jdbc.update("update sys_user set del_flag = '2' where user_id = ?", f.userId);
            f.tokens.refreshUsersAfterCommit(List.of(f.userId));
            assertNull(f.request(deletedJwt));
        }
    }

    @Test
    void concurrentMenuEditRestoringItsOriginalPermissionRefreshesEverySession() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            String first = f.login();
            String second = f.login();
            SysMenu restoreOriginal = f.menus.selectMenuById(f.menuId);
            SysMenu changePermission = f.menus.selectMenuById(f.menuId);
            changePermission.setPerms("b01:concurrent");
            CountDownLatch oldReadCompleted = new CountDownLatch(1);
            CountDownLatch concurrentEditCommitted = new CountDownLatch(1);
            var executor = Executors.newSingleThreadExecutor();
            doAnswer(invocation -> {
                // 旧实现已读取 before=P，此处暂缓 B 的写入，允许 A 完成 Q 的写入和 Token 刷新。
                oldReadCompleted.countDown();
                assertTrue(concurrentEditCommitted.await(10, TimeUnit.SECONDS));
                return invocation.callRealMethod();
            }).when(f.menus).updateMenu(same(restoreOriginal));
            try
            {
                var restoring = executor.submit(() -> f.editMenu(restoreOriginal));
                assertTrue(oldReadCompleted.await(10, TimeUnit.SECONDS));
                assertEquals(200, f.editMenu(changePermission).get("code"));
                assertEquals("b01:concurrent", f.jdbc.queryForObject(
                        "select perms from sys_menu where menu_id = ?", String.class, f.menuId));
                assertEquals(Set.of("b01:concurrent"), f.request(first).getPermissions());
                assertEquals(Set.of("b01:concurrent"), f.request(second).getPermissions());
                concurrentEditCommitted.countDown();
                assertEquals(200, restoring.get(10, TimeUnit.SECONDS).get("code"));

                assertEquals("b01:before", f.jdbc.queryForObject(
                        "select perms from sys_menu where menu_id = ?", String.class, f.menuId));
                assertEquals(Set.of("b01:before"), f.request(first).getPermissions());
                assertEquals(Set.of("b01:before"), f.request(second).getPermissions());
            }
            finally
            {
                concurrentEditCommitted.countDown();
                executor.shutdown();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    private static final class Fixture implements AutoCloseable
    {
        final JdbcTemplate jdbc;
        final TransactionTemplate transaction;
        final SysUserMapper userMapper;
        final SysRoleMapper roles;
        final SysUserServiceImpl users;
        final SysMenuServiceImpl menus;
        final SysPermissionService permissions;
        final RedisCache cache = new RedisCache();
        final TokenService tokens;
        final LettuceConnectionFactory redis;
        final List<String> sessionKeys = new ArrayList<>();
        Long userId;
        Long roleId;
        Long menuId;

        Fixture() throws Exception
        {
            String url = System.getenv("WORKFLOW_MYSQL_TEST_URL");
            String host = System.getenv("WORKFLOW_REDIS_TEST_HOST");
            assumeTrue(url != null && host != null, "WORKFLOW_MYSQL_TEST_URL and WORKFLOW_REDIS_TEST_HOST required");
            assertTrue(url.matches("jdbc:mysql://[^/]+/[^?]*_it(?:\\?.*)?"), "Use an isolated _it database");
            DriverManagerDataSource dataSource = new DriverManagerDataSource(url,
                    System.getenv("WORKFLOW_MYSQL_TEST_USERNAME"), System.getenv("WORKFLOW_MYSQL_TEST_PASSWORD"));
            jdbc = new JdbcTemplate(dataSource);
            DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
            transaction = new TransactionTemplate(manager);
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setTypeAliasesPackage("com.ruoyi.common.core.domain.entity");
            factory.setMapperLocations(new ClassPathResource("mapper/system/SysUserMapper.xml"),
                    new ClassPathResource("mapper/system/SysMenuMapper.xml"), new ClassPathResource("mapper/system/SysRoleMapper.xml"));
            SqlSessionTemplate sql = new SqlSessionTemplate(factory.getObject());
            userMapper = sql.getMapper(SysUserMapper.class);
            roles = sql.getMapper(SysRoleMapper.class);
            users = spy(new SysUserServiceImpl());
            ReflectionTestUtils.setField(users, "userMapper", userMapper);
            menus = spy(new SysMenuServiceImpl());
            ReflectionTestUtils.setField(menus, "menuMapper", sql.getMapper(SysMenuMapper.class));
            SysPermissionService raw = new SysPermissionService();
            ReflectionTestUtils.setField(raw, "userService", users);
            ReflectionTestUtils.setField(raw, "menuService", menus);
            ProxyFactory proxy = new ProxyFactory(raw);
            proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
            permissions = (SysPermissionService) proxy.getProxy();
            RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(host,
                    Integer.parseInt(System.getenv().getOrDefault("WORKFLOW_REDIS_TEST_PORT", "6379")));
            String password = System.getenv("WORKFLOW_REDIS_TEST_PASSWORD");
            if (password != null && !password.isBlank())
            {
                configuration.setPassword(password);
            }
            redis = new LettuceConnectionFactory(configuration);
            redis.afterPropertiesSet();
            redis.start();
            cache.redisTemplate = new RedisConfig().redisTemplate(redis);
            tokens = spy(new TokenService());
            ReflectionTestUtils.setField(tokens, "redisCache", cache);
            ReflectionTestUtils.setField(tokens, "permissionService", permissions);
            ReflectionTestUtils.setField(tokens, "expireTime", 30);
            ReflectionTestUtils.setField(tokens, "header", "Authorization");
            ReflectionTestUtils.setField(tokens, "secret", Base64.getEncoder().encodeToString(new byte[64]));
            tokens.validateSecret();
            doNothing().when(tokens).setUserAgent(any());
            try
            {
                String name = "b01_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                userId = insert("insert into sys_user(user_name,nick_name,status,del_flag,dept_id) values (?,?,'0','0',100)", name, name);
                roleId = insert("insert into sys_role(role_name,role_key,role_sort,data_scope,status,del_flag) values (?,?,1,'1','0','0')", name, name);
                menuId = insert("insert into sys_menu(menu_name,menu_type,perms,status,parent_id,order_num) values (?,'F','b01:before','0',0,0)", name);
                jdbc.update("insert into sys_user_role(user_id, role_id) values (?, ?)", userId, roleId);
                jdbc.update("insert into sys_role_menu(role_id, menu_id) values (?, ?)", roleId, menuId);
            }
            catch (Exception exception)
            {
                close();
                throw exception;
            }
        }

        private Long insert(String sql, Object... values)
        {
            GeneratedKeyHolder key = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                for (int i = 0; i < values.length; i++)
                {
                    statement.setObject(i + 1, values[i]);
                }
                return statement;
            }, key);
            return key.getKey().longValue();
        }

        String login()
        {
            LoginUser identity = permissions.loadIdentity(userId);
            String jwt = tokens.createToken(identity);
            sessionKeys.add(CacheConstants.LOGIN_TOKEN_KEY + identity.getToken());
            return jwt;
        }

        LoginUser request(String jwt)
        {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("Authorization", "Bearer " + jwt);
            return tokens.getLoginUser(request);
        }

        AjaxResult editMenu(SysMenu menu)
        {
            SysMenuController controller = new SysMenuController();
            ReflectionTestUtils.setField(controller, "menuService", menus);
            ReflectionTestUtils.setField(controller, "userService", users);
            ReflectionTestUtils.setField(controller, "tokenService", tokens);
            SysUser actor = new SysUser(1L);
            actor.setUserName("admin");
            LoginUser login = new LoginUser(1L, 100L, actor, Set.of("*:*:*"));
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(login, null, login.getAuthorities()));
            try
            {
                return controller.edit(menu);
            }
            finally
            {
                SecurityContextHolder.clearContext();
            }
        }

        @Override
        public void close()
        {
            try
            {
                sessionKeys.forEach(cache::deleteObject);
            }
            finally
            {
                redis.destroy();
                if (userId != null)
                {
                    jdbc.update("delete from sys_user_role where user_id = ?", userId);
                    jdbc.update("delete from sys_user where user_id = ?", userId);
                }
                if (roleId != null)
                {
                    jdbc.update("delete from sys_role_menu where role_id = ?", roleId);
                    jdbc.update("delete from sys_role where role_id = ?", roleId);
                }
                if (menuId != null)
                {
                    jdbc.update("delete from sys_menu where menu_id = ?", menuId);
                }
            }
        }
    }
}
