package com.ruoyi.framework.web.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.exception.base.BaseException;
import com.ruoyi.common.utils.spring.SpringUtils;
import com.ruoyi.framework.web.exception.GlobalExceptionHandler;
import com.ruoyi.framework.config.RedisConfig;
import com.ruoyi.framework.config.SecurityConfig;
import com.ruoyi.framework.config.properties.PermitAllUrlProperties;
import com.ruoyi.framework.security.filter.JwtAuthenticationTokenFilter;
import com.ruoyi.framework.security.handle.AuthenticationEntryPointImpl;
import com.ruoyi.framework.security.handle.LogoutSuccessHandlerImpl;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.system.service.ISysLogininforService;
import com.ruoyi.system.service.ISysMenuService;
import com.ruoyi.system.service.ISysRoleService;
import com.ruoyi.system.service.ISysUserService;
import com.ruoyi.web.controller.monitor.SysLogininforController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Real Spring Security configuration/manager/provider, production identity loader, login service,
 * retry service and RedisConfig against an explicitly supplied isolated Redis-compatible server.
 * Identity/permissions/config, token output and audit scheduling are controlled external boundaries.
 */
@ResourceLock("SpringUtils")
@Tag("login-redis")
class LoginAuthenticationRedisIT
{
    private static final String PASSWORD = "C14-real-password";
    private AnnotationConfigWebApplicationContext context;
    private RedisCache cache;
    private SysLoginService login;
    private String username;
    private String key;
    private SysUser user;
    private Object previousBeanFactory;
    private Object previousApplicationContext;

