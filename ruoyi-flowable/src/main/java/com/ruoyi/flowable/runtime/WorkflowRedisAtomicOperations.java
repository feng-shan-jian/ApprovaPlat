package com.ruoyi.flowable.runtime;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 提供工作流短期状态所需的 Redis 原子写入能力，业务服务负责 Key 命名和失败语义。
 */
@Component
public class WorkflowRedisAtomicOperations
{
    private static final String MARKER_VALUE = "1";
    private static final int MAX_KEY_LENGTH = 512;
    private static final DefaultRedisScript<Long> INCREMENT_WITH_EXPIRY_SCRIPT =
            new DefaultRedisScript<>("local count = redis.call('INCR', KEYS[1]); "
                    + "if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]); end; "
                    + "return count;", Long.class);

    private final StringRedisTemplate redisTemplate;

    /**
     * 创建工作流 Redis 原子操作组件。
     *
     * @param redisTemplate StringRedisTemplate，应用统一 Lettuce 连接与序列化配置
     * @return 无返回值，构造后由 Spring 管理
     */
    public WorkflowRedisAtomicOperations(StringRedisTemplate redisTemplate)
    {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 使用 Redis 的带过期 SET NX 原子建立短期占位；冲突时读取剩余 TTL 供业务返回重试时间。
     *
     * @param key String，业务命名空间内不含敏感正文的稳定 Redis Key
     * @param ttl Duration，必须为正整数秒的自动过期时间
     * @return ExpiringSetResult，包含是否建立成功以及冲突 Key 的剩余秒数
     * @throws org.springframework.dao.DataAccessException Redis 连接或命令执行失败时透传，由业务层映射失败策略
     */
    public ExpiringSetResult setIfAbsent(String key, Duration ttl)
    {
        String normalizedKey = requireKey(key);
        Duration normalizedTtl = requireWholeSecondTtl(ttl);
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(normalizedKey, MARKER_VALUE, normalizedTtl);
        if (Boolean.TRUE.equals(acquired))
        {
            return new ExpiringSetResult(true, normalizedTtl.toSeconds());
        }
        Long remaining = redisTemplate.getExpire(normalizedKey, TimeUnit.SECONDS);
        // Key 可能恰好在 SET NX 与 TTL 查询之间过期；返回 1 秒避免向调用方暴露无效负值。
        return new ExpiringSetResult(false, remaining == null || remaining < 1 ? 1 : remaining);
    }

    /**
     * 使用 Lua 原子执行 INCR 与首次 EXPIRE，避免并发首请求造成计数 Key 永久保留。
     *
     * @param key String，业务命名空间内不含敏感正文的计数 Key
     * @param ttl Duration，首次计数时设置的正整数秒保留期
     * @return long，本次递增后的 Redis 计数
     * @throws org.springframework.dao.DataAccessException Redis 连接或脚本执行失败时透传
     */
    public long incrementWithExpiry(String key, Duration ttl)
    {
        String normalizedKey = requireKey(key);
        Duration normalizedTtl = requireWholeSecondTtl(ttl);
        Long count = redisTemplate.execute(INCREMENT_WITH_EXPIRY_SCRIPT,
                List.of(normalizedKey), Long.toString(normalizedTtl.toSeconds()));
        if (count == null || count < 1)
        {
            throw new IllegalStateException("Redis 原子计数返回值不合法");
        }
        return count;
    }

    /**
     * 校验 Redis Key 只承载有界业务标识，防止空 Key 或无界输入污染共享命名空间。
     *
     * @param key String，调用方生成的业务 Key
     * @return String，去除首尾空白后的规范 Key
     */
    private String requireKey(String key)
    {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Redis Key 不能为空");
        String normalized = key.trim();
        if (normalized.length() > MAX_KEY_LENGTH || normalized.chars().anyMatch(Character::isISOControl))
        {
            throw new IllegalArgumentException("Redis Key 不合法");
        }
        return normalized;
    }

    /**
     * 校验过期时间可以由 Redis 秒级 EX 语义准确表达，避免静默截断造成频控窗口漂移。
     *
     * @param ttl Duration，业务配置的短期状态有效期
     * @return Duration，满足正整数秒约束的原值
     */
    private Duration requireWholeSecondTtl(Duration ttl)
    {
        if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.getNano() != 0)
        {
            throw new IllegalArgumentException("Redis 过期时间必须为正整数秒");
        }
        return ttl;
    }

    /**
     * Redis 短期占位结果。
     *
     * @param acquired true 表示本次原子建立成功，false 表示 Key 已存在
     * @param remainingSeconds 建立成功时为配置 TTL，冲突时为至少 1 秒的剩余时间
     */
    public record ExpiringSetResult(boolean acquired, long remainingSeconds) { }
}
