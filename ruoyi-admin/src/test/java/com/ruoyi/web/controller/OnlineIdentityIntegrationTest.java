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
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
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
import com.ruoyi.system.mapper.SysRoleMenuMapper;
import com.ruoyi.system.mapper.SysUserRoleMapper;
import com.ruoyi.system.service.ISysMenuService;
import com.ruoyi.system.service.ISysRoleService;
import com.ruoyi.system.service.impl.SysMenuServiceImpl;
import com.ruoyi.system.service.impl.SysRoleServiceImpl;
import com.ruoyi.system.service.impl.SysUserServiceImpl;
import com.ruoyi.web.controller.system.SysMenuController;
import com.ruoyi.web.controller.system.SysRoleController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    @Test
    void menuDeletionAndRoleEditThenBatchGrantCannotLeaveAnAuthorizedDeletedMenu() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.jdbc.update("delete from sys_role_menu where role_id = ?", f.roleId);
            f.jdbc.update("delete from sys_user_role where user_id = ?", f.userId);
            String first = f.login();
            String second = f.login();
            CountDownLatch referencesRead = new CountDownLatch(1);
            CountDownLatch resumeDeletion = new CountDownLatch(1);
            var executor = Executors.newFixedThreadPool(2);
            doAnswer(invocation -> {
                Object result = invocation.callRealMethod();
                assertEquals(0, result);
                referencesRead.countDown();
                assertTrue(resumeDeletion.await(10, TimeUnit.SECONDS));
                return result;
            }).when(f.roleMenus).checkMenuExistRole(f.menuId);
            try
            {
                var deletion = executor.submit(f::removeMenu);
                assertTrue(referencesRead.await(10, TimeUnit.SECONDS));
                var grant = executor.submit(() -> {
                    assertEquals(200, f.editRoleMenu().get("code"));
                    return f.grantRole();
                });
                // 旧实现的授予会在删除前完成并刷新 Token；修复后应等待删除持有的菜单行锁。
                try { grant.get(500, TimeUnit.MILLISECONDS); } catch (TimeoutException expectedLock) { }
                resumeDeletion.countDown();
                assertEquals(200, deletion.get(10, TimeUnit.SECONDS).get("code"));
                try { grant.get(10, TimeUnit.SECONDS); }
                catch (java.util.concurrent.ExecutionException rejected)
                {
                    assertEquals(404, assertInstanceOf(com.ruoyi.common.exception.ServiceException.class,
                            rejected.getCause()).getCode());
                }
                assertNull(f.menus.selectMenuById(f.menuId));
                assertTrue(f.request(first).getPermissions().isEmpty(), "已删除菜单不能保留在线授权");
                assertTrue(f.request(second).getPermissions().isEmpty(), "全部 Token 必须与删除后的事实一致");
                assertEquals(0, f.jdbc.queryForObject("select count(*) from sys_role_menu where menu_id = ?",
                        Integer.class, f.menuId), "授予不能提交不存在菜单的引用");
            }
            finally
            {
                resumeDeletion.countDown(); executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void roleMenuGrantBeforeDeletionMakesDeletionWaitAndReject() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.jdbc.update("delete from sys_role_menu where role_id = ?", f.roleId);
            f.jdbc.update("delete from sys_user_role where user_id = ?", f.userId);
            String first = f.login();
            String second = f.login();
            CountDownLatch associationWritten = new CountDownLatch(1);
            CountDownLatch commitGrant = new CountDownLatch(1);
            var executor = Executors.newFixedThreadPool(2);
            doAnswer(invocation -> {
                Object rows = invocation.callRealMethod();
                associationWritten.countDown();
                assertTrue(commitGrant.await(10, TimeUnit.SECONDS));
                return rows;
            }).when(f.roleMenus).batchRoleMenu(anyList());
            try
            {
                var grant = executor.submit(() -> {
                    assertEquals(200, f.editRoleMenu().get("code"));
                    return f.grantRole();
                });
                assertTrue(associationWritten.await(10, TimeUnit.SECONDS));
                var deletion = executor.submit(f::removeMenu);
                try { deletion.get(500, TimeUnit.MILLISECONDS); } catch (TimeoutException expectedLock) { }
                commitGrant.countDown();
                assertEquals(200, grant.get(10, TimeUnit.SECONDS).get("code"));
                assertEquals(601, deletion.get(10, TimeUnit.SECONDS).get("code"));
                assertNotNull(f.menus.selectMenuById(f.menuId));
                assertEquals(Set.of("b01:before"), f.request(first).getPermissions());
                assertEquals(Set.of("b01:before"), f.request(second).getPermissions());
            }
            finally
            {
                commitGrant.countDown(); executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void roleCreationAndEditRejectAlreadyDeletedMenusWithoutPartialWrites(boolean create) throws Exception
    {
        try (Fixture f = new Fixture())
        {
            f.jdbc.update("delete from sys_role_menu where role_id = ?", f.roleId);
            assertEquals(200, f.removeMenu().get("code"));
            SysRole update = f.roles.selectRoleById(f.roleId);
            update.setMenuIds(new Long[] {f.menuId});
            update.setRemark("must not commit");
            if (create)
            {
                update.setRoleId(null);
                update.setRoleName(update.getRoleName() + "_new");
                update.setRoleKey(update.getRoleKey() + "_new");
            }
            try
            {
                var failure = assertThrows(com.ruoyi.common.exception.ServiceException.class,
                        () -> f.asAdmin(() -> create ? f.roleController().add(update) : f.roleController().edit(update)));
                assertEquals(404, failure.getCode());
                assertEquals(0, f.jdbc.queryForObject("select count(*) from sys_role_menu where menu_id = ?",
                        Integer.class, f.menuId));
                if (create)
                    assertEquals(0, f.jdbc.queryForObject("select count(*) from sys_role where role_key = ?",
                            Integer.class, update.getRoleKey()));
                else assertNotEquals("must not commit", f.roles.selectRoleById(f.roleId).getRemark());
            }
            finally
            {
                if (create && update.getRoleId() != null)
                {
                    f.jdbc.update("delete from sys_role_menu where role_id = ?", update.getRoleId());
                    f.jdbc.update("delete from sys_role where role_id = ?", update.getRoleId());
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void emptyMenuArrayStillAllowsRoleCreationAndEdit(boolean create) throws Exception
    {
        try (Fixture f = new Fixture())
        {
            SysRole role = f.roles.selectRoleById(f.roleId);
            role.setMenuIds(new Long[0]);
            if (create)
            {
                role.setRoleId(null);
                role.setRoleName(role.getRoleName() + "_empty");
                role.setRoleKey(role.getRoleKey() + "_empty");
            }
            try
            {
                assertEquals(200, f.asAdmin(() -> create ? f.roleController().add(role)
                        : f.roleController().edit(role)).get("code"));
                assertNotNull(f.roles.selectRoleById(role.getRoleId()));
                assertEquals(0, f.jdbc.queryForObject("select count(*) from sys_role_menu where role_id = ?",
                        Integer.class, role.getRoleId()));
            }
            finally
            {
                if (create && role.getRoleId() != null)
                    f.jdbc.update("delete from sys_role where role_id = ?", role.getRoleId());
            }
        }
    }

    @Test
    void menuSortKeepsIdValuePairsAndLastValueForDuplicateIds() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            Long second = f.insert("insert into sys_menu(menu_name,menu_type,status,parent_id,order_num) "
                    + "values ('b01_sort','F','0',0,0)");
            try
            {
                f.transactionalMenus.updateMenuSort(new String[] {second.toString(), f.menuId.toString(), second.toString()},
                        new String[] {"9", "5", "2"});
                assertEquals(5, f.menus.selectMenuById(f.menuId).getOrderNum());
                assertEquals(2, f.menus.selectMenuById(second).getOrderNum());
            }
            finally
            {
                f.jdbc.update("delete from sys_menu where menu_id = ?", second);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void menuDeletionRefreshWaitsForCommitAndRollbackKeepsSessions(boolean rollback) throws Exception
    {
        try (Fixture f = new Fixture())
        {
            String first = f.login();
            String second = f.login();
            // Controller 已取受影响用户后，事务内先移除引用；删除后的刷新仍服从整体事务结果。
            doAnswer(invocation -> {
                f.jdbc.update("delete from sys_role_menu where role_id = ?", f.roleId);
                return invocation.callRealMethod();
            }).when(f.menus).deleteMenuById(f.menuId);
            f.transaction.executeWithoutResult(status -> {
                assertEquals(200, f.removeMenu().get("code"));
                assertNull(f.menus.selectMenuById(f.menuId));
                assertEquals(Set.of("b01:before"), f.request(first).getPermissions());
                if (rollback) status.setRollbackOnly();
            });
            assertEquals(rollback, f.menus.selectMenuById(f.menuId) != null);
            assertEquals(rollback ? 1 : 0, f.jdbc.queryForObject("select count(*) from sys_role_menu where role_id = ?",
                    Integer.class, f.roleId));
            assertEquals(rollback ? Set.of("b01:before") : Set.of(), f.request(first).getPermissions());
            assertEquals(rollback ? Set.of("b01:before") : Set.of(), f.request(second).getPermissions());
        }
    }

    private static final class Fixture implements AutoCloseable
    {
        final JdbcTemplate jdbc;
        final TransactionTemplate transaction;
        final SysUserMapper userMapper;
        final SysRoleMapper roles;
        final SysRoleMenuMapper roleMenus;
        final SysUserServiceImpl users;
        final SysMenuServiceImpl menus;
        final ISysMenuService transactionalMenus;
        final ISysRoleService roleService;
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
            factory.setTypeAliasesPackage("com.ruoyi.common.core.domain.entity,com.ruoyi.system.domain");
            factory.setMapperLocations(new ClassPathResource("mapper/system/SysUserMapper.xml"),
                    new ClassPathResource("mapper/system/SysMenuMapper.xml"), new ClassPathResource("mapper/system/SysRoleMapper.xml"),
                    new ClassPathResource("mapper/system/SysRoleMenuMapper.xml"), new ClassPathResource("mapper/system/SysUserRoleMapper.xml"));
            SqlSessionTemplate sql = new SqlSessionTemplate(factory.getObject());
            userMapper = sql.getMapper(SysUserMapper.class);
            roles = sql.getMapper(SysRoleMapper.class);
            roleMenus = spy(sql.getMapper(SysRoleMenuMapper.class));
            users = spy(new SysUserServiceImpl());
            ReflectionTestUtils.setField(users, "userMapper", userMapper);
            menus = spy(new SysMenuServiceImpl());
            ReflectionTestUtils.setField(menus, "menuMapper", sql.getMapper(SysMenuMapper.class));
            ReflectionTestUtils.setField(menus, "roleMenuMapper", roleMenus);
            transactionalMenus = transactional(menus, manager);
            SysRoleServiceImpl roleTarget = new SysRoleServiceImpl();
            ReflectionTestUtils.setField(roleTarget, "roleMapper", roles);
            ReflectionTestUtils.setField(roleTarget, "menuMapper", sql.getMapper(SysMenuMapper.class));
            ReflectionTestUtils.setField(roleTarget, "roleMenuMapper", roleMenus);
            ReflectionTestUtils.setField(roleTarget, "userRoleMapper", sql.getMapper(SysUserRoleMapper.class));
            roleService = transactional(roleTarget, manager);
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
            return asAdmin(() -> menuController().edit(menu));
        }

        AjaxResult removeMenu()
        {
            return asAdmin(() -> menuController().remove(menuId));
        }

        private SysMenuController menuController()
        {
            var controller = new SysMenuController();
            ReflectionTestUtils.setField(controller, "menuService", transactionalMenus);
            ReflectionTestUtils.setField(controller, "userService", users);
            ReflectionTestUtils.setField(controller, "tokenService", tokens);
            return controller;
        }

        AjaxResult editRoleMenu()
        {
            SysRole update = roles.selectRoleById(roleId);
            update.setMenuIds(new Long[] {menuId});
            return asAdmin(() -> roleController().edit(update));
        }

        AjaxResult grantRole()
        {
            return asAdmin(() -> roleController().selectAuthUserAll(roleId, new Long[] {userId}));
        }

        private SysRoleController roleController()
        {
            var controller = new SysRoleController();
            ReflectionTestUtils.setField(controller, "roleService", roleService);
            ReflectionTestUtils.setField(controller, "userService", users);
            ReflectionTestUtils.setField(controller, "tokenService", tokens);
            return controller;
        }

        private <T> T transactional(T target, DataSourceTransactionManager manager)
        {
            ProxyFactory proxy = new ProxyFactory(target);
            proxy.setProxyTargetClass(true);
            proxy.setExposeProxy(true);
            proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
            return (T) proxy.getProxy();
        }

        private AjaxResult asAdmin(Supplier<AjaxResult> operation)
        {
            SysUser actor = new SysUser(1L);
            actor.setUserName("admin");
            LoginUser login = new LoginUser(1L, 100L, actor, Set.of("*:*:*"));
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(login, null, login.getAuthorities()));
            try
            {
                return operation.get();
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
