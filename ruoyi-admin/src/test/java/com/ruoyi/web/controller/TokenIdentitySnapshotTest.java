package com.ruoyi.web.controller;

import java.util.HashMap;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.BaseEntity;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.framework.config.FastJson2JsonRedisSerializer;
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.framework.aspectj.DataScopeAspect;
import com.ruoyi.system.service.ISysUserService;
import com.ruoyi.system.service.ISysMenuService;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.web.controller.system.SysLoginController;
import org.aspectj.lang.JoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TokenIdentitySnapshotTest
{
    @AfterEach
    void clearContext()
    {
        SecurityContextHolder.clearContext();
        if (TransactionSynchronizationManager.isSynchronizationActive())
        {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void renewalDoesNotOverwritePublishedIdentity()
    {
        MemoryCache cache = new MemoryCache();
        TokenService service = tokens(cache);
        LoginUser oldRequest = login("one", "system:user:edit");
        cache.setCacheObject(key("one"), login("one", "system:user:list"));

        service.refreshToken(oldRequest);

        LoginUser actual = cache.getCacheObject(key("one"));
        assertEquals(Set.of("system:user:list"), actual.getPermissions());
    }

    @Test
    void expiredSessionCannotBeResurrectedByProfileWrite()
    {
        MemoryCache cache = new MemoryCache();
        TokenService service = tokens(cache);
        service.setLoginUser(login("expired", "system:user:edit"));
        assertNull(cache.getCacheObject(key("expired")));
    }

    @Test
    void currentFactsRefreshAllTokensIncludingNewRoleGrantAndUnchangedPermissionDataScope()
    {
        MemoryCache cache = new MemoryCache();
        TokenService service = tokens(cache);
        ISysUserService users = mock(ISysUserService.class);
        SysPermissionService permissions = permissions(users);
        ReflectionTestUtils.setField(service, "permissionService", permissions);
        cache.setCacheObject(key("one"), login("one", "system:user:list"));
        cache.setCacheObject(key("two"), login("two", "system:user:list"));
        SysUser user = user("0", "0", "3");
        when(users.selectUserById(42L)).thenReturn(user);

        service.refreshUsersAfterCommit(List.of(42L));

        for (String token : List.of("one", "two"))
        {
            LoginUser updated = cache.getCacheObject(key(token));
            assertEquals(Set.of("system:user:list"), updated.getPermissions());
            assertEquals(301L, updated.getDeptId());
            assertEquals(9L, updated.getUser().getRoles().get(0).getRoleId());
            assertEquals("3", updated.getUser().getRoles().get(0).getDataScope());
            assertEquals(Set.of("system:user:list"), updated.getUser().getRoles().get(0).getPermissions());
            BaseEntity query = new BaseEntity();
            JoinPoint point = mock(JoinPoint.class);
            when(point.getArgs()).thenReturn(new Object[] { query });
            DataScopeAspect.dataScopeFilter(point, updated.getUser(), "u", "d", "user_id", "dept_id", "system:user:list");
            assertTrue(query.getParams().get("dataScope").toString().contains("d.dept_id = 301"));
        }
        verify(users, times(2)).selectUserById(42L);
        verify(users, never()).selectUserByIdInScope(anyLong());
    }

    @Test
    void revokedAndDisabledRolesCannotRetainRoleOrMenuPermissions()
    {
        ISysUserService users = mock(ISysUserService.class);
        SysPermissionService permissions = permissions(users);
        SysUser disabledRole = user("0", "0", "1");
        disabledRole.getRoles().get(0).setStatus("1");
        disabledRole.getRoles().get(0).setPermissions(Set.of("system:user:edit"));
        when(users.selectUserById(42L)).thenReturn(disabledRole);
        LoginUser updated = permissions.loadIdentity(42L);
        assertTrue(updated.getPermissions().isEmpty());
        assertTrue(permissions.getRolePermission(updated.getUser()).isEmpty());
        assertTrue(updated.getUser().getRoles().get(0).getPermissions().isEmpty());
        disabledRole.setRoles(new ArrayList<>());
        assertTrue(permissions.loadIdentity(42L).getPermissions().isEmpty());
    }

    @Test
    void userOneKeepsWildcardAndMultipleRolesKeepTheirIndividualPermissionSets()
    {
        ISysUserService users = mock(ISysUserService.class);
        ISysMenuService menus = mock(ISysMenuService.class);
        SysPermissionService permissions = permissions(users);
        ReflectionTestUtils.setField(permissions, "menuService", menus);
        SysUser user = user("0", "0", "3");
        SysRole second = new SysRole();
        second.setRoleId(10L);
        second.setRoleKey("editor");
        second.setStatus("0");
        user.getRoles().add(second);
        when(users.selectUserById(42L)).thenReturn(user);
        when(menus.selectMenuPermsByRoleId(9L)).thenReturn(Set.of("system:user:list"));
        when(menus.selectMenuPermsByRoleId(10L)).thenReturn(Set.of("system:user:edit"));
        LoginUser identity = permissions.loadIdentity(42L);
        assertEquals(Set.of("system:user:list", "system:user:edit"), identity.getPermissions());
        assertEquals(Set.of("system:user:list"), identity.getUser().getRoles().get(0).getPermissions());
        assertEquals(Set.of("system:user:edit"), identity.getUser().getRoles().get(1).getPermissions());
        user.setUserId(1L);
        when(users.selectUserById(1L)).thenReturn(user);
        assertEquals(Set.of("*:*:*"), permissions.loadIdentity(1L).getPermissions());
        assertEquals(Set.of("admin"), permissions.getRolePermission(user));
    }

    @ParameterizedTest
    @ValueSource(strings = { "disabled", "deleted", "missing" })
    void inactiveAccountsInvalidateEveryToken(String kind)
    {
        MemoryCache cache = new MemoryCache();
        TokenService service = tokens(cache);
        ISysUserService users = mock(ISysUserService.class);
        ReflectionTestUtils.setField(service, "permissionService", permissions(users));
        SysUser user = "missing".equals(kind) ? null : user("disabled".equals(kind) ? "1" : "0",
                "deleted".equals(kind) ? "2" : "0", "1");
        when(users.selectUserById(42L)).thenReturn(user);
        cache.setCacheObject(key("one"), login("one", "system:user:list"));
        cache.setCacheObject(key("two"), login("two", "system:user:list"));
        service.refreshUsersAfterCommit(List.of(42L));
        assertNull(cache.getCacheObject(key("one")));
        assertNull(cache.getCacheObject(key("two")));
        service.refreshToken(login("one", "system:user:edit"));
        assertNull(cache.getCacheObject(key("one")));
    }

    @Test
    void renewalRetriesWhenPermissionRefreshWinsBetweenReadAndWrite()
    {
        MemoryCache cache = new MemoryCache();
        TokenService service = tokens(cache);
        cache.setCacheObject(key("one"), login("one", "system:user:edit"));
        cache.beforeCompare = () -> cache.setCacheObject(key("one"), login("one", "system:user:list"));
        service.refreshToken(login("one", "system:user:edit"));
        assertEquals(Set.of("system:user:list"), ((LoginUser) cache.getCacheObject(key("one"))).getPermissions());
        assertEquals(2, cache.comparisons);
    }

    @Test
    void profileRefreshRereadsIdentityAfterConcurrentRevocation()
    {
        MemoryCache cache = new MemoryCache();
        TokenService service = tokens(cache);
        SysPermissionService permissions = mock(SysPermissionService.class);
        ReflectionTestUtils.setField(service, "permissionService", permissions);
        when(permissions.loadIdentity(42L)).thenReturn(login("one", "system:user:edit"), login("one", "system:user:list"));
        cache.setCacheObject(key("one"), login("one", "system:user:edit"));
        cache.beforeCompare = () -> cache.setCacheObject(key("one"), login("one", "system:user:list"));
        service.setLoginUser(login("one", "system:user:edit"));
        assertEquals(Set.of("system:user:list"), ((LoginUser) cache.getCacheObject(key("one"))).getPermissions());
        verify(permissions, times(2)).loadIdentity(42L);
    }

    @Test
    void loginCreationRegistersBeforeReadingAndRetriesConcurrentRevocation()
    {
        MemoryCache cache = new MemoryCache();
        TokenService service = spy(tokens(cache));
        doNothing().when(service).setUserAgent(any());
        ReflectionTestUtils.setField(service, "header", "Authorization");
        ReflectionTestUtils.setField(service, "secret", java.util.Base64.getEncoder().encodeToString(new byte[64]));
        service.validateSecret();
        SysPermissionService permissions = mock(SysPermissionService.class);
        ReflectionTestUtils.setField(service, "permissionService", permissions);
        when(permissions.loadIdentity(42L)).thenReturn(login("ignored", "system:user:edit"),
                login("ignored", "system:user:list"), login("ignored", "system:user:list"));
        cache.beforeCompare = () -> service.refreshUsersAfterCommit(List.of(42L));

        String jwt = service.createToken(login("ignored", "system:user:edit"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + jwt);
        assertEquals(Set.of("system:user:list"), service.getLoginUser(request).getPermissions());
        verify(permissions, times(3)).loadIdentity(42L);
    }

    @Test
    void getInfoReturnsFreshRoleScopeEvenWhenTotalPermissionsAreUnchanged()
    {
        MemoryCache cache = new MemoryCache();
        TokenService service = tokens(cache);
        ISysUserService users = mock(ISysUserService.class);
        SysPermissionService permissions = permissions(users);
        ReflectionTestUtils.setField(service, "permissionService", permissions);
        when(users.selectUserById(42L)).thenReturn(user("0", "0", "5"));
        LoginUser original = login("one", "system:user:list");
        cache.setCacheObject(key("one"), original);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(original, null, original.getAuthorities()));
        SysLoginController controller = new SysLoginController();
        ReflectionTestUtils.setField(controller, "tokenService", service);
        ReflectionTestUtils.setField(controller, "permissionService", permissions);
        ReflectionTestUtils.setField(controller, "configService", mock(ISysConfigService.class));
        var result = controller.getInfo();
        assertEquals(Set.of("reviewer"), result.get("roles"));
        assertEquals("5", ((SysUser) result.get("user")).getRoles().get(0).getDataScope());
        assertEquals("5", ((LoginUser) cache.getCacheObject(key("one"))).getUser().getRoles().get(0).getDataScope());
    }

    @Test
    void refreshWaitsForCommitAndRollbackDoesNotPublish()
    {
        MemoryCache cache = new MemoryCache();
        TokenService service = tokens(cache);
        SysPermissionService permissions = mock(SysPermissionService.class);
        ReflectionTestUtils.setField(service, "permissionService", permissions);
        when(permissions.loadIdentity(42L)).thenReturn(login("one", "system:user:list"));
        cache.setCacheObject(key("one"), login("one", "system:user:edit"));
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        service.refreshUsersAfterCommit(List.of(42L));
        verifyNoInteractions(permissions);
        var callbacks = TransactionSynchronizationManager.getSynchronizations();
        callbacks.forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verifyNoInteractions(permissions);
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        service.refreshUsersAfterCommit(List.of(42L));
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertEquals(Set.of("system:user:list"), ((LoginUser) cache.getCacheObject(key("one"))).getPermissions());
    }

    @Test
    void cacheRefreshFailureInvalidatesSessionWithoutPretendingDatabaseRollback()
    {
        MemoryCache cache = new MemoryCache();
        TokenService service = tokens(cache);
        SysPermissionService permissions = mock(SysPermissionService.class);
        ReflectionTestUtils.setField(service, "permissionService", permissions);
        when(permissions.loadIdentity(42L)).thenThrow(new IllegalStateException("controlled identity read failure"));
        cache.setCacheObject(key("one"), login("one", "system:user:edit"));
        assertDoesNotThrow(() -> service.refreshUsersAfterCommit(List.of(42L)));
        assertNull(cache.getCacheObject(key("one")));
    }

    private SysPermissionService permissions(ISysUserService users)
    {
        SysPermissionService permissions = new SysPermissionService();
        ISysMenuService menus = mock(ISysMenuService.class);
        when(menus.selectMenuPermsByRoleId(9L)).thenReturn(Set.of("system:user:list"));
        ReflectionTestUtils.setField(permissions, "userService", users);
        ReflectionTestUtils.setField(permissions, "menuService", menus);
        return permissions;
    }

    private SysUser user(String status, String deleted, String scope)
    {
        SysUser user = login("unused", "system:user:list").getUser();
        user.setStatus(status);
        user.setDelFlag(deleted);
        user.setDeptId(301L);
        SysRole role = new SysRole();
        role.setRoleId(9L);
        role.setRoleKey("reviewer");
        role.setStatus("0");
        role.setDataScope(scope);
        user.setRoles(new ArrayList<>(List.of(role)));
        return user;
    }

    private TokenService tokens(MemoryCache cache)
    {
        TokenService service = new TokenService();
        ReflectionTestUtils.setField(service, "redisCache", cache);
        ReflectionTestUtils.setField(service, "expireTime", 30);
        return service;
    }

    private static String key(String token)
    {
        return CacheConstants.LOGIN_TOKEN_KEY + token;
    }

    private static LoginUser login(String token, String permission)
    {
        SysUser user = new SysUser();
        user.setUserId(42L);
        user.setUserName("identity-test");
        user.setStatus("0");
        user.setDelFlag("0");
        LoginUser login = new LoginUser(42L, 100L, user, Set.of(permission));
        login.setToken(token);
        login.setLoginTime(100L);
        login.setExpireTime(System.currentTimeMillis() + 900000L);
        return login;
    }

    private static class MemoryCache extends RedisCache
    {
        final Map<String, byte[]> values = new HashMap<>();
        final FastJson2JsonRedisSerializer<Object> serializer = new FastJson2JsonRedisSerializer<>(Object.class);
        Runnable beforeCompare;
        int comparisons;

        @Override
        public Collection<String> keys(String pattern)
        {
            return new ArrayList<>(values.keySet());
        }

        @Override
        public boolean deleteObject(String key)
        {
            return values.remove(key) != null;
        }

        @Override
        public <T> void setCacheObject(String key, T value)
        {
            values.put(key, serializer.serialize(value));
        }

        @Override
        public <T> void setCacheObject(String key, T value, Integer timeout, TimeUnit unit)
        {
            setCacheObject(key, value);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getCacheObject(String key)
        {
            return (T) serializer.deserialize(values.get(key));
        }

        @Override
        public byte[] getCacheObjectBytes(String key)
        {
            return values.get(key);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T deserializeCacheObject(byte[] value)
        {
            return (T) serializer.deserialize(value);
        }

        @Override
        public boolean compareAndSetCacheObject(String key, byte[] expected, Object value, long ttl)
        {
            comparisons++;
            if (beforeCompare != null)
            {
                Runnable hook = beforeCompare;
                beforeCompare = null;
                hook.run();
            }
            if (expected == null || !Arrays.equals(expected, values.get(key)))
            {
                return false;
            }
            if (value == null)
            {
                values.remove(key);
            }
            else
            {
                setCacheObject(key, value);
            }
            return true;
        }
    }
}
