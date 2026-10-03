package com.ruoyi.web.controller;

import java.sql.Statement;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.system.mapper.SysUserMapper;
import com.ruoyi.system.service.impl.SysUserServiceImpl;
import com.ruoyi.web.controller.system.SysProfileController;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

/** A profile request already in flight must not undo a later committed account change. */
class ProfileIdentityMysqlTest
{
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void oldProfileRequestPreservesCommittedIdentityAndPassword(boolean disabled) throws Exception
    {
        String url = System.getenv("WORKFLOW_MYSQL_TEST_URL");
        assumeTrue(url != null, "WORKFLOW_MYSQL_TEST_URL required");
        assertTrue(url.matches("jdbc:mysql://[^/]+/[^?]*_it(?:\\?.*)?"), "Use an isolated _it database");
        var dataSource = new DriverManagerDataSource(url, System.getenv("WORKFLOW_MYSQL_TEST_USERNAME"),
                System.getenv("WORKFLOW_MYSQL_TEST_PASSWORD"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setTypeAliasesPackage("com.ruoyi.common.core.domain.entity");
        factory.setMapperLocations(new ClassPathResource("mapper/system/SysUserMapper.xml"));
        SysUserMapper mapper = new SqlSessionTemplate(factory.getObject()).getMapper(SysUserMapper.class);
        SysUserServiceImpl users = spy(new SysUserServiceImpl());
        ReflectionTestUtils.setField(users, "userMapper", mapper);
        SysProfileController controller = new SysProfileController();
        ReflectionTestUtils.setField(controller, "userService", users);
        TokenService tokens = mock(TokenService.class);
        ReflectionTestUtils.setField(controller, "tokenService", tokens);
        String name = "b01_profile_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("insert into sys_user(user_name,nick_name,status,del_flag,dept_id,password)"
                    + " values (?,?,'0','0',100,'old-password')", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, name);
            statement.setString(2, name);
            return statement;
        }, key);
        long userId = key.getKey().longValue();
        String phone = "p" + (userId % 10_000_000_000L);
        CountDownLatch requestReady = new CountDownLatch(1);
        CountDownLatch identityCommitted = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try
        {
            LoginUser oldRequest = new LoginUser(userId, 100L, mapper.selectUserById(userId), Set.of());
            oldRequest.setToken(name);
            doAnswer(invocation -> {
                requestReady.countDown();
                assertTrue(identityCommitted.await(10, TimeUnit.SECONDS));
                return invocation.callRealMethod();
            }).when(users).updateUserProfile(any());
            var response = executor.submit(() -> {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        oldRequest, null, oldRequest.getAuthorities()));
                try
                {
                    SysUser profile = new SysUser();
                    profile.setNickName("updated profile");
                    profile.setEmail(name + "@example.test");
                    profile.setPhonenumber(phone);
                    profile.setSex("1");
                    return controller.updateProfile(profile);
                }
                finally
                {
                    SecurityContextHolder.clearContext();
                }
            });
            assertTrue(requestReady.await(10, TimeUnit.SECONDS));
            transaction.executeWithoutResult(status -> jdbc.update(
                    "update sys_user set status = ?, dept_id = 101, password = 'new-password' where user_id = ?",
                    disabled ? "1" : "0", userId));
            identityCommitted.countDown();
            assertEquals(200, response.get(10, TimeUnit.SECONDS).get("code"));
            SysUser current = mapper.selectUserById(userId);
            assertAll(
                    () -> assertEquals(disabled ? "1" : "0", current.getStatus(), "profile must preserve account status"),
                    () -> assertEquals(101L, current.getDeptId(), "profile must preserve department"),
                    () -> assertEquals("new-password", current.getPassword(), "profile must preserve reset password"),
                    () -> assertEquals("updated profile", current.getNickName()),
                    () -> assertEquals(name + "@example.test", current.getEmail()),
                    () -> assertEquals(phone, current.getPhonenumber()),
                    () -> assertEquals("1", current.getSex()));
            SysPermissionService permissions = new SysPermissionService();
            ReflectionTestUtils.setField(permissions, "userService", users);
            LoginUser refreshed = permissions.loadIdentity(userId);
            if (disabled)
            {
                assertNull(refreshed);
            }
            else
            {
                assertEquals(101L, refreshed.getDeptId());
            }
            verify(tokens).setLoginUser(oldRequest);
        }
        finally
        {
            identityCommitted.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            jdbc.update("delete from sys_user where user_id = ?", userId);
        }
    }
}
