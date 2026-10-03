package com.ruoyi.common.core.redis;

import java.util.Collection;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.stereotype.Component;

/**
 * spring redis 工具类
 *
 * @author ruoyi
 **/
@SuppressWarnings(value = { "unchecked", "rawtypes" })
@Component
public class RedisCache
{
    private static final byte[] COMPARE_AND_SET = ("if redis.call('get', KEYS[1]) ~= ARGV[1] then return 0 end "
            + "if ARGV[2] == '' then redis.call('del', KEYS[1]) "
            + "else redis.call('psetex', KEYS[1], ARGV[3], ARGV[2]) end return 1")
            .getBytes(StandardCharsets.UTF_8);

    @Autowired
    public RedisTemplate redisTemplate;

    /**
     * 缓存基本的对象，Integer、String、实体类等
     *
     * @param key 缓存的键值
     * @param value 缓存的值
     */
    public <T> void setCacheObject(final String key, final T value)
    {
        redisTemplate.opsForValue().set(key, value);
    }

    /**
     * 缓存基本的对象，Integer、String、实体类等
     *
     * @param key 缓存的键值
     * @param value 缓存的值
     * @param timeout 时间
     * @param timeUnit 时间颗粒度
     */
    public <T> void setCacheObject(final String key, final T value, final Integer timeout, final TimeUnit timeUnit)
    {
        redisTemplate.opsForValue().set(key, value, timeout, timeUnit);
    }

    /**
     * 设置有效时间
     *
     * @param key Redis键
     * @param timeout 超时时间
     * @return true=设置成功；false=设置失败
     */
    public boolean expire(final String key, final long timeout)
    {
        return expire(key, timeout, TimeUnit.SECONDS);
    }

    /**
     * 设置有效时间
     *
     * @param key Redis键
     * @param timeout 超时时间
     * @param unit 时间单位
     * @return true=设置成功；false=设置失败
     */
    public boolean expire(final String key, final long timeout, final TimeUnit unit)
    {
        return redisTemplate.expire(key, timeout, unit);
    }

    /**
     * 获取有效时间
     *
     * @param key Redis键
     * @return 有效时间
     */
    public long getExpire(final String key)
    {
        return redisTemplate.getExpire(key);
    }

    /**
     * 判断 key是否存在
     *
     * @param key 键
     * @return true 存在 false不存在
     */
    public Boolean hasKey(String key)
    {
        return redisTemplate.hasKey(key);
    }

    /**
     * 获得缓存的基本对象。
     *
     * @param key 缓存键值
     * @return 缓存键值对应的数据
     */
    public <T> T getCacheObject(final String key)
    {
        ValueOperations<String, T> operation = redisTemplate.opsForValue();
        return operation.get(key);
    }

    /** 读取原始值作为条件写凭据，避免反序列化再序列化改变比较字节。 */
    public byte[] getCacheObjectBytes(String key)
    {
        byte[] encodedKey = redisTemplate.getKeySerializer().serialize(key);
        return (byte[]) redisTemplate.execute((RedisCallback<byte[]>) connection -> connection.get(encodedKey));
    }

    public <T> T deserializeCacheObject(byte[] value)
    {
        return (T) redisTemplate.getValueSerializer().deserialize(value);
    }

    /** 仅替换仍为 expected 的现存值；value 为 null 时条件删除，不恢复已过期/退出的会话。 */
    public boolean compareAndSetCacheObject(String key, byte[] expected, Object value, long timeoutMillis)
    {
        if (expected == null)
        {
            return false;
        }
        byte[] encodedKey = redisTemplate.getKeySerializer().serialize(key);
        byte[] encodedValue = value == null ? new byte[0] : redisTemplate.getValueSerializer().serialize(value);
        byte[] timeout = Long.toString(timeoutMillis).getBytes(StandardCharsets.UTF_8);
        Long result = (Long) redisTemplate.execute((RedisCallback<Long>) connection -> connection.eval(
                COMPARE_AND_SET, ReturnType.INTEGER, 1, encodedKey, expected, encodedValue, timeout));
        return Long.valueOf(1).equals(result);
    }

    /**
     * 删除单个对象
     *
     * @param key
     */
    public boolean deleteObject(final String key)
    {
        return redisTemplate.delete(key);
    }

    /**
     * 删除集合对象
     *
     * @param collection 多个对象
     * @return
     */
    public boolean deleteObject(final Collection collection)
    {
        return redisTemplate.delete(collection) > 0;
    }

    /**
     * 获得缓存的基本对象列表
     *
     * @param pattern 字符串前缀
     * @return 对象列表
     */
    public Collection<String> keys(final String pattern)
    {
        return redisTemplate.keys(pattern);
    }
}
