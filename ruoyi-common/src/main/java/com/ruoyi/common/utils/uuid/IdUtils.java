package com.ruoyi.common.utils.uuid;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * ID生成器工具类
 * 
 * @author ruoyi
 */
public class IdUtils
{
    /**
     * 获取随机UUID
     * 
     * @return 随机UUID
     */
    public static String randomUUID()
    {
        return UUID.randomUUID().toString();
    }

    /**
     * 简化的UUID，去掉了横线
     * 
     * @return 简化的UUID，去掉了横线
     */
    public static String simpleUUID()
    {
        return randomUUID().replace("-", "");
    }

    /**
     * 获取随机UUID，使用性能更好的ThreadLocalRandom生成UUID
     * 
     * @return 随机UUID
     */
    public static String fastUUID()
    {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        // 保留 UUID v4 的版本位和 RFC 4122 变体位，其余 122 位来自快速随机源。
        return new UUID((random.nextLong() & 0xffffffffffff0fffL) | 0x4000L,
                (random.nextLong() & 0x3fffffffffffffffL) | Long.MIN_VALUE).toString();
    }

    /**
     * 简化的UUID，去掉了横线，使用性能更好的ThreadLocalRandom生成UUID
     * 
     * @return 简化的UUID，去掉了横线
     */
    public static String fastSimpleUUID()
    {
        return fastUUID().replace("-", "");
    }
}
