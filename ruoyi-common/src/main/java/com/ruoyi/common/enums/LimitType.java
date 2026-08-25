package com.ruoyi.common.enums;

/**
 * 限流类型
 *
 * @author ruoyi
 */

public enum LimitType
{
    /**
     * 默认策略全局限流
     */
    DEFAULT,

    /**
     * 根据请求者IP进行限流
     */
    IP,

    /**
     * 使用当前认证用户桶和可信代理来源 IP 桶同时限流。
     */
    USER_IP
}
