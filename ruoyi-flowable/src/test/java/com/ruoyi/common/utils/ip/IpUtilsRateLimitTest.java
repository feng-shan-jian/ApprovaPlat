package com.ruoyi.common.utils.ip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

/**
 * 验证限流 IP 仅信任本机反向代理覆盖后的来源地址。
 */
class IpUtilsRateLimitTest
{
    /**
     * 直接访问应用时忽略调用者伪造的代理请求头。
     *
     * @return void，无返回值
     */
    @Test
    void directRequestIgnoresSpoofedForwardingHeaders()
    {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("198.51.100.27");
        when(request.getHeader("X-Real-IP")).thenReturn("203.0.113.99");
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.98");

        assertEquals("198.51.100.27", IpUtils.getRateLimitIpAddr(request));
    }

    /**
     * 本机 Nginx 转发时使用其覆盖后的 X-Real-IP。
     *
     * @return void，无返回值
     */
    @Test
    void loopbackProxyUsesOverwrittenRealIp()
    {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getHeader("X-Real-IP")).thenReturn("203.0.113.8");

        assertEquals("203.0.113.8", IpUtils.getRateLimitIpAddr(request));
    }

    /**
     * 本机代理传入多值或控制字符时回退到对端地址，避免污染 Redis 键。
     *
     * @return void，无返回值
     */
    @Test
    void loopbackProxyRejectsUntrustedRealIpShape()
    {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("::1");
        when(request.getHeader("X-Real-IP")).thenReturn("203.0.113.8, 198.51.100.1");

        assertEquals("127.0.0.1", IpUtils.getRateLimitIpAddr(request));
    }
}
