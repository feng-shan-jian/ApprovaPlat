package com.ruoyi.common.utils.http;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import com.ruoyi.common.utils.ip.IpUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 请求元数据公共工具回归测试，覆盖登录审计使用的 User-Agent 解析与内网 IP 判定。
 */
class RequestMetadataUtilitiesTest
{
    /**
     * 验证 RFC1918 网段边界，尤其防止 172.16/12 分支穿透后误判其他 172 地址。
     * 无入参。
     * 无返回值。
     */
    @Test
    void shouldRecognizeOnlyConfiguredPrivateIpv4Ranges()
    {
        assertTrue(IpUtils.internalIp("10.0.0.1"));
        assertTrue(IpUtils.internalIp("172.16.0.1"));
        assertTrue(IpUtils.internalIp("172.31.255.255"));
        assertTrue(IpUtils.internalIp("192.168.1.1"));
        assertTrue(IpUtils.internalIp("127.0.0.1"));

        assertFalse(IpUtils.internalIp("172.15.255.255"));
        assertFalse(IpUtils.internalIp("172.32.0.1"));
        assertFalse(IpUtils.internalIp("172.168.1.1"));
        assertFalse(IpUtils.internalIp("192.167.1.1"));
        assertFalse(IpUtils.internalIp("8.8.8.8"));
    }

    /**
     * 验证缺失或非法地址继续按内部地址失败关闭，不把无效输入送往外部 IP 查询。
     * 无入参。
     * 无返回值。
     */
    @Test
    void shouldFailClosedForMissingOrInvalidIpv4Address()
    {
        assertTrue(IpUtils.internalIp(null));
        assertTrue(IpUtils.internalIp(""));
        assertTrue(IpUtils.internalIp("not-an-ip"));
    }

    /**
     * 验证浏览器与操作系统回退解析仍保留原有主版本输出契约。
     * 无入参。
     * 无返回值。
     */
    @Test
    void shouldPreserveFallbackUserAgentMajorVersionContract()
    {
        assertEquals("Chrome142", UserAgentUtils.formatBrowser("Chrome/142.0.1.9"));
        assertEquals("Firefox128", UserAgentUtils.formatBrowser("Firefox/128.4"));
        assertEquals("Windows10", UserAgentUtils.formatOperatingSystem("Windows NT 10.0"));
        assertEquals("macOS14", UserAgentUtils.formatOperatingSystem("Mac OS X 14_6_1"));
        assertEquals("Android15", UserAgentUtils.formatOperatingSystem("Android 15.0.0"));
    }

    /**
     * 验证攻击者提供超长版本段时回退正则不会递归耗尽线程栈。
     * 无入参。
     * 无返回值。
     */
    @Test
    void shouldParseLongUserAgentWithoutRegexStackOverflow()
    {
        String longVersion = "Chrome/1" + ".1".repeat(20_000);

        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertEquals("Chrome1", UserAgentUtils.formatBrowser(longVersion)));
    }
}
