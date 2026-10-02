package com.ruoyi.web.controller;

import java.util.List;
import java.util.Set;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.system.domain.SysUserOnline;
import com.ruoyi.system.service.impl.SysUserOnlineServiceImpl;
import com.ruoyi.web.controller.monitor.SysUserOnlineController;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class OnlineUserQueryTest
{
    @ParameterizedTest
    @CsvSource({",,second|first", "10.0.0.1,,first", ",alice,first",
            "10.0.0.1,alice,first", "10.0.0.2,alice,''", "10.0.0.1,ali,''"})
    void filtersOnceAndSkipsExpiredOrIncompleteSessions(String ip, String name, String expected)
    {
        RedisCache cache = mock(RedisCache.class);
        when(cache.keys(anyString())).thenReturn(List.of("first", "expired", "incomplete", "second"));
        when(cache.getCacheObject("first")).thenReturn(login("first", "alice", "10.0.0.1"));
        when(cache.getCacheObject("second")).thenReturn(login("second", "bob", "10.0.0.2"));
        when(cache.getCacheObject("incomplete")).thenReturn(new LoginUser());
        SysUserOnlineServiceImpl service = spy(new SysUserOnlineServiceImpl());
        SysUserOnlineController controller = new SysUserOnlineController();
        ReflectionTestUtils.setField(controller, "redisCache", cache);
        ReflectionTestUtils.setField(controller, "userOnlineService", service);
        List<?> rows = controller.list(ip, name).getRows();
        assertEquals(expected, String.join("|", rows.stream().map(row -> ((SysUserOnline) row).getTokenId()).toList()));
        verify(service, times(4)).loginUserToUserOnline(any());
    }

    private LoginUser login(String token, String name, String ip)
    {
        SysUser user = new SysUser();
        user.setUserName(name);
        LoginUser login = new LoginUser(user, Set.of());
        login.setToken(token);
        login.setIpaddr(ip);
        return login;
    }
}
