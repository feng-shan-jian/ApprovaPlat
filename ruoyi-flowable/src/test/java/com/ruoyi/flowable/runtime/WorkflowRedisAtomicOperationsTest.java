package com.ruoyi.flowable.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

class WorkflowRedisAtomicOperationsTest
{
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private WorkflowRedisAtomicOperations operations;

    /**
     * 创建 Redis 边界替身，验证组件只使用原子带 TTL 的 SET NX 入口。
     *
     * @return void，每个测试获得隔离的 Redis 调用记录
     */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp()
    {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        operations = new WorkflowRedisAtomicOperations(redisTemplate);
    }

    /**
     * 验证首次写入通过单条带过期 SET NX 建立占位并返回完整窗口。
     *
     * @return void，拆分为非原子写入或返回错误 TTL 时测试失败
     */
    @Test
    void acquiresExpiringMarkerAtomically()
    {
        Duration ttl = Duration.ofMinutes(5);
        when(valueOperations.setIfAbsent("workflow:test:1", "1", ttl)).thenReturn(true);

        var result = operations.setIfAbsent("workflow:test:1", ttl);

        assertThat(result.acquired()).isTrue();
        assertThat(result.remainingSeconds()).isEqualTo(300);
        verify(valueOperations).setIfAbsent("workflow:test:1", "1", ttl);
    }

    /**
     * 验证冲突时读取 Redis 剩余 TTL，供业务层返回可执行的冷却时间。
     *
     * @return void，已存在 Key 被误判成功或剩余时间丢失时测试失败
     */
    @Test
    void returnsRemainingSecondsWhenMarkerAlreadyExists()
    {
        Duration ttl = Duration.ofMinutes(5);
        when(valueOperations.setIfAbsent("workflow:test:1", "1", ttl)).thenReturn(false);
        when(redisTemplate.getExpire("workflow:test:1", TimeUnit.SECONDS)).thenReturn(127L);

        var result = operations.setIfAbsent("workflow:test:1", ttl);

        assertThat(result.acquired()).isFalse();
        assertThat(result.remainingSeconds()).isEqualTo(127);
    }

    /**
     * 验证只能提交 Redis EX 能准确表达的正整数秒 TTL。
     *
     * @return void，零值、负值或小数秒被静默接受时测试失败
     */
    @Test
    void rejectsInvalidExpiryBeforeCallingRedis()
    {
        assertThatThrownBy(() -> operations.setIfAbsent("workflow:test:1", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> operations.setIfAbsent("workflow:test:1", Duration.ofMillis(1500)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 验证分钟计数通过单次 Lua 调用完成 INCR 与首次 EXPIRE，并返回递增后计数。
     *
     * @return void，脚本参数、TTL 或返回计数不一致时失败
     */
    @Test
    @SuppressWarnings("unchecked")
    void incrementsAndExpiresCounterWithSingleLuaExecution()
    {
        when(redisTemplate.execute(org.mockito.ArgumentMatchers.any(RedisScript.class),
                org.mockito.ArgumentMatchers.eq(java.util.List.of("workflow:rate:1")),
                org.mockito.ArgumentMatchers.eq("60"))).thenReturn(7L);

        long count = operations.incrementWithExpiry("workflow:rate:1", Duration.ofMinutes(1));

        assertThat(count).isEqualTo(7L);
        verify(redisTemplate).execute(org.mockito.ArgumentMatchers.any(RedisScript.class),
                org.mockito.ArgumentMatchers.eq(java.util.List.of("workflow:rate:1")),
                org.mockito.ArgumentMatchers.eq("60"));
    }
}