    @BeforeEach
    void start()
    {
        previousBeanFactory = ReflectionTestUtils.getField(SpringUtils.class, "beanFactory");
        previousApplicationContext = ReflectionTestUtils.getField(SpringUtils.class, "applicationContext");
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("login-test",
                Map.of("user.password.maxRetryCount", "5", "user.password.lockTime", "1", "token.expireTime", "30")));
        context.register(LoginConfiguration.class);
        context.refresh();
        cache = context.getBean(RedisCache.class);
        try (var connection = context.getBean(LettuceConnectionFactory.class).getConnection())
        {
            assertThat(connection.ping()).isEqualTo("PONG");
        }
        login = context.getBean(SysLoginService.class);
        username = "c14_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        key = CacheConstants.PWD_ERR_CNT_KEY + username;
        user = new SysUser();
        user.setUserId(42L);
        user.setDeptId(10L);
        user.setUserName(username);
        user.setStatus("0");
        user.setDelFlag("0");
        user.setPassword(context.getBean(BCryptPasswordEncoder.class).encode(PASSWORD));
        when(context.getBean(ISysUserService.class).selectUserByUserName(username)).thenReturn(user);
        when(context.getBean(SysPermissionService.class).getMenuPermission(user)).thenReturn(Set.of("test:read"));
        when(context.getBean(TokenService.class).createToken(any(LoginUser.class))).thenReturn("token-boundary");
    }

    @AfterEach
    void stop()
    {
        if (cache != null && key != null)
        {
            cache.deleteObject(key);
        }
        if (context != null)
        {
            context.close();
        }
        ReflectionTestUtils.setField(SpringUtils.class, "beanFactory", previousBeanFactory);
        ReflectionTestUtils.setField(SpringUtils.class, "applicationContext", previousApplicationContext);
        RequestContextHolder.resetRequestAttributes();
        LocaleContextHolder.resetLocaleContext();
        SecurityContextHolder.clearContext();
    }

    @Test
    void successfulLoginUsesOneRealBcryptComparisonAndClearsLegacyInteger()
    {
        cache.setCacheObject(key, 2, 1, TimeUnit.MINUTES);
        AtomicInteger comparisons = new AtomicInteger();
        try (var bcrypt = mockStatic(BCrypt.class, CALLS_REAL_METHODS))
        {
            bcrypt.when(() -> BCrypt.checkpw(anyString(), anyString())).thenAnswer(call -> {
                comparisons.incrementAndGet();
                return call.callRealMethod();
            });
            assertThat(authenticate(PASSWORD)).isEqualTo("token-boundary");
            assertThat(cache.hasKey(key)).isFalse();
            assertThat(comparisons).hasValue(1);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 8})
    void concurrentFailuresAreAllCountedAndNextAttemptIsLocked(int attempts) throws Exception
    {
        // Pause real BCrypt results before retry writes; all requests passed the lock check.
        CyclicBarrier comparisonBarrier = new CyclicBarrier(attempts);
        var workers = Executors.newFixedThreadPool(attempts);
        try
        {
            List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < attempts; i++)
            {
                results.add(workers.submit(() -> {
                    try (var bcrypt = mockStatic(BCrypt.class, CALLS_REAL_METHODS))
                    {
                        bcrypt.when(() -> BCrypt.checkpw(anyString(), anyString())).thenAnswer(call -> {
                            Object matched = call.callRealMethod();
                            comparisonBarrier.await(15, TimeUnit.SECONDS);
                            return matched;
                        });
                        assertThatThrownBy(() -> authenticate("incorrect-password"))
                                .isInstanceOf(ServiceException.class).hasMessage("用户不存在/密码错误");
                    }
                }));
            }
            for (Future<?> result : results)
            {
                result.get(30, TimeUnit.SECONDS);
            }
        }
        finally
        {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
        assertThat((Object) cache.getCacheObject(key)).isEqualTo(attempts);
        if (attempts == 4)
        {
            assertThatThrownBy(() -> authenticate("incorrect-password")).hasMessage("用户不存在/密码错误");
            assertThat((Object) cache.getCacheObject(key)).isEqualTo(5);
        }
        assertThatThrownBy(() -> authenticate(PASSWORD)).hasMessage("密码输入错误5次，帐户锁定1分钟");
    }

    @Test
    void productionManagerUsesOnlyRetryProviderAndErasesSuccessfulCredentials()
    {
        var manager = (ProviderManager) context.getBean(AuthenticationManager.class);
        assertThat(manager.getProviders()).containsExactly(context.getBean(SysPasswordService.class));
        var result = manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(username, PASSWORD));
        assertThat(result.isAuthenticated()).isTrue();
        assertThat(result.getCredentials()).isNull();
        assertThat(result.getPrincipal()).isInstanceOf(LoginUser.class);
        assertThat(((LoginUser) result.getPrincipal()).getUserId()).isEqualTo(42L);
        assertThat(((LoginUser) result.getPrincipal()).getPermissions()).containsExactly("test:read");
    }

    @Test
    void identityLoadingNeedsNoCredentialsAndDoesNotConsumeLockState()
    {
        cache.setCacheObject(key, 5, 1, TimeUnit.MINUTES);
        withComparisons(0, () -> {
            var loaded = context.getBean(UserDetailsServiceImpl.class).loadUserByUsername(username);
            assertThat(loaded.getUsername()).isEqualTo(username);
            assertThat(loaded.getPassword()).isEqualTo(user.getPassword());
            assertThat((Object) cache.getCacheObject(key)).isEqualTo(5);
        });
    }

    @Test
    void wrongPasswordUsesOneComparisonAndKeepsLoginErrorResponse()
    {
        withComparisons(1, () -> {
            ServiceException error = catchThrowableOfType(ServiceException.class,
                    () -> authenticate("incorrect-password"));
            assertThat(error).hasMessage("用户不存在/密码错误");
            var response = new GlobalExceptionHandler().handleServiceException(error, new MockHttpServletRequest());
            assertThat(response).containsEntry("code", 500).containsEntry("msg", "用户不存在/密码错误");
        });
        assertThat((Object) cache.getCacheObject(key)).isEqualTo(1);
        try (var connection = context.getBean(LettuceConnectionFactory.class).getConnection())
        {
            assertThat(connection.stringCommands().get(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .isEqualTo(new byte[] { '1' });
        }
        assertThat(cache.redisTemplate.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(55_000L, 60_000L);
    }

    @Test
    void lockedAccountDoesNotCompareOrRefreshExpiryEvenWithCorrectPassword()
    {
        cache.setCacheObject(key, 5, 15, TimeUnit.SECONDS);
        long ttlBefore = cache.redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
        withComparisons(0, () -> assertThatThrownBy(() -> authenticate(PASSWORD))
                .isExactlyInstanceOf(ServiceException.class).hasMessage("密码输入错误5次，帐户锁定1分钟"));
        assertThat((Object) cache.getCacheObject(key)).isEqualTo(5);
        assertThat(cache.redisTemplate.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, ttlBefore);
    }

    @ParameterizedTest
    @CsvSource({"missing,用户不存在/密码错误", "deleted,对不起，您的账号已被删除", "disabled,用户已封禁，请联系管理员"})
    void identityRejectionsKeepMessagesAndDoNotChangeRetries(String state, String message)
    {
        if (state.equals("missing"))
        {
            when(context.getBean(ISysUserService.class).selectUserByUserName(username)).thenReturn(null);
        }
        else if (state.equals("deleted"))
        {
            user.setDelFlag("2");
        }
        else
        {
            user.setStatus("1");
        }
        cache.setCacheObject(key, 5, 1, TimeUnit.MINUTES);
        withComparisons(0, () -> assertThatThrownBy(() -> authenticate(PASSWORD))
                .isExactlyInstanceOf(ServiceException.class).hasMessage(message));
        assertThat((Object) cache.getCacheObject(key)).isEqualTo(5);
    }

    @Test
    void anotherFailureIncrementsLegacyIntegerAndRefreshesItsTtl()
    {
        cache.setCacheObject(key, 2, 2, TimeUnit.SECONDS);
        assertThatThrownBy(() -> authenticate("incorrect-password")).hasMessage("用户不存在/密码错误");
        assertThat((Object) cache.getCacheObject(key)).isEqualTo(3);
        assertThat(cache.redisTemplate.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(55_000L, 60_000L);
        // An old-version writer can still read and update the new counter.
        Integer count = cache.getCacheObject(key);
        cache.setCacheObject(key, count + 1, 1, TimeUnit.MINUTES);
        assertThatThrownBy(() -> authenticate("incorrect-password")).hasMessage("用户不存在/密码错误");
        assertThat((Object) cache.getCacheObject(key)).isEqualTo(5);
    }

    @Test
    void expiredLockAllowsNewFailureAndSuccessfulCleanup()
    {
        cache.setCacheObject(key, 5, 150, TimeUnit.MILLISECONDS);
        await().atMost(5, TimeUnit.SECONDS).until(() -> !cache.hasKey(key));
        assertThatThrownBy(() -> authenticate("incorrect-password")).hasMessage("用户不存在/密码错误");
        assertThat((Object) cache.getCacheObject(key)).isEqualTo(1);
        assertThat(authenticate(PASSWORD)).isEqualTo("token-boundary");
        assertThat(cache.hasKey(key)).isFalse();
    }

    @Test
    void independentRequestsAlwaysCompareCurrentPassword()
    {
        withComparisons(1, () -> assertThat(authenticate(PASSWORD)).isEqualTo("token-boundary"));
        String newPassword = "changed-password";
        user.setPassword(context.getBean(BCryptPasswordEncoder.class).encode(newPassword));
        withComparisons(1, () -> assertThatThrownBy(() -> authenticate(PASSWORD)).hasMessage("用户不存在/密码错误"));
        assertThat((Object) cache.getCacheObject(key)).isEqualTo(1);
        withComparisons(1, () -> assertThat(authenticate(newPassword)).isEqualTo("token-boundary"));
        assertThat(cache.hasKey(key)).isFalse();
    }

    @Test
    void inFlightSuccessAfterFailureClearsEvenNewlyReachedLimit() throws Exception
    {
        raceSuccessAndFailure(true);
        assertThat(cache.hasKey(key)).isFalse();
    }

    @Test
    void inFlightFailureAfterSuccessStartsNewRetryCount() throws Exception
    {
        raceSuccessAndFailure(false);
        assertThat((Object) cache.getCacheObject(key)).isEqualTo(1);
    }

    @Test
    void adminUnlockRequiresExistingPermissionAndClearsSameKey()
    {
        cache.setCacheObject(key, 5, 1, TimeUnit.MINUTES);
        SysLogininforController controller = context.getBean(SysLogininforController.class);
        LoginUser operator = new LoginUser(user, Set.of("monitor:logininfor:list"));
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(operator, null, operator.getAuthorities()));
        assertThatThrownBy(() -> controller.unlock(username)).isInstanceOf(AccessDeniedException.class);
        assertThat((Object) cache.getCacheObject(key)).isEqualTo(5);
        operator.setPermissions(Set.of("monitor:logininfor:unlock"));
        assertThat(controller.unlock(username)).containsEntry("code", 200);
        assertThat(cache.hasKey(key)).isFalse();
        assertThat(controller.unlock(username)).containsEntry("code", 200);
        assertThat(authenticate(PASSWORD)).isEqualTo("token-boundary");
    }

    @Test
    void retriesStillUsePresentedLoginNameWhenStoredNameDiffers()
    {
        user.setUserName("canonical-user");
        assertThatThrownBy(() -> authenticate("incorrect-password")).hasMessage("用户不存在/密码错误");
        assertThat((Object) cache.getCacheObject(key)).isEqualTo(1);
        assertThat(authenticate(PASSWORD)).isEqualTo("token-boundary");
        assertThat(cache.hasKey(key)).isFalse();
    }

    @Test
    void malformedStoredCounterFailsClosedWithoutPasswordComparison()
    {
        cache.setCacheObject(key, "invalid-counter", 1, TimeUnit.MINUTES);
        withComparisons(0, () -> assertThatThrownBy(() -> authenticate(PASSWORD))
                .isExactlyInstanceOf(ServiceException.class));
        assertThat((Object) cache.getCacheObject(key)).isEqualTo("invalid-counter");
    }

    private void raceSuccessAndFailure(boolean successLast) throws Exception
    {
        cache.setCacheObject(key, 4, 1, TimeUnit.MINUTES);
        CountDownLatch compared = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        var worker = Executors.newSingleThreadExecutor();
        try
        {
            Future<?> paused = worker.submit(() -> {
                try (var bcrypt = mockStatic(BCrypt.class, CALLS_REAL_METHODS))
                {
                    bcrypt.when(() -> BCrypt.checkpw(anyString(), anyString())).thenAnswer(call -> {
                        Object matched = call.callRealMethod();
                        compared.countDown();
                        assertThat(finish.await(15, TimeUnit.SECONDS)).isTrue();
                        return matched;
                    });
                    if (successLast)
                    {
                        assertThat(authenticate(PASSWORD)).isEqualTo("token-boundary");
                    }
                    else
                    {
                        assertThatThrownBy(() -> authenticate("incorrect-password")).hasMessage("用户不存在/密码错误");
                    }
                }
            });
            assertThat(compared.await(15, TimeUnit.SECONDS)).isTrue();
            if (successLast)
            {
                assertThatThrownBy(() -> authenticate("incorrect-password")).hasMessage("用户不存在/密码错误");
                assertThat((Object) cache.getCacheObject(key)).isEqualTo(5);
            }
            else
            {
                assertThat(authenticate(PASSWORD)).isEqualTo("token-boundary");
                assertThat(cache.hasKey(key)).isFalse();
            }
            finish.countDown();
            paused.get(20, TimeUnit.SECONDS);
        }
        finally
        {
            finish.countDown();
            worker.shutdownNow();
            assertThat(worker.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void withComparisons(int expected, Runnable action)
    {
        AtomicInteger comparisons = new AtomicInteger();
        try (var bcrypt = mockStatic(BCrypt.class, CALLS_REAL_METHODS))
        {
            bcrypt.when(() -> BCrypt.checkpw(anyString(), anyString())).thenAnswer(call -> {
                comparisons.incrementAndGet();
                return call.callRealMethod();
            });
            action.run();
            assertThat(comparisons).hasValue(expected);
        }
    }

    private String authenticate(String password)
    {
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        try
        {
            return login.login(username, password, null, null);
        }
        catch (BaseException ex)
        {
            // 本夹具测试认证阶段，前置校验拒绝说明输入不合法。
            // 在上下文关闭前固化消息，避免延迟 getMessage 使 JUnit 报告器丢失失败事件。
            throw new AssertionError("Login precheck rejected test input: " + ex.getMessage());
        }
        finally
        {
            RequestContextHolder.resetRequestAttributes();
            LocaleContextHolder.resetLocaleContext();
        }
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, RedisConfig.class, SpringUtils.class, UserDetailsServiceImpl.class,
            SysPasswordService.class, SysLoginService.class, SysLogininforController.class, PermissionService.class})
    static class LoginConfiguration
    {
        @Bean
        LettuceConnectionFactory redisConnectionFactory()
        {
            String port = System.getenv("LOGIN_REDIS_PORT");
            if (port == null || port.isBlank())
            {
                throw new IllegalStateException("LOGIN_REDIS_PORT must name an isolated local Redis instance");
            }
            return new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(port));
        }

        @Bean RedisCache redisCache() { return new RedisCache(); }
        @Bean RedisCacheManager cacheManager(LettuceConnectionFactory factory)
        {
            return RedisCacheManager.create(factory);
        }
        @Bean ISysUserService users() { return mock(ISysUserService.class); }
        @Bean ISysRoleService roles() { return mock(ISysRoleService.class); }
        @Bean ISysMenuService menus() { return mock(ISysMenuService.class); }
        @Bean SysPermissionService permissions() { return mock(SysPermissionService.class); }
        @Bean ISysConfigService configs() { return mock(ISysConfigService.class); }
        @Bean TokenService tokens() { return mock(TokenService.class); }
        @Bean ISysLogininforService loginInfo() { return mock(ISysLogininforService.class); }
        @Bean ScheduledExecutorService scheduledExecutorService() { return mock(ScheduledExecutorService.class); }
        @Bean AuthenticationEntryPointImpl unauthorizedHandler() { return new AuthenticationEntryPointImpl(); }
        @Bean LogoutSuccessHandlerImpl logoutSuccessHandler() { return mock(LogoutSuccessHandlerImpl.class); }
        @Bean JwtAuthenticationTokenFilter jwtFilter() { return new JwtAuthenticationTokenFilter(); }
        @Bean CorsFilter corsFilter() { return new CorsFilter(mock(CorsConfigurationSource.class)); }
        @Bean PermitAllUrlProperties permitAllUrlProperties()
        {
            PermitAllUrlProperties permit = mock(PermitAllUrlProperties.class);
            when(permit.getUrls()).thenReturn(List.of());
            return permit;
        }
        @Bean ResourceBundleMessageSource messageSource()
        {
            ResourceBundleMessageSource source = new ResourceBundleMessageSource();
            source.setBasename("i18n/messages");
            source.setDefaultEncoding("UTF-8");
            return source;
        }
    }
}
