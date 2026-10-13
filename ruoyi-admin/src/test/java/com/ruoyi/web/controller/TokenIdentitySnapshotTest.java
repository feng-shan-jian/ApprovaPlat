package com.ruoyi.web.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.framework.config.FastJson2JsonRedisSerializer;
import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.system.service.ISysMenuService;
import com.ruoyi.system.service.ISysUserService;
import com.ruoyi.web.controller.system.SysLoginController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

/** Controller 返回值及缓存刷新入口；纯 Token/权限合同位于 framework。 */
class TokenIdentitySnapshotTest
{
    @AfterEach
    void clearContext()
    {
        SecurityContextHolder.clearContext();
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
        private final Map<String, byte[]> values = new HashMap<>();
        private final FastJson2JsonRedisSerializer<Object> serializer = new FastJson2JsonRedisSerializer<>(Object.class);

        @Override
        public <T> void setCacheObject(String key, T value)
        {
            values.put(key, serializer.serialize(value));
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
            if (expected == null || !Arrays.equals(expected, values.get(key))) return false;
            if (value == null) values.remove(key);
            else setCacheObject(key, value);
            return true;
        }
    }
}
